package io.adaptiq.titan.flow.model;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A single pipeline step (e.g. {@code sh}, {@code echo}, {@code git}).
 *
 * <p>Instances are Jackson-friendly: a public no-arg constructor plus getters/setters allow
 * transparent serialization to and from JSON via {@code ObjectMapper}.
 *
 * <p>The {@link #parentIds} list captures ordering constraints inside the execution DAG — a step
 * whose {@code parentIds} contains {@code "stage-1-step-1"} must not start before that step has
 * completed.
 */
public class StepModel {

  /** Unique identifier within the pipeline, e.g. {@code "stage-1-step-2"}. */
  @NonNull private String id = "";

  /** Kind of step — {@code "sh"}, {@code "echo"}, {@code "git"}, etc. */
  @NonNull private String descriptorId = "";

  /** Free-form arguments passed to the step. */
  @NonNull private Map<String, Object> arguments = new LinkedHashMap<>();

  /** IDs of parent nodes in the DAG (ordering dependencies). */
  @NonNull private List<String> parentIds = new ArrayList<>();

  /**
   * For a {@code script} step body — the runtime the body is written in ({@code "groovy"}, {@code
   * "shell"}, …), design/29 §3. {@code null} for an ordinary descriptor step. The body runs on an
   * agent, to completion; the controller never executes it (design/29 §0). 6F hardens dispatch of
   * this step.
   */
  @edu.umd.cs.findbugs.annotations.Nullable private String runtime;

  /**
   * For a {@code script} step body — the source text shipped to the agent. {@code null} otherwise.
   */
  @edu.umd.cs.findbugs.annotations.Nullable private String body;

  /**
   * Container image this step runs in (design/31 §6G). {@code null} → the step inherits its stage's
   * {@code image}, or runs as a local process if the stage has none. Declarative data, not an
   * imperative call — see {@code StageModel.image}.
   */
  @edu.umd.cs.findbugs.annotations.Nullable private String image;

  /**
   * Credential bindings scoped to this step (design/39, implementing design/32 §12 D6). Titan has
   * no blocks, so a credential binding is not a wrapping construct — it is a declarative property
   * of the step it applies to. Each entry names a credential-store id and the shape it is bound in;
   * the controller resolves them at dispatch time and the worker applies them as masked,
   * step-scoped env (design/39 §3). A stage-level {@code credentials:} list is flattened onto its
   * steps at parse time, so the baked DAG only ever carries step-scoped bindings.
   */
  @NonNull private List<CredentialBinding> credentials = new ArrayList<>();

  /**
   * SSH-agent credential ids scoped to this step (design/41, extending design/39). Each entry is a
   * credential-store id naming an SSH private key; the controller resolves them at dispatch and the
   * worker loads them into a per-step {@code ssh-agent} whose lifetime is exactly the step's shell
   * process (design/41 §4). Titan has no blocks, so {@code sshagent} is not a wrapping construct —
   * it is a declarative property of the {@code sh} step it applies to (design/41 §2.1). A
   * stage-level {@code sshAgent:} list is flattened onto its steps at parse time (de-duplicated,
   * order preserved), so the baked DAG only ever carries step-scoped ids.
   */
  @NonNull private List<String> sshAgent = new ArrayList<>();

  /**
   * The declarative retry policy scoped to this step (design/44 §1), or {@code null} for no step
   * retry — today's behaviour, where a non-zero exit folds straight to a {@code FAILED} node. Titan
   * has no CPS and no blocks, so {@code retry} is not a wrapping construct — it is a declarative
   * property of the step it applies to, parsed off the {@code retry:} grammar scope. A stage-level
   * {@code retry:} is flattened onto every step in the stage at parse time; a step's own {@code
   * retry:} wins. Carried as a Temporal-shaped {@link RetryPolicy}: bounded attempts, exponential
   * backoff with a cap, an optional retryable-exit-code allowlist.
   */
  @edu.umd.cs.findbugs.annotations.Nullable private RetryPolicy retry;

  /**
   * Per-step execution deadline in milliseconds; {@code null} = no timeout. A stage-level {@code
   * timeout:} is flattened onto every step that lacks its own; a step's own {@code timeout:} wins —
   * exactly the {@code retry:} flattening rule (timer subsystem, Phase 3).
   */
  @edu.umd.cs.findbugs.annotations.Nullable private Long timeoutMillis;

  /**
   * Optional CEL-subset {@code when:} condition for this specific step (design/29 §3, GH #240).
   * When present the orchestrator evaluates the expression before dispatching the step; if it
   * evaluates false the step is materialised {@code SKIPPED}. {@code null} means the step always
   * runs (subject only to any stage-level {@code when:} that guards the entire stage). This is
   * step-scoped — it is independent of, and does not inherit from, the stage's own {@code when:}.
   */
  @edu.umd.cs.findbugs.annotations.Nullable private String when;

  /**
   * Optional <strong>structured</strong> {@code when:} condition for this step (GH #1093) — the
   * typed discriminated union of {@code branch} / {@code previous} / {@code files_changed}. A step
   * carries <em>either</em> this or the legacy CEL string {@link #when}, never both: the grammar's
   * {@code when:} key is a {@code oneOf[string, object]} and {@code WhenScope} picks the branch at
   * parse time. {@code null} means no structured guard. The orchestrator evaluates it at
   * step-dispatch time; a false condition materialises the step {@code SKIPPED}.
   */
  @edu.umd.cs.findbugs.annotations.Nullable private WhenCondition whenCondition;

  /**
   * Step-level environment variables (GH #239). {@code null} when the {@code env:} key is absent —
   * preserving the "absent vs empty" distinction. At dispatch time the worker receives the merged
   * map (pipeline env ← stage env ← step env), with later layers overriding earlier.
   */
  @Nullable private Map<String, String> env;

  /** Default constructor for Jackson deserialization. */
  public StepModel() {}

  /**
   * Convenience constructor.
   *
   * @param id unique step id
   * @param descriptorId step descriptor
   * @param arguments step arguments
   * @param parentIds parent node ids for DAG ordering
   */
  public StepModel(
      @NonNull String id,
      @NonNull String descriptorId,
      @NonNull Map<String, Object> arguments,
      @NonNull List<String> parentIds) {
    this.id = id;
    this.descriptorId = descriptorId;
    this.arguments = new LinkedHashMap<>(arguments);
    this.parentIds = new ArrayList<>(parentIds);
  }

  @NonNull
  public String getId() {
    return id;
  }

  public void setId(@NonNull String id) {
    this.id = id;
  }

  @NonNull
  public String getDescriptorId() {
    return descriptorId;
  }

  public void setDescriptorId(@NonNull String descriptorId) {
    this.descriptorId = descriptorId;
  }

  @NonNull
  public Map<String, Object> getArguments() {
    return arguments;
  }

  public void setArguments(@NonNull Map<String, Object> arguments) {
    this.arguments = arguments;
  }

  @NonNull
  public List<String> getParentIds() {
    return parentIds;
  }

  public void setParentIds(@NonNull List<String> parentIds) {
    this.parentIds = parentIds;
  }

  @edu.umd.cs.findbugs.annotations.Nullable
  public String getRuntime() {
    return runtime;
  }

  public void setRuntime(@edu.umd.cs.findbugs.annotations.Nullable String runtime) {
    this.runtime = runtime;
  }

  @edu.umd.cs.findbugs.annotations.Nullable
  public String getBody() {
    return body;
  }

  public void setBody(@edu.umd.cs.findbugs.annotations.Nullable String body) {
    this.body = body;
  }

  @edu.umd.cs.findbugs.annotations.Nullable
  public String getImage() {
    return image;
  }

  public void setImage(@edu.umd.cs.findbugs.annotations.Nullable String image) {
    this.image = image;
  }

  @NonNull
  public List<CredentialBinding> getCredentials() {
    return credentials;
  }

  public void setCredentials(@NonNull List<CredentialBinding> credentials) {
    this.credentials = credentials;
  }

  @NonNull
  public List<String> getSshAgent() {
    return sshAgent;
  }

  public void setSshAgent(@NonNull List<String> sshAgent) {
    this.sshAgent = sshAgent;
  }

  @edu.umd.cs.findbugs.annotations.Nullable
  public RetryPolicy getRetry() {
    return retry;
  }

  public void setRetry(@edu.umd.cs.findbugs.annotations.Nullable RetryPolicy retry) {
    this.retry = retry;
  }

  /** Per-step execution deadline in milliseconds; {@code null} = no timeout. */
  @edu.umd.cs.findbugs.annotations.Nullable
  public Long getTimeoutMillis() {
    return timeoutMillis;
  }

  public void setTimeoutMillis(@edu.umd.cs.findbugs.annotations.Nullable Long timeoutMillis) {
    this.timeoutMillis = timeoutMillis;
  }

  /** Optional per-step condition expression (design/29 §3, GH #240); {@code null} = always run. */
  @edu.umd.cs.findbugs.annotations.Nullable
  public String getWhen() {
    return when;
  }

  public void setWhen(@edu.umd.cs.findbugs.annotations.Nullable String when) {
    this.when = when;
  }

  /** Optional structured per-step {@code when:} guard (GH #1093); {@code null} = no guard. */
  @edu.umd.cs.findbugs.annotations.Nullable
  public WhenCondition getWhenCondition() {
    return whenCondition;
  }

  public void setWhenCondition(
      @edu.umd.cs.findbugs.annotations.Nullable WhenCondition whenCondition) {
    this.whenCondition = whenCondition;
  }

  /** Step-level environment variables; {@code null} when not declared (absent vs empty). */
  @Nullable
  public Map<String, String> getEnv() {
    return env;
  }

  public void setEnv(@Nullable Map<String, String> env) {
    this.env = env;
  }
}
