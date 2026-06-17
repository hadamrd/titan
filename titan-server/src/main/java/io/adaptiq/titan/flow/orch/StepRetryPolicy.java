package io.adaptiq.titan.flow.orch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.adaptiq.titan.flow.model.PipelineModel;
import io.adaptiq.titan.flow.model.RetryPolicy;
import io.adaptiq.titan.flow.model.StageModel;
import io.adaptiq.titan.flow.model.StepModel;
import io.adaptiq.titan.store.FlowNodeDao;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.FlowNodeRow;
import io.adaptiq.titan.store.rows.LogRow;
import io.adaptiq.titan.store.rows.TaskQueueRow;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Step-level retry decisions and re-dispatch (design/44, #357 decomposition). Extracted verbatim
 * from {@code TitanOrchestrator}. Holds the policy logic ({@link #isRetryable}, {@link
 * #stepExitCategory}, {@link #stepExitReason}, {@link #exitCodeOf}) and the re-dispatch driver
 * {@link #retryStep}, which delegates the actual enqueue to the injected {@link StepDispatcher}.
 */
public final class StepRetryPolicy {

  private static final Logger LOGGER = Logger.getLogger(StepRetryPolicy.class.getName());
  private static final ObjectMapper JSON = new ObjectMapper();

  private final TitanStores daos;
  private final long buildId;
  private final StepDispatcher stepDispatcher;
  private final OutputContext outputContext;

  public StepRetryPolicy(
      @NonNull TitanStores daos,
      long buildId,
      @NonNull StepDispatcher stepDispatcher,
      @NonNull OutputContext outputContext) {
    this.daos = daos;
    this.buildId = buildId;
    this.stepDispatcher = stepDispatcher;
    this.outputContext = outputContext;
  }

  /**
   * Decide whether a FAILED step task should retry, and if so re-dispatch it (design/44 §4).
   *
   * <p>Retry happens iff the step carries a {@link RetryPolicy} with {@code maxAttempts > 1},
   * attempts remain ({@code node.attempt < maxAttempts}), and the failure is retryable (design/44
   * §3 — see {@link #isRetryable}). When all hold, the orchestrator:
   *
   * <ol>
   *   <li><b>compare-and-sets</b> the node's {@code attempt} from {@code k} to {@code k+1} ({@link
   *       FlowNodeDao#compareAndSetRetryAttempt} — keyed on {@code status='QUEUED'} AND {@code
   *       attempt=k}, so a second pass or a re-delivered failed task cannot double-enqueue). The
   *       node stays {@code QUEUED} — "retrying" — so the normal DAG scan never sees a dispatchable
   *       {@code PENDING} node and cannot race this path.
   *   <li>re-enqueues a fresh {@code EXECUTE_COMMAND} task with {@code available_at = now +
   *       backoff(k+1)} — the durable queue's delayed delivery (design/26), no controller-side
   *       timer. The payload is rebuilt fresh, re-resolving {@code credentials:} (design/39) — a
   *       retry is a clean re-run.
   * </ol>
   *
   * @return {@code true} if a retry was won and re-dispatched (the node is now QUEUED for the next
   *     attempt); {@code false} if the step should fall through to {@code FAILED}.
   */
  public boolean retryStep(
      @NonNull FlowNodeDao flowNodes,
      @NonNull PipelineModel model,
      @NonNull TaskQueueRow failedTask) {
    if (failedTask.nodeId == null) {
      return false; // non-step task — no node to retry against
    }
    FlowNodeRow node = flowNodes.findByBuildAndNode(buildId, failedTask.nodeId).orElse(null);
    if (node == null || !"QUEUED".equals(node.status) || node.maxAttempts <= 1) {
      return false; // no retry policy, or the node has already moved on
    }
    if (node.attempt >= node.maxAttempts) {
      return false; // attempts exhausted — fall through to FAILED
    }
    StepModel step = PipelineNodes.findStep(model, failedTask.nodeId);
    StageModel stage = PipelineNodes.findStage(model, failedTask.nodeId);
    if (step == null || stage == null || step.getRetry() == null) {
      return false; // not a step node, or the policy is gone — fall through to FAILED
    }
    RetryPolicy policy = step.getRetry();
    if (!isRetryable(policy, failedTask.resultJson)) {
      LOGGER.log(
          Level.FINE,
          "[titan] build {0}: step {1} failure not retryable — FAILED",
          new Object[] {buildId, failedTask.nodeId});
      return false; // a deterministic / non-allowlisted failure fails fast (design/44 §3)
    }
    int nextAttempt = node.attempt + 1;
    // CAS the attempt FIRST — keyed on the current (status, attempt). If this returns 0 a
    // peer pass already advanced the node, so we must NOT re-enqueue (no double-dispatch).
    if (flowNodes.compareAndSetRetryAttempt(buildId, failedTask.nodeId, node.attempt) != 1) {
      return true; // a concurrent pass already handled this retry — treat as reconciled
    }
    long delayMillis = backoffMillis(policy, nextAttempt);
    Instant availableAt = Instant.now().plusMillis(delayMillis);
    Map<String, Object> ctx =
        outputContext.build(model, PipelineNodes.byId(flowNodes.listByBuild(buildId)));
    long taskId = stepDispatcher.reEnqueueStep(stage, step, model, ctx, availableAt);
    recordRetryBoundary(taskId, nextAttempt, node.maxAttempts, delayMillis);
    LOGGER.log(
        Level.INFO,
        "[titan] build {0}: step {1} retrying — attempt {2}/{3} after {4}ms",
        new Object[] {buildId, failedTask.nodeId, nextAttempt, node.maxAttempts, delayMillis});
    return true;
  }

  /**
   * Whether a step's non-zero exit is retryable under its policy (design/44 §3). An empty {@code
   * retryableExitCodes} list retries any non-zero exit; a non-empty list retries only an exit code
   * it contains — any other code fails fast. A transient infrastructure failure (the worker died,
   * the visibility timeout lapsed — the reaper writes a result_json with an {@code error} field and
   * no {@code exitCode}) is always retryable, bounded by {@code maxAttempts}.
   */
  public static boolean isRetryable(@NonNull RetryPolicy policy, @Nullable String resultJson) {
    Integer exitCode = exitCodeOf(resultJson);
    if (exitCode == null) {
      return true; // infra failure — no step exit code recorded — retry, bounded by maxAttempts
    }
    if (exitCode == 0) {
      return false; // not a failure at all
    }
    List<Integer> allow = policy.getRetryableExitCodes();
    return allow.isEmpty() || allow.contains(exitCode);
  }

  /**
   * The backoff delay before attempt {@code k} (1-indexed): {@code min(initial*mult^(k-1), max)}.
   */
  public static long backoffMillis(@NonNull RetryPolicy policy, int attempt) {
    RetryPolicy.Backoff b = policy.getBackoff();
    double delay = b.getInitialMillis() * Math.pow(b.getMultiplier(), Math.max(0, attempt - 1));
    return (long) Math.min(delay, (double) b.getMaxMillis());
  }

  /** The {@code exitCode} a task's result_json carries, or {@code null} if it carries none. */
  @Nullable
  public static Integer exitCodeOf(@Nullable String resultJson) {
    if (resultJson == null || resultJson.isBlank()) {
      return null;
    }
    try {
      JsonNode node = JSON.readTree(resultJson);
      return node.has("exitCode") ? node.get("exitCode").asInt() : null;
    } catch (Exception e) {
      return null;
    }
  }

  /**
   * The design/45 §3 category for a step task that ended without success. A task carrying an {@code
   * exitCode} ran and exited non-zero → {@code STEP_EXIT}; a task with no exit code was an
   * infrastructure failure (worker death, visibility-timeout reap) → {@code TIMEOUT}.
   */
  @NonNull
  public static String stepExitCategory(@NonNull TaskQueueRow task) {
    return exitCodeOf(task.resultJson) != null ? "STEP_EXIT" : "TIMEOUT";
  }

  /**
   * The one-line {@code failure_reason} for a non-successful step task (design/45 §1). For a step
   * that exited non-zero the detail is already in its log, so the reason is just the summary
   * ({@code "exited <code>"}); for an infra failure (no exit code) it surfaces the reaper's note.
   */
  @NonNull
  public static String stepExitReason(@NonNull TaskQueueRow task) {
    Integer exit = exitCodeOf(task.resultJson);
    if (exit != null) {
      return "Step exited " + exit + " — see the step log above for the failure detail.";
    }
    String err = errorOf(task.resultJson);
    return err != null
        ? "Step did not complete: " + err
        : "Step did not complete — the worker stopped reporting and the task was reaped.";
  }

  /** The {@code error} string a task's result_json carries (the reaper writes one), or null. */
  @Nullable
  private static String errorOf(@Nullable String resultJson) {
    if (resultJson == null || resultJson.isBlank()) {
      return null;
    }
    try {
      JsonNode node = JSON.readTree(resultJson);
      return node.has("error") && !node.get("error").isNull() ? node.get("error").asText() : null;
    } catch (Exception e) {
      return null;
    }
  }

  /**
   * Write the retry-boundary marker — {@code — retry k/N after Ns —} (design/44 §5) — into the
   * fresh retry task's log, at {@code chunk_index = -1} so it sorts before the worker's first
   * chunk. The console assembles a node's log across every task token chronologically ({@code
   * TaskQueueDao#logTokensForNode}), so the marker shows on the node's console without a DB query,
   * just ahead of the retried attempt's output.
   */
  private void recordRetryBoundary(
      long retryTaskId, int attempt, int maxAttempts, long delayMillis) {
    try {
      TaskQueueRow task = daos.taskQueue().findById(retryTaskId).orElse(null);
      if (task == null) {
        return;
      }
      LogRow line = new LogRow();
      line.taskId = task.taskToken;
      line.chunkIndex = -1;
      line.stream = "stdout";
      line.data =
          String.format("%n— retry %d/%d after %ds —%n", attempt, maxAttempts, delayMillis / 1000);
      line.isFinal = false;
      daos.logs().insert(line);
    } catch (RuntimeException e) {
      // a missing log line must never abort the retry — log and carry on
      LOGGER.log(
          Level.FINE,
          "[titan] build {0}: could not write retry-boundary log: {1}",
          new Object[] {buildId, e.getMessage()});
    }
  }
}
