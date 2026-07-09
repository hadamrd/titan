package io.adaptiq.titan.worker;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reaps the workspaces of finished builds (design/43 W4).
 *
 * <p>Since design/43, a build's workspace is one directory — {@code <workspaceRoot>/build-<id>} —
 * shared by every step of the build. Nothing deletes it inline: the worker runs one step task at a
 * time and never knows which step is a build's last. So the worker owns the lifecycle of its own
 * disk, exactly as {@link ContainerReaper} owns its containers — it sweeps periodically (and once
 * at startup, to catch a previous life's leftovers) and removes any {@code build-<id>} directory
 * whose build has reached a terminal state.
 *
 * <p>A {@code RUNNING}/{@code QUEUED} build is left untouched — its steps are still using the
 * directory. <b>A missing build row is NOT proof the workspace is dead (issue #60):</b> the build
 * may have been deleted out from under a still-running (orphaned) execution — post-#58 the executor
 * detects the vanished row and stops itself, but reaping the directory while that detection is
 * still in flight is a disk-state-vs-execution race. So an absent row only makes the workspace
 * reapable once its age exceeds a generous TTL ({@link #DEFAULT_ORPHAN_TTL}, overridable via {@code
 * TITAN_WORKSPACE_ORPHAN_TTL_MS}) — long past any plausible orphaned execution, but short enough
 * that deleted builds do not leak disk forever. Age is the directory's last-modified time, so a
 * workspace whose top level is still being written earns more life.
 *
 * <p>Archived artifacts are unaffected: they live in the {@code ArtifactStore}, a separate root,
 * not in the workspace. Best-effort throughout — every failure is logged and swallowed; reaping
 * must never disturb task execution. Idempotent: a sweep that finds nothing reapable is a no-op.
 */
final class WorkspaceReaper {

  private static final Logger LOG = LoggerFactory.getLogger(WorkspaceReaper.class);

  private static final String BUILD_DIR_PREFIX = "build-";

  /**
   * Default TTL after which a workspace whose build row is <em>gone</em> is reaped anyway. 24 hours
   * — generous by design: an orphaned execution lives at most one step-timeout, so a day-old
   * row-less workspace is guaranteed leftover disk, not a live race.
   */
  static final Duration DEFAULT_ORPHAN_TTL = Duration.ofHours(24);

  /** Env var overriding {@link #DEFAULT_ORPHAN_TTL}, in milliseconds. */
  static final String ORPHAN_TTL_ENV = "TITAN_WORKSPACE_ORPHAN_TTL_MS";

  /** Looks up a build's status — {@code null} when the row no longer exists. Seam for tests. */
  @FunctionalInterface
  interface BuildStatusLookup {
    String buildStatus(long buildId) throws SQLException;
  }

  private final BuildStatusLookup db;
  private final Path workspaceRoot;
  private final Duration orphanTtl;

  WorkspaceReaper(BuildStatusLookup db, Path workspaceRoot, Duration orphanTtl) {
    this.db = db;
    this.workspaceRoot = workspaceRoot;
    this.orphanTtl = orphanTtl;
  }

  /**
   * The orphan TTL from the environment: {@value #ORPHAN_TTL_ENV} in milliseconds, or {@link
   * #DEFAULT_ORPHAN_TTL} when unset/blank/garbage (a bad value must not stop the worker booting —
   * it is WARN-ed and defaulted).
   */
  static Duration orphanTtlFromEnv(Map<String, String> env) {
    String raw = env.get(ORPHAN_TTL_ENV);
    if (raw == null || raw.isBlank()) {
      return DEFAULT_ORPHAN_TTL;
    }
    try {
      long ms = Long.parseLong(raw.trim());
      if (ms > 0) {
        return Duration.ofMillis(ms);
      }
      LOG.warn("{}={} is not positive — using default {}", ORPHAN_TTL_ENV, raw, DEFAULT_ORPHAN_TTL);
    } catch (NumberFormatException e) {
      LOG.warn("{}={} is not a number — using default {}", ORPHAN_TTL_ENV, raw, DEFAULT_ORPHAN_TTL);
    }
    return DEFAULT_ORPHAN_TTL;
  }

  /**
   * Sweep the workspace root once: delete every {@code build-<id>} directory whose build is
   * terminal, or whose build row is gone AND whose age exceeds the orphan TTL. Safe to call from
   * any thread, at startup and on a schedule; a repeat sweep over an already-clean root is a no-op.
   *
   * @return the number of build workspaces reaped
   */
  int sweep() {
    if (!Files.isDirectory(workspaceRoot)) {
      return 0;
    }
    int reaped = 0;
    try (Stream<Path> entries = Files.list(workspaceRoot)) {
      for (Path dir : (Iterable<Path>) entries::iterator) {
        Long buildId = buildIdOf(dir);
        if (buildId == null) {
          continue;
        }
        String status;
        try {
          status = db.buildStatus(buildId);
        } catch (SQLException e) {
          LOG.warn("workspace reaper: build {} status lookup failed: {}", buildId, e.getMessage());
          continue;
        }
        if (isReapable(buildId, status, dir) && deleteRecursively(dir)) {
          reaped++;
        }
      }
    } catch (IOException e) {
      LOG.warn("workspace reaper: cannot list {}: {}", workspaceRoot, e.getMessage());
    }
    if (reaped > 0) {
      LOG.info("workspace reaper: removed {} finished-build workspace(s)", reaped);
    }
    return reaped;
  }

  /**
   * The reap predicate (issue #60). Terminal status → finished, reap. Live status → in use, keep.
   * Row gone → possibly an orphaned execution still winding down; keep until the workspace's age
   * exceeds the orphan TTL, then reap.
   */
  private boolean isReapable(long buildId, String status, Path dir) {
    if (status != null) {
      return isTerminal(status);
    }
    Duration age = ageOf(dir);
    if (age != null && age.compareTo(orphanTtl) > 0) {
      LOG.info(
          "workspace reaper: build {} row is gone and workspace age {} exceeds TTL {} — reaping",
          buildId,
          age,
          orphanTtl);
      return true;
    }
    LOG.debug(
        "workspace reaper: build {} row is gone but workspace is younger than TTL {} — keeping",
        buildId,
        orphanTtl);
    return false;
  }

  /** The workspace's age via its last-modified time, or {@code null} if unreadable (keep it). */
  private static Duration ageOf(Path dir) {
    try {
      return Duration.between(Files.getLastModifiedTime(dir).toInstant(), Instant.now());
    } catch (IOException e) {
      LOG.warn("workspace reaper: cannot stat {}: {}", dir, e.getMessage());
      return null;
    }
  }

  /** The build id encoded in a {@code build-<id>} directory name, or {@code null} if not one. */
  private static Long buildIdOf(Path dir) {
    if (!Files.isDirectory(dir)) {
      return null;
    }
    String name = dir.getFileName().toString();
    if (!name.startsWith(BUILD_DIR_PREFIX)) {
      return null;
    }
    try {
      return Long.parseLong(name.substring(BUILD_DIR_PREFIX.length()));
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static boolean isTerminal(String status) {
    return switch (status) {
      case "SUCCESS", "FAILED", "ABORTED", "UNSTABLE" -> true;
      default -> false;
    };
  }

  private static boolean deleteRecursively(Path root) {
    try (Stream<Path> walk = Files.walk(root)) {
      for (Path p : (Iterable<Path>) walk.sorted(Comparator.reverseOrder())::iterator) {
        Files.deleteIfExists(p);
      }
      return true;
    } catch (IOException e) {
      LOG.warn("workspace reaper: could not delete {}: {}", root, e.getMessage());
      return false;
    }
  }
}
