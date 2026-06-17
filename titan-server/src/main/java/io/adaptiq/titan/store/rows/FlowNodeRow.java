package io.adaptiq.titan.store.rows;

import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.util.UUID;

/** Mutable POJO mapping to a row in {@code rf_flow_nodes}. Composite PK: (build_id, node_id). */
public class FlowNodeRow {
  public long buildId;
  public String nodeId;

  @Nullable public String parentIds;

  public String nodeType;

  @Nullable public String displayName;

  @Nullable public String stepDescriptor;

  @Nullable public String stepArgsJson;

  public String status;

  @Nullable public String agentId;

  @Nullable public String agentLabel;

  @Nullable public Instant startedAt;

  @Nullable public Instant completedAt;

  /** Wall-clock instant a SLEEPING node should wake at; NULL unless the node is a durable wait. */
  @Nullable public Instant wakeAt;

  @Nullable public Long durationMs;

  @Nullable public String resultJson;

  @Nullable public UUID logTaskId;

  /**
   * Which attempt this step node is currently on (design/44 §5). Starts at 1; the orchestrator
   * increments it on every retry re-dispatch. Always 1 for stage/gate/precondition nodes.
   */
  public int attempt = 1;

  /**
   * The step's {@code RetryPolicy.maxAttempts} copied onto the node at bake (design/44 §5); {@code
   * 1} = no retry. The orchestrator's failure path and the graph API read it without re-loading the
   * pipeline model.
   */
  public int maxAttempts = 1;

  /**
   * Why this node ended {@code FAILED} (design/45 §3) — one of the closed category set ({@code
   * STEP_EXIT}, {@code CREDENTIAL}, {@code DISPATCH}, {@code PRECONDITION}, {@code BAKE}, {@code
   * SYNTHESIS}, {@code TIMEOUT}, {@code INTERNAL}). Null on a node that has not failed.
   */
  @Nullable public String failureCategory;

  /**
   * A concise, customer-facing sentence naming the failure and the fix (design/45 §4) — the
   * <em>only</em> copy of the reason for a failure that produced no step log. Null unless the node
   * ended {@code FAILED}.
   */
  @Nullable public String failureReason;
}
