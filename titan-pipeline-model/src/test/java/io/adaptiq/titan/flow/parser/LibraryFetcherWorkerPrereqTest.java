package io.adaptiq.titan.flow.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Worker-prereq guard for {@link LibraryFetcher} — the engine/rig seam that e2e specs
 * 47-shared-library-pipeline and 48-multi-repo-checkout exercise (issue #1243, test-matrix item
 * "Worker-prereq").
 *
 * <p>Those specs drive the full inbound-webhook golden-build path on the live rig; when they go red
 * the failure surfaces 200s deep in a rig run as an opaque "marker not in artifact". The two
 * failure modes the issue calls out — the worker image lacking a writable libraries (cache) root,
 * or the library remote being unreachable — must NOT degrade into a flake (a hang, an NPE, a
 * half-written cache dir, or a generic {@code RuntimeException}). They must surface as a precise,
 * typed {@link PipelineParseException} that names the prereq and the offending path, so an operator
 * triaging a red 47/48 build sees the root cause immediately instead of chasing a phantom.
 *
 * <p>This is the cheap, deterministic, rig-free half of #1243's verification loop: it pins the
 * worker-side contract the live specs depend on. The adversarial paths below are root-independent
 * (they rely on {@code createDirectories} over a regular file, which fails for every uid — never on
 * a {@code chmod} that {@code root} bypasses) so the guard cannot itself become the flake it guards
 * against.
 */
class LibraryFetcherWorkerPrereqTest {

  /**
   * Prereq: the libraries root must be a usable directory. When it is a regular FILE (a
   * misconfigured {@code TITAN_LIBRARIES_ROOT} pointing at a file, or a name collision on the
   * worker volume), the very first {@code ls-remote} staging step — {@code
   * Files.createDirectories(cacheRoot)} — cannot proceed. The fetcher must fail with a precise,
   * located {@link PipelineParseException}, not the raw {@code FileAlreadyExistsException} / an
   * NPE. Root-independent: createDirectories over an existing regular file throws for every uid.
   */
  @Test
  void aLibrariesRootThatIsAFileFailsWithAPreciseLocatedError(@TempDir Path tmp) throws Exception {
    Path rootAsFile = tmp.resolve("libraries-root");
    Files.writeString(rootAsFile, "not a directory");

    // A mutable ref (no full SHA) routes through ls-remote, whose first act is to create the
    // cache root — which is the file above. Coordinate validity is irrelevant: we never reach git.
    PipelineParseException e =
        assertThrows(
            PipelineParseException.class,
            () -> new LibraryFetcher(rootAsFile).resolve("https://example.invalid/lib.git@main"));

    assertTrue(
        e.getMessage().contains("library cache dir")
            && e.getMessage().contains(rootAsFile.toString()),
        "the libraries-root prereq failure must name the cache dir + the offending path, got: "
            + e.getMessage());
  }

  /**
   * Prereq (the finalisation half): even when the ref is an immutable SHA — so {@code ls-remote} is
   * skipped — the fetch path itself ({@code fetchAtomically}) must still create the cache root.
   * When that root cannot be a directory (here its parent is a regular file, so {@code mkdir -p} is
   * impossible), the fetcher fails precisely rather than leaving a half-published checkout. This is
   * the path a real worker hits on a full-SHA library pin. Root-independent for the same reason.
   */
  @Test
  void anUncreatableCacheRootFailsTheFetchPrecisely(@TempDir Path tmp) throws Exception {
    // Build a real fixture repo so the coordinate + SHA are genuine — the failure must come from
    // the
    // cache root, not from a bogus coordinate.
    Path repo = tmp.resolve("repo");
    String url =
        GitLibraryFixture.buildLibraryOnBranch(
            repo, "main", Map.of("vars/ci.groovy", "def buildAndTest(Map a){ stage('x'){} }\n"));
    String sha = GitLibraryFixture.headSha(repo);

    // cacheRoot lives UNDER a regular file → createDirectories(cacheRoot) is impossible for any
    // uid.
    Path blocker = tmp.resolve("blocker");
    Files.writeString(blocker, "regular file");
    Path cacheRoot = blocker.resolve("nested-cache");

    PipelineParseException e =
        assertThrows(
            PipelineParseException.class,
            () -> new LibraryFetcher(cacheRoot).resolve(url + "@" + sha));

    assertTrue(
        e.getMessage().contains("library cache dir")
            && e.getMessage().contains(cacheRoot.toString()),
        "an uncreatable cache root must fail with a located cache-dir error, got: "
            + e.getMessage());
  }

  /**
   * Prereq: the library remote must be reachable. An unreachable / non-existent remote must fail
   * FAST with a typed, git-located error — never hang the build to the {@code GIT_TIMEOUT_SECONDS}
   * wall (the flake mode: a build that sits "running" for 45s then dies opaquely). We bound the
   * call well under that 45s budget to prove it fails on git's own non-zero exit, not on the
   * timeout.
   */
  @Test
  void anUnreachableRemoteFailsFastWithATypedError(@TempDir Path tmp) {
    Path cacheRoot = tmp.resolve("cache");
    // A file:// URL to a path that is not a git repo: git ls-remote exits non-zero immediately.
    String bogus = tmp.resolve("does-not-exist.git").toUri() + "@main";
    LibraryFetcher fetcher = new LibraryFetcher(cacheRoot);

    PipelineParseException e =
        assertTimeoutPreemptively(
            Duration.ofSeconds(20),
            () -> assertThrows(PipelineParseException.class, () -> fetcher.resolve(bogus)),
            "an unreachable remote must fail fast (git non-zero exit), not hang to the git timeout");

    assertTrue(
        e.getMessage().contains("synthesis:"),
        "an unreachable-remote failure must be a typed synthesis error, got: " + e.getMessage());
  }

  /**
   * Happy prereq: when git IS installed and the libraries root IS writable — the state a correctly
   * provisioned worker is in — a real library coordinate resolves to a finalised, marker-bearing
   * checkout containing the {@code vars/} file the dotted-step rewrite ({@code ci.buildAndTest})
   * will load. This is the positive control for the three adversarial cases above: it proves they
   * fail because of the injected prereq breakage, not because the path is broken for everyone.
   */
  @Test
  void aProvisionedWorkerResolvesARealLibraryCheckout(@TempDir Path tmp) throws Exception {
    String url =
        GitLibraryFixture.buildLibrary(
            tmp.resolve("repo"),
            "v1",
            Map.of(
                "vars/ci.groovy",
                "def buildAndTest(Map a){ sh 'echo TITAN_LIB_MARKER_buildAndTest_v1 > lib-out.txt' }\n"));
    LibraryFetcher fetcher = new LibraryFetcher(tmp.resolve("cache"));

    Path checkout = fetcher.resolve(url + "@v1");

    assertTrue(
        Files.isRegularFile(checkout.resolve(".titan-fetched")),
        "a provisioned worker must produce a finalised cache entry");
    Path script = checkout.resolve("vars/ci.groovy");
    assertTrue(Files.isRegularFile(script), "the library's vars/ci.groovy must be checked out");
    assertTrue(
        Files.readString(script).contains("TITAN_LIB_MARKER_buildAndTest_v1"),
        "the checked-out library must carry the marker spec-47 asserts in lib-out.txt");
    assertEquals(checkout, fetcher.resolve(url + "@v1"), "a second resolve is a stable cache hit");
  }
}
