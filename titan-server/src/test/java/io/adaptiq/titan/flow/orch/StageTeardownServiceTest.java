package io.adaptiq.titan.flow.orch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.adaptiq.titan.flow.model.StageModel;
import io.adaptiq.titan.flow.model.StepModel;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Adversarial tests for {@link StageTeardownService} (#662 — design/62 §3).
 *
 * <p>Drives the service with a recording {@link StageTeardownService.KubectlRunner} so behaviour is
 * asserted without a real cluster / a real {@code kubectl} on PATH.
 */
class StageTeardownServiceTest {

  /** A runner that records every invocation and returns a configurable exit code / throws. */
  static final class RecordingRunner implements StageTeardownService.KubectlRunner {
    final java.util.List<String> labels = new java.util.ArrayList<>();
    int exitCode = 0;
    IOException throwOnRun;

    @Override
    public StageTeardownService.RunResult run(String labelSelector, long timeout, TimeUnit unit)
        throws IOException {
      labels.add(labelSelector);
      if (throwOnRun != null) {
        throw throwOnRun;
      }
      return new StageTeardownService.RunResult(exitCode, "");
    }
  }

  // ── adversarial: never shell out when the stage had no k8sApply step ──────

  @Test
  void stageWithNoK8sApplyStepDoesNotShellOutKubectl() {
    RecordingRunner runner = new RecordingRunner();
    StageTeardownService svc = new StageTeardownService(runner);
    StageModel stage = stage("build-and-test", List.of(shStep("compile"), shStep("test")));

    svc.teardownIfNeeded(42L, stage);

    assertTrue(
        runner.labels.isEmpty(),
        "kubectl must NOT be invoked for a stage that never ran k8sApply (would cost a"
            + " ProcessBuilder.start() on every stage completion in non-k8s pipelines)");
  }

  // ── adversarial: teardown still fires when the stage failed ───────────────

  @Test
  void stageFailureStillRunsTeardown() {
    RecordingRunner runner = new RecordingRunner();
    StageTeardownService svc = new StageTeardownService(runner);
    StageModel stage = stage("integration", List.of(k8sApply("apply-deps"), shStep("verify")));

    // The orchestrator calls teardownIfNeeded regardless of stage result — design/62 §3 wants
    // failed-stage cleanup to be guaranteed. We assert the call here directly.
    svc.teardownIfNeeded(7L, stage);

    assertEquals(1, runner.labels.size(), "kubectl delete should run on stage failure too");
    assertEquals("7-integration", runner.labels.get(0));
  }

  // ── adversarial: kubectl non-zero exit must not crash the orchestrator ────

  @Test
  void kubectlNonZeroExitIsLoggedNotPropagated() {
    RecordingRunner runner = new RecordingRunner();
    runner.exitCode = 1; // simulate "cluster unreachable" or "RBAC denied"
    StageTeardownService svc = new StageTeardownService(runner);
    StageModel stage = stage("deps", List.of(k8sApply("apply")));

    // No exception escapes — the orchestrator's terminal write must never be unwound by a sweep
    // failure.
    svc.teardownIfNeeded(99L, stage);

    assertEquals(1, runner.labels.size(), "runner was still invoked");
  }

  @Test
  void kubectlIoExceptionIsLoggedNotPropagated() {
    RecordingRunner runner = new RecordingRunner();
    runner.throwOnRun = new IOException("Cannot run program \"kubectl\": No such file");
    StageTeardownService svc = new StageTeardownService(runner);
    StageModel stage = stage("deps", List.of(k8sApply("apply")));

    // The worker may run kubectl-less — that must NOT take down the controller. Swallow.
    svc.teardownIfNeeded(101L, stage);

    assertEquals(1, runner.labels.size());
  }

  // ── label-value contract ──────────────────────────────────────────────────

  @Test
  void labelValueMatchesK8sApplyHandlerFormat() {
    // The handler stamps `titan.stage=<buildId>-<stageId>` (sanitised). Teardown MUST use the
    // same label or the sweep silently matches nothing.
    assertEquals("42-integration-test", StageTeardownService.labelValue(42L, "integration-test"));
  }

  @Test
  void labelValueSanitisesUnsafeChars() {
    // Matrix cell ids embed ':' and '[' — k8s label values forbid them.
    String result = StageTeardownService.labelValue(7L, "test:linux[ubuntu]");
    assertFalse(result.contains(":"), "': stripped: " + result);
    assertFalse(result.contains("["), "[ stripped: " + result);
    assertTrue(result.length() <= 63, "k8s label values must be <= 63 chars: " + result);
  }

  @Test
  void labelValueTruncatesAt63Chars() {
    String longStage = "a".repeat(80);
    String result = StageTeardownService.labelValue(1L, longStage);
    assertEquals(63, result.length());
  }

  // ── plumbing-only safety: only one descriptor matters ─────────────────────

  @Test
  void detectionIsCaseSensitiveOnDescriptorId() {
    // Defensive: someone aliasing to "K8sApply" should not auto-opt-in. The descriptorId is the
    // contract.
    StageModel stage = stage("misc", List.of(stepWithDescriptor("typo", "K8sApply")));
    assertFalse(StageTeardownService.hasK8sApplyStep(stage));
  }

  @Test
  void detectionFindsK8sApplyAmongMixedSteps() {
    StageModel stage =
        stage("mixed", List.of(shStep("a"), k8sApply("apply"), shStep("b"), shStep("c")));
    assertTrue(StageTeardownService.hasK8sApplyStep(stage));
  }

  // ── runner is called exactly once per call ────────────────────────────────

  @Test
  void runnerInvokedExactlyOncePerStageTerminal() {
    AtomicInteger calls = new AtomicInteger();
    StageTeardownService.KubectlRunner runner =
        (label, t, u) -> {
          calls.incrementAndGet();
          return new StageTeardownService.RunResult(0, "");
        };
    StageTeardownService svc = new StageTeardownService(runner);

    svc.teardownIfNeeded(1L, stage("s", List.of(k8sApply("a"))));

    assertEquals(1, calls.get());
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private static StageModel stage(String id, List<StepModel> steps) {
    StageModel s = new StageModel();
    s.setId(id);
    s.setName(id);
    s.setSteps(steps);
    return s;
  }

  private static StepModel shStep(String id) {
    return stepWithDescriptor(id, "sh");
  }

  private static StepModel k8sApply(String id) {
    return stepWithDescriptor(id, "k8sApply");
  }

  private static StepModel stepWithDescriptor(String id, String descriptorId) {
    StepModel step = new StepModel();
    step.setId(id);
    step.setDescriptorId(descriptorId);
    return step;
  }
}
