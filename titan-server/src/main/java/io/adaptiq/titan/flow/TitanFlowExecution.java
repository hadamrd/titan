package io.adaptiq.titan.flow;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.adaptiq.titan.flow.expr.WhenEvaluator;
import io.adaptiq.titan.flow.model.GateModel;
import io.adaptiq.titan.flow.model.PipelineModel;
import io.adaptiq.titan.flow.model.PipelineNode;
import io.adaptiq.titan.flow.model.PreconditionModel;
import io.adaptiq.titan.flow.model.StageModel;
import io.adaptiq.titan.flow.model.StepModel;
import io.adaptiq.titan.flow.parser.TitanYamlParser;
import io.adaptiq.titan.store.BuildDao;
import io.adaptiq.titan.store.FlowNodeDao;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.BuildRow;
import io.adaptiq.titan.store.rows.FlowNodeRow;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Drives a Titan build through its two pre-execution phases (design/38 §3):
 * <strong>synthesis</strong> — turn the job's pipeline definition into the immutable {@code
 * pipeline_model_json} — and <strong>bake</strong> — turn that stored model into the {@code
 * titan.flow_nodes} DAG.
 *
 * <p>design/38 un-fuses what was historically a single {@code bake(pipelineScript)} call:
 *
 * <ul>
 *   <li>{@link #synthesize(String)} parses {@code pipelineScript} (Titan YAML — the trivial
 *       identity synthesis program of design/38 §2) into a {@link PipelineModel} and persists it as
 *       {@code pipeline_model_json}. It runs nothing; it constructs data.
 *   <li>{@link #bake()} consumes the <em>stored</em> {@code pipeline_model_json} — it does
 *       <strong>not</strong> re-parse the script — resolves parameters, materialises every DAG node
 *       into {@code flow_nodes}, and activates the build.
 * </ul>
 *
 * <p>Both phases are dispatched by {@link io.adaptiq.titan.queue.QueueProcessor} as distinct task
 * actions ({@code SYNTHESIZE} then {@code BAKE}). Each is:
 *
 * <ul>
 *   <li><strong>Retry-safe.</strong> A re-delivered {@code SYNTHESIZE} detects an already-stored
 *       model and skips; a re-delivered {@code BAKE} detects existing {@code flow_nodes} rows and
 *       skips. The baked DAG is immutable (design/26 Tier B / design/30 task re-delivery).
 *   <li><strong>Atomic.</strong> Bake writes every {@code flow_nodes} row, the effective parameters
 *       and the build's {@code RUNNING} transition in one transaction — a crash leaves the build
 *       still {@code QUEUED} with no partial DAG, and the reaper re-runs a clean bake.
 * </ul>
 *
 * <p>Materialisation maps the design/29 model to {@code flow_nodes}: one row per stage, step, gate
 * and precondition. A stage whose {@code when:} expression (evaluated at bake time against {@code
 * params}) is false is materialised {@code SKIPPED} — and so are its steps — so the DAG stays
 * WYSIWYG. Root nodes (no {@code dependsOn}) are {@code QUEUED}; the rest {@code PENDING}.
 *
 * <p>Reactive DAG advancement ({@code ORCHESTRATE}) is Chunk 6D and lives elsewhere; this class is
 * synthesis + bake + read-only queries only.
 */
public final class TitanFlowExecution {

  private static final Logger LOGGER = Logger.getLogger(TitanFlowExecution.class.getName());
  // Lenient on unknown properties so the persisted model survives forward model evolution.
  private static final ObjectMapper JSON =
      new ObjectMapper()
          .configure(
              com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
              false);
  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

  /** Terminal statuses — a node in one of these will not change again. */
  private static final Set<String> TERMINAL_STATUSES =
      Set.of("SUCCESS", "FAILED", "ABORTED", "SKIPPED");

  /** Outcome of a {@link #synthesize(String)} call. */
  public enum SynthesisResult {
    /** This call parsed the pipeline definition and persisted the model. */
    SYNTHESIZED,
    /** The model was already synthesized by an earlier delivery — this call was a no-op. */
    ALREADY_SYNTHESIZED
  }

  /** Outcome of a {@link #bake()} call. */
  public enum BakeResult {
    /** This call materialised and activated the build. */
    BAKED,
    /** The build was already baked by an earlier delivery — this call was a no-op. */
    ALREADY_BAKED
  }

  private final TitanStores daos;
  private final long buildId;
  private PipelineModel model; // cached after bake / lazy load

  public TitanFlowExecution(@NonNull TitanStores daos, long buildId) {
    this.daos = daos;
    this.buildId = buildId;
  }

  // ── Synthesize ────────────────────────────────────────────────────────

  /**
   * Retry-safe synthesis: turn the job's pipeline definition into the immutable {@code
   * pipeline_model_json}. The controller parses Titan YAML via {@link
   * TitanYamlParser#parseAndValidate} into a {@link PipelineModel} — no code is executed, just
   * deserialise and DAG-validate.
   *
   * <p>Idempotent: a re-delivered {@code SYNTHESIZE} task that finds {@code pipeline_model_json}
   * already populated is a no-op — the stored model is authoritative and the bake phase consumes it
   * as-is.
   *
   * @param pipelineScript the job's Titan YAML pipeline definition
   * @return {@link SynthesisResult#SYNTHESIZED} if this call synthesized the model, {@link
   *     SynthesisResult#ALREADY_SYNTHESIZED} if an earlier delivery already did.
   * @throws io.adaptiq.titan.flow.parser.PipelineParseException if the YAML is invalid or the DAG
   *     is broken — synthesis fails fast, before the build (design/38 §3).
   */
  @NonNull
  public SynthesisResult synthesize(@NonNull String pipelineScript) {
    BuildRow build =
        daos.builds()
            .findById(buildId)
            .orElseThrow(
                () -> new IllegalStateException("synthesize: build not found: " + buildId));

    if (build.pipelineModelJson != null && !build.pipelineModelJson.isBlank()) {
      LOGGER.log(
          Level.INFO, "[titan] build {0} already synthesized — skipping (retry-safe)", buildId);
      return SynthesisResult.ALREADY_SYNTHESIZED;
    }

    // Parse + DAG-validate. A bad pipeline fails synthesis here, without touching the
    // database. Titan YAML is the only accepted form.
    PipelineModel parsed = TitanYamlParser.parseAndValidate(pipelineScript);
    String modelJson = serialize(parsed);

    daos.builds().updatePipelineModelJson(buildId, modelJson);
    this.model = parsed;

    LOGGER.log(
        Level.INFO,
        "[titan] synthesized build {0}: {1} stage(s), {2} gate(s), {3} precondition(s)",
        new Object[] {
          buildId,
          parsed.getStages().size(),
          parsed.getGates().size(),
          parsed.getPreconditions().size()
        });
    return SynthesisResult.SYNTHESIZED;
  }

  // ── Bake ──────────────────────────────────────────────────────────────

  /**
   * Retry-safe, atomic bake of the <em>already-synthesized</em> {@code pipeline_model_json} into
   * the build's {@code flow_nodes} DAG (design/38 §3, the BAKE phase). Bake never re-parses the
   * pipeline script — {@link #synthesize(String)} must have run first.
   *
   * <p>Idempotency keys off the materialised DAG: a build that already has {@code flow_nodes} rows
   * is treated as baked and this call is a no-op. (It cannot key off {@code pipeline_model_json}
   * like the pre-design/38 monolithic bake did — that column is now written by the earlier
   * synthesis phase.)
   *
   * @return {@link BakeResult#BAKED} if this call baked it, {@link BakeResult#ALREADY_BAKED} if an
   *     earlier delivery already did.
   * @throws IllegalStateException if the build has no synthesized model — synthesize must run
   *     before bake.
   */
  @NonNull
  public BakeResult bake() {
    BuildRow build =
        daos.builds()
            .findById(buildId)
            .orElseThrow(() -> new IllegalStateException("bake: build not found: " + buildId));

    if (build.pipelineModelJson == null || build.pipelineModelJson.isBlank()) {
      throw new IllegalStateException(
          "bake: build " + buildId + " has no synthesized model — synthesize must run first");
    }

    // Retry-safety: a re-delivered BAKE task finds the DAG already materialised and skips.
    // The baked DAG is immutable (design/26 Tier B); re-baking would duplicate flow nodes.
    if (daos.flowNodes().countByBuild(buildId) > 0) {
      LOGGER.log(
          Level.INFO, "[titan] build {0} already baked — skipping rebake (retry-safe)", buildId);
      return BakeResult.ALREADY_BAKED;
    }

    // Consume the synthesized model — no re-parse of the pipeline script.
    PipelineModel parsed = deserialize(build.pipelineModelJson);

    // Resolve the declared parameters against the values this build supplied: apply defaults,
    // enforce required, coerce types, check choices. A bad parameter fails the bake here.
    Map<String, Object> effectiveParams =
        ParameterResolver.resolve(parsed.getParameters(), parseParams(build.parametersJson));

    String effectiveParamsJson = serialize(effectiveParams);

    // Effective params + every flow_nodes row + the RUNNING transition commit together.
    // `when:` is evaluated against the effective (defaults-applied) params.
    daos.withTransaction(
        conn -> {
          materialise(conn, parsed, effectiveParams);
          TitanStores.onConnection(
              conn,
              BuildDao.class,
              b -> {
                b.updateParametersJson(buildId, effectiveParamsJson);
                if (!b.activateIfQueued(buildId, Instant.now())) {
                  LOGGER.log(
                      Level.WARNING,
                      "[titan] build {0} was not QUEUED at bake — status left as-is",
                      buildId);
                }
                return null;
              });
          return null;
        });

    this.model = parsed;
    LOGGER.log(
        Level.INFO,
        "[titan] baked build {0}: {1} stage(s), {2} gate(s), {3} precondition(s)",
        new Object[] {
          buildId,
          parsed.getStages().size(),
          parsed.getGates().size(),
          parsed.getPreconditions().size()
        });
    return BakeResult.BAKED;
  }

  /**
   * Convenience for the synthesis + bake phases back to back, from a pipeline script — the
   * pre-design/38 monolithic flow. Used by tests and any caller that wants both phases without the
   * {@code QueueProcessor}'s task hand-off.
   *
   * @param pipelineScript the job's Titan YAML pipeline definition
   * @return {@link BakeResult#BAKED} if this call baked it, {@link BakeResult#ALREADY_BAKED} if an
   *     earlier delivery already did (synthesis is run unconditionally; it is itself idempotent).
   */
  @NonNull
  public BakeResult bake(@NonNull String pipelineScript) {
    synthesize(pipelineScript);
    return bake();
  }

  /** Materialise every DAG node into {@code flow_nodes}, on the bake transaction's connection. */
  private void materialise(
      @NonNull java.sql.Connection conn,
      @NonNull PipelineModel pipeline,
      @NonNull Map<String, Object> params) {

    // dependsOn references nodes by name; flow_nodes edges are by id.
    Map<String, String> nameToId = new HashMap<>();
    for (PipelineNode n : pipeline.getAllNodes()) {
      nameToId.put(n.getName(), n.getId());
    }

    TitanStores.onConnection(
        conn,
        FlowNodeDao.class,
        flowNodes -> {
          for (StageModel stage : pipeline.getStages()) {
            boolean skipped = isSkippedByWhen(stage, params);
            // design/68 / #947: a failure-handler stage (non-empty onFailureStages)
            // is NOT a DAG root even though its dependsOn list is empty — it must
            // sit PENDING until the orchestrator's onFailure gate decides
            // FIRE/SKIP based on upstream terminal state. Treat as non-root here.
            String stageStatus =
                skipped
                    ? "SKIPPED"
                    : (stage.getOnFailureStages().isEmpty()
                        ? rootOrPending(stage.getDependsOn())
                        : "PENDING");
            FlowNodeRow stageNode =
                newNode(
                    stage.getId(),
                    "STAGE",
                    stage.getName(),
                    null,
                    stageStatus,
                    parentIdsOf(stage.getDependsOn(), nameToId));
            stageNode.agentLabel = stage.getAgentLabel();
            flowNodes.insert(stageNode);

            String stepStatus = skipped ? "SKIPPED" : "PENDING";
            for (StepModel step : stage.getSteps()) {
              FlowNodeRow stepNode =
                  newNode(
                      step.getId(),
                      "STEP",
                      step.getDescriptorId(),
                      step.getDescriptorId(),
                      stepStatus,
                      stage.getId());
              stepNode.stepArgsJson = serialize(step);
              // design/44 §5: copy the step's retry policy maxAttempts onto the node so the
              // orchestrator's failure path and the graph API read "attempt k of N" without
              // re-loading the pipeline model. Absent policy / maxAttempts <= 1 → no retry.
              if (step.getRetry() != null && step.getRetry().getMaxAttempts() > 1) {
                stepNode.maxAttempts = step.getRetry().getMaxAttempts();
              }
              flowNodes.insert(stepNode);
            }
          }
          // Gates and preconditions are control nodes — materialised as STAGE-typed rows
          // (the flow_nodes node_type domain is STAGE/STEP/PARALLEL_BRANCH) with the kind
          // recorded in step_descriptor. Their full detail lives in pipeline_model_json.
          for (GateModel gate : pipeline.getGates()) {
            flowNodes.insert(
                newNode(
                    gate.getId(),
                    "STAGE",
                    gate.getName(),
                    "gate",
                    rootOrPending(gate.getDependsOn()),
                    parentIdsOf(gate.getDependsOn(), nameToId)));
          }
          for (PreconditionModel pre : pipeline.getPreconditions()) {
            flowNodes.insert(
                newNode(
                    pre.getId(),
                    "STAGE",
                    pre.getName(),
                    "precondition",
                    rootOrPending(pre.getDependsOn()),
                    parentIdsOf(pre.getDependsOn(), nameToId)));
          }
          return null;
        });
  }

  // ── Read-only queries (used by the bake IT and, later, 6D) ────────────

  /** Flow nodes currently active — {@code RUNNING} or {@code QUEUED}. */
  @NonNull
  public List<FlowNodeRow> getCurrentHeads() {
    return daos.flowNodes().listByBuildAndStatuses(buildId, "RUNNING", "QUEUED");
  }

  /** True once every node in the build has reached a terminal status. */
  public boolean isComplete() {
    return daos.flowNodes().countNonTerminal(buildId) == 0;
  }

  /**
   * Aggregate the node statuses into the build result.
   *
   * @return {@code FAILED} / {@code ABORTED} / {@code SUCCESS}, or {@code null} while the build
   *     still has a non-terminal node.
   */
  @Nullable
  public String getResult() {
    boolean anyFailed = false;
    boolean anyAborted = false;
    for (FlowNodeRow n : daos.flowNodes().listByBuild(buildId)) {
      if (!TERMINAL_STATUSES.contains(n.status)) {
        return null;
      }
      if ("FAILED".equals(n.status)) {
        anyFailed = true;
      } else if ("ABORTED".equals(n.status)) {
        anyAborted = true;
      }
    }
    if (anyFailed) {
      return "FAILED";
    }
    if (anyAborted) {
      return "ABORTED";
    }
    return "SUCCESS";
  }

  /** Lazy-load the baked {@link PipelineModel} from the build's persisted JSON. */
  @NonNull
  public PipelineModel loadModel() {
    if (model != null) {
      return model;
    }
    BuildRow build =
        daos.builds()
            .findById(buildId)
            .orElseThrow(() -> new IllegalStateException("loadModel: build not found: " + buildId));
    if (build.pipelineModelJson == null || build.pipelineModelJson.isBlank()) {
      throw new IllegalStateException("loadModel: build " + buildId + " is not baked");
    }
    try {
      model = JSON.readValue(build.pipelineModelJson, PipelineModel.class);
      return model;
    } catch (Exception e) {
      throw new IllegalStateException(
          "loadModel: cannot deserialize model for build " + buildId, e);
    }
  }

  // ── Helpers ───────────────────────────────────────────────────────────

  /**
   * A {@code when:} stage is skipped when its expression evaluates false against {@code params}.
   * Delegates to the shared {@link WhenEvaluator} — the same evaluator the orchestrator uses for
   * step-level {@code when:} at run time (PR #260).
   */
  private static boolean isSkippedByWhen(
      @NonNull StageModel stage, @NonNull Map<String, Object> params) {
    return WhenEvaluator.isSkipped(stage.getWhen(), Map.of("params", params));
  }

  private static String rootOrPending(@NonNull List<String> dependsOn) {
    return dependsOn.isEmpty() ? "QUEUED" : "PENDING";
  }

  @Nullable
  private static String parentIdsOf(
      @NonNull List<String> dependsOnNames, @NonNull Map<String, String> nameToId) {
    if (dependsOnNames.isEmpty()) {
      return null;
    }
    StringBuilder sb = new StringBuilder();
    for (String name : dependsOnNames) {
      if (sb.length() > 0) {
        sb.append(',');
      }
      sb.append(nameToId.get(name)); // guaranteed present — DAG validator ran
    }
    return sb.toString();
  }

  @NonNull
  private FlowNodeRow newNode(
      @NonNull String nodeId,
      @NonNull String nodeType,
      @Nullable String displayName,
      @Nullable String stepDescriptor,
      @NonNull String status,
      @Nullable String parentIds) {
    FlowNodeRow row = new FlowNodeRow();
    row.buildId = buildId;
    row.nodeId = nodeId;
    row.nodeType = nodeType;
    row.displayName = displayName;
    row.stepDescriptor = stepDescriptor;
    row.status = status;
    row.parentIds = parentIds;
    return row;
  }

  @NonNull
  private static Map<String, Object> parseParams(@Nullable String parametersJson) {
    if (parametersJson == null || parametersJson.isBlank()) {
      return Map.of();
    }
    try {
      Map<String, Object> parsed = JSON.readValue(parametersJson, MAP_TYPE);
      return parsed != null ? parsed : Map.of();
    } catch (Exception e) {
      throw new IllegalStateException("bake: build parameters_json is not a JSON object", e);
    }
  }

  @NonNull
  private static String serialize(@NonNull Object value) {
    try {
      return JSON.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException("bake: failed to serialize " + value.getClass(), e);
    }
  }

  /** Deserialize the persisted {@code pipeline_model_json} that synthesis stored. */
  @NonNull
  private static PipelineModel deserialize(@NonNull String modelJson) {
    try {
      return JSON.readValue(modelJson, PipelineModel.class);
    } catch (Exception e) {
      throw new IllegalStateException("bake: failed to deserialize synthesized model", e);
    }
  }
}
