package io.adaptiq.titan.flow.model;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A single pipeline stage (e.g. "Build", "Test", "Deploy").
 *
 * <p>Stages form a DAG via {@link #parentStageIds}; root stages have an empty list. When {@link
 * #parallel} is {@code true}, the child stages of this stage execute concurrently.
 *
 * <p>Jackson-friendly: public no-arg constructor plus getters/setters.
 */
public class StageModel implements PipelineNode {

  /** Human-readable stage name, e.g. {@code "Build"}. */
  @NonNull private String name = "";

  /** Unique identifier, e.g. {@code "stage-1"}. */
  @NonNull private String id = "";

  /** Ordered list of steps within this stage. */
  @NonNull private List<StepModel> steps = new ArrayList<>();

  /** Requested agent label ({@code null} if unset / inherited). */
  @Nullable private String agentLabel;

  /**
   * Container image this stage's steps run in (design/31 §6G). {@code null} → the steps run as
   * local processes on the worker host. A step may override it with its own {@code image}. This is
   * declarative <em>data</em> on the stage — the WYSIWYG runtime environment, visible to the
   * controller and the reader alike — never an imperative in-step {@code image(){}} call.
   */
  @Nullable private String image;

  /** IDs of parent stages in the DAG for ordering and parallel support. */
  @NonNull private List<String> parentStageIds = new ArrayList<>();

  /** Whether children of this stage execute in parallel. */
  private boolean parallel;

  /**
   * Names of the nodes this stage depends on (design/29 §3 {@code dependsOn}). Empty for a root
   * stage. This is the design/29 DAG edge — distinct from the legacy {@link #parentStageIds} (by
   * id) which the pre-6C {@code TitanFlowExecution} still consumes.
   */
  @NonNull private List<String> dependsOn = new ArrayList<>();

  /**
   * Optional CEL-subset {@code when:} expression (design/29 §4). Evaluated at bake time against
   * {@code params}; a stage that evaluates false is materialised {@code SKIPPED}. {@code null}
   * means the stage always runs. The expression string is opaque to the model — the evaluator is
   * Chunk 6B.
   */
  @Nullable private String when;

  /**
   * Stage-level environment variables (GH #239). {@code null} when the {@code env:} key is absent —
   * preserving the "absent vs empty" distinction. Merged into every step's effective environment at
   * dispatch; step-level env overrides these (step > stage > pipeline precedence).
   */
  @Nullable private Map<String, String> env;

  /**
   * Stage-level lifecycle hooks (#245). Fired by the orchestrator when this stage reaches its
   * terminal state — regardless of which step ran. Empty when not declared.
   */
  @NonNull private List<NotifyHook> notify = new ArrayList<>();

  /**
   * Names of upstream stages whose failure triggers this stage (design/68 — failure-handler stages,
   * #947). Empty when not declared (the common case: a regular stage advancing on upstream success
   * via {@link #dependsOn}). When non-empty, this stage is a <em>failure handler</em>: it advances
   * iff at least one listed upstream stage reached a terminal {@code FAILED} state, AND its
   * optional {@link #when} expression evaluates true.
   *
   * <p>Mutually exclusive with {@link #dependsOn} at parse time (a stage cannot be both an
   * advance-on-success node and a failure handler — see design/68 D1). The references are
   * <em>pre-expansion</em> stage names: a reference to a matrix/each prototype name resolves (in
   * the orchestrator) to "any of the prototype's expanded cells reached FAILED" — the PDL contract
   * is the user-written name (design/68 D3).
   *
   * <p>Forward-compatible: an engine that does not yet recognise the field simply never advances
   * such a stage (it has no {@code dependsOn} to wake it on success and the engine doesn't know to
   * wake it on failure) — the safe fail-closed default.
   */
  @NonNull private List<String> onFailureStages = new ArrayList<>();

  /** Default constructor for Jackson deserialization. */
  public StageModel() {}

  /**
   * Convenience constructor.
   *
   * @param name stage name
   * @param id unique stage id
   * @param steps steps within this stage
   * @param agentLabel requested agent label (nullable)
   * @param parentStageIds parent stage ids for DAG ordering
   * @param parallel whether children execute in parallel
   */
  public StageModel(
      @NonNull String name,
      @NonNull String id,
      @NonNull List<StepModel> steps,
      @Nullable String agentLabel,
      @NonNull List<String> parentStageIds,
      boolean parallel) {
    this.name = name;
    this.id = id;
    this.steps = new ArrayList<>(steps);
    this.agentLabel = agentLabel;
    this.parentStageIds = new ArrayList<>(parentStageIds);
    this.parallel = parallel;
  }

  @NonNull
  public String getName() {
    return name;
  }

  public void setName(@NonNull String name) {
    this.name = name;
  }

  @NonNull
  public String getId() {
    return id;
  }

  public void setId(@NonNull String id) {
    this.id = id;
  }

  @NonNull
  public List<StepModel> getSteps() {
    return steps;
  }

  public void setSteps(@NonNull List<StepModel> steps) {
    this.steps = steps;
  }

  @Nullable
  public String getAgentLabel() {
    return agentLabel;
  }

  public void setAgentLabel(@Nullable String agentLabel) {
    this.agentLabel = agentLabel;
  }

  @NonNull
  public List<String> getParentStageIds() {
    return parentStageIds;
  }

  public void setParentStageIds(@NonNull List<String> parentStageIds) {
    this.parentStageIds = parentStageIds;
  }

  public boolean isParallel() {
    return parallel;
  }

  public void setParallel(boolean parallel) {
    this.parallel = parallel;
  }

  @Override
  @NonNull
  public List<String> getDependsOn() {
    return dependsOn;
  }

  public void setDependsOn(@NonNull List<String> dependsOn) {
    this.dependsOn = dependsOn;
  }

  @Nullable
  public String getWhen() {
    return when;
  }

  public void setWhen(@Nullable String when) {
    this.when = when;
  }

  /** Stage-level environment variables; {@code null} when not declared (absent vs empty). */
  @Nullable
  public Map<String, String> getEnv() {
    return env;
  }

  public void setEnv(@Nullable Map<String, String> env) {
    this.env = env;
  }

  @Nullable
  public String getImage() {
    return image;
  }

  public void setImage(@Nullable String image) {
    this.image = image;
  }

  /** Stage-level lifecycle hooks (#245); never {@code null}, empty when not declared. */
  @NonNull
  public List<NotifyHook> getNotify() {
    return notify;
  }

  public void setNotify(@NonNull List<NotifyHook> notify) {
    this.notify = notify;
  }

  /**
   * Upstream stages whose failure triggers this stage (design/68, #947); never {@code null}, empty
   * when not declared. Mutually exclusive with {@link #dependsOn}.
   */
  @NonNull
  public List<String> getOnFailureStages() {
    return onFailureStages;
  }

  public void setOnFailureStages(@NonNull List<String> onFailureStages) {
    this.onFailureStages = onFailureStages;
  }

  @Override
  @com.fasterxml.jackson.annotation.JsonIgnore
  @NonNull
  public String getNodeType() {
    return "STAGE";
  }
}
