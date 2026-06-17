package io.adaptiq.titan.queue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.AgentRow;
import io.adaptiq.titan.store.rows.AuditLogRow;
import io.adaptiq.titan.store.rows.TaskQueueRow;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Fail-fast on stale step-queue with no worker (issue #1049, V1-shippable-bar #4 "no silent
 * stalls"). A QUEUED {@code EXECUTE_COMMAND} task whose target queue has zero live workers cannot
 * make progress — without this sweep it sits in QUEUED forever, the UI shows a build "running" with
 * nothing happening, and a real user reports "Titan hangs".
 *
 * <p>The sweep runs from {@link QueueProcessor#tick} on every controller tick (500 ms cadence) and
 * does two things:
 *
 * <ol>
 *   <li>Pull QUEUED EXECUTE_COMMAND tasks whose {@code available_at} is older than {@code
 *       timeoutSeconds} via {@link
 *       io.adaptiq.titan.store.TaskQueueDao#findExecuteCommandQueuedBefore}.
 *   <li>Compute the union of queue names covered by currently-online workers (their labels CSV,
 *       their {@code agent_id}, plus the universal {@code default} / {@code synthesis} pools that
 *       every worker drains). If the task's {@code queue_name} is NOT in the covered set, fail it
 *       (and its build) with a clear user-facing error and record an audit row of action {@code
 *       no_worker_timeout}.
 * </ol>
 *
 * <p>The {@code available_at}-based clock means a worker that registers <em>before</em> the timeout
 * claims the task normally — registration moves the task to CLAIMED, the sweep query no longer
 * matches it. A worker that registers, then drops just before pickup, leaves the task back in
 * QUEUED (the reaper bumps {@code available_at = now} on requeue) — the timeout window restarts
 * cleanly from the requeue.
 *
 * <p>Race safety: between detection and the fail-write, a real worker may register and claim the
 * task. The {@code AND status = 'QUEUED'} guard on {@link
 * io.adaptiq.titan.store.TaskQueueDao#failQueuedTask} drops zero rows in that case — we never step
 * on a freshly-claimed task.
 *
 * <p>Coverage approximation: the controller cannot see a worker's {@code TITAN_QUEUE} env-var
 * override (only labels + agent_id reach the {@code titan.agents} row). A worker pinned to a
 * non-label queue therefore reads as "uncovered" — we accept this false-positive risk because the
 * documented contract is to express such pins via labels, not the override. Mass-deployed workers
 * (the common case) use labels exclusively.
 */
final class NoWorkerTimeoutSweeper {

  private static final Logger LOGGER = Logger.getLogger(NoWorkerTimeoutSweeper.class.getName());
  private static final ObjectMapper JSON = new ObjectMapper();

  static final int DEFAULT_TIMEOUT_S = 60;
  static final int MIN_TIMEOUT_S = 5;
  static final int MAX_TIMEOUT_S = 3600;

  /**
   * Liveness window for "online" agents (seconds since last heartbeat). 60s matches the agent
   * reaper's default and is two orders of magnitude above the worker's heartbeat cadence — a
   * flapping worker stays in the covered set for at least one full reap cycle.
   */
  static final int AGENT_LIVENESS_S = 60;

  static final int BATCH_LIMIT = 64;

  /** Env var name (per issue #1049 acceptance criteria). */
  static final String ENV_KEY = "LOOP_NO_WORKER_TIMEOUT_S";

  private final int timeoutSeconds;

  NoWorkerTimeoutSweeper(int timeoutSeconds) {
    this.timeoutSeconds = clamp(timeoutSeconds);
  }

  static NoWorkerTimeoutSweeper fromEnv() {
    return fromEnv(System.getenv());
  }

  static NoWorkerTimeoutSweeper fromEnv(@NonNull Map<String, String> env) {
    return new NoWorkerTimeoutSweeper(parseTimeout(env.get(ENV_KEY)));
  }

  /** Visible for tests — parse + clamp the env value. */
  static int parseTimeout(String raw) {
    if (raw == null || raw.isBlank()) {
      return DEFAULT_TIMEOUT_S;
    }
    int n;
    try {
      n = Integer.parseInt(raw.trim());
    } catch (NumberFormatException e) {
      LOGGER.log(
          Level.WARNING,
          "[titan] NoWorkerTimeoutSweeper: ignoring non-numeric {0}=\"{1}\" — using default {2}s",
          new Object[] {ENV_KEY, raw, DEFAULT_TIMEOUT_S});
      return DEFAULT_TIMEOUT_S;
    }
    return clamp(n);
  }

  static int clamp(int n) {
    if (n < MIN_TIMEOUT_S) {
      return MIN_TIMEOUT_S;
    }
    if (n > MAX_TIMEOUT_S) {
      return MAX_TIMEOUT_S;
    }
    return n;
  }

  int timeoutSeconds() {
    return timeoutSeconds;
  }

  /**
   * Run one sweep tick. Best-effort: any individual task-level failure is logged and swallowed so a
   * poisoned row never blocks the per-tick sweep.
   *
   * @return number of tasks transitioned to FAILED on this tick.
   */
  int sweep(@NonNull TitanStores daos, @NonNull QueueHandlerSupport support) {
    List<TaskQueueRow> stale;
    Timestamp cutoff = new Timestamp(System.currentTimeMillis() - timeoutSeconds * 1000L);
    try {
      stale = daos.taskQueue().findExecuteCommandQueuedBefore(cutoff, BATCH_LIMIT);
    } catch (RuntimeException e) {
      LOGGER.log(Level.WARNING, "[titan] NoWorkerTimeoutSweeper: stale-task lookup failed", e);
      return 0;
    }
    if (stale.isEmpty()) {
      return 0;
    }

    Set<String> covered;
    try {
      covered = coveredQueues(daos.agents().listOnline(AGENT_LIVENESS_S));
    } catch (RuntimeException e) {
      LOGGER.log(Level.WARNING, "[titan] NoWorkerTimeoutSweeper: agent-coverage lookup failed", e);
      return 0;
    }

    int failed = 0;
    for (TaskQueueRow task : stale) {
      if (task.queueName != null && covered.contains(task.queueName)) {
        continue;
      }
      if (failTaskAndBuild(daos, support, task)) {
        failed++;
      }
    }
    if (failed > 0) {
      LOGGER.log(
          Level.WARNING,
          "[titan] NoWorkerTimeoutSweeper: failed {0} task(s) with no-worker timeout "
              + "(timeout={1}s)",
          new Object[] {failed, timeoutSeconds});
    }
    return failed;
  }

  private boolean failTaskAndBuild(
      @NonNull TitanStores daos, @NonNull QueueHandlerSupport support, @NonNull TaskQueueRow task) {
    String queueName = task.queueName != null ? task.queueName : "<unknown>";
    String reason =
        "No worker available for queue '"
            + queueName
            + "' after "
            + timeoutSeconds
            + "s — register a worker or change the step's queue selector.";

    String resultJson = buildResultJson(reason, queueName);

    boolean updated;
    try {
      updated = daos.taskQueue().failQueuedTask(task.id, resultJson);
    } catch (RuntimeException e) {
      LOGGER.log(
          Level.WARNING,
          "[titan] NoWorkerTimeoutSweeper: failQueuedTask failed for task id={0}",
          new Object[] {task.id, e});
      return false;
    }
    if (!updated) {
      // A worker raced us and claimed the row between detection and the fail-write — nothing
      // to do; the worker will run the task normally. This is the protective race-loser path.
      return false;
    }

    if (task.buildId != null) {
      support.markBuildFailed(daos, task.buildId, reason);
    }
    recordAudit(daos, task, queueName);
    LOGGER.log(
        Level.WARNING,
        "[titan] NoWorkerTimeoutSweeper: no_worker_timeout — task id={0} build={1} "
            + "queue={2} timeout={3}s",
        new Object[] {task.id, task.buildId, queueName, timeoutSeconds});
    return true;
  }

  private String buildResultJson(String reason, String queueName) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("error", reason);
    payload.put("kind", "no_worker_timeout");
    payload.put("queue", queueName);
    payload.put("timeoutSeconds", timeoutSeconds);
    try {
      return JSON.writeValueAsString(payload);
    } catch (JsonProcessingException e) {
      // Fallback to a hand-built minimal object — the error message is the load-bearing part.
      return "{\"error\":\"" + reason.replace("\"", "\\\"") + "\",\"kind\":\"no_worker_timeout\"}";
    }
  }

  private void recordAudit(
      @NonNull TitanStores daos, @NonNull TaskQueueRow task, @NonNull String queueName) {
    try {
      AuditLogRow row = new AuditLogRow();
      row.actor = "titan-controller";
      row.action = "no_worker_timeout";
      row.targetType = "task";
      row.targetId = String.valueOf(task.id);
      Map<String, Object> details = new HashMap<>();
      details.put("queue", queueName);
      details.put("buildId", task.buildId);
      details.put("nodeId", task.nodeId);
      details.put("timeoutSeconds", timeoutSeconds);
      try {
        row.detailsJson = JSON.writeValueAsString(details);
      } catch (JsonProcessingException e) {
        row.detailsJson = "{\"queue\":\"" + queueName + "\"}";
      }
      daos.auditLog().insert(row);
    } catch (RuntimeException e) {
      // Audit-write failure must never block the main sweep.
      LOGGER.log(
          Level.FINE,
          "[titan] NoWorkerTimeoutSweeper: audit insert failed for task id={0}",
          new Object[] {task.id, e});
    }
  }

  /**
   * Set of queue names that have at least one live worker draining them. Empty if no workers are
   * online — in that case every QUEUED step task is uncovered and fail-fast is universal.
   */
  static Set<String> coveredQueues(@NonNull List<AgentRow> onlineAgents) {
    Set<String> covered = new HashSet<>();
    if (onlineAgents.isEmpty()) {
      return covered;
    }
    // Every live worker drains "default" and "synthesis" (see WorkerConfig.queueNames + the
    // synthesis-last drain path in TitanWorker).
    covered.add("default");
    covered.add("synthesis");
    for (AgentRow agent : onlineAgents) {
      if (agent.agentId != null && !agent.agentId.isBlank()) {
        covered.add(agent.agentId);
      }
      if (agent.labels != null && !agent.labels.isBlank()) {
        for (String tok : agent.labels.split(",")) {
          String t = tok.trim();
          if (!t.isEmpty()) {
            covered.add(t);
          }
        }
      }
    }
    return covered;
  }
}
