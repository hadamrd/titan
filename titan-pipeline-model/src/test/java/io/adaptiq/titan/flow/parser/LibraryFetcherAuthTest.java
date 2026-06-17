package io.adaptiq.titan.flow.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.adaptiq.titan.flow.crypto.SecretProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Authenticated shared-library fetch — design/40 §4–5. {@link LibraryFetcher#resolve(String,
 * String)} resolves a {@code credential:}-named secret via a {@link SecretProvider} and
 * authenticates a private-repo git fetch with it through a {@code GIT_ASKPASS} helper.
 *
 * <p>The end-to-end tests run a <strong>real</strong> git fetch over HTTP against {@link
 * AuthGitHttpServer} — a server that issues a {@code 401 WWW-Authenticate: Basic} challenge, which
 * is exactly what makes git invoke the askpass helper. They self-skip when {@code git http-backend}
 * is not on the box. The fail-closed and wiring tests need no network and always run.
 */
class LibraryFetcherAuthTest {

  /** A {@link SecretProvider} stub that maps a fixed name to a fixed value — nothing else. */
  private static SecretProvider providerWith(String name, String value) {
    return new SecretProvider() {
      @Override
      public String secret(String n) {
        return name.equals(n) ? value : null;
      }

      @Override
      public String describe() {
        return "test:stub";
      }
    };
  }

  // ── fail-closed (no network) ────────────────────────────────────────────

  /**
   * design/40 §4: a {@code null} from the secret provider fails synthesis closed — no silent
   * fall-through to an unauthenticated fetch.
   */
  @Test
  void aMissingSecretFailsClosed(@TempDir Path tmp) throws Exception {
    String url =
        GitLibraryFixture.buildLibrary(
            tmp.resolve("repo"),
            "v1.0",
            Map.of("vars/hello.groovy", "def call() { stage('Hello') {} }\n"));
    LibraryFetcher fetcher =
        new LibraryFetcher(tmp.resolve("cache"), providerWith("other-secret", "value"));

    PipelineParseException e =
        assertThrows(
            PipelineParseException.class, () -> fetcher.resolve(url + "@v1.0", "github-ci-token"));
    assertTrue(
        e.getMessage().contains("github-ci-token") && e.getMessage().contains("fails closed"),
        e.getMessage());
    // No fetch happened — the cache root holds no checkout.
    assertFalse(
        Files.exists(tmp.resolve("cache").resolve("vars")),
        "a fail-closed resolve must not have fetched anything");
  }

  /** A blank secret value is treated as missing — also fails closed. */
  @Test
  void aBlankSecretFailsClosed(@TempDir Path tmp) throws Exception {
    String url =
        GitLibraryFixture.buildLibrary(
            tmp.resolve("repo"),
            "v1.0",
            Map.of("vars/hello.groovy", "def call() { stage('Hello') {} }\n"));
    LibraryFetcher fetcher =
        new LibraryFetcher(tmp.resolve("cache"), providerWith("github-ci-token", "   "));

    PipelineParseException e =
        assertThrows(
            PipelineParseException.class, () -> fetcher.resolve(url + "@v1.0", "github-ci-token"));
    assertTrue(e.getMessage().contains("fails closed"), e.getMessage());
  }

  /**
   * A blank {@code credentialName} is treated as "no credential" — an ordinary unauthenticated
   * fetch of a public library, not a fail-closed error.
   */
  @Test
  void aBlankCredentialNameIsAnUnauthenticatedFetch(@TempDir Path tmp) throws Exception {
    String url =
        GitLibraryFixture.buildLibrary(
            tmp.resolve("repo"),
            "v1.0",
            Map.of("vars/hello.groovy", "def call() { stage('Hello') {} }\n"));
    // A provider that holds nothing — it must not even be consulted for a blank credential.
    LibraryFetcher fetcher =
        new LibraryFetcher(tmp.resolve("cache"), providerWith("unused", "unused"));

    Path dir = fetcher.resolve(url + "@v1.0", "  ");
    assertTrue(
        Files.exists(dir.resolve("vars/hello.groovy")),
        "a blank credential name must fall back to a public fetch");
  }

  // ── authenticated end-to-end fetch over HTTP ────────────────────────────

  /**
   * The headline test: a private repository served over HTTP behind Basic auth is fetched, by git,
   * with the token supplied through {@code GIT_ASKPASS} — never in argv, never in the URL.
   */
  @Test
  void anAuthenticatedFetchSucceedsWithTheRightToken(@TempDir Path tmp) throws Exception {
    assumeTrue(
        AuthGitHttpServer.isSupported(), "skipped — git http-backend not available on this box");

    Path projectRoot = tmp.resolve("git-root");
    Path repo = projectRoot.resolve("ci-private.git");
    GitLibraryFixture.buildBareServableLibrary(
        repo,
        Map.of("vars/deploy.groovy", "def call(String env) { stage(\"Deploy ${env}\") {} }\n"));

    try (AuthGitHttpServer server = AuthGitHttpServer.start(projectRoot)) {
      String url = server.baseUrl() + "/ci-private.git";
      LibraryFetcher fetcher =
          new LibraryFetcher(
              tmp.resolve("cache"),
              providerWith("github-ci-token", AuthGitHttpServer.EXPECTED_TOKEN));

      Path dir = fetcher.resolve(url + "@main", "github-ci-token");

      assertTrue(
          Files.exists(dir.resolve("vars/deploy.groovy")),
          "the private library must have been fetched and checked out");
      assertTrue(server.servedRequests() > 0, "git must have completed an authenticated fetch");
      // .git/config must hold no token — only a username-bearing or plain URL (design/40 §5).
      String gitConfig = Files.readString(dir.resolve(".git/config"));
      assertFalse(
          gitConfig.contains(AuthGitHttpServer.EXPECTED_TOKEN),
          "the cached checkout's .git/config must never contain the token");
    }
  }

  /**
   * A wrong token does not authenticate: the server keeps returning 401 and the fetch fails —
   * fail-fast, because {@code GIT_TERMINAL_PROMPT=0} stops git hanging on an interactive prompt.
   */
  @Test
  void anAuthenticatedFetchFailsWithAWrongToken(@TempDir Path tmp) throws Exception {
    assumeTrue(
        AuthGitHttpServer.isSupported(), "skipped — git http-backend not available on this box");

    Path projectRoot = tmp.resolve("git-root");
    GitLibraryFixture.buildBareServableLibrary(
        projectRoot.resolve("ci-private.git"),
        Map.of("vars/deploy.groovy", "def call() { stage('Deploy') {} }\n"));

    try (AuthGitHttpServer server = AuthGitHttpServer.start(projectRoot)) {
      String url = server.baseUrl() + "/ci-private.git";
      LibraryFetcher fetcher =
          new LibraryFetcher(
              tmp.resolve("cache"), providerWith("github-ci-token", "the-wrong-token"));

      PipelineParseException e =
          assertThrows(
              PipelineParseException.class,
              () -> fetcher.resolve(url + "@main", "github-ci-token"));
      // The token must never surface in the failure message (design/40 §5 redaction).
      assertFalse(
          e.getMessage().contains("the-wrong-token"),
          "the token must never appear in an error message");
    }
  }

  /**
   * An <em>unauthenticated</em> fetch of the same private repo is refused — proving the server
   * genuinely gates on auth, so the success test above really exercised the credential path.
   */
  @Test
  void anUnauthenticatedFetchOfAPrivateRepoIsRefused(@TempDir Path tmp) throws Exception {
    assumeTrue(
        AuthGitHttpServer.isSupported(), "skipped — git http-backend not available on this box");

    Path projectRoot = tmp.resolve("git-root");
    GitLibraryFixture.buildBareServableLibrary(
        projectRoot.resolve("ci-private.git"),
        Map.of("vars/deploy.groovy", "def call() { stage('Deploy') {} }\n"));

    try (AuthGitHttpServer server = AuthGitHttpServer.start(projectRoot)) {
      String url = server.baseUrl() + "/ci-private.git";
      LibraryFetcher fetcher = new LibraryFetcher(tmp.resolve("cache"));

      // No credential — the fetch is unauthenticated and the server's 401 stands.
      assertThrows(PipelineParseException.class, () -> fetcher.resolve(url + "@main"));
    }
  }

  /**
   * design/40 §5: "a re-used cache hit performs no fetch, so it needs none." A full-SHA coordinate
   * short-circuits ref resolution (no {@code ls-remote}), so a second resolve is a pure cache hit —
   * no git request — and may be done with <em>no credential at all</em>, even against a private
   * remote.
   */
  @Test
  void aCacheHitNeedsNoCredential(@TempDir Path tmp) throws Exception {
    assumeTrue(
        AuthGitHttpServer.isSupported(), "skipped — git http-backend not available on this box");

    Path projectRoot = tmp.resolve("git-root");
    Path repo = projectRoot.resolve("ci-private.git");
    GitLibraryFixture.buildBareServableLibrary(
        repo, Map.of("vars/deploy.groovy", "def call() { stage('Deploy') {} }\n"));
    String sha = GitLibraryFixture.bareHeadSha(repo);
    Path cache = tmp.resolve("cache");

    try (AuthGitHttpServer server = AuthGitHttpServer.start(projectRoot)) {
      String url = server.baseUrl() + "/ci-private.git";
      // First resolve: authenticated by full SHA — populates the SHA-keyed cache.
      new LibraryFetcher(cache, providerWith("tok", AuthGitHttpServer.EXPECTED_TOKEN))
          .resolve(url + "@" + sha, "tok");
      int afterFirst = server.servedRequests();

      // Second resolve of the same SHA coordinate with NO credential — a pure cache hit must
      // perform no git request, so the absent credential cannot matter.
      Path dir = new LibraryFetcher(cache).resolve(url + "@" + sha);
      assertTrue(Files.exists(dir.resolve("vars/deploy.groovy")));
      assertEquals(afterFirst, server.servedRequests(), "a cache hit must perform no git request");
    }
  }
}
