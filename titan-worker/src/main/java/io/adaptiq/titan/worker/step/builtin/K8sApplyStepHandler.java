package io.adaptiq.titan.worker.step.builtin;

import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import io.adaptiq.titan.worker.step.LogSink;
import io.adaptiq.titan.worker.step.ParamSpec;
import io.adaptiq.titan.worker.step.StepDescriptor;
import io.adaptiq.titan.worker.step.StepHandler;
import io.adaptiq.titan.worker.step.StepRequest;
import io.adaptiq.titan.worker.step.StepResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The built-in {@code k8sApply} step — applies a Kubernetes manifest, shelling out to {@code
 * kubectl} (design/62, closes #242).
 *
 * <p>This is the cheap-correct answer to the rejected {@code services:} / {@code sidecars:} scope
 * proposal (PR #571). Authors keep their k8s manifests under version control (typically a {@code
 * deps/} dir or a private Helm chart) and reference them from a stage as just another step:
 *
 * <pre>{@code
 * steps:
 *   - k8sApply: ./deps/postgres.yaml      # scalar shorthand
 *   - k8sApply:                           # object form with overrides
 *       manifest: ./deps/redis.yaml
 *       namespace: integration
 *       wait: true
 *       timeout: 5m
 * }</pre>
 *
 * <h2>Idempotency (design/30 caveat 3)</h2>
 *
 * <p>{@code kubectl apply} is idempotent by nature — re-applying the same manifest is a no-op (with
 * the resource version bumped only if the spec changed). A reaped-and-retried {@code k8sApply} is
 * therefore always safe; this handler adds nothing on top.
 *
 * <h2>What this step does NOT do</h2>
 *
 * <ul>
 *   <li>It does not parse the YAML — the manifest is opaque bytes passed to {@code kubectl}.
 *   <li>It does not resolve secrets — if the manifest references a {@code Secret}, that is
 *       Kubernetes's problem, not Titan's.
 *   <li>It does not handle teardown — per-stage cleanup is the orchestrator's job (design/62 §3),
 *       not this handler's.
 * </ul>
 */
public final class K8sApplyStepHandler implements StepHandler {

  /** The default {@code --timeout} value passed to {@code kubectl apply --wait}. */
  static final String DEFAULT_TIMEOUT = "5m";

  /** The {@code kubectl} binary name — resolved through {@code PATH} at execution time. */
  private static final String KUBECTL = "kubectl";

  /**
   * The stage-scoped label key stamped onto every applied resource (design/62 §3). The orchestrator
   * sweeps these at stage-terminal time with {@code kubectl delete -l <KEY>=<value>}; the value is
   * {@code <buildId>-<stageId>} so it is unique cluster-wide.
   */
  static final String STAGE_LABEL_KEY = "titan.stage";

  /** Fallback label key used when no stage id is in the env (degenerate / test paths). */
  static final String BUILD_LABEL_KEY = "titan.build";

  /** Env var the orchestrator sets at dispatch — see {@code StepDispatcher.stepPayload}. */
  static final String ENV_STAGE_ID = "TITAN_STAGE_ID";

  static final String ENV_BUILD_ID = "TITAN_BUILD_ID";

  /** YAML mapper that emits clean block-style multi-document output for {@code kubectl apply}. */
  private static final ObjectMapper YAML =
      new ObjectMapper(
          new YAMLFactory()
              .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
              .disable(YAMLGenerator.Feature.SPLIT_LINES));

  @Override
  public String descriptorId() {
    return "k8sApply";
  }

  @Override
  public StepDescriptor descriptor() {
    return new StepDescriptor(
        "k8sApply",
        "Apply Kubernetes manifest",
        "Applies a Kubernetes manifest via `kubectl apply -f`. Idempotent — re-running with the "
            + "same manifest is a no-op. The manifest is the discriminator: Titan does not parse "
            + "or validate it.",
        List.of(
            ParamSpec.required(
                "manifest", "string", "Workspace-relative path to the manifest YAML file."),
            ParamSpec.optional(
                "namespace", "string", "Target Kubernetes namespace (passed as `-n <ns>`)."),
            ParamSpec.optional(
                "wait",
                "boolean",
                "Wait for the applied resources to become ready (default true)."),
            ParamSpec.optional(
                "timeout",
                "string",
                "How long to wait when `wait` is true (default '" + DEFAULT_TIMEOUT + "').")),
        // design/42 §4.6: the scalar shorthand `k8sApply: <path>` resolves to `manifest`.
        "manifest");
  }

  @Override
  public StepResult execute(StepRequest request) throws Exception {
    // Scalar shorthand `k8sApply: ./deps/foo.yaml` lands under "value"; object form under
    // "manifest". Accept either.
    String manifestArg = request.argString("manifest");
    if (manifestArg == null) {
      manifestArg = request.argString("value");
    }
    if (manifestArg == null || manifestArg.isBlank()) {
      return StepResult.failed("k8sApply step has no 'manifest'");
    }

    Path manifest = confineToWorkspace(request.workDir(), manifestArg.trim());
    if (manifest == null) {
      return StepResult.failed("k8sApply: manifest escapes the workspace: " + manifestArg);
    }
    if (!Files.isRegularFile(manifest)) {
      return StepResult.failed("k8sApply: manifest not found: " + manifestArg);
    }

    String namespace = request.argString("namespace");
    boolean wait = request.argBoolean("wait", true);
    String timeout = request.argString("timeout", DEFAULT_TIMEOUT);

    // design/62 §3: stamp `titan.stage=<buildId>-<stageId>` on every resource so the orchestrator's
    // per-stage teardown can sweep them with `kubectl delete -l titan.stage=…`. We rewrite the
    // manifest into a temp file with the label injected, then apply that — kubectl apply is
    // idempotent against the injected labels (the resource version only bumps if the spec changed).
    Path manifestToApply;
    try {
      manifestToApply = labelledManifest(request, manifest);
    } catch (IOException e) {
      return StepResult.failed(
          "k8sApply: could not rewrite manifest with stage label: " + e.getMessage());
    }

    List<String> command = new ArrayList<>();
    command.add(KUBECTL);
    command.add("apply");
    command.add("-f");
    command.add(manifestToApply.toAbsolutePath().toString());
    if (namespace != null && !namespace.isBlank()) {
      command.add("-n");
      command.add(namespace.trim());
    }
    if (wait) {
      command.add("--wait");
      command.add("--timeout=" + timeout);
    }

    int exit;
    try {
      exit = request.executor().run(command, request.workDir(), request.env(), request.log(), null);
    } catch (IOException e) {
      // The executor raises IOException when ProcessBuilder cannot start kubectl — typically
      // "kubectl: not found". Surface that as a clean failure, not a worker crash.
      return failedFromLaunchError(request.log(), e);
    } catch (RuntimeException e) {
      // Some executor implementations wrap launch failures in unchecked exceptions; treat the
      // same way so a missing binary never bubbles up as an infrastructure failure.
      Throwable cause = e.getCause();
      if (cause instanceof IOException io) {
        return failedFromLaunchError(request.log(), io);
      }
      throw e;
    }

    if (exit == 0) {
      return StepResult.success(exit);
    }
    return StepResult.failed(
        exit, "k8sApply: kubectl exited with code " + exit + " (see step log for details)");
  }

  /** Build the "kubectl could not be launched" failure result — message echoed to the step log. */
  private static StepResult failedFromLaunchError(LogSink log, IOException e) {
    String msg = "k8sApply: could not launch kubectl: " + e.getMessage();
    log.system(msg);
    return StepResult.failed(msg);
  }

  /**
   * Pre-process the manifest: parse each YAML document, inject {@code metadata.labels.titan.stage =
   * <buildId>-<stageId>} (or the {@code titan.build} fallback when no stage id is present), and
   * write the result to a sibling temp file. Returns the path to the rewritten manifest.
   *
   * <p>The rewrite is idempotent — re-running the step with the same env produces the same label
   * value, so {@code kubectl apply} stays a no-op on unchanged specs (design/62 §3).
   */
  private static Path labelledManifest(StepRequest request, Path manifest) throws IOException {
    String labelValue = stageLabelValue(request);
    String labelKey = labelValue == null ? null : labelKeyFor(request);
    if (labelValue == null) {
      // Worker invoked outside the orchestrator's dispatch path (e.g. an ad-hoc CLI run) — no
      // stage / build id available. Apply the manifest unchanged; teardown is the orchestrator's
      // job and only runs against labelled resources, so an unlabelled apply is harmless.
      return manifest;
    }

    String raw = Files.readString(manifest, StandardCharsets.UTF_8);
    List<Map<String, Object>> documents = new ArrayList<>();
    try (MappingIterator<Object> docs = YAML.readerFor(Object.class).readValues(raw)) {
      while (docs.hasNextValue()) {
        Object doc = docs.nextValue();
        if (doc instanceof Map<?, ?> map) {
          documents.add(injectLabel(coerce(map), labelKey, labelValue));
        }
        // Non-map / null documents (a stray `---` followed by nothing) are silently dropped —
        // kubectl ignores them too.
      }
    }

    Path out =
        Files.createTempFile(
            manifest.getParent() == null ? Path.of(".") : manifest.getParent(),
            "titan-k8s-",
            ".yaml");
    StringBuilder rendered = new StringBuilder();
    for (int i = 0; i < documents.size(); i++) {
      if (i > 0) {
        rendered.append("---\n");
      }
      // ObjectMapper.writeValueAsString for a YAMLFactory emits one block-style document, with
      // its leading `---` suppressed by WRITE_DOC_START_MARKER=false. We prepend our own
      // separator between docs for a clean multi-doc stream.
      rendered.append(YAML.writeValueAsString(documents.get(i)));
      if (rendered.length() > 0 && rendered.charAt(rendered.length() - 1) != '\n') {
        rendered.append('\n');
      }
    }
    Files.writeString(out, rendered.toString(), StandardCharsets.UTF_8);
    return out;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> coerce(Map<?, ?> map) {
    return (Map<String, Object>) map;
  }

  /**
   * Mutate {@code doc} so its {@code metadata.labels} contains {@code labelKey=labelValue}, and
   * (for workload kinds that propagate to pods) so the pod template carries the same label — making
   * the sweep with {@code kubectl delete all -l titan.stage=…} actually reach the pods.
   */
  private static Map<String, Object> injectLabel(
      Map<String, Object> doc, String labelKey, String labelValue) {
    Map<String, Object> metadata = childMap(doc, "metadata");
    Map<String, Object> labels = childMap(metadata, "labels");
    labels.put(labelKey, labelValue);

    // Propagate into the pod template for kinds that have one — without it, `kubectl delete all`
    // on the parent works but stranded pods can survive a brief window. This matches kubectl's
    // own behaviour: a label on a Deployment.spec.template.metadata.labels propagates to its
    // managed Pods.
    Object spec = doc.get("spec");
    if (spec instanceof Map<?, ?> specMap) {
      Object template = ((Map<String, Object>) coerce(specMap)).get("template");
      if (template instanceof Map<?, ?> templateMap) {
        Map<String, Object> tplMetadata = childMap(coerce(templateMap), "metadata");
        Map<String, Object> tplLabels = childMap(tplMetadata, "labels");
        tplLabels.put(labelKey, labelValue);
      }
    }
    return doc;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> childMap(Map<String, Object> parent, String key) {
    Object existing = parent.get(key);
    if (existing instanceof Map<?, ?> m) {
      return (Map<String, Object>) m;
    }
    Map<String, Object> created = new LinkedHashMap<>();
    parent.put(key, created);
    return created;
  }

  /**
   * The label value to stamp: {@code <buildId>-<stageId>}, or {@code null} when either id is absent
   * from the env (which suppresses the rewrite entirely). The orchestrator's StepDispatcher sets
   * both {@code TITAN_BUILD_ID} and {@code TITAN_STAGE_ID} on every k8sApply dispatch, so a missing
   * id means the handler is being driven outside the engine (ad-hoc CLI, TCK contract test) —
   * teardown does not apply to that path.
   */
  private static String stageLabelValue(StepRequest request) {
    Map<String, String> env = request.env();
    String buildId = env.get(ENV_BUILD_ID);
    String stageId = env.get(ENV_STAGE_ID);
    if (buildId == null || buildId.isBlank() || stageId == null || stageId.isBlank()) {
      return null;
    }
    return sanitiseLabel(buildId + "-" + stageId);
  }

  /** Always {@code titan.stage} when we rewrite — the build-only fallback was removed (#662). */
  private static String labelKeyFor(StepRequest request) {
    return STAGE_LABEL_KEY;
  }

  /**
   * Strip characters k8s label values forbid. K8s labels are limited to alphanumerics, '-', '_',
   * '.' and must be ≤ 63 chars. Stage ids are slug-like (matrix cells use ':' and '['), so we
   * replace anything outside the safe set with '-' and truncate.
   */
  private static String sanitiseLabel(String raw) {
    StringBuilder sb = new StringBuilder(raw.length());
    for (int i = 0; i < raw.length(); i++) {
      char c = raw.charAt(i);
      if ((c >= 'a' && c <= 'z')
          || (c >= 'A' && c <= 'Z')
          || (c >= '0' && c <= '9')
          || c == '-'
          || c == '_'
          || c == '.') {
        sb.append(c);
      } else {
        sb.append('-');
      }
    }
    String out = sb.toString();
    return out.length() <= 63 ? out : out.substring(0, 63);
  }

  /**
   * Resolve {@code relative} under {@code workDir} and confirm it stays inside it. Returns {@code
   * null} when the path escapes the workspace.
   */
  private static Path confineToWorkspace(Path workDir, String relative) {
    Path resolved = workDir.resolve(relative).normalize();
    return resolved.startsWith(workDir.normalize()) ? resolved : null;
  }
}
