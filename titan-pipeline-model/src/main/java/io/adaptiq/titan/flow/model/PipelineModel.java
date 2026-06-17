package io.adaptiq.titan.flow.model;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Top-level model representing a fully parsed pipeline.
 *
 * <p>The pipeline is a directed acyclic graph of {@link StageModel stages}, each containing an
 * ordered list of {@link StepModel steps}. Navigation helpers allow traversal by stage or step id
 * without requiring callers to iterate manually.
 *
 * <p>Jackson-friendly: public no-arg constructor plus getters/setters.
 */
public class PipelineModel {

  /** All stages in the pipeline. */
  @NonNull private List<StageModel> stages = new ArrayList<>();

  /** Declared build parameters (design/29 §5). */
  @NonNull private List<ParameterModel> parameters = new ArrayList<>();

  /** Declared build triggers (design/50) — schedules that start a build automatically. */
  @NonNull private List<TriggerModel> triggers = new ArrayList<>();

  /** All manual-approval gate nodes (design/29 §3). */
  @NonNull private List<GateModel> gates = new ArrayList<>();

  /** All automated-precondition nodes (design/29 §4). */
  @NonNull private List<PreconditionModel> preconditions = new ArrayList<>();

  /** Default agent label for stages that do not request their own ({@code null} = any). */
  @Nullable private String agent;

  /**
   * Build-level failure policy (design/29 §3, issue #392). {@link FailurePolicy#BLOCK_ON_FAILURE}
   * (default) stops the DAG on the first failed node; {@link FailurePolicy#CONTINUE_ON_FAILURE}
   * lets the DAG run past a failed node. Typed enum, not a free-form string — unknown YAML values
   * are rejected at parse time, not silently dropped at runtime.
   */
  @NonNull private FailurePolicy failurePolicy = FailurePolicy.BLOCK_ON_FAILURE;

  /**
   * Alias → library-coordinate map (design/53). Parsed from the YAML's {@code libraries:} block — a
   * map of alias to {@code <git-url>@<ref>}. Drives the {@code <alias>.<method>} dotted-step
   * dispatch: the parser rewrites a step descriptor matching an alias into a {@code libraryCall}
   * step. The only supported shape — arbitrary-code synthesis was removed.
   */
  @NonNull private Map<String, String> libraryAliases = new LinkedHashMap<>();

  /**
   * Per-alias credential name, parallel to {@link #libraryAliases}. Populated when the YAML's alias
   * entry is the object form {@code { url: "...", credential: "TOKEN_NAME" }}. Empty for
   * bare-string aliases (public repos). The value is the credential NAME, never the secret value —
   * resolved on the worker via {@code SecretProvider.active()} (design/40).
   */
  @NonNull private Map<String, String> libraryAliasCredentials = new LinkedHashMap<>();

  /**
   * Pipeline-level environment variables (GH #239). {@code null} when the {@code env:} key is
   * absent — preserving the "absent vs empty" distinction. An empty map ({@code env: {}}) is a
   * distinct state from no declaration. Merged into every step's effective environment at dispatch
   * time; stage- and step-level env override these (step > stage > pipeline precedence).
   */
  @Nullable private Map<String, String> env;

  /**
   * Pipeline-level execution deadline in milliseconds (issue #244). {@code null} means no
   * pipeline-wide timeout — the build runs until natural completion or some step/stage-level
   * timeout fires. When set, the controller computes a wall-clock deadline at bake (deadline =
   * bakeTime + timeoutMillis) and the {@code QueueProcessor} reaper kills any still-running build
   * past that deadline as {@code FAILED}. Distinct from {@link StepModel#getTimeoutMillis()} and
   * {@link StageModel} stage-level {@code timeout:}: a pipeline-root timeout is the outermost
   * safety net — it catches a hung pipeline where no step/stage timeout fires.
   *
   * <p>Grammar: {@code 30s}, {@code 5m}, {@code 2h}, {@code 1d}, or bare seconds.
   */
  @Nullable private Long timeoutMillis;

  /**
   * Pipeline-level lifecycle hooks (#245). Fired by the orchestrator at the build's terminal state
   * — regardless of which step ran. Empty when not declared. Each hook is a declarative, fire-and-
   * forget {@code webhook} / (future) {@code slack} sink; see {@link NotifyHook}.
   */
  @NonNull private List<NotifyHook> notify = new ArrayList<>();

  /**
   * Per-job build-retention override (#640) — the {@code buildRetention.keepLast} value parsed from
   * the pipeline YAML. {@code null} when the {@code buildRetention:} block is absent (the common
   * case), and the daily prune falls back to the server-wide {@code TITAN_JOB_BUILD_RETENTION}
   * default. A value of {@code 0} is the per-job opt-out — keeps full history regardless of the
   * global default.
   */
  @Nullable private Integer buildRetentionKeepLast;

  /**
   * Per-job concurrency limit (#1101). {@code null} when the {@code concurrency:} block is absent
   * (the legacy default) — the engine then runs unlimited builds of this job in parallel. When
   * present, the engine gates new builds at synthesis-time per {@link ConcurrencyConfig}.
   */
  @Nullable private ConcurrencyConfig concurrency;

  /**
   * Per-job queue priority (#1100). {@code null} when the {@code priority:} block is absent — the
   * engine treats the job as {@link PriorityConfig#NORMAL} (weight 0, same as the column default).
   * When present, every controller-side ORCHESTRATE enqueue for builds of this job stamps {@link
   * PriorityConfig#weight()} into {@code task_queue.priority} so the highest-priority job is
   * claimed first.
   */
  @Nullable private PriorityConfig priority;

  /** Default constructor for Jackson deserialization. */
  public PipelineModel() {}

  /**
   * Convenience constructor.
   *
   * @param stages all stages in the pipeline
   */
  public PipelineModel(@NonNull List<StageModel> stages) {
    this.stages = new ArrayList<>(stages);
  }

  @NonNull
  public List<StageModel> getStages() {
    return stages;
  }

  public void setStages(@NonNull List<StageModel> stages) {
    this.stages = stages;
  }

  @NonNull
  public List<ParameterModel> getParameters() {
    return parameters;
  }

  public void setParameters(@NonNull List<ParameterModel> parameters) {
    this.parameters = parameters;
  }

  @NonNull
  public List<TriggerModel> getTriggers() {
    return triggers;
  }

  public void setTriggers(@NonNull List<TriggerModel> triggers) {
    this.triggers = triggers;
  }

  @NonNull
  public List<GateModel> getGates() {
    return gates;
  }

  public void setGates(@NonNull List<GateModel> gates) {
    this.gates = gates;
  }

  @NonNull
  public List<PreconditionModel> getPreconditions() {
    return preconditions;
  }

  public void setPreconditions(@NonNull List<PreconditionModel> preconditions) {
    this.preconditions = preconditions;
  }

  @Nullable
  public String getAgent() {
    return agent;
  }

  public void setAgent(@Nullable String agent) {
    this.agent = agent;
  }

  @NonNull
  public FailurePolicy getFailurePolicy() {
    return failurePolicy;
  }

  public void setFailurePolicy(@NonNull FailurePolicy failurePolicy) {
    this.failurePolicy = failurePolicy;
  }

  @NonNull
  public Map<String, String> getLibraryAliases() {
    return libraryAliases;
  }

  public void setLibraryAliases(@NonNull Map<String, String> libraryAliases) {
    this.libraryAliases = libraryAliases;
  }

  @NonNull
  public Map<String, String> getLibraryAliasCredentials() {
    return libraryAliasCredentials;
  }

  public void setLibraryAliasCredentials(@NonNull Map<String, String> libraryAliasCredentials) {
    this.libraryAliasCredentials = libraryAliasCredentials;
  }

  /** Pipeline-level environment variables; {@code null} when not declared (absent vs empty). */
  @Nullable
  public Map<String, String> getEnv() {
    return env;
  }

  public void setEnv(@Nullable Map<String, String> env) {
    this.env = env;
  }

  /** Pipeline-level execution deadline in milliseconds; {@code null} when not declared. */
  @Nullable
  public Long getTimeoutMillis() {
    return timeoutMillis;
  }

  public void setTimeoutMillis(@Nullable Long timeoutMillis) {
    this.timeoutMillis = timeoutMillis;
  }

  /** Pipeline-level lifecycle hooks (#245); never {@code null}, empty when not declared. */
  @NonNull
  public List<NotifyHook> getNotify() {
    return notify;
  }

  public void setNotify(@NonNull List<NotifyHook> notify) {
    this.notify = notify;
  }

  /**
   * Per-job build-retention override (#640); {@code null} when not declared — the daily prune falls
   * back to the server-wide {@code TITAN_JOB_BUILD_RETENTION} default.
   */
  @Nullable
  public Integer getBuildRetentionKeepLast() {
    return buildRetentionKeepLast;
  }

  public void setBuildRetentionKeepLast(@Nullable Integer buildRetentionKeepLast) {
    this.buildRetentionKeepLast = buildRetentionKeepLast;
  }

  /**
   * Per-job concurrency policy (#1101); {@code null} when no {@code concurrency:} block is declared
   * — the engine treats the job as unlimited (backwards compatible).
   */
  @Nullable
  public ConcurrencyConfig getConcurrency() {
    return concurrency;
  }

  public void setConcurrency(@Nullable ConcurrencyConfig concurrency) {
    this.concurrency = concurrency;
  }

  /**
   * Per-job queue priority (#1100); {@code null} when no {@code priority:} block is declared — the
   * engine treats the job as {@link PriorityConfig#NORMAL}.
   */
  @Nullable
  public PriorityConfig getPriority() {
    return priority;
  }

  public void setPriority(@Nullable PriorityConfig priority) {
    this.priority = priority;
  }

  /**
   * Effective integer priority weight for this pipeline — {@link PriorityConfig#weight()} when
   * declared, else {@link PriorityConfig#NORMAL}'s weight ({@code 0}).
   */
  public int effectivePriorityWeight() {
    return (priority != null ? priority : PriorityConfig.NORMAL).weight();
  }

  /**
   * Every top-level DAG node — stages, gates and preconditions — as a uniform {@link PipelineNode}
   * list. The order is stages, then gates, then preconditions; callers that need execution order
   * use {@code dependsOn}, not list order.
   *
   * <p>{@code @JsonIgnore}: a derived view, not persisted state — {@code PipelineNode} is an
   * interface and would not round-trip.
   *
   * @return all DAG nodes, never {@code null}
   */
  @com.fasterxml.jackson.annotation.JsonIgnore
  @NonNull
  public List<PipelineNode> getAllNodes() {
    List<PipelineNode> all = new ArrayList<>(stages.size() + gates.size() + preconditions.size());
    all.addAll(stages);
    all.addAll(gates);
    all.addAll(preconditions);
    return all;
  }

  /**
   * Every id that can serve as a replay anchor — stages, gates, preconditions AND step ids. Used by
   * {@code BuildService.replay} to validate the {@code nodeId} accepted from the UI's per-step
   * "Replay from here" button (issue #621). Distinct from {@link #getAllNodes()}, which is the
   * DAG-only view consumed by {@code PipelineDagValidator} and the orchestrator's stage-graph walk
   * — steps are not DAG nodes (their ordering is purely intra-stage). Returns true for any id that
   * the orchestrator's REPLAY_FROM_NODE handler knows how to anchor on.
   *
   * @param nodeId candidate node id (stage / gate / precondition / step)
   * @return true iff a node or step with that id exists in this model
   */
  public boolean containsReplayAnchor(@NonNull String nodeId) {
    for (PipelineNode n : getAllNodes()) {
      if (nodeId.equals(n.getId())) {
        return true;
      }
    }
    for (StageModel s : stages) {
      for (StepModel step : s.getSteps()) {
        if (nodeId.equals(step.getId())) {
          return true;
        }
      }
    }
    return false;
  }

  // ---- navigation helpers ------------------------------------------------

  /**
   * Returns root stages — those with an empty {@code parentStageIds} list.
   *
   * @return root stages, never {@code null}
   */
  @com.fasterxml.jackson.annotation.JsonIgnore
  @NonNull
  public List<StageModel> getRootStages() {
    return stages.stream()
        .filter(s -> s.getParentStageIds().isEmpty())
        .collect(Collectors.toList());
  }

  /**
   * Returns stages whose {@code parentStageIds} contains {@code stageId}.
   *
   * @param stageId parent stage id
   * @return child stages, never {@code null}
   */
  @NonNull
  public List<StageModel> getChildStages(@NonNull String stageId) {
    return stages.stream()
        .filter(s -> s.getParentStageIds().contains(stageId))
        .collect(Collectors.toList());
  }

  /**
   * Looks up a stage by id.
   *
   * @param stageId stage id
   * @return the stage, or {@code null} if not found
   */
  @Nullable
  public StageModel getStage(@NonNull String stageId) {
    return stages.stream().filter(s -> stageId.equals(s.getId())).findFirst().orElse(null);
  }

  /**
   * Looks up a step by id across all stages.
   *
   * @param stepId step id
   * @return the step, or {@code null} if not found
   */
  @Nullable
  public StepModel getStep(@NonNull String stepId) {
    return stages.stream()
        .flatMap(s -> s.getSteps().stream())
        .filter(st -> stepId.equals(st.getId()))
        .findFirst()
        .orElse(null);
  }

  /**
   * Returns direct child node IDs of the given node.
   *
   * <ul>
   *   <li><b>Stage id</b> — returns the step IDs contained within that stage.
   *   <li><b>Step id</b> — returns the IDs of steps (across all stages) whose {@code parentIds}
   *       list contains the given step id.
   * </ul>
   *
   * @param nodeId a stage id or step id
   * @return child node IDs, never {@code null}; empty if the id is unknown
   */
  @NonNull
  public List<String> getChildren(@NonNull String nodeId) {
    // Try as a stage id first.
    StageModel stage = getStage(nodeId);
    if (stage != null) {
      return stage.getSteps().stream().map(StepModel::getId).collect(Collectors.toList());
    }

    // Otherwise treat as a step id — find steps whose parentIds contain it.
    return stages.stream()
        .flatMap(s -> s.getSteps().stream())
        .filter(st -> st.getParentIds().contains(nodeId))
        .map(StepModel::getId)
        .collect(Collectors.toList());
  }
}
