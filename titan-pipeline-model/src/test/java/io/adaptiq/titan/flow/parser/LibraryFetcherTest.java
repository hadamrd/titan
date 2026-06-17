package io.adaptiq.titan.flow.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit-level tests for {@link LibraryFetcher} — the shared-library git cache (design/38 §4–5, Stage
 * 2b). These drive {@link LibraryFetcher#resolve} directly against real {@code file://} git
 * repositories built by {@link GitLibraryFixture}, with no synthesis program around it.
 *
 * <p>The cache is content-addressed by the <em>resolved commit SHA</em>: an immutable ref (a tag or
 * a full SHA) is stable, but a mutable ref (a branch) is re-resolved via {@code git ls-remote} on
 * every call so a moved branch is re-fetched rather than frozen at its first-fetched commit.
 */
class LibraryFetcherTest {

  private static int cacheDirCount(Path cacheRoot) throws Exception {
    try (Stream<Path> entries = Files.list(cacheRoot)) {
      return (int) entries.filter(Files::isDirectory).count();
    }
  }

  /**
   * (a) The regression test for the stale-pin bug: a branch that has moved is re-fetched, not
   * served stale from the cache. Against the pre-fix code this fails — the second resolve returns
   * the original v1 checkout.
   */
  @Test
  void aMovedBranchIsReFetched(@TempDir Path tmp) throws Exception {
    Path repo = tmp.resolve("repo");
    String url =
        GitLibraryFixture.buildLibraryOnBranch(
            repo, "main", Map.of("vars/banner.groovy", "def call() { stage('v1') {} }\n"));
    Path cache = tmp.resolve("cache");
    LibraryFetcher fetcher = new LibraryFetcher(cache);

    Path first = fetcher.resolve(url + "@main");
    // contains(), not exact-equals: git may rewrite line endings on checkout (core.autocrlf).
    assertTrue(Files.readString(first.resolve("vars/banner.groovy")).contains("stage('v1')"));

    GitLibraryFixture.addCommitOnBranch(
        repo, "main", Map.of("vars/banner.groovy", "def call() { stage('v2') {} }\n"));

    Path second = fetcher.resolve(url + "@main");
    assertNotEquals(first, second, "a moved branch must resolve to a new cache dir");
    assertTrue(
        Files.readString(second.resolve("vars/banner.groovy")).contains("stage('v2')"),
        "the second resolve must reflect the new commit, not the frozen first fetch");
    assertEquals(2, cacheDirCount(cache), "v1 and v2 each get their own SHA-keyed cache dir");
  }

  /** (b) An unchanged branch is a cache hit — no re-clone. */
  @Test
  void anUnchangedBranchHitsTheCache(@TempDir Path tmp) throws Exception {
    String url =
        GitLibraryFixture.buildLibraryOnBranch(
            tmp.resolve("repo"),
            "main",
            Map.of("vars/hello.groovy", "def call() { stage('Hello') {} }\n"));
    LibraryFetcher fetcher = new LibraryFetcher(tmp.resolve("cache"));

    Path first = fetcher.resolve(url + "@main");
    // A sentinel proves reuse: a fresh clone wipes the dir, a cache hit leaves it.
    Path sentinel = first.resolve("vars/.cache-sentinel");
    Files.writeString(sentinel, "kept");

    Path second = fetcher.resolve(url + "@main");
    assertEquals(first, second, "the unchanged branch resolves to the same cache dir");
    assertTrue(Files.exists(sentinel), "a cache hit must not re-clone (sentinel survived)");
  }

  /** (c) A full commit SHA coordinate resolves and caches without an ls-remote round-trip. */
  @Test
  void aFullShaCoordinateResolves(@TempDir Path tmp) throws Exception {
    Path repo = tmp.resolve("repo");
    String url =
        GitLibraryFixture.buildLibraryOnBranch(
            repo, "main", Map.of("vars/hello.groovy", "def call() { stage('Hello') {} }\n"));
    String sha = GitLibraryFixture.headSha(repo);
    LibraryFetcher fetcher = new LibraryFetcher(tmp.resolve("cache"));

    Path dir = fetcher.resolve(url + "@" + sha);
    assertTrue(
        Files.isRegularFile(dir.resolve(".titan-fetched")),
        "a SHA coordinate must produce a finalised cache entry");
    assertTrue(Files.exists(dir.resolve("vars/hello.groovy")));
  }

  /** (d) A ref that exists on no branch or tag fails with a clear, located error. */
  @Test
  void anUnknownRefFailsCleanly(@TempDir Path tmp) throws Exception {
    String url =
        GitLibraryFixture.buildLibraryOnBranch(
            tmp.resolve("repo"),
            "main",
            Map.of("vars/hello.groovy", "def call() { stage('Hello') {} }\n"));
    LibraryFetcher fetcher = new LibraryFetcher(tmp.resolve("cache"));

    PipelineParseException e =
        assertThrows(PipelineParseException.class, () -> fetcher.resolve(url + "@no-such-ref"));
    assertTrue(
        e.getMessage().contains("no-such-ref") && e.getMessage().contains("not found"),
        e.getMessage());
  }

  /** (e) A lightweight tag still resolves and is cache-stable across resolves. */
  @Test
  void aLightweightTagIsCacheStable(@TempDir Path tmp) throws Exception {
    String url =
        GitLibraryFixture.buildLibrary(
            tmp.resolve("repo"),
            "v1.0",
            Map.of("vars/hello.groovy", "def call() { stage('Hello') {} }\n"));
    LibraryFetcher fetcher = new LibraryFetcher(tmp.resolve("cache"));

    Path first = fetcher.resolve(url + "@v1.0");
    Path sentinel = first.resolve("vars/.cache-sentinel");
    Files.writeString(sentinel, "kept");

    Path second = fetcher.resolve(url + "@v1.0");
    assertEquals(first, second);
    assertTrue(Files.exists(sentinel), "a tag re-resolve must be a cache hit");
  }

  /** (f) An annotated tag — ls-remote reports a peeled line — resolves deterministically. */
  @Test
  void anAnnotatedTagResolvesViaThePeeledLine(@TempDir Path tmp) throws Exception {
    Path repo = tmp.resolve("repo");
    String url =
        GitLibraryFixture.buildLibraryOnBranch(
            repo, "main", Map.of("vars/hello.groovy", "def call() { stage('Hello') {} }\n"));
    GitLibraryFixture.annotateTag(repo, "rel-1");
    LibraryFetcher fetcher = new LibraryFetcher(tmp.resolve("cache"));

    Path first = fetcher.resolve(url + "@rel-1");
    Path second = fetcher.resolve(url + "@rel-1");
    assertEquals(first, second, "an annotated tag must resolve to one deterministic cache dir");
    assertEquals(1, cacheDirCount(tmp.resolve("cache")));
  }
}
