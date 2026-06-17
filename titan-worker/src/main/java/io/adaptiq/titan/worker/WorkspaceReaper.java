package io.adaptiq.titan.worker;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Comparator;
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
 * whose build has reached a terminal state, or whose build no longer exists.
 *
 * <p>A {@code RUNNING}/{@code QUEUED} build is left untouched — its steps are still using the
 * directory. Archived artifacts are unaffected: they live in the {@code ArtifactStore}, a separate
 * root, not in the workspace. Best-effort throughout — every failure is logged and swallowed;
 * reaping must never disturb task execution.
 */
final class WorkspaceReaper {

  private static final Logger LOG = LoggerFactory.getLogger(WorkspaceReaper.class);

  private static final String BUILD_DIR_PREFIX = "build-";

  private final WorkerDb db;
  private final Path workspaceRoot;

  WorkspaceReaper(WorkerDb db, Path workspaceRoot) {
    this.db = db;
    this.workspaceRoot = workspaceRoot;
  }

  /**
   * Sweep the workspace root once: delete every {@code build-<id>} directory whose build is
   * terminal or gone. Safe to call from any thread, at startup and on a schedule.
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
        // A null status means the build row is gone; a terminal status means it is
        // finished — either way no step will touch this directory again.
        if (status == null || isTerminal(status)) {
          if (deleteRecursively(dir)) {
            reaped++;
          }
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
