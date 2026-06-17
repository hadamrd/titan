package io.adaptiq.titan.flow.orch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.api.FakeTitanStores;
import io.adaptiq.titan.flow.CredentialsPort;
import io.adaptiq.titan.flow.crypto.EnvCredentialKeyProvider;
import io.adaptiq.titan.flow.crypto.SecretCipher;
import io.adaptiq.titan.flow.model.CredentialBinding;
import io.adaptiq.titan.flow.model.PipelineModel;
import io.adaptiq.titan.flow.model.StageModel;
import io.adaptiq.titan.flow.model.StepModel;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.BuildRow;
import io.adaptiq.titan.store.rows.FlowNodeRow;
import io.adaptiq.titan.store.rows.JobRow;
import io.adaptiq.titan.store.rows.TaskQueueRow;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * End-to-end coverage of the declarative {@code env:} {@code secret:<id>} path through {@link
 * StepDispatcher} (closes #1094).
 *
 * <p>Asserts the security contract:
 *
 * <ul>
 *   <li>a {@code secret:<id>} env value is resolved at dispatch and lands in the SEALED credentials
 *       bundle (env + maskSecrets), so the worker injects it into the step process env and the
 *       masking log sink redacts it;
 *   <li>the resolved plaintext NEVER appears in the plaintext {@code payload.env};
 *   <li>the resolved plaintext is NOT exposed through the {@code ${{ env.X }}} evaluator (#1212
 *       reconciliation) — a {@code ${{ env.SECRET }}} template renders empty, so a secret can't be
 *       inlined into a templated argument or the build log;
 *   <li>a literal env value coexists: visible in {@code payload.env} AND readable via {@code ${{
 *       env.X }}};
 *   <li>a {@code secret:} ref to a missing credential fails the step CLEANLY with {@code
 *       failure_category=CREDENTIAL} — not an NPE — and the secret-shaped value never leaks.
 * </ul>
 *
 * <p>Sealing uses a deterministic key driven through the {@code titan.credentialKey} system
 * property (read by {@code EnvCredentialKeyProvider}), so the test can unseal the bundle and assert
 * on the plaintext it carries. {@code titan.profile=dev} is set so the {@code DevAutoKeyProvider}
 * discovered by {@code CredentialKeyProvider.active()} constructs (it returns null with the flag
 * unset and the chain falls through to the env key) rather than throwing its production-refusal
 * guard.
 */
class StepDispatcherEnvSecretTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  /** A fixed, deterministic 32-byte AES-256 key (all 0x07), base64-encoded for the env provider. */
  private static final byte[] KEY = newKey();

  private static final String KEY_B64 = Base64.getEncoder().encodeToString(KEY);

  private static String savedProfile;
  private static String savedKey;

  private static byte[] newKey() {
    byte[] k = new byte[SecretCipher.KEY_LENGTH_BYTES];
    java.util.Arrays.fill(k, (byte) 0x07);
    return k;
  }

  @BeforeAll
  static void configureKey() {
    savedProfile = System.getProperty("titan.profile");
    savedKey = System.getProperty(EnvCredentialKeyProvider.SYSTEM_PROPERTY);
    // dev profile so DevAutoKeyProvider (in the production ServiceLoader chain) constructs and
    // returns null, rather than throwing its prod-refusal guard; the env key below is what wins.
    System.setProperty("titan.profile", "dev");
    System.setProperty(EnvCredentialKeyProvider.SYSTEM_PROPERTY, KEY_B64);
  }

  @AfterAll
  static void restoreKey() {
    restore("titan.profile", savedProfile);
    restore(EnvCredentialKeyProvider.SYSTEM_PROPERTY, savedKey);
  }

  private static void restore(String key, String value) {
    if (value != null) {
      System.setProperty(key, value);
    } else {
      System.clearProperty(key);
    }
  }

  /**
   * A port whose {@code resolveSecretRef} answers from a fixed id→plaintext map and whose {@code
   * resolve} (for {@code credentials:} bindings) is a no-op — this test only exercises {@code env:
   * secret:}.
   */
  private static CredentialsPort secretPort(Map<String, String> store) {
    return new CredentialsPort() {
      @Override
      @NonNull
      public Resolved resolve(
          @NonNull List<CredentialBinding> bindings, @NonNull List<String> sshAgentIds) {
        return EMPTY;
      }

      @Override
      @NonNull
      public Optional<String> resolveSecretRef(@NonNull String id) {
        return Optional.ofNullable(store.get(id));
      }
    };
  }

  @Test
  void secretEnvLandsInSealedBundleAndNeverInPlaintextPayload() throws Exception {
    TitanStores stores = FakeTitanStores.create();
    long buildId = seedBuild(stores);
    CredentialsPort port = secretPort(Map.of("gh-token", "ghp_TOPSECRET_VALUE"));
    StepDispatcher dispatcher = new StepDispatcher(stores, buildId, port);

    StepModel step = shStep("step-1", "gh release list");
    Map<String, String> env = new LinkedHashMap<>();
    env.put("LOG_LEVEL", "info"); // literal
    env.put("GH_TOKEN", "secret:gh-token"); // secret ref
    step.setEnv(env);
    StageModel stage = stage("stage-a", "linux", List.of(step));
    PipelineModel model = model(List.of(stage));

    dispatcher.enqueueStep(stage, step, model, Map.of());

    TaskQueueRow t = stores.taskQueue().listByBuild(buildId).get(0);
    JsonNode payload = JSON.readTree(t.payloadJson);

    // Literal env rides plaintext payload.env.
    JsonNode plainEnv = payload.get("env");
    assertNotNull(plainEnv, "payload.env present");
    assertEquals("info", plainEnv.get("LOG_LEVEL").asText());
    // The secret KEY is absent from plaintext payload.env, and the plaintext VALUE never appears.
    assertFalse(plainEnv.has("GH_TOKEN"), "secret env key must NOT be in plaintext payload.env");
    assertFalse(
        t.payloadJson.contains("ghp_TOPSECRET_VALUE"),
        "the resolved secret must NEVER appear anywhere in the plaintext payload JSON");

    // Unseal the credentials bundle: the secret lives there, plus a mask entry.
    String sealed = payload.get("credentialsSealed").asText();
    String aad = buildId + ":" + "step-1";
    String bundleJson = SecretCipher.unseal(sealed, KEY, aad);
    JsonNode bundle = JSON.readTree(bundleJson);
    assertEquals(
        "ghp_TOPSECRET_VALUE",
        bundle.get("env").get("GH_TOKEN").asText(),
        "resolved secret must be in the SEALED bundle env (worker injects it into process env)");
    JsonNode masks = bundle.get("maskSecrets");
    assertTrue(masks.isArray());
    boolean masked = false;
    for (JsonNode m : masks) {
      if ("ghp_TOPSECRET_VALUE".equals(m.asText())) {
        masked = true;
      }
    }
    assertTrue(masked, "resolved secret must be registered for log masking (redaction policy)");
  }

  @Test
  void secretEnvIsNotExposedThroughTemplateEvaluatorButLiteralIs() throws Exception {
    TitanStores stores = FakeTitanStores.create();
    long buildId = seedBuild(stores);
    CredentialsPort port = secretPort(Map.of("gh-token", "ghp_LEAKME"));
    StepDispatcher dispatcher = new StepDispatcher(stores, buildId, port);

    // The step templates BOTH a literal env var and the secret env var into its command.
    StepModel step = shStep("step-1", "use ${{ env.REGISTRY }} tok=${{ env.GH_TOKEN }}");
    Map<String, String> env = new LinkedHashMap<>();
    env.put("REGISTRY", "registry.example.com"); // literal — readable via ${{ }}
    env.put("GH_TOKEN", "secret:gh-token"); // secret — NOT readable via ${{ }}
    step.setEnv(env);
    StageModel stage = stage("stage-a", "linux", List.of(step));
    PipelineModel model = model(List.of(stage));

    dispatcher.enqueueStep(stage, step, model, Map.of());

    TaskQueueRow t = stores.taskQueue().listByBuild(buildId).get(0);
    JsonNode payload = JSON.readTree(t.payloadJson);
    String command = payload.get("command").get(2).asText();
    // Literal resolved; the secret rendered EMPTY (the ${{ }} namespace omits secret refs) — so the
    // plaintext secret never gets inlined into the command (which would also reach the build log).
    assertEquals("use registry.example.com tok=", command);
    assertFalse(
        t.payloadJson.contains("ghp_LEAKME"),
        "secret must not leak into the templated command nor anywhere in plaintext payload");
  }

  @Test
  void missingSecretFailsStepWithCredentialCategoryNotNpe() throws Exception {
    TitanStores stores = FakeTitanStores.create();
    long buildId = seedBuild(stores);
    // Empty store: the secret ref cannot resolve.
    CredentialsPort port = secretPort(Map.of());
    StepDispatcher dispatcher = new StepDispatcher(stores, buildId, port);

    StepModel step = shStep("step-1", "gh release list");
    step.setEnv(Map.of("GH_TOKEN", "secret:gh-token"));
    StageModel stage = stage("stage-a", "linux", List.of(step));
    PipelineModel model = model(List.of(stage));

    // The dispatcher CASes QUEUED->FAILED — seed the node as QUEUED first.
    seedNode(stores, buildId, "step-1", "QUEUED");

    dispatcher.enqueueStep(stage, step, model, Map.of());

    // No task enqueued — the step failed at dispatch, before any worker ran.
    assertTrue(
        stores.taskQueue().listByBuild(buildId).isEmpty(),
        "a missing secret must fail the step at dispatch, not enqueue a half-populated task");
    FlowNodeRow node = stores.flowNodes().findByBuildAndNode(buildId, "step-1").orElseThrow();
    assertEquals("FAILED", node.status);
    assertEquals("CREDENTIAL", node.failureCategory, "missing env secret → CREDENTIAL category");
    assertNotNull(node.failureReason);
    assertTrue(node.failureReason.contains("GH_TOKEN"), node.failureReason);
    assertTrue(node.failureReason.contains("gh-token"), node.failureReason);
  }

  @Test
  void scopedSecretIdResolvesThroughThePort() throws Exception {
    TitanStores stores = FakeTitanStores.create();
    long buildId = seedBuild(stores);
    CredentialsPort port = secretPort(Map.of("aws/dev-key", "AKIA-DEV-SECRET"));
    StepDispatcher dispatcher = new StepDispatcher(stores, buildId, port);

    StepModel step = shStep("step-1", "deploy");
    step.setEnv(Map.of("AWS_KEY", "secret:aws/dev-key"));
    StageModel stage = stage("stage-a", "linux", List.of(step));
    PipelineModel model = model(List.of(stage));

    dispatcher.enqueueStep(stage, step, model, Map.of());

    TaskQueueRow t = stores.taskQueue().listByBuild(buildId).get(0);
    JsonNode payload = JSON.readTree(t.payloadJson);
    String aad = buildId + ":step-1";
    JsonNode bundle =
        JSON.readTree(SecretCipher.unseal(payload.get("credentialsSealed").asText(), KEY, aad));
    assertEquals("AKIA-DEV-SECRET", bundle.get("env").get("AWS_KEY").asText());
    assertFalse(
        t.payloadJson.contains("AKIA-DEV-SECRET"),
        "the scoped secret must be sealed, never in the plaintext payload");
  }

  // ── seed helpers ──────────────────────────────────────────────────────────

  private static long seedBuild(TitanStores stores) {
    JobRow job = new JobRow();
    job.fullName = "step-dispatcher-env-secret-test/" + System.nanoTime();
    job.pipelineScript = "titan: {}";
    job.configJson = "{}";
    job.enabled = true;
    job.createdAt = Instant.now();
    job.updatedAt = job.createdAt;
    long jobId = stores.jobs().insert(job);

    BuildRow build = new BuildRow();
    build.jobId = jobId;
    build.buildNumber = 1;
    build.status = "RUNNING";
    build.queuedAt = Instant.now();
    return stores.builds().insert(build);
  }

  private static void seedNode(TitanStores stores, long buildId, String nodeId, String status) {
    FlowNodeRow r = new FlowNodeRow();
    r.buildId = buildId;
    r.nodeId = nodeId;
    r.nodeType = "STEP";
    r.status = status;
    stores.flowNodes().insert(r);
  }

  private static StepModel shStep(String id, String script) {
    StepModel s = new StepModel();
    s.setId(id);
    s.setDescriptorId("sh");
    s.setArguments(Map.of("script", script));
    return s;
  }

  private static StageModel stage(String id, String agentLabel, List<StepModel> steps) {
    StageModel s = new StageModel();
    s.setId(id);
    s.setName(id);
    s.setAgentLabel(agentLabel);
    s.setSteps(steps);
    return s;
  }

  private static PipelineModel model(List<StageModel> stages) {
    PipelineModel m = new PipelineModel();
    m.setStages(stages);
    return m;
  }
}
