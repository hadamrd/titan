package io.adaptiq.titan.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Issue #60 — the reap predicate must not treat "build row is gone" as "safe to delete": a deleted
 * build can leave an orphaned execution still using the workspace. Only a terminal build status, or
 * a row-less workspace older than the orphan TTL, is reapable; everything else survives the sweep.
 */
class WorkspaceReaperTest {

  private static final Duration TTL = Duration.ofHours(24);

  @TempDir Path root;

  private Path buildDir(long id) throws IOException {
    Path dir = Files.createDirectories(root.resolve("build-" + id));
    // Give the workspace some content so the recursive delete path is exercised.
    Files.writeString(Files.createDirectories(dir.resolve("src")).resolve("a.txt"), "x");
    return dir;
  }

  private static void age(Path dir, Duration by) throws IOException {
    Files.setLastModifiedTime(dir, FileTime.from(Instant.now().minus(by)));
  }

  private WorkspaceReaper reaper(Map<Long, String> statuses) {
    // The lookup returns null for ids absent from the map — "build row is gone".
    return new WorkspaceReaper(statuses::get, root, TTL);
  }

  @Test
  void terminalBuildWorkspaceIsReaped() throws IOException {
    Path done = buildDir(1);
    assertEquals(1, reaper(Map.of(1L, "SUCCESS")).sweep());
    assertFalse(Files.exists(done));
  }

  @Test
  void runningBuildWorkspaceSurvives() throws IOException {
    Path running = buildDir(2);
    assertEquals(0, reaper(Map.of(2L, "RUNNING")).sweep());
    assertTrue(Files.exists(running));
  }

  @Test
  void missingBuildRowWithYoungWorkspaceSurvives() throws IOException {
    // The issue-#60 core case: the build row vanished under a possibly-still-running
    // (orphaned) execution. A fresh workspace must NOT be reaped.
    Path orphan = buildDir(3);
    assertEquals(0, reaper(Map.of()).sweep());
    assertTrue(Files.exists(orphan), "young row-less workspace must survive the sweep");
  }

  @Test
  void missingBuildRowPastTtlIsReaped() throws IOException {
    Path stale = buildDir(4);
    age(stale, TTL.plusHours(1));
    assertEquals(1, reaper(Map.of()).sweep());
    assertFalse(Files.exists(stale), "row-less workspace older than the TTL must be reaped");
  }

  @Test
  void sweepIsIdempotent() throws IOException {
    Path done = buildDir(5);
    Path stale = buildDir(6);
    age(stale, TTL.plusHours(1));
    Path young = buildDir(7);
    WorkspaceReaper reaper = reaper(Map.of(5L, "FAILED"));

    assertEquals(2, reaper.sweep(), "first pass reaps the terminal and TTL-expired workspaces");
    assertFalse(Files.exists(done));
    assertFalse(Files.exists(stale));
    assertTrue(Files.exists(young));

    assertEquals(0, reaper.sweep(), "second pass is a no-op");
    assertTrue(Files.exists(young), "the young orphan still survives the repeat sweep");
  }

  @Test
  void statusLookupFailureKeepsTheWorkspace() throws IOException {
    Path dir = buildDir(8);
    WorkspaceReaper reaper =
        new WorkspaceReaper(
            id -> {
              throw new SQLException("db down");
            },
            root,
            TTL);
    assertEquals(0, reaper.sweep());
    assertTrue(Files.exists(dir), "an unanswerable status question must never reap");
  }

  @Test
  void nonBuildDirectoriesAndFilesAreIgnored() throws IOException {
    Path other = Files.createDirectories(root.resolve("not-a-build"));
    Path file = Files.writeString(root.resolve("build-9"), "a FILE named like a build dir");
    assertEquals(0, reaper(Map.of()).sweep());
    assertTrue(Files.exists(other));
    assertTrue(Files.exists(file));
  }

  // ── TTL env parsing ────────────────────────────────────────────────────────

  @Test
  void ttlDefaultsTo24HoursAndIsEnvOverridable() {
    assertEquals(Duration.ofHours(24), WorkspaceReaper.orphanTtlFromEnv(Map.of()));
    assertEquals(
        Duration.ofMinutes(5),
        WorkspaceReaper.orphanTtlFromEnv(Map.of(WorkspaceReaper.ORPHAN_TTL_ENV, "300000")));
  }

  @Test
  void garbageTtlFallsBackToDefaultInsteadOfFailingBoot() {
    Map<String, String> env = new HashMap<>();
    env.put(WorkspaceReaper.ORPHAN_TTL_ENV, "soon");
    assertEquals(WorkspaceReaper.DEFAULT_ORPHAN_TTL, WorkspaceReaper.orphanTtlFromEnv(env));
    env.put(WorkspaceReaper.ORPHAN_TTL_ENV, "-1");
    assertEquals(WorkspaceReaper.DEFAULT_ORPHAN_TTL, WorkspaceReaper.orphanTtlFromEnv(env));
    env.put(WorkspaceReaper.ORPHAN_TTL_ENV, "  ");
    assertEquals(WorkspaceReaper.DEFAULT_ORPHAN_TTL, WorkspaceReaper.orphanTtlFromEnv(env));
  }
}
