package io.adaptiq.titan.worker.step.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.adaptiq.titan.worker.step.LogSink;
import io.adaptiq.titan.worker.step.StepExecutor;
import io.adaptiq.titan.worker.step.StepHandler;
import io.adaptiq.titan.worker.step.StepHandlerTck;
import io.adaptiq.titan.worker.step.StepRequest;
import io.adaptiq.titan.worker.step.StepResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/**
 * {@link K8sApplyStepHandler} — the shared TCK plus {@code k8sApply}-specifics (design/62, #242).
 *
 * <p>This test does NOT shell out to a real {@code kubectl}. Instead it substitutes a fake {@link
 * StepExecutor} that records the {@code argv} the handler builds; this is what the Titan SPI is
 * designed for (design/32 §10), and it keeps the test deterministic and binary-free. The "missing
 * kubectl" path is exercised with a real {@link
 * io.adaptiq.titan.worker.step.exec.LocalProcessExecutor} pointed at a bogus binary via a separate
 * test.
 */
class K8sApplyStepHandlerTest extends StepHandlerTck {

  /**
   * A fake {@link StepExecutor} that returns a pre-canned exit code and records each invocation.
   */
  static final class RecordingExecutor implements StepExecutor {
    final List<List<String>> invocations = new CopyOnWriteArrayList<>();
    int exitCode = 0;
    IOException throwOnRun;

    @Override
    public int run(
        List<String> command,
        Path workDir,
        Map<String, String> env,
        LogSink log,
        String displayCommand)
        throws Exception {
      invocations.add(List.copyOf(command));
      if (throwOnRun != null) {
        throw throwOnRun;
      }
      log.system("$ " + String.join(" ", command));
      return exitCode;
    }
  }

  private final RecordingExecutor executor = new RecordingExecutor();

  @Override
  protected StepHandler newHandler() {
    return new K8sApplyStepHandler();
  }

  @Override
  protected StepExecutor newExecutor() {
    return executor;
  }

  @Override
  protected Map<String, Object> validArguments() {
    // The TCK calls validArguments() to assert the handler succeeds on its declared shape; it
    // needs the manifest file on disk. The TCK's @TempDir workDir is initialised before the
    // contract tests run, so write the fixture eagerly here.
    try {
      Files.writeString(workDir.resolve("manifest.yaml"), "kind: ConfigMap\n");
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    return Map.of("manifest", "manifest.yaml", "wait", false);
  }

  // ── handler-specific contract ─────────────────────────────────────────

  @Test
  void buildsTheExpectedKubectlCommandLine() throws Exception {
    Files.writeString(workDir.resolve("postgres.yaml"), "kind: Deployment\n");

    StepResult result =
        new K8sApplyStepHandler()
            .execute(
                request(
                    Map.of(
                        "manifest", "postgres.yaml",
                        "namespace", "integration",
                        "wait", true,
                        "timeout", "30s")));

    assertTrue(result.isSuccess(), "exit 0 means success: " + result.message());
    assertEquals(1, executor.invocations.size(), "kubectl invoked exactly once");
    List<String> argv = executor.invocations.get(0);
    assertEquals("kubectl", argv.get(0));
    assertEquals("apply", argv.get(1));
    assertEquals("-f", argv.get(2));
    assertTrue(argv.get(3).endsWith("postgres.yaml"), "manifest path passed absolute");
    assertTrue(argv.contains("-n"), "namespace flag emitted");
    assertTrue(argv.contains("integration"), "namespace value emitted");
    assertTrue(argv.contains("--wait"), "wait flag emitted");
    assertTrue(argv.contains("--timeout=30s"), "timeout flag emitted with value");
  }

  @Test
  void scalarShorthandFoldsManifestIntoValue() throws Exception {
    Files.writeString(workDir.resolve("svc.yaml"), "kind: Service\n");
    // The parser writes a scalar `k8sApply: foo.yaml` as {value: foo.yaml}. The handler must
    // accept that fold per design/42 §4.6, exactly like sh/junit/error.
    StepResult result =
        new K8sApplyStepHandler().execute(request(Map.of("value", "svc.yaml", "wait", false)));

    assertTrue(result.isSuccess(), result.message());
    assertEquals(1, executor.invocations.size());
    assertTrue(executor.invocations.get(0).get(3).endsWith("svc.yaml"));
  }

  @Test
  void waitDefaultsToTrueAndUsesDefaultTimeout() throws Exception {
    Files.writeString(workDir.resolve("d.yaml"), "kind: Deployment\n");

    new K8sApplyStepHandler().execute(request(Map.of("manifest", "d.yaml")));

    List<String> argv = executor.invocations.get(0);
    assertTrue(argv.contains("--wait"), "wait defaults to true");
    assertTrue(argv.contains("--timeout=" + K8sApplyStepHandler.DEFAULT_TIMEOUT));
  }

  @Test
  void waitFalseOmitsTheWaitFlags() throws Exception {
    Files.writeString(workDir.resolve("d.yaml"), "kind: Deployment\n");

    new K8sApplyStepHandler().execute(request(Map.of("manifest", "d.yaml", "wait", false)));

    List<String> argv = executor.invocations.get(0);
    assertFalse(argv.contains("--wait"), "wait=false suppresses --wait");
    assertFalse(
        argv.stream().anyMatch(a -> a.startsWith("--timeout=")), "and the timeout flag with it");
  }

  @Test
  void missingManifestArgumentIsAFailureNotACrash() throws Exception {
    StepResult result = new K8sApplyStepHandler().execute(request(Map.of()));

    assertFalse(result.isSuccess());
    assertNotNull(result.message());
    assertTrue(result.message().contains("manifest"));
    assertTrue(executor.invocations.isEmpty(), "kubectl never invoked when arg validation fails");
  }

  @Test
  void missingManifestFileIsAFailureNotACrash() throws Exception {
    StepResult result =
        new K8sApplyStepHandler().execute(request(Map.of("manifest", "does-not-exist.yaml")));

    assertFalse(result.isSuccess());
    assertTrue(result.message().contains("not found"));
    assertTrue(executor.invocations.isEmpty());
  }

  @Test
  void manifestPathEscapingTheWorkspaceIsRejected() throws Exception {
    StepResult result =
        new K8sApplyStepHandler().execute(request(Map.of("manifest", "../../etc/passwd")));

    assertFalse(result.isSuccess());
    assertTrue(result.message().contains("escapes the workspace"));
    assertTrue(executor.invocations.isEmpty());
  }

  @Test
  void nonZeroKubectlExitSurfacesAsAFailureNotACrash() throws Exception {
    Files.writeString(workDir.resolve("bad.yaml"), "kind: Garbage\n");
    executor.exitCode = 1;

    StepResult result =
        new K8sApplyStepHandler().execute(request(Map.of("manifest", "bad.yaml", "wait", false)));

    assertFalse(result.isSuccess(), "non-zero exit = failure");
    assertEquals(Integer.valueOf(1), result.exitCode(), "exit code propagated");
    assertNotNull(result.message());
    assertTrue(result.message().contains("kubectl"), "the failure cites kubectl");
  }

  @Test
  void missingKubectlBinaryReturnsFailedInsteadOfCrashing() throws Exception {
    // Simulate "kubectl: not found" the way ProcessBuilder.start surfaces it: an IOException
    // out of the executor. The handler must wrap that into a clean StepResult.failed(...),
    // not let it propagate as an infrastructure exception.
    Files.writeString(workDir.resolve("ok.yaml"), "kind: ConfigMap\n");
    executor.throwOnRun = new IOException("Cannot run program \"kubectl\": No such file");

    StepResult result =
        new K8sApplyStepHandler().execute(request(Map.of("manifest", "ok.yaml", "wait", false)));

    assertFalse(result.isSuccess());
    assertNotNull(result.message());
    assertTrue(
        result.message().contains("kubectl"),
        "message must mention kubectl so the operator knows what to install");
  }

  // ── #662: stage-scoped label injection ────────────────────────────────

  /** Make a StepRequest with an explicit env (the TCK's helper passes Map.of()). */
  private StepRequest requestWithEnv(Map<String, Object> args, Map<String, String> env) {
    return new StepRequest(
        "k8sApply",
        args,
        workDir,
        env,
        42L,
        "step-1",
        null,
        executor,
        new io.adaptiq.titan.worker.step.builtin.K8sApplyStepHandlerTest.NoopLog(),
        new io.adaptiq.titan.worker.step.builtin.K8sApplyStepHandlerTest.NoopOutputs());
  }

  static final class NoopLog implements io.adaptiq.titan.worker.step.LogSink {
    @Override
    public void line(String stream, String text) {}
  }

  static final class NoopOutputs implements io.adaptiq.titan.worker.step.OutputSink {
    @Override
    public void put(String name, Object value) {}
  }

  @Test
  void labelStageInjectsTitanStageOntoEveryDoc() throws Exception {
    Files.writeString(
        workDir.resolve("multi.yaml"),
        "apiVersion: v1\nkind: ConfigMap\nmetadata:\n  name: cm\n"
            + "---\n"
            + "apiVersion: apps/v1\nkind: Deployment\nmetadata:\n  name: dep\n"
            + "spec:\n  template:\n    metadata:\n      labels:\n        app: x\n");

    StepResult result =
        new K8sApplyStepHandler()
            .execute(
                requestWithEnv(
                    Map.of("manifest", "multi.yaml", "wait", false),
                    Map.of("TITAN_BUILD_ID", "42", "TITAN_STAGE_ID", "integration")));

    assertTrue(result.isSuccess(), result.message());
    // The handler rewrote the manifest to a temp file under workDir. Read it back and check.
    Path applied = Path.of(executor.invocations.get(0).get(3));
    String rewritten = Files.readString(applied);
    // The YAML mapper quotes the label value — accept either form (round-trip robustness).
    String stamped = "titan.stage: \"42-integration\"";
    assertTrue(
        rewritten.contains(stamped),
        "every doc must carry titan.stage=<buildId>-<stageId>; got:\n" + rewritten);
    // The Deployment's pod template also gets the label so `kubectl delete all -l titan.stage=…`
    // sweeps managed pods cleanly.
    int firstHit = rewritten.indexOf(stamped);
    int secondHit = rewritten.indexOf(stamped, firstHit + 1);
    int thirdHit = rewritten.indexOf(stamped, secondHit + 1);
    assertTrue(
        secondHit > 0 && thirdHit > 0,
        "expected titan.stage on ConfigMap, Deployment.metadata AND its pod template; got\n"
            + rewritten);
  }

  @Test
  void labelStageSkippedWhenStageIdAbsent() throws Exception {
    // When only TITAN_BUILD_ID is set (no TITAN_STAGE_ID), the handler MUST NOT rewrite — there
    // is no stage scope to tear down against. The original manifest is applied unchanged so
    // operator-driven k8sApply usage stays predictable.
    Path source = workDir.resolve("cm.yaml");
    Files.writeString(source, "apiVersion: v1\nkind: ConfigMap\nmetadata:\n  name: cm\n");

    new K8sApplyStepHandler()
        .execute(
            requestWithEnv(
                Map.of("manifest", "cm.yaml", "wait", false),
                Map.of("TITAN_BUILD_ID", "42"))); // no STAGE_ID

    // No rewrite: argv carries the source path verbatim.
    assertEquals(source.toAbsolutePath().toString(), executor.invocations.get(0).get(3));
  }

  @Test
  void labelStageIsIdempotentReapplyingProducesSameLabel() throws Exception {
    Files.writeString(workDir.resolve("a.yaml"), "kind: ConfigMap\nmetadata:\n  name: a\n");

    new K8sApplyStepHandler()
        .execute(
            requestWithEnv(
                Map.of("manifest", "a.yaml", "wait", false),
                Map.of("TITAN_BUILD_ID", "1", "TITAN_STAGE_ID", "deps")));
    new K8sApplyStepHandler()
        .execute(
            requestWithEnv(
                Map.of("manifest", "a.yaml", "wait", false),
                Map.of("TITAN_BUILD_ID", "1", "TITAN_STAGE_ID", "deps")));

    Path firstApplied = Path.of(executor.invocations.get(0).get(3));
    Path secondApplied = Path.of(executor.invocations.get(1).get(3));
    // Different temp files, same label value — kubectl apply stays a no-op on the second run.
    assertTrue(Files.readString(firstApplied).contains("titan.stage: \"1-deps\""));
    assertTrue(Files.readString(secondApplied).contains("titan.stage: \"1-deps\""));
  }

  @Test
  void labelStageSanitisesUnsafeMatrixCellChars() throws Exception {
    // Matrix cell stage ids embed ':' and '[' which k8s labels forbid.
    Files.writeString(workDir.resolve("cm.yaml"), "kind: ConfigMap\nmetadata:\n  name: cm\n");

    new K8sApplyStepHandler()
        .execute(
            requestWithEnv(
                Map.of("manifest", "cm.yaml", "wait", false),
                Map.of("TITAN_BUILD_ID", "9", "TITAN_STAGE_ID", "test:linux[ubuntu]")));

    Path applied = Path.of(executor.invocations.get(0).get(3));
    String rewritten = Files.readString(applied);
    assertFalse(rewritten.contains("test:linux"), "must sanitise ':'");
    assertTrue(
        rewritten.contains("titan.stage: \"9-test-linux-ubuntu-\""),
        "expected sanitised label; got:\n" + rewritten);
  }

  @Test
  void labelStageOmittedWhenNoEnvIdsPresent() throws Exception {
    // Ad-hoc CLI / TCK-style invocation with no TITAN_* env: apply the original manifest
    // unchanged, no label rewrite. Confirmed by argv pointing at the SOURCE file.
    Path source = workDir.resolve("cm.yaml");
    Files.writeString(source, "kind: ConfigMap\nmetadata:\n  name: cm\n");

    // Use a request whose env AND buildId leave no id derivable. buildId 0 → no fallback either.
    new K8sApplyStepHandler()
        .execute(
            new StepRequest(
                "k8sApply",
                Map.of("manifest", "cm.yaml", "wait", false),
                workDir,
                Map.of(),
                0L,
                "step-1",
                null,
                executor,
                new NoopLog(),
                new NoopOutputs()));

    // No rewrite — kubectl gets the original file path.
    assertEquals(source.toAbsolutePath().toString(), executor.invocations.get(0).get(3));
  }

  @Test
  void registeredInTheSocleProvider() {
    // Adversarial: a step that ships but is not in the socle provider is invisible to the
    // ServiceLoader path. Guard against a future refactor silently dropping the registration.
    List<String> ids = new ArrayList<>();
    for (StepHandler h :
        new io.adaptiq.titan.worker.step.SocleStepHandlerProvider()
            .handlers(
                new io.adaptiq.titan.worker.step.StepHandlerContext(
                    workDir,
                    io.adaptiq.titan.worker.step.StepApi.VERSION,
                    System.getLogger("test")))) {
      ids.add(h.descriptorId());
    }
    assertTrue(ids.contains("k8sApply"), "socle must register k8sApply; got " + ids);
  }
}
