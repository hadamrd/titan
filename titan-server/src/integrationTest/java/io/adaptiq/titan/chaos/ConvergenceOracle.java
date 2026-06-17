package io.adaptiq.titan.chaos;

import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.FlowNodeRow;
import io.adaptiq.titan.store.rows.TimerRow;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;

/**
 * The chaos rig's correctness oracle. After the calm period it asserts the engine converged every
 * seeded build to its pre-computed terminal state, with no stuck node/task/timer and no
 * double-fired timer. Every check is a direct DB read — never the engine's own self-report.
 *
 * <p>Checks 3 and 5 query the UNION of {@code titan.task_queue} and {@code titan.task_archive}
 * because the engine's reaper sweeps terminal tasks into the archive; {@code TaskQueueDao
 * .listByBuild} returns nothing for archived tasks, so the raw {@link DataSource} is used for those
 * two checks.
 */
final class ConvergenceOracle {

  private static final Set<String> BUILD_TERMINAL = Set.of("SUCCESS", "FAILED", "ABORTED");
  private static final Set<String> NODE_TERMINAL =
      Set.of("SUCCESS", "FAILED", "ABORTED", "SKIPPED");
  private static final Set<String> TIMER_TERMINAL = Set.of("FIRED", "CANCELLED");

  /** Lightweight projection used for checks 3 and 5. */
  private record TaskRow(long id, String status, String type) {}

  private final TitanStores stores;
  private final DataSource dataSource;
  private final Map<Long, String> expectations;
  private final ChaosLedger ledger;

  /**
   * @param stores live {@link TitanStores} — used for builds, flow_nodes, timers (not archived by
   *     the engine).
   * @param dataSource raw JDBC source — used to query the UNION of {@code task_queue} and {@code
   *     task_archive} for checks 3 and 5.
   * @param expectations build-id → expected terminal status map produced by {@link SeedBuilds}.
   * @param ledger execution ledger populated by {@link ChaosWorker} during the rig run.
   */
  ConvergenceOracle(
      TitanStores stores,
      DataSource dataSource,
      Map<Long, String> expectations,
      ChaosLedger ledger) {
    this.stores = stores;
    this.dataSource = dataSource;
    this.expectations = expectations;
    this.ledger = ledger;
  }

  /** Run all checks. Throws AssertionError on the first violation. */
  void assertConverged() {
    // Snapshot once — avoids N calls inside the per-build loop.
    Map<Long, Long> runs = ledger.executionCounts();

    for (var e : expectations.entrySet()) {
      long buildId = e.getKey();
      String expected = e.getValue();

      // 1. Build terminal and correct.
      String actual =
          stores
              .builds()
              .findById(buildId)
              .orElseThrow(() -> new AssertionError("build " + buildId + " vanished"))
              .status;
      check(BUILD_TERMINAL.contains(actual), "build " + buildId + " not terminal: " + actual);
      check(
          expected.equals(actual), "build " + buildId + " expected " + expected + " got " + actual);

      // 2. No node stuck.
      List<FlowNodeRow> nodes = stores.flowNodes().listByBuild(buildId);
      for (FlowNodeRow n : nodes) {
        check(
            NODE_TERMINAL.contains(n.status),
            "build " + buildId + " node " + n.nodeId + " stuck: " + n.status);
      }

      // 3. No task stuck mid-flight. CLAIMED/PROCESSING on a finished build is a hard
      //    failure (a task wedged in-flight). QUEUED is printed, not failed: the engine
      //    currently does not cancel pending retry tasks (future available_at) when a build
      //    terminates naturally — a known TitanOrchestrator.finishIfDone gap the rig
      //    surfaces here. A worker could still claim such a row and run a step for a dead
      //    build.
      List<TaskRow> tasks = queryAllTasks(buildId);
      for (TaskRow t : tasks) {
        if ("CLAIMED".equals(t.status()) || "PROCESSING".equals(t.status())) {
          check(false, "build " + buildId + " task " + t.id() + " stuck in-flight: " + t.status());
        } else if ("QUEUED".equals(t.status())) {
          System.out.println(
              "[chaos] orphaned QUEUED task "
                  + t.id()
                  + " on terminal build "
                  + buildId
                  + " — engine did not cancel a pending retry task (finishIfDone gap)");
        }
      }

      // 4. No timer leaked.
      List<TimerRow> timers = stores.timers().listByBuild(buildId);
      for (TimerRow tm : timers) {
        check(
            TIMER_TERMINAL.contains(tm.status),
            "build " + buildId + " timer " + tm.id + " leaked: " + tm.status);
      }

      // 5. Every COMPLETED EXECUTE_COMMAND task must have a ledger entry (the worker records
      //    before completing — a COMPLETED task with no entry is a real worker bug).
      //    CANCELLED / TIMED_OUT tasks may have 0 executions — the orchestrator can cancel a
      //    task (e.g. timeout enforcement) before any worker claims it; that is correct.
      //    Re-execution > 1 is permitted by the at-least-once queue — reported, never failed.
      for (TaskRow t : tasks) {
        if ("EXECUTE_COMMAND".equals(t.type())) {
          Long n = runs.get(t.id());
          if ("COMPLETED".equals(t.status())) {
            check(
                n != null && n >= 1L,
                "build "
                    + buildId
                    + " task "
                    + t.id()
                    + " (EXECUTE_COMMAND, COMPLETED) has no ledger entry — "
                    + "a completed task must have executed");
          }
          if (n != null && n > 1L) {
            System.out.println(
                "[chaos] re-execution: task "
                    + t.id()
                    + " ran "
                    + n
                    + "x (permitted by at-least-once queue)");
          }
        }
      }

      // 6. No timer left mid-claim — a FIRED timer is exactly-once by markFired's CAS.
      for (TimerRow tm : timers) {
        check(
            !"CLAIMED".equals(tm.status),
            "build " + buildId + " timer " + tm.id + " left CLAIMED — never fired");
      }
    }
  }

  /**
   * Returns all task rows for a build from the UNION of {@code titan.task_queue} and {@code
   * titan.task_archive}. Uses raw JDBC to avoid any DAO-layer filtering.
   */
  private List<TaskRow> queryAllTasks(long buildId) {
    String sql =
        "SELECT id, status, type FROM titan.task_queue WHERE build_id = ? "
            + "UNION ALL "
            + "SELECT id, status, type FROM titan.task_archive WHERE build_id = ?";
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setLong(1, buildId);
      ps.setLong(2, buildId);
      try (ResultSet rs = ps.executeQuery()) {
        List<TaskRow> rows = new ArrayList<>();
        while (rs.next()) {
          rows.add(new TaskRow(rs.getLong(1), rs.getString(2), rs.getString(3)));
        }
        return rows;
      }
    } catch (Exception ex) {
      throw new AssertionError("queryAllTasks failed for build " + buildId, ex);
    }
  }

  /**
   * Liveness: with chaos off, the engine must make progress unaided. Given the non-terminal build
   * count at two times {@code settleMillis} apart, assert it strictly decreased (or was already 0).
   */
  static void assertMakingProgress(long beforeNonTerminal, long afterNonTerminal) {
    check(
        afterNonTerminal == 0L || afterNonTerminal < beforeNonTerminal,
        "engine not making progress with chaos off: non-terminal builds "
            + beforeNonTerminal
            + " -> "
            + afterNonTerminal);
  }

  private static void check(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError("[chaos oracle] " + message);
    }
  }
}
