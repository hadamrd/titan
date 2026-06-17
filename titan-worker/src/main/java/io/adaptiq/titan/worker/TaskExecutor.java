package io.adaptiq.titan.worker;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.adaptiq.titan.flow.artifact.ArtifactStore;
import io.adaptiq.titan.flow.crypto.CredentialKeyProvider;
import io.adaptiq.titan.flow.crypto.SecretCipher;
import io.adaptiq.titan.worker.step.ArtifactSink;
import io.adaptiq.titan.worker.step.ExecutionAugmenter;
import io.adaptiq.titan.worker.step.LogSink;
import io.adaptiq.titan.worker.step.MaskingLogSink;
import io.adaptiq.titan.worker.step.OutputSink;
import io.adaptiq.titan.worker.step.StepArgumentValidator;
import io.adaptiq.titan.worker.step.StepDescriptor;
import io.adaptiq.titan.worker.step.StepExecutionContext;
import io.adaptiq.titan.worker.step.StepExecutor;
import io.adaptiq.titan.worker.step.StepHandler;
import io.adaptiq.titan.worker.step.StepHandlerContext;
import io.adaptiq.titan.worker.step.StepHandlerDiscovery;
import io.adaptiq.titan.worker.step.StepHandlerRegistry;
import io.adaptiq.titan.worker.step.StepRequest;
import io.adaptiq.titan.worker.step.StepResult;
import io.adaptiq.titan.worker.step.TestResultSink;
import io.adaptiq.titan.worker.step.augment.ExecutionAugmenterDiscovery;
import io.adaptiq.titan.worker.step.builtin.ShellStepHandler;
import io.adaptiq.titan.worker.step.exec.ContainerExecutor;
import io.adaptiq.titan.worker.step.exec.LocalProcessExecutor;
import io.adaptiq.titan.worker.step.exec.WorkspacePathMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Executes a single claimed {@code EXECUTE_COMMAND} task (Chunk 32A).
 *
 * <p>It is the worker's <strong>dispatcher and wire adapter</strong>, nothing more: it parses the
 * queue payload and routes the task by its shape.
 *
 * <ul>
 *   <li><strong>Step path</strong> — a step-shaped payload ({@code stepDescriptor} / step {@code
 *       arguments}) builds a typed {@link StepRequest}, is looked up in the {@link
 *       StepHandlerRegistry}, validated against the handler's {@code ParamSpec} schema, and run
 *       through the handler. {@code sh}, {@code script} and fetched third-party handlers all take
 *       this path; the handler never sees the wire format.
 *   <li><strong>Raw-command path</strong> — a {@code TitanLauncher} freestyle task ships a
 *       top-level {@code command} argv with no step shape. It runs directly through a {@link
 *       StepExecutor}, with no handler, no schema validation and no {@code sh} fallback: a raw
 *       command is not a DSL step.
 * </ul>
 */
final class TaskExecutor {

  private static final Logger LOG = LoggerFactory.getLogger(TaskExecutor.class);
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

  private final WorkerDb db;
  private final Path workspaceRoot;
  private final String agentId;
  private final StepHandlerRegistry registry;
  private final List<ExecutionAugmenter> augmenters;

  /**
   * Translates the worker-side workspace path to the host path for {@code image:} stage bind mounts
   * (#1246). Identity unless {@code TITAN_WORKSPACE_HOST_ROOT} is set (docker-out-of-docker rigs).
   */
  private final WorkspacePathMapper workspaceMapper;

  /** The configured artifact backend, or {@code null} when none is wired (design/41 §8.6). */
  private final ArtifactStore artifactStore;

  TaskExecutor(
      WorkerDb db,
      Path workspaceRoot,
      String workspaceHostRoot,
      Path libraryCacheRoot,
      String agentId,
      ArtifactStore artifactStore) {
    this.db = db;
    this.workspaceRoot = workspaceRoot;
    this.workspaceMapper = WorkspacePathMapper.of(workspaceRoot, workspaceHostRoot);
    this.agentId = agentId;
    this.artifactStore = artifactStore;
    // The registry is assembled by ServiceLoader discovery (design/42 §4.3): the socle's own
    // SocleStepHandlerProvider on the worker classpath, plus any Tier-2 jar in TITAN_STEPS_DIR.
    // The socle dogfoods the SPI — there is no privileged built-in code path.
    StepHandlerContext stepContext =
        new StepHandlerContext(
            libraryCacheRoot,
            io.adaptiq.titan.worker.step.StepApi.VERSION,
            System.getLogger(StepHandlerDiscovery.class.getName()));
    this.registry = StepHandlerDiscovery.discover(stepContext);
    // Execution decoration is decomposed behind the ExecutionAugmenter SPI (design/42 §4.9):
    // credentials: (design/39) and sshAgent: (design/41) are the first two built-ins, loaded
    // and ordered here. TaskExecutor is a thin engine that loops them.
    this.augmenters = ExecutionAugmenterDiscovery.discover();
  }

  /** Outcome of running a task. */
  record Result(boolean success, int exitCode, String resultJson) {}

  Result run(WorkerDb.ClaimedTask task) {
    DbLogSink log = new DbLogSink(db, task.taskToken());
    try {
      JsonNode payload = JSON.readTree(task.payloadJson());

      // The build's workspace (design/43 — build-scoped workspaces).
      // Keyed by build id, not task token: every step of one build resolves the SAME
      // directory, so a step sees the files a previous step produced — the foundation
      // archiveArtifacts / stash / junit / checkout→build all need. Two UNRELATED
      // builds still never share (build-<A> != build-<B>) — that is the real intent of
      // design/26 Tier C's isolation; the prior per-task key was a too-fine granularity
      // that wrongly isolated the steps of one build from each other. Steps within a
      // stage are strictly sequential (TitanOrchestrator.advanceSteps), so they never
      // race; two parallel stages of one build share this dir, knowingly — the same
      // semantics as a `parallel` block (design/43 W3). A task with no build context
      // (none today) falls back to per-task isolation.
      long buildId = payload.path("buildId").asLong(0L);
      Path taskBase =
          buildId > 0
              ? workspaceRoot.resolve("build-" + buildId)
              : workspaceRoot.resolve(task.taskToken().toString());
      Files.createDirectories(taskBase);
      Path workDir = taskBase;
      // A payload workDir is honoured, but always RESOLVED UNDER the workspace base —
      // never used as an absolute path. Resolving an absolute path verbatim would let
      // the orchestrator (deliberately or by a bug) place a task outside its build's
      // workspace. A relative sub-path stays inside; an absolute one is rebased onto
      // taskBase, and a "../" escape is rejected.
      if (payload.hasNonNull("workDir")) {
        String requested = payload.get("workDir").asText();
        Path candidate = Path.of(requested);
        String relative =
            candidate.isAbsolute()
                ? candidate.getRoot().relativize(candidate).toString()
                : requested;
        Path resolved = taskBase.resolve(relative).normalize();
        if (!resolved.startsWith(taskBase)) {
          return fail(log, "workDir '" + requested + "' escapes the build workspace");
        }
        workDir = resolved;
        Files.createDirectories(workDir);
      }

      // design/42 regression fix: a TitanLauncher freestyle task ("Execute shell" on a
      // Titan agent) ships a RAW command — a top-level `command` argv array, no
      // `stepDescriptor`, no step-shaped `arguments`. It is NOT a pipeline DSL step: there
      // is no handler to resolve, no ParamSpec to validate against. Route it straight to a
      // StepExecutor (this is the pre-design/42 raw-command path, dropped by the 32A
      // StepHandler-SPI refactor and restored here). The step-handler path below handles
      // genuinely step-shaped payloads (`stepDescriptor` / step `arguments`).
      if (isRawCommandPayload(payload)) {
        return runRawCommand(payload, task, workDir, log);
      }

      String descriptorId = resolveDescriptorId(payload);
      StepHandler handler = registry.find(descriptorId).orElse(null);
      if (handler == null) {
        return fail(
            log,
            "unknown step type '" + descriptorId + "' (known: " + registry.descriptorIds() + ")");
      }

      String nodeId = payload.path("nodeId").asText("");
      String image = payload.path("image").asText(null);
      Map<String, Object> arguments = buildArguments(payload, descriptorId);
      // design/42 §4.6: the controller's generic parser folds a bare scalar step value
      // (`sh: echo hi`) into a conventional `value` argument with zero per-step knowledge.
      // The worker — where the step jars live — resolves that `value` to the named argument
      // the step's descriptor declares via scalarShorthandKey ("script" for sh, "url" for
      // git). Doing it here, before StepArgumentValidator runs, means both validation (§4.5)
      // and the handler's execute() see the named argument uniformly.
      applyScalarShorthand(arguments, handler.descriptor());
      Map<String, String> env = buildEnv(payload);

      // design/39 / D6: the controller resolved this step's credential bindings against the
      // credentials store and delivered them in the payload. The credential bundle travels
      // SEALED (AES-256-GCM, design/39 §3.1) — never plaintext in the persisted task row.
      // Unsealing is foundational and stays here, in TaskExecutor: it must happen before the
      // augmenters run so they receive plaintext. The unsealed bundle is converted to plain
      // JDK data (a Map) and handed to the StepExecutionContext — titan-step-api stays
      // Jackson-free (design/42 §4.9).
      Map<String, Object> credentialBundle = unsealCredentials(payload, buildId, nodeId);

      // design/42 §4.9: execution decoration is decomposed behind the ExecutionAugmenter
      // SPI. TaskExecutor builds the mutable StepExecutionContext, then loops the discovered
      // augmenters in order(): CredentialsAugmenter (design/39 — env merge, secretFiles,
      // mask registration, secret-file wipe) then SshAgentAugmenter (design/41 — the
      // ssh-agent command wrap, askpass cleanup). The bespoke inline blocks are gone; their
      // behaviour is now entirely in the two augmenters.
      StepExecutionContext augCtx =
          new StepExecutionContext(
              descriptorId, arguments, env, workDir, buildId, nodeId, credentialBundle);
      try {
        for (ExecutionAugmenter augmenter : augmenters) {
          augmenter.augment(augCtx);
        }
        // Apply the command-wrapping hook an augmenter may have installed (design/41 §4 —
        // the ssh-agent wrap). This rewrites the step's shell `script`/`value` argument in
        // place so the existing handler path runs the wrapped program unchanged.
        StepExecutionContext.CommandWrapper wrapper = augCtx.commandWrapper();
        if (wrapper != null) {
          String scriptArg = arguments.containsKey("script") ? "script" : "value";
          Object original = arguments.get(scriptArg);
          // Stash the user's ORIGINAL script under a reserved key so ShellStepHandler can
          // echo it as the `$ …` command line instead of the wrapper's plumbing preamble
          // (design/41 §4 — log hygiene). The wrapped program still runs verbatim.
          arguments.put(ShellStepHandler.DISPLAY_SCRIPT_KEY, String.valueOf(original));
          arguments.put(scriptArg, wrapper.wrap(String.valueOf(original)));
        }
      } catch (StepExecutionContext.AugmentAbort abort) {
        // An augmenter aborted the step before any side effect — fail it cleanly, then
        // still run whatever post-step cleanup did get registered.
        runPostStepActions(augCtx, log);
        return fail(log, abort.getMessage());
      }

      // The credential secret values registered by the augmenters — masked in this step's
      // log, and never laundered into the KV store by setOutput (design/26 Tier C).
      List<String> secrets = augCtx.maskValues();
      LogSink stepLog = MaskingLogSink.wrap(log, secrets);

      StepExecutor executor =
          (image != null && !image.isBlank())
              ? new ContainerExecutor(
                  image,
                  agentId,
                  task.taskToken().toString(),
                  String.valueOf(buildId),
                  nodeId,
                  cancelSignal(task),
                  workspaceMapper)
              : new LocalProcessExecutor(cancelSignal(task));

      // setOutput collects here; folded into result_json on completion (Chunk 6E). An
      // output value that equals a known secret is dropped — a step must not be able to
      // exfiltrate a credential through the output store.
      Map<String, Object> outputs = new LinkedHashMap<>();
      OutputSink outputSink =
          (k, v) -> {
            String asText = v instanceof CharSequence ? v.toString() : String.valueOf(v);
            if (!secrets.isEmpty() && secrets.contains(asText)) {
              log.system("setOutput('" + k + "') rejected — value matches a credential secret");
              return;
            }
            outputs.put(k, v instanceof CharSequence ? v.toString() : v);
          };

      // The artifact sink (design/41 §8.5) — a real DB-backed sink when a store is wired,
      // else the loud-failing UNCONFIGURED sink so an archiveArtifacts step on a worker
      // with no store fails with a clear, recorded message rather than silently dropping.
      ArtifactSink artifactSink =
          artifactStore != null
              ? new DbArtifactSink(artifactStore, db, buildId, nodeId)
              : ArtifactSink.UNCONFIGURED;

      // The test-result sink (issue #298) — always DB-backed when the worker has a build
      // context: per-case persistence is independent of the artifact store. The pre-#298
      // silent UNCONFIGURED fallback covers the no-build-context edge (a raw command path
      // does not pass through here at all, but a future build-less step path would).
      TestResultSink testResultSink =
          buildId > 0 ? new DbTestResultSink(db, buildId, nodeId) : TestResultSink.UNCONFIGURED;

      StepRequest request =
          new StepRequest(
              descriptorId,
              arguments,
              workDir,
              env,
              buildId,
              nodeId,
              image,
              executor,
              stepLog,
              outputSink,
              artifactSink,
              testResultSink);

      // design/42 §4.5: validate the request against the handler's declared ParamSpec
      // schema BEFORE execute — so a missing required arg, a non-coercible boolean/number,
      // or an out-of-range enum fails the step with a precise, located message and BEFORE
      // any side effect (no process spawned, no file written). Unknown argument keys are a
      // warning only — a handler may accept the scalar-shorthand 'value' or be lenient.
      StepArgumentValidator.Result validation =
          StepArgumentValidator.validate(handler.descriptor(), arguments);
      for (String warning : validation.warnings()) {
        stepLog.system("argument warning: " + warning);
      }
      if (!validation.ok()) {
        // Recorded exactly as a handler-returned StepResult.failed(...) would be — the
        // message reaches the step log and the terminal status; no exception escapes.
        return finish(
            log,
            StepResult.failed("argument validation failed: " + validation.failureMessage()),
            outputs);
      }

      StepResult result;
      try {
        result = handler.execute(request);
      } catch (Exception e) {
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        result = StepResult.failed("step '" + descriptorId + "' threw: " + cause.getMessage());
      } finally {
        // Run the post-step cleanup actions the augmenters registered (design/42 §4.9):
        // the credential secret-file wipe (design/39 §3) and the one-shot SSH_ASKPASS
        // helper deletion (design/41 §4). Env-var secrets need no clear — they lived only
        // in the step process's environment, which has already exited; the ssh-agent and
        // its keys are already gone via the wrapper's `trap … EXIT`.
        runPostStepActions(augCtx, log);
      }
      return finish(log, result, outputs);
    } catch (Exception e) {
      LOG.error("task {} execution failed", task.id(), e);
      return fail(log, "execution error: " + e.getMessage());
    }
  }

  /**
   * Is this a raw-command payload — a {@code TitanLauncher} freestyle task — rather than a pipeline
   * DSL step? The decision is unambiguous and shape-based:
   *
   * <ul>
   *   <li>a top-level {@code command} array <em>and</em> no step shape ({@code stepDescriptor} /
   *       step {@code arguments}) → raw path;
   *   <li>{@code stepDescriptor} or step {@code arguments} present → step path.
   * </ul>
   *
   * A raw command carries an argv to run directly; a step carries a descriptor + an argument
   * schema. They are never both present in a well-formed payload.
   */
  static boolean isRawCommandPayload(JsonNode payload) {
    JsonNode command = payload.get("command");
    if (command == null || !command.isArray()) {
      return false;
    }
    boolean stepShaped =
        (payload.hasNonNull("stepDescriptor") && !payload.get("stepDescriptor").asText().isBlank())
            || (payload.get("arguments") != null && payload.get("arguments").isObject());
    return !stepShaped;
  }

  /**
   * Execute a raw {@code command} argv directly through a {@link StepExecutor} — the pre-design/42
   * freestyle path. No {@link StepHandler} is resolved, no {@code ParamSpec} schema is validated,
   * no {@code sh} fallback is applied: a raw command is not a DSL step.
   *
   * <p>The step env is built from the payload's {@code env} only (design/26 Tier C); the payload's
   * {@code image} selects {@link ContainerExecutor} over {@link LocalProcessExecutor}.
   * Success/failure is reported purely by the process exit code.
   *
   * <p>{@link ExecutionAugmenter}s are skipped: a {@code TitanLauncher} command payload carries no
   * {@code credentialsSealed} bundle, so credentials/sshAgent augmentation has nothing to act on.
   * (The step path keeps the full unseal + augmenter logic, unchanged.)
   */
  private Result runRawCommand(
      JsonNode payload, WorkerDb.ClaimedTask task, Path workDir, DbLogSink log) {
    List<String> command = new ArrayList<>();
    payload.get("command").forEach(n -> command.add(n.asText()));
    if (command.isEmpty()) {
      return fail(log, "raw command payload has an empty 'command' array");
    }
    Map<String, String> env = buildEnv(payload);
    long buildId = payload.path("buildId").asLong();
    String nodeId = payload.path("nodeId").asText("");
    String image = payload.path("image").asText(null);
    StepExecutor executor =
        (image != null && !image.isBlank())
            ? new ContainerExecutor(
                image,
                agentId,
                task.taskToken().toString(),
                String.valueOf(buildId),
                nodeId,
                cancelSignal(task),
                workspaceMapper)
            : new LocalProcessExecutor(cancelSignal(task));
    try {
      int exitCode = executor.run(command, workDir, env, log);
      boolean success = exitCode == 0;
      log.finish(success ? "command completed" : "command failed (exit " + exitCode + ")");
      return new Result(
          success, exitCode, JSON.createObjectNode().put("exitCode", exitCode).toString());
    } catch (Exception e) {
      LOG.error("raw command task {} failed to run", task.id(), e);
      return fail(log, "raw command execution error: " + e.getMessage());
    }
  }

  /** An empty credential bundle — a step that declared no {@code credentials:}. */
  private static final Map<String, Object> NO_CREDENTIALS = Map.of();

  /**
   * Unseal a step's credential bundle (design/39 §3.1) into plain JDK data. The controller sealed
   * the bundle — {@code {env, maskSecrets, secretFiles, sshAgentKeys}} — with AES-256-GCM before it
   * entered the persisted task payload, so the database row holds only the {@code
   * credentialsSealed} ciphertext. This decrypts it in memory, here, with the worker's copy of the
   * key.
   *
   * <p>Unsealing is foundational and stays in {@code TaskExecutor} (design/42 §4.9) — it must
   * happen before the augmenters run so they receive plaintext. The result is a Jackson-free {@link
   * Map} so {@code titan-step-api}'s {@link StepExecutionContext} stays JSON-free.
   *
   * <p>Fail closed: a step that carries a sealed bundle but for which no key is configured, or
   * whose ciphertext does not authenticate, throws — the task fails rather than running without the
   * credentials it declared. A step with no {@code credentialsSealed} field returns the empty
   * bundle (the common, no-credentials case).
   */
  private static Map<String, Object> unsealCredentials(
      JsonNode payload, long buildId, String nodeId) {
    JsonNode sealed = payload.get("credentialsSealed");
    if (sealed == null || !sealed.isTextual()) {
      return NO_CREDENTIALS;
    }
    byte[] key = CredentialKeyProvider.active().credentialKey();
    if (key == null) {
      throw new IllegalStateException(
          "step carries sealed credentials but no credential "
              + "key is configured on this worker (set TITAN_CREDENTIAL_KEY)");
    }
    try {
      String plaintext = SecretCipher.unseal(sealed.asText(), key, buildId + ":" + nodeId);
      return JSON.readValue(plaintext, MAP_TYPE);
    } catch (Exception e) {
      throw new IllegalStateException(
          "could not unseal the step's credential payload: " + e.getMessage(), e);
    }
  }

  /**
   * The cancel signal for a running task — polled by the executors. A transient DB read failure is
   * treated as "not cancelled" so an unrelated hiccup never aborts a healthy step.
   */
  private BooleanSupplier cancelSignal(WorkerDb.ClaimedTask task) {
    return () -> {
      try {
        return db.isCancelled(task.id());
      } catch (SQLException e) {
        return false;
      }
    };
  }

  /**
   * Run the post-step cleanup actions the augmenters registered (design/42 §4.9). Runs in the
   * caller's {@code finally} block; a throwing action does not abort the rest.
   */
  private static void runPostStepActions(StepExecutionContext ctx, DbLogSink log) {
    for (Runnable action : ctx.postStepActions()) {
      try {
        action.run();
      } catch (Exception e) {
        log.system("post-step cleanup action failed: " + e.getMessage());
      }
    }
  }

  /**
   * The step type for a step-shaped payload. The orchestrator always sets {@code stepDescriptor};
   * the {@code runtime} fallback keeps a hand-written {@code script}-step payload working too.
   * Raw-command payloads never reach here — they are dispatched by {@link #isRawCommandPayload}.
   */
  private static String resolveDescriptorId(JsonNode payload) {
    String descriptor = payload.path("stepDescriptor").asText(null);
    if (descriptor != null && !descriptor.isBlank()) {
      return descriptor;
    }
    if ("groovy".equals(payload.path("runtime").asText(null))) {
      return "script";
    }
    return "sh";
  }

  /**
   * Adapt the wire payload into the SPI's argument map. The payload's {@code arguments} object is
   * the base; a {@code script} step's {@code runtime}/{@code body}/{@code libraries} (carried at
   * the payload top level) are lifted in so the handler reads everything from one map.
   */
  private static Map<String, Object> buildArguments(JsonNode payload, String descriptorId) {
    Map<String, Object> args = new LinkedHashMap<>();
    JsonNode a = payload.get("arguments");
    if (a != null && a.isObject()) {
      args.putAll(JSON.convertValue(a, MAP_TYPE));
    }
    for (String key : new String[] {"runtime", "body"}) {
      if (payload.hasNonNull(key)) {
        args.put(key, payload.get(key).asText());
      }
    }
    JsonNode libraries = payload.get("libraries");
    if (libraries != null && libraries.isArray()) {
      List<String> libs = new ArrayList<>();
      libraries.forEach(n -> libs.add(n.asText()));
      args.put("libraries", libs);
    }
    return args;
  }

  /** The conventional key the controller's generic parser folds a bare scalar step value into. */
  private static final String SHORTHAND_VALUE_KEY = "value";

  /**
   * Resolve the scalar-shorthand argument (design/42 §4.6). The controller's generic parser cannot
   * know a step's grammar, so it folds a bare scalar step value (e.g. {@code sh: echo hi}) into a
   * conventional {@code value} key. The worker — which holds the step jars — completes the fold
   * here: if {@code arguments} carries {@code value}, the resolved handler's descriptor declares a
   * {@link StepDescriptor#scalarShorthandKey()}, and that named key is not already present, the
   * {@code value} is copied into the named key. The original {@code value} is left in place
   * (harmless — the validator never warns about it; handlers read the named key).
   *
   * <p>A no-op when the step declares no shorthand, when no {@code value} was sent, or when the
   * named argument was already supplied explicitly.
   */
  static void applyScalarShorthand(Map<String, Object> arguments, StepDescriptor descriptor) {
    if (descriptor == null) {
      return;
    }
    String key = descriptor.scalarShorthandKey();
    if (key == null || key.isBlank()) {
      return;
    }
    if (arguments.containsKey(SHORTHAND_VALUE_KEY) && !arguments.containsKey(key)) {
      arguments.put(key, arguments.get(SHORTHAND_VALUE_KEY));
    }
  }

  /** The step environment — payload only, never the worker's own (design/26 Tier C). */
  private static Map<String, String> buildEnv(JsonNode payload) {
    Map<String, String> env = new LinkedHashMap<>();
    JsonNode envNode = payload.path("env");
    envNode.fieldNames().forEachRemaining(k -> env.put(k, envNode.get(k).asText()));
    return env;
  }

  /** Fold a handler's outcome + collected outputs into the task's {@code result_json}. */
  private Result finish(DbLogSink log, StepResult result, Map<String, Object> outputs) {
    log.finish(result.isSuccess() ? "step completed" : "step failed");
    int exitCode = result.exitCode() != null ? result.exitCode() : (result.isSuccess() ? 0 : -1);
    ObjectNode json = JSON.createObjectNode();
    json.put("exitCode", exitCode);
    if (result.message() != null) {
      json.put("error", result.message());
    }
    if (!outputs.isEmpty()) {
      json.set("outputs", JSON.valueToTree(outputs));
    }
    return new Result(result.isSuccess(), exitCode, json.toString());
  }

  private Result fail(DbLogSink log, String message) {
    LOG.warn("task failed: {}", message);
    log.finish(message);
    return new Result(
        false, -1, JSON.createObjectNode().put("exitCode", -1).put("error", message).toString());
  }
}
