package io.adaptiq.titan.store.rows;

import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.util.UUID;

/** Mutable POJO mapping to a row in {@code titan.task_queue} (also used for titan.task_archive). */
public class TaskQueueRow {
  public long id;
  public String type;
  public String queueName;
  public String status;
  public int priority;
  public String payloadJson;

  @Nullable public String resultJson;

  public int attempts;
  public int maxAttempts;
  public int visibilityTimeoutSeconds;

  /** Lease token written on claim, verified on completion (doc-27 G3). Null while QUEUED. */
  @Nullable public UUID claimToken;

  @Nullable public String claimedBy;

  @Nullable public Instant claimedAt;

  /** Visibility / delayed-delivery gate — claimable only once this is in the past. */
  public Instant availableAt;

  @Nullable public Long buildId;

  @Nullable public String nodeId;

  public UUID taskToken;
  public Instant createdAt;

  @Nullable public Instant completedAt;

  /**
   * Cancel-intent timestamp (#668). Stamped by the abort path the moment the controller decides to
   * cancel; never cleared. Distinct from {@code status='CANCELLED'} (which is a terminal state):
   * this is the in-flight signal a worker's heartbeat polls so it can SIGTERM/SIGKILL the running
   * step and report CANCELLED through the normal token-guarded completion path. Null when no cancel
   * has been requested.
   */
  @Nullable public Instant cancelRequestedAt;

  /**
   * W3C {@code traceparent} captured at enqueue time (issue #314). Carries the controller-side OTel
   * span context to the worker that claims the task so the worker's spans nest under the same
   * trace. Null when no active OTel span existed at enqueue (background reaper, test, or no SDK on
   * the classpath) — trace propagation is best-effort and never blocks task dispatch.
   *
   * <p>Format: {@code "00-{32 hex trace-id}-{16 hex parent-id}-{2 hex flags}"} (55 chars).
   */
  @Nullable public String traceParent;
}
