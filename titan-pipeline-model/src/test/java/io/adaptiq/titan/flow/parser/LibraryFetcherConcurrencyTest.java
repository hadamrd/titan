package io.adaptiq.titan.flow.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Concurrency stress test for {@link LibraryFetcher} — design/38 §4–5, Stage 2b.
 *
 * <p>Several synthesis runs can resolve the same shared-library coordinate at the same instant:
 * concurrent executor slots within one worker, or two workers on one host sharing {@link
 * LibraryFetcher#defaultCacheRoot()}. This test drives N threads through one shared cache root
 * simultaneously and asserts every resolve yields a complete, uncorrupted checkout — the guarantee
 * the staging-directory + atomic-move publish provides. Against the previous
 * fetch-straight-into-the-cache-directory code this was a check-then-act race that could interleave
 * two {@code git} clones into one directory.
 */
class LibraryFetcherConcurrencyTest {

  @Test
  @Timeout(120)
  void concurrentResolvesOfTheSameCoordinateAllSucceedUncorrupted(@TempDir Path tmp)
      throws Exception {
    String url =
        GitLibraryFixture.buildLibrary(
            tmp.resolve("repo"),
            "v1.0",
            Map.of(
                "vars/deployApp.groovy", "def call() { stage('Deploy') { sh 'noop' } }\n",
                "README.md", "titan shared library fixture\n"));
    String coordinate = url + "@v1.0";
    Path cacheRoot = tmp.resolve("shared-cache");

    int threads = 6;
    CyclicBarrier startLine = new CyclicBarrier(threads);
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      List<Callable<Path>> jobs = new ArrayList<>();
      for (int i = 0; i < threads; i++) {
        jobs.add(
            () -> {
              startLine.await(30, TimeUnit.SECONDS); // release every thread together
              return new LibraryFetcher(cacheRoot).resolve(coordinate);
            });
      }
      List<Future<Path>> results = pool.invokeAll(jobs, 90, TimeUnit.SECONDS);

      Path shared = null;
      for (Future<Path> r : results) {
        Path dest;
        try {
          dest = r.get();
        } catch (Exception e) {
          throw new AssertionError("a concurrent resolve failed", e);
        }
        // Every resolve of one immutable tag must land on the same cache entry.
        if (shared == null) {
          shared = dest;
        } else {
          assertEquals(shared, dest, "all resolves must share one cache directory");
        }
        // ...and that entry must be a complete, uncorrupted checkout.
        assertTrue(
            Files.isRegularFile(dest.resolve(".titan-fetched")),
            "missing completion marker — checkout was not finalised atomically");
        assertTrue(
            Files.isRegularFile(dest.resolve("vars/deployApp.groovy")),
            "missing library file — checkout is corrupt");
        assertEquals(
            "titan shared library fixture\n",
            Files.readString(dest.resolve("README.md")),
            "library file content is corrupt");
      }

      // The losers of the race must clean up their staging dirs — none may leak.
      try (Stream<Path> entries = Files.list(cacheRoot)) {
        long leaked =
            entries.filter(p -> p.getFileName().toString().startsWith(".staging-")).count();
        assertEquals(0, leaked, "staging directories must be cleaned up after the race");
      }
    } finally {
      pool.shutdownNow();
    }
  }
}
