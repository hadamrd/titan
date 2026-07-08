package io.adaptiq.titan.queue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.audit.AuditAction;
import io.adaptiq.titan.audit.AuditTargetType;
import io.adaptiq.titan.flow.NotificationDispatcher;
import io.adaptiq.titan.flow.model.NotifyHook;
import io.adaptiq.titan.flow.model.PipelineModel;
import io.adaptiq.titan.flow.parser.TitanYamlParser;
import io.adaptiq.titan.observability.TraceContext;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.AuditLogRow;
import io.adaptiq.titan.store.rows.BuildRow;
import io.adaptiq.titan.store.rows.JobRow;
import io.adaptiq.titan.store.rows.TaskQueueRow;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Shared collaborator passed to each {@link QueueMessageHandler} — owns the cross-handler
 * primitives (task lifecycle writes, follow-up task enqueues, build fail-close + bake-failure
 * notify fan-out). Extracted from {@link QueueProcessor} as part of design 67 step 6 so the handler
 * classes do not each carry private copies of these helpers.
 *
 * <p>This class deliberately holds no per-task state: every method takes the {@link TitanStores}
 * explicitly so a {@code QueueProcessor.tick} can inject a per-tick DAO bundle into every handler
 * invocation without rebuilding the support graph.
 */
class QueueHandlerSupport {

  private static final Logger LOGGER = Logger.getLogger(QueueHandlerSupport.class.getName());

  static final ObjectMapper JSON = new ObjectMapper();
  static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

  /**
   * Provides the current bake-failure dispatcher (a Supplier so the static test-seam still wins).
   */
  private final Supplier<NotificationDispatcher> bakeFailureDispatcher;

  /** Per-build transition-spam guard (issue #1050). Shared across all handlers. */
  private final TransitionCapGuard capGuard;

  QueueHandlerSupport(@NonNull Supplier<NotificationDispatcher> bakeFailureDispatcher) {
    this(bakeFailureDispatcher, new TransitionCapGuard());
  }

  QueueHandlerSupport(
      @NonNull Supplier<NotificationDispatcher> bakeFailureDispatcher,
      @NonNull TransitionCapGuard capGuard) {
    this.bakeFailureDispatcher = bakeFailureDispatcher;
    this.capGuard = capGuard;
  }

  /** Access the shared transition-cap guard (for handlers + tests). */
  @NonNull
  TransitionCapGuard capGuard() {
    return capGuard;
  }

  /**
   * Record one state transition of {@code kind} for {@code buildId} through the shared {@link
   * TransitionCapGuard}, AND emit the corresponding structured audit event on a cap crossing (issue
   * #1074). This is the single entry point handlers should call — it keeps the metric increment,
   * the structured WARN/HALT log line (inside the guard) and the {@code audit_log} event in
   * lock-step.
   *
   * <p>Exactly-once semantics: the guard returns {@link TransitionCapGuard.Outcome#SOFT_WARN} only
   * on the crossing edge ({@code count == softCap + 1}), so one {@link
   * AuditAction#TRANSITION_CAP_WARN} row is written per (build, kind). For the hard cap the guard
   * returns {@link TransitionCapGuard.Outcome#HARD_HALT} idempotently on every subsequent call once
   * halted; we gate the {@link AuditAction#TRANSITION_CAP_HALT} emit on the not-yet-halted → halted
   * edge (checked via {@link TransitionCapGuard#isHalted(long)} before the increment) so the halt
   * row is written once, not once per spammed tick. Per-build transition processing is serialized
   * by {@code FOR UPDATE SKIP LOCKED} task claiming, so this pre/post check is race-free in
   * practice.
   *
   * <p>The audit write is best-effort — a failure (including a {@code null} DAO bundle in unit
   * tests) is swallowed so the spam guard's fail-close path is never itself blocked by an audit
   * failure.
   */
  @NonNull
  TransitionCapGuard.Outcome recordTransition(
      @NonNull TitanStores daos, long buildId, @NonNull String kind) {
    boolean wasHalted = capGuard.isHalted(buildId);
    TransitionCapGuard.Outcome outcome = capGuard.recordTransition(buildId, kind);
    switch (outcome) {
      case SOFT_WARN ->
          emitCapEvent(
              daos,
              AuditAction.TRANSITION_CAP_WARN,
              buildId,
              kind,
              capGuard.countFor(buildId, kind),
              capGuard.softCap());
      case HARD_HALT -> {
        // Only on the not-halted → halted edge — never on the repeated idempotent HALT returns.
        if (!wasHalted) {
          emitCapEvent(
              daos,
              AuditAction.TRANSITION_CAP_HALT,
              buildId,
              kind,
              capGuard.countFor(buildId, kind),
              capGuard.hardCap());
        }
      }
      default -> {
        // OK — within budget, no audit event.
      }
    }
    return outcome;
  }

  /**
   * Best-effort emit of a {@code transition_cap_warn} / {@code transition_cap_halt} structured
   * audit row (issue #1074). Mirrors {@link NoWorkerTimeoutSweeper}'s controller-side audit
   * pattern: a background actor (no {@code SecurityIdentity}), action carried as the {@link
   * AuditAction} enum name, target = {@code BUILD/{id}}, structured payload in {@code
   * details_json}. Package-private + overridable so unit tests can capture emissions without a live
   * DAO.
   *
   * <p>Failure (including {@code daos == null}) is logged at {@code FINE} and swallowed — the audit
   * trail is a best-effort observability surface, not a correctness dependency of the fail-close.
   */
  void emitCapEvent(
      @NonNull TitanStores daos,
      @NonNull AuditAction action,
      long buildId,
      @NonNull String kind,
      long count,
      int cap) {
    try {
      AuditLogRow row = new AuditLogRow();
      row.actor = "titan-controller";
      row.action = action.name();
      row.targetType = AuditTargetType.BUILD.name();
      row.targetId = String.valueOf(buildId);
      Map<String, Object> details = new LinkedHashMap<>();
      details.put("buildId", buildId);
      details.put("kind", kind);
      details.put("count", count);
      details.put(action == AuditAction.TRANSITION_CAP_HALT ? "hardCap" : "softCap", cap);
      try {
        row.detailsJson = JSON.writeValueAsString(details);
      } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
        row.detailsJson = "{\"buildId\":" + buildId + ",\"kind\":\"" + kind + "\"}";
      }
      daos.auditLog().insert(row);
    } catch (RuntimeException e) {
      LOGGER.log(
          Level.FINE,
          "[titan] transition-cap audit emit failed for build {0} kind {1}: {2}",
          new Object[] {buildId, kind, e.getMessage()});
    }
  }

  // ── Task lifecycle writes ────────────────────────────────────────────────

  void completeTaskSafely(@NonNull TitanStores daos, @NonNull TaskQueueRow task) {
    try {
      daos.taskQueue().completeTask(task.id, "{\"status\":\"OK\"}");
    } catch (RuntimeException e) {
      LOGGER.log(
          Level.WARNING,
          "[release-flow] QueueProcessor: completeTask failed for id={0}",
          new Object[] {task.id, e});
    }
  }

  void failTaskSafely(@NonNull TitanStores daos, @NonNull TaskQueueRow task, String reason) {
    try {
      String result = JSON.writeValueAsString(Map.of("error", reason != null ? reason : "unknown"));
      daos.taskQueue().failTask(task.id, result);
    } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
      LOGGER.log(
          Level.WARNING,
          "[release-flow] QueueProcessor: failTask failed for id={0}",
          new Object[] {task.id, e});
    }
  }

  // ── Follow-up task enqueues ──────────────────────────────────────────────

  /**
   * Look up the job-level priority weight (#1100) for {@code buildId} from its synthesised pipeline
   * model. Best-effort: returns {@code 0} (the {@code NORMAL} default and column default) on any
   * failure — pre-synthesis builds, missing model, parse errors. The within-pipeline tier (BAKE
   * &gt; ADVANCE) is layered on top by callers, so HIGH/LOW only shifts cross-build ordering.
   */
  int priorityWeightFor(@NonNull TitanStores daos, long buildId) {
    try {
      PipelineModel model = io.adaptiq.titan.cache.PipelineModelCache.loadOrFresh(daos, buildId);
      return model.effectivePriorityWeight();
    } catch (RuntimeException e) {
      // No synthesised model yet (the SYNTHESIZE poll path) or unreadable — default to 0.
      return 0;
    }
  }

  /** Enqueue an {@code ORCHESTRATE/ADVANCE} task for {@code buildId}, claimable after a delay. */
  void enqueueAdvance(@NonNull TitanStores daos, long buildId, int delaySeconds) {
    TaskQueueRow advance = new TaskQueueRow();
    advance.type = "ORCHESTRATE";
    advance.queueName = "default";
    advance.status = "QUEUED";
    advance.priority = priorityWeightFor(daos, buildId);
    advance.payloadJson = "{\"action\":\"ADVANCE\",\"buildId\":" + buildId + "}";
    advance.attempts = 0;
    advance.maxAttempts = 3;
    advance.visibilityTimeoutSeconds = 3600;
    advance.buildId = buildId;
    advance.availableAt = Instant.now().plusSeconds(delaySeconds);
    advance.traceParent = TraceContext.currentTraceParent();
    daos.taskQueue().insert(advance);
  }

  /**
   * Enqueue an {@code ORCHESTRATE/BAKE} task for {@code buildId} (design/38 §3, the BAKE phase).
   */
  void enqueueBake(@NonNull TitanStores daos, long buildId) {
    TaskQueueRow bake = new TaskQueueRow();
    bake.type = "ORCHESTRATE";
    bake.queueName = "default";
    bake.status = "QUEUED";
    // Tier-5 within the build's own ORCHESTRATE chain (BAKE > ADVANCE) plus the job's
    // declared priority (#1100) — same-tier tasks then sort high → normal → low across builds.
    bake.priority = 5 + priorityWeightFor(daos, buildId);
    bake.payloadJson = "{\"action\":\"BAKE\",\"buildId\":" + buildId + "}";
    bake.attempts = 0;
    bake.maxAttempts = 3;
    bake.visibilityTimeoutSeconds = 300;
    bake.buildId = buildId;
    bake.traceParent = TraceContext.currentTraceParent();
    daos.taskQueue().insert(bake);
  }

  /** Enqueue a worker-side {@code SYNTHESIZE} task (design/38 Stage 1b). */
  void enqueueWorkerSynthesis(
      @NonNull TitanStores daos, long buildId, @NonNull String pipelineScript) {
    TaskQueueRow synth = new TaskQueueRow();
    synth.type = "EXECUTE_COMMAND";
    synth.queueName = QueueProcessor.SYNTHESIS_QUEUE;
    synth.status = "QUEUED";
    synth.priority = 10;
    try {
      synth.payloadJson =
          JSON.writeValueAsString(
              Map.of(
                  "action", "SYNTHESIZE",
                  "buildId", buildId,
                  "pipelineScript", pipelineScript));
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("could not serialise SYNTHESIZE payload", e);
    }
    synth.attempts = 0;
    synth.maxAttempts = 3;
    synth.visibilityTimeoutSeconds = 300;
    synth.buildId = buildId;
    synth.traceParent = TraceContext.currentTraceParent();
    daos.taskQueue().insert(synth);
  }

  /** Re-queue the {@code SYNTHESIZE} orchestration task to poll for worker completion. */
  void enqueueSynthesizePoll(@NonNull TitanStores daos, long buildId, int delaySeconds) {
    TaskQueueRow poll = new TaskQueueRow();
    poll.type = "ORCHESTRATE";
    poll.queueName = "default";
    poll.status = "QUEUED";
    poll.priority = 5;
    poll.payloadJson = "{\"action\":\"SYNTHESIZE\",\"buildId\":" + buildId + "}";
    poll.attempts = 0;
    poll.maxAttempts = 3;
    poll.visibilityTimeoutSeconds = 300;
    poll.buildId = buildId;
    poll.availableAt = Instant.now().plusSeconds(delaySeconds);
    poll.traceParent = TraceContext.currentTraceParent();
    daos.taskQueue().insert(poll);
  }

  // ── Build fail-close + bake-failure notify fan-out ──────────────────────

  /**
   * Mark a build {@code FAILED} and record the customer-facing reason on {@code failure_summary}
   * (design/45 §1/§4). All writes are best-effort.
   */
  void markBuildFailed(@NonNull TitanStores daos, long buildId, @NonNull String reason) {
    capGuard.onBuildTerminal(buildId);
    try {
      daos.builds().updateStatus(buildId, "FAILED", null, Instant.now(), null, reason);
      io.adaptiq.titan.cache.PipelineModelCache.invalidateIfActive(buildId);
    } catch (RuntimeException e) {
      LOGGER.log(
          Level.WARNING,
          "[titan] QueueProcessor: could not mark build {0} FAILED",
          new Object[] {buildId, e});
    }
    try {
      daos.builds().updateFailureSummary(buildId, reason);
    } catch (RuntimeException e) {
      LOGGER.log(
          Level.FINE,
          "[titan] QueueProcessor: could not record failure_summary for build {0}",
          new Object[] {buildId, e});
    }
    fireBakeFailureNotifications(daos, buildId, reason);
  }

  /**
   * Best-effort fire-and-forget dispatch of declarative {@code notify:} hooks on the bake /
   * synthesis crash path (#360).
   */
  private void fireBakeFailureNotifications(
      @NonNull TitanStores daos, long buildId, @NonNull String reason) {
    try {
      BuildRow build = daos.builds().findById(buildId).orElse(null);
      if (build == null) {
        return;
      }
      JobRow job = daos.jobs().findById(build.jobId).orElse(null);
      if (job == null || job.pipelineScript == null || job.pipelineScript.isBlank()) {
        return;
      }
      List<NotifyHook> hooks = TitanYamlParser.parseNotifyHooksTolerant(job.pipelineScript);
      if (hooks.isEmpty()) {
        return;
      }
      NotificationDispatcher dispatcher = bakeFailureDispatcher.get();
      if (dispatcher == null) {
        return;
      }
      dispatcher.fireBakeFailureHooks(buildId, job.fullName, reason, hooks);
    } catch (RuntimeException e) {
      LOGGER.log(
          Level.WARNING,
          "[titan] QueueProcessor: BAKE_FAILURE notify dispatch threw (swallowed) for build {0}",
          new Object[] {buildId, e});
    }
  }

  // ── Pure helpers ─────────────────────────────────────────────────────────

  /** A short, human-readable description of an exception — its message, or its type. */
  @NonNull
  static String describe(@NonNull Throwable t) {
    String msg = t.getMessage();
    return msg != null && !msg.isBlank() ? msg : t.getClass().getSimpleName();
  }

  /**
   * A root-cause-preserving description of an exception (issue #36): the outermost exception's
   * class + message, plus — when a cause chain exists — the innermost cause's class + message. A
   * wrapper like {@code TitanDataException("Transaction failed")} can therefore never reach a
   * build's {@code error_message}/{@code failure_summary} bare: the diagnostic an SRE acts on (the
   * root cause) always rides along.
   */
  @NonNull
  static String describeWithCause(@NonNull Throwable t) {
    Throwable root = t;
    while (root.getCause() != null && root.getCause() != root) {
      root = root.getCause();
    }
    String summary = t.getClass().getSimpleName() + ": " + describe(t);
    if (root != t) {
      summary += " (caused by " + root.getClass().getSimpleName() + ": " + describe(root) + ")";
    }
    return summary;
  }

  /** Coerce a payload {@code buildId} (a JSON number or numeric string) to a {@code long}. */
  static long toBuildId(@NonNull Object buildIdObj) {
    try {
      return ((Number) buildIdObj).longValue();
    } catch (ClassCastException e) {
      return Long.parseLong(buildIdObj.toString());
    }
  }

  /**
   * Compute and persist the pipeline-root wall-clock deadline for {@code buildId} (issue #244).
   * Best-effort: any failure is logged and swallowed.
   */
  void stampPipelineDeadline(@NonNull TitanStores daos, long buildId) {
    try {
      BuildRow build = daos.builds().findById(buildId).orElse(null);
      if (build == null || build.pipelineModelJson == null || build.pipelineModelJson.isBlank()) {
        return;
      }
      PipelineModel model = JSON.readValue(build.pipelineModelJson, PipelineModel.class);
      Long timeoutMillis = model.getTimeoutMillis();
      if (timeoutMillis == null || timeoutMillis <= 0) {
        return;
      }
      Instant deadline = Instant.now().plusMillis(timeoutMillis);
      daos.builds().updateDeadline(buildId, deadline);
      LOGGER.log(
          Level.INFO,
          "[titan] QueueProcessor: build {0} pipeline deadline = {1} ({2}ms from now)",
          new Object[] {buildId, deadline, timeoutMillis});
    } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
      LOGGER.log(
          Level.WARNING,
          "[titan] QueueProcessor: could not stamp pipeline deadline for build {0}: {1}",
          new Object[] {buildId, e.getMessage()});
    }
  }
}
