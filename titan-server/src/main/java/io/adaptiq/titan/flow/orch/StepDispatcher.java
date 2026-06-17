package io.adaptiq.titan.flow.orch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.adaptiq.titan.flow.CredentialsPort;
import io.adaptiq.titan.flow.TemplateResolver;
import io.adaptiq.titan.flow.crypto.CredentialKeyProvider;
import io.adaptiq.titan.flow.crypto.SecretCipher;
import io.adaptiq.titan.flow.model.PipelineModel;
import io.adaptiq.titan.flow.model.StageModel;
import io.adaptiq.titan.flow.model.StepModel;
import io.adaptiq.titan.flow.parser.EnvValueRef;
import io.adaptiq.titan.flow.parser.MergedEnv;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.TaskQueueRow;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Dispatches step {@code EXECUTE_COMMAND} tasks onto the durable queue (#357 decomposition).
 *
 * <p>Owns: payload-build + credential sealing + the dispatch-side failure modes (CREDENTIAL,
 * DISPATCH). Extracted verbatim from {@code TitanOrchestrator}.
 */
public final class StepDispatcher {

  private static final Logger LOGGER = Logger.getLogger(StepDispatcher.class.getName());
  private static final ObjectMapper JSON = new ObjectMapper();

  private final TitanStores daos;
  private final long buildId;
  private final CredentialsPort credentialsPort;
  private final EnvResolver envResolver;

  public StepDispatcher(
      @NonNull TitanStores daos, long buildId, @NonNull CredentialsPort credentialsPort) {
    this.daos = daos;
    this.buildId = buildId;
    this.credentialsPort = credentialsPort;
    // GH #1094: env: 'secret:<id>' values resolve against the same CredentialsPort the
    // credentials: bindings already use. The noOp port returns Optional.empty() for every id,
    // so 'secret:' refs on a deployment without a wired store fail the step at dispatch with a
    // clear "not in store" error — which is what the operator should see.
    this.envResolver = new EnvResolver(credentialsPort::resolveSecretRef);
  }

  /**
   * Enqueue an {@code EXECUTE_COMMAND} task for a step on its stage's agent-label queue. The step's
   * {@code ${{ … }}} references — in its arguments <em>and</em> in a {@code script} body — are
   * resolved against {@code outputContext} here, at dispatch time, when the producing steps'
   * outputs exist (design/29 §6/§7.1).
   *
   * <p>The step's declarative {@code credentials:} bindings (design/39, design/32 §12 D6) are
   * resolved here too — against the credentials store, on the controller — and the resulting masked
   * env / secret files travel only in this one step's payload. A credential that cannot be resolved
   * (missing id, wrong type) is not a dispatch the worker can ever recover, so the step is failed
   * immediately, in {@link #failStepForCredentials}.
   */
  public void enqueueStep(
      @NonNull StageModel stage,
      @NonNull StepModel step,
      @NonNull PipelineModel model,
      @NonNull Map<String, Object> outputContext) {
    dispatchStepTask(stage, step, model, outputContext, null);
  }

  /**
   * Re-dispatch a step's {@code EXECUTE_COMMAND} task for a retry (design/44 §4), with a future
   * {@code available_at} ({@code availableAt}) — the durable queue's delayed delivery is the
   * backoff. The payload is rebuilt fresh, re-resolving {@code credentials:}. Returns the new task
   * id (so the caller can write the retry-boundary log line against its token).
   *
   * @return the new task's id, or {@code -1} if the dispatch failed credential resolution.
   */
  public long reEnqueueStep(
      @NonNull StageModel stage,
      @NonNull StepModel step,
      @NonNull PipelineModel model,
      @NonNull Map<String, Object> outputContext,
      @NonNull Instant availableAt) {
    return dispatchStepTask(stage, step, model, outputContext, availableAt);
  }

  /**
   * Enqueue an {@code EXECUTE_COMMAND} task for a step on its stage's agent-label queue. The step's
   * {@code ${{ … }}} references and its declarative {@code credentials:} bindings (design/39) are
   * resolved here, at dispatch time, on the controller. {@code availableAt} {@code null} →
   * claimable immediately (the normal first dispatch); non-{@code null} → a delayed delivery (a
   * backed-off retry, design/44 §4).
   *
   * @return the new task's id, or {@code -1} if the step failed credential resolution.
   */
  public long dispatchStepTask(
      @NonNull StageModel stage,
      @NonNull StepModel step,
      @NonNull PipelineModel model,
      @NonNull Map<String, Object> outputContext,
      @Nullable Instant availableAt) {
    // Routing: the stage's own agent label, else the pipeline-wide default
    // (titan.agent), else the shared "default" queue. The model-level
    // fallback is what lets a stage omit `agent:` and inherit `titan.agent`.
    String queue =
        stage.getAgentLabel() != null
            ? stage.getAgentLabel()
            : model.getAgent() != null ? model.getAgent() : "default";

    // design/47 §env / GH #239 / #267 / #847: compute the merged effective env BEFORE resolving
    // ${{ … }} references, so a step that templates ${{ env.X }} into an argument or a script body
    // resolves against the SAME map that lands in payload.env (closes #1212). The order matters:
    // the env must exist before TemplateResolver runs, or `env` is an unknown variable.
    Map<String, String> mergedEnv = mergedStepEnv(stage, step, model);

    // GH #1094: split the merged env into its literal entries and its 'secret:<id>' references.
    // Literals ride the plaintext payload.env and are readable via ${{ env.X }}; resolved secrets
    // ride the SEALED credentials bundle and are NEVER written to plaintext payload.env, NEVER
    // exposed through the ${{ }} evaluator (so they can't leak into templated args or logs), and
    // are added to the worker's mask list. A 'secret:' ref that can't be resolved fails the step
    // at dispatch with the CREDENTIAL category — the worker never runs half-populated.
    EnvSplit envSplit;
    try {
      envSplit = splitEnvForDispatch(mergedEnv);
    } catch (EnvResolver.EnvResolutionException e) {
      failStepForCredentials(step, e.getMessage());
      return -1;
    }
    // The literal env is the single floor written into payload.env (below) and bound as the
    // ${{ env.X }} namespace (#1212). Engine-reserved TITAN_* names for k8sApply are layered on.
    Map<String, String> stepEnv = envSplit.plain();

    // Bind the literal env as a first-class `env` namespace in the ${{ }} resolution context
    // (#1212), alongside params / steps / pipeline. Per-step copy: the base outputContext is the
    // build-wide one (params/steps/pipeline) shared across dispatches; env is the only per-step
    // scope, so we layer it onto a fresh map and never mutate the caller's context. Resolved
    // secrets are intentionally absent from this namespace (#1094) so a ${{ env.SECRET }} template
    // cannot inline a plaintext secret into an argument or the build log.
    Map<String, Object> stepContext = new java.util.HashMap<>(outputContext);
    stepContext.put("env", new java.util.LinkedHashMap<String, Object>(stepEnv));

    Map<String, Object> resolvedArgs =
        TemplateResolver.resolveArguments(step.getArguments(), stepContext);
    // The container the step runs in (design/31 §6G): a step's own image overrides its
    // stage's; neither set → the worker runs it as a local process.
    String image = step.getImage() != null ? step.getImage() : stage.getImage();

    // design/39 / D6: resolve the step's credential bindings via the injected CredentialsPort.
    // In titan-server this delegates to Vault / the standalone secrets store; in tests the
    // no-op port returns EMPTY. A resolution failure fails the step at dispatch — the worker
    // has no path to the store and could never resolve a credential id itself.
    CredentialsPort.Resolved credentials;
    try {
      credentials = credentialsPort.resolve(step.getCredentials(), step.getSshAgent());
    } catch (CredentialsPort.CredentialResolutionException e) {
      failStepForCredentials(step, e.getMessage());
      return -1;
    }
    // GH #1094: fold the env: 'secret:<id>' values resolved above into the SAME sealed credentials
    // bundle. They become bundle env (injected into the worker process env by CredentialsAugmenter,
    // overriding any literal env clash — same "credential variable wins" rule as credentials:) and
    // bundle mask values (redacted from the step log by MaskingLogSink). The plaintext never enters
    // payload.env and never enters the ${{ }} namespace.
    credentials = augmentCredentialsWithEnvSecrets(credentials, envSplit);

    // design/39 §3.1: a step's resolved secrets are sealed (AES-256-GCM) before they enter
    // the persisted task payload. Fail closed — if no key is configured, the step is failed
    // here rather than dispatched with secrets the database would store in the clear.
    byte[] credentialKey = null;
    if (!credentials.isEmpty()) {
      credentialKey = CredentialKeyProvider.active().credentialKey();
      if (credentialKey == null) {
        failStepForCredentials(
            step,
            "the credential encryption key is not configured "
                + "(set the TITAN_CREDENTIAL_KEY environment variable on the controller) — "
                + "Titan refuses to dispatch a step's credentials to the database "
                + "un-encrypted (design/39 fail-closed)");
        return -1;
      }
    }

    // design/62 §3: the k8sApply step handler stamps every applied k8s resource with a
    // `titan.stage=<buildId>-<stageId>` label so the orchestrator's per-stage teardown can sweep
    // them with a label selector. We surface the stage / build id to the worker via the same env
    // map the user's `env:` block uses — keeps the StepRequest record unchanged. Engine-reserved
    // names (TITAN_*) are documented; the dispatcher overrides any user attempt to set them.
    if ("k8sApply".equals(step.getDescriptorId())) {
      java.util.LinkedHashMap<String, String> withTitanIds = new java.util.LinkedHashMap<>(stepEnv);
      withTitanIds.put("TITAN_BUILD_ID", Long.toString(buildId));
      withTitanIds.put("TITAN_STAGE_ID", stage.getId());
      stepEnv = withTitanIds;
    }

    String payload;
    long taskId;
    try {
      payload =
          stepPayload(
              step, resolvedArgs, model, stepContext, image, credentials, credentialKey, stepEnv);
    } catch (RuntimeException e) {
      // Building the EXECUTE_COMMAND payload threw — a malformed step, an un-serialisable
      // argument, a sealing failure. The worker has no path to recover this, so the step is
      // failed at dispatch with a customer-facing reason.
      failStepAtDispatch(
          step,
          "DISPATCH",
          "Could not build the execution payload for step '"
              + step.getId()
              + "': "
              + describe(e)
              + " — check the step's arguments in your pipeline.");
      return -1;
    }
    try {
      taskId =
          availableAt == null
              ? daos.taskQueue()
                  .enqueue("EXECUTE_COMMAND", queue, 0, payload, 3, 3600, buildId, step.getId())
              : daos.taskQueue()
                  .enqueueDelayed(
                      "EXECUTE_COMMAND",
                      queue,
                      0,
                      payload,
                      3,
                      3600,
                      availableAt,
                      buildId,
                      step.getId());
    } catch (RuntimeException e) {
      failStepAtDispatch(
          step,
          "DISPATCH",
          "Could not enqueue step '"
              + step.getId()
              + "' onto queue '"
              + queue
              + "': "
              + describe(e)
              + " — this is an internal engine error; "
              + "check the controller log.");
      return -1;
    }
    // issue #536: stamp flow_nodes.log_task_id RIGHT HERE, in the same call that dispatches
    // the EXECUTE_COMMAND. PR #510 wired this via reconcileFinishedSteps, but that path only
    // runs on a follow-up ADVANCE tick that observes the task — and on the live rig there are
    // failure shapes (final step fails, build closes in the same pass that observed the task
    // for the first time as terminal-in-archive, archive-sweep race against
    // listByBuildIncludingArchive,
    // etc.) where the reconcile setLogTaskId never fires in production even though the IT seeded
    // the exact same shape and passed. The dispatch path is the single point we know the token
    // exists AND the node is QUEUED — write here, idempotently, so the UI Logs panel can always
    // join titan.logs.task_id regardless of how / when the step ultimately terminates.
    stampLogTaskIdFromTaskId(step.getId(), taskId);
    LOGGER.log(
        Level.FINE,
        "[titan] build {0}: dispatched step {1} to queue ''{2}''{3}",
        new Object[] {
          buildId,
          step.getId(),
          queue,
          availableAt == null ? "" : " (delayed retry, available_at=" + availableAt + ")"
        });
    return taskId;
  }

  /**
   * Read back the just-inserted task's DB-generated {@code task_token} and stamp it onto the step's
   * {@code flow_nodes.log_task_id} (issue #536). Idempotent: re-running with the same token is a
   * no-op write; a retry dispatch overwrites with the new attempt's token (which is what the per-
   * node Logs panel should show — the latest attempt's stream).
   *
   * <p>Best-effort by design — a transient DB blip here must not roll back the successful dispatch
   * the orchestrator already committed. The next reconcile pass (PR #510) is the belt-and-braces
   * fallback if this fails. We swallow + log so the orchestrator keeps moving.
   */
  private void stampLogTaskIdFromTaskId(@NonNull String nodeId, long taskId) {
    try {
      TaskQueueRow row = daos.taskQueue().findById(taskId).orElse(null);
      if (row == null || row.taskToken == null) {
        return;
      }
      UUID token = row.taskToken;
      daos.flowNodes().setLogTaskId(buildId, nodeId, token);
    } catch (RuntimeException e) {
      LOGGER.log(
          Level.WARNING,
          "[titan] build {0}: stampLogTaskIdFromTaskId failed for node {1}: {2}",
          new Object[] {buildId, nodeId, e.getMessage()});
    }
  }

  /**
   * Build the {@code EXECUTE_COMMAND} payload from a step's <em>resolved</em> arguments. An {@code
   * sh} step becomes a {@code command} array the worker runs directly; a {@code script} step
   * carries its {@code runtime} + {@code body} + the pipeline's declared {@code libraries}
   * (design/29 §10) so the worker can load the shared libraries before running the Groovy body
   * (Chunk 6F). Any other descriptor carries its descriptor + arguments for a descriptor-aware
   * worker. An effective {@code image} (design/31 §6G) is added when set, so the worker runs the
   * step inside that container.
   *
   * <p>The step's resolved credentials (design/39, D6) are folded in here: the credential env is
   * merged into {@code env}; the secret values to mask go into {@code maskSecrets}; any secret file
   * goes into {@code secretFiles}; any {@code sshAgent:} key goes into {@code sshAgentKeys}
   * (design/41). All fields are additive — a step with no {@code credentials:} and no {@code
   * sshAgent:} produces none of them and an older worker simply ignores them.
   */
  @NonNull
  public String stepPayload(
      @NonNull StepModel step,
      @NonNull Map<String, Object> resolvedArgs,
      @NonNull PipelineModel model,
      @NonNull Map<String, Object> outputContext,
      @Nullable String image,
      @NonNull CredentialsPort.Resolved credentials,
      @Nullable byte[] credentialKey,
      @NonNull Map<String, String> stepEnv) {
    ObjectNode payload = JSON.createObjectNode();
    payload.put("buildId", buildId);
    payload.put("nodeId", step.getId());
    payload.put("stepDescriptor", step.getDescriptorId());
    // The container image (design/31 §6G) — the worker runs the step inside it; absent → local.
    if (image != null && !image.isBlank()) {
      payload.put("image", image);
    }
    // GH #267: write the merged pipeline ← stage ← step env into the payload. The worker's
    // TaskExecutor.buildEnv(payload) hydrates this back into a Map<String,String> and hands
    // it to every StepHandler via StepRequest.env(). Credentials.augment() merges its own
    // env keys on top later — the order is intentional: declarative env is the floor,
    // credential bindings override it.
    if (!stepEnv.isEmpty()) {
      ObjectNode envNode = payload.putObject("env");
      stepEnv.forEach(envNode::put);
    }
    applyCredentials(payload, credentials, credentialKey, step.getId());
    if ("sh".equals(step.getDescriptorId())) {
      // YAML shorthand stores the script under "value"; the script form uses the "script" key.
      Object script =
          resolvedArgs.containsKey("value")
              ? resolvedArgs.get("value")
              : resolvedArgs.get("script");
      if (script != null) {
        ArrayNode cmd = payload.putArray("command");
        cmd.add("sh");
        cmd.add("-c");
        cmd.add(String.valueOf(script));
      }
    }
    if (step.getBody() != null) {
      payload.put("runtime", step.getRuntime());
      // ${{ … }} references in the body resolve at dispatch (design/29 §6), like arguments.
      payload.put("body", TemplateResolver.resolve(step.getBody(), outputContext));
    }
    payload.set("arguments", JSON.valueToTree(resolvedArgs));
    return payload.toString();
  }

  /**
   * Seal a step's resolved credentials (design/39, D6) into its {@code EXECUTE_COMMAND} payload.
   *
   * <p><strong>The credential bundle is never written in the clear.</strong> The task payload is
   * persisted — a row in {@code titan.task_queue.payload_json} — so the resolved env, the
   * mask-values and the secret files are assembled into one JSON bundle and encrypted with
   * AES-256-GCM ({@link SecretCipher}) before they enter the payload, under a single {@code
   * credentialsSealed} field (design/39 §3.1). The AES tag is bound to {@code buildId:nodeId} as
   * additional authenticated data, so a sealed blob cannot be lifted into another task. The worker
   * holds the same key (a {@link CredentialKeyProvider}) and unseals it in memory just before the
   * step runs. The database sees ciphertext only.
   *
   * <p>{@code credentialKey} is non-{@code null} whenever {@code credentials} is non-empty — {@link
   * #enqueueStep} fails the step closed before reaching here if no key is configured.
   */
  public void applyCredentials(
      @NonNull ObjectNode payload,
      @NonNull CredentialsPort.Resolved credentials,
      @Nullable byte[] credentialKey,
      @NonNull String nodeId) {
    if (credentials.isEmpty()) {
      return;
    }
    if (credentialKey == null) {
      // Unreachable — enqueueStep is fail-closed — but never emit plaintext if it ever is.
      throw new IllegalStateException(
          "credential key absent at payload-sealing time for node " + nodeId);
    }
    ObjectNode bundle = JSON.createObjectNode();
    ObjectNode envNode = bundle.putObject("env");
    credentials.env().forEach(envNode::put);
    ArrayNode mask = bundle.putArray("maskSecrets");
    credentials.maskValues().forEach(mask::add);
    ArrayNode files = bundle.putArray("secretFiles");
    for (CredentialsPort.SecretFile f : credentials.files()) {
      ObjectNode node = files.addObject();
      node.put("variable", f.variable());
      node.put("fileName", f.fileName());
      node.put("contentBase64", f.contentBase64());
    }
    // design/41 §3: sshAgent keys ride inside the SAME sealed blob — no new payload field.
    ArrayNode sshAgentKeys = bundle.putArray("sshAgentKeys");
    for (CredentialsPort.SshAgentKey k : credentials.sshAgentKeys()) {
      ObjectNode node = sshAgentKeys.addObject();
      node.put("privateKey", k.privateKey());
      if (k.passphrase() == null) {
        node.putNull("passphrase");
      } else {
        node.put("passphrase", k.passphrase());
      }
    }
    String aad = buildId + ":" + nodeId;
    payload.put("credentialsSealed", SecretCipher.seal(bundle.toString(), credentialKey, aad));
  }

  /**
   * Fail a step node because its credentials could not be resolved (design/39 §5, design/45 §3
   * {@code CREDENTIAL}). The node records the structured failure, then is compare-and-set from
   * {@code QUEUED} to {@code FAILED} — {@link #enqueueStep} runs after the orchestrator already
   * moved the node to {@code QUEUED} — and the DAG handles the failure under {@code failurePolicy}
   * on a later pass, exactly as any other step failure.
   *
   * <p>A credential failure happens on the controller, before any worker step runs, so the node's
   * {@code failure_reason} is the <em>only</em> copy of the reason: without it the customer's
   * console would show nothing and the build would "just crash".
   */
  public void failStepForCredentials(@NonNull StepModel step, @Nullable String reason) {
    String detail = reason == null ? "credential resolution failed" : reason;
    LOGGER.log(
        Level.WARNING,
        "[titan] build {0}: step {1} — credential resolution failed: {2}",
        new Object[] {buildId, step.getId(), detail});
    failStepAtDispatch(step, "CREDENTIAL", detail);
  }

  /**
   * Fail a step node at dispatch — before any worker step ran (design/45 §3). The node records its
   * structured failure ({@code failure_category} + a customer-facing {@code failure_reason})
   * <em>before</em> the compare-and-set {@code QUEUED -> FAILED}, so the reason is in place when
   * the node next renders. The DAG handles the failure under {@code failurePolicy} on a later pass
   * like any step failure. A dispatch failure produces no step log, so the node's {@code
   * failure_reason} is the only place the customer sees why.
   *
   * @param category {@code CREDENTIAL} (a binding could not be resolved) or {@code DISPATCH} (the
   *     step could not be turned into a runnable task — payload build, enqueue).
   */
  public void failStepAtDispatch(
      @NonNull StepModel step, @NonNull String category, @NonNull String reason) {
    // Record the structured failure BEFORE the CAS so the reason is in place when the per-node
    // console / graph API next reads the node.
    daos.flowNodes().updateFailure(buildId, step.getId(), category, reason);
    ObjectNode result = JSON.createObjectNode();
    result.put("exitCode", -1);
    result.put("error", reason);
    daos.flowNodes()
        .compareAndSetStatus(
            buildId,
            step.getId(),
            "QUEUED",
            "FAILED",
            null,
            Instant.now(),
            null,
            result.toString());
  }

  /**
   * Compute the merged effective env for a step (design/47 §env / GH #239 / #267 / #847): the
   * declarative pipeline ← stage ← step layers, then the engine-implicit build env (GIT_COMMIT,
   * GIT_BRANCH, BUILD_NUMBER, BUILD_ID) overlaid last so the engine-owned names always win over any
   * user attempt to set the same key.
   *
   * <p>This is the single source of truth for "what env does this step see": it is both written
   * into {@code payload.env} (the worker subprocess environment) and bound as the {@code env}
   * namespace in the {@code ${{ … }}} resolution context (closes #1212), so {@code ${{ env.X }}} in
   * a step argument resolves to exactly the value the step runs with — no drift between the
   * templated value and the runtime env.
   */
  @NonNull
  private Map<String, String> mergedStepEnv(
      @NonNull StageModel stage, @NonNull StepModel step, @NonNull PipelineModel model) {
    Map<String, String> stepEnv = MergedEnv.merge(model.getEnv(), stage.getEnv(), step.getEnv());
    Map<String, String> implicitEnv = loadImplicitBuildEnv();
    if (implicitEnv.isEmpty()) {
      return stepEnv;
    }
    java.util.LinkedHashMap<String, String> merged = new java.util.LinkedHashMap<>(stepEnv);
    merged.putAll(implicitEnv); // implicit env wins over user env (engine-reserved names)
    return merged;
  }

  /**
   * Split this step's merged env (closes #1094) into the literal entries that travel in the
   * plaintext payload and the {@code secret:<id>} entries that must travel in the SEALED
   * credentials bundle.
   *
   * <p>If no env value looks like a {@code secret:} reference, this is a cheap no-op that returns
   * the input as-is. Otherwise each secret value is resolved via the injected {@link EnvResolver}
   * (which calls {@code CredentialsPort.resolveSecretRef}) and collected into the secret env map
   * and the mask-values list. Missing / empty / unresolvable secrets throw {@link
   * EnvResolver.EnvResolutionException} — the caller catches and fails the step with {@code
   * failure_category=CREDENTIAL}.
   */
  @NonNull
  private EnvSplit splitEnvForDispatch(@NonNull Map<String, String> mergedEnv) {
    boolean hasSecretRef = false;
    for (String v : mergedEnv.values()) {
      if (v != null && EnvValueRef.isSecretRef(v)) {
        hasSecretRef = true;
        break;
      }
    }
    if (!hasSecretRef) {
      // Cheap fast path — most steps have no secret refs at all.
      return new EnvSplit(mergedEnv, Map.of(), List.of());
    }
    EnvResolver.Split split = envResolver.split(mergedEnv);
    return new EnvSplit(split.plain(), split.secret(), split.maskValues());
  }

  /**
   * Fold the env secrets (resolved at dispatch) onto an existing credentials bundle so the worker's
   * {@code CredentialsAugmenter} merges them into the step env and the masking log sink redacts the
   * plaintext. The bundle's env wins a clash with a literal env entry — matching the pre-existing
   * "credential variable wins" rule (see {@code CredentialsAugmenter.mergeCredentialEnv}).
   */
  @NonNull
  private static CredentialsPort.Resolved augmentCredentialsWithEnvSecrets(
      @NonNull CredentialsPort.Resolved existing, @NonNull EnvSplit split) {
    if (split.secretEnv().isEmpty() && split.secretMaskValues().isEmpty()) {
      return existing;
    }
    LinkedHashMap<String, String> env = new LinkedHashMap<>(existing.env());
    env.putAll(split.secretEnv());
    List<String> masks = new ArrayList<>(existing.maskValues());
    masks.addAll(split.secretMaskValues());
    return new CredentialsPort.Resolved(env, masks, existing.files(), existing.sshAgentKeys());
  }

  /**
   * Outcome of {@link #splitEnvForDispatch} — the literal entries (ride plaintext payload.env and
   * the {@code ${{ env.X }}} namespace) and the two pieces of the resolved-secret payload (env +
   * masks) that ride the SEALED credentials bundle.
   */
  private record EnvSplit(
      @NonNull Map<String, String> plain,
      @NonNull Map<String, String> secretEnv,
      @NonNull List<String> secretMaskValues) {}

  /**
   * Load this build's implicit env map (closes #847). Best-effort: a missing build row is logged
   * and treated as "no implicit env", so dispatch is never blocked by a transient DB read failure.
   * (The orchestrator only dispatches steps for a build it has already loaded once.)
   */
  @NonNull
  private Map<String, String> loadImplicitBuildEnv() {
    try {
      return daos.builds()
          .findById(buildId)
          .map(ImplicitBuildEnv::forBuild)
          .orElseGet(java.util.Collections::emptyMap);
    } catch (RuntimeException e) {
      LOGGER.log(
          Level.WARNING,
          "[titan] build {0}: could not load implicit build env: {1}",
          new Object[] {buildId, e.getMessage()});
      return java.util.Collections.emptyMap();
    }
  }

  /** A short, human-readable description of an exception — its message, or its type if none. */
  @NonNull
  private static String describe(@NonNull Throwable t) {
    String msg = t.getMessage();
    return msg != null && !msg.isBlank() ? msg : t.getClass().getSimpleName();
  }
}
