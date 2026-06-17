package io.adaptiq.titan.flow.orch;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.flow.model.StageModel;
import io.adaptiq.titan.flow.model.StepModel;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Stage-scoped teardown for {@code k8sApply} steps (design/62 §3, closes #662).
 *
 * <p>The {@code k8sApply} step handler stamps every applied resource with a {@code
 * titan.stage=<buildId>-<stageId>} label. When the orchestrator transitions a stage to its terminal
 * state ({@code SUCCESS} / {@code FAILED} / {@code ABORTED}), this service shells out to {@code
 * kubectl delete all,configmap,secret -l titan.stage=<buildId>-<stageId>} to reclaim those
 * resources. The sweep is bounded by a 60s timeout and runs only when at least one step in the
 * stage was {@code k8sApply} — a stage with no k8s steps never shells out.
 *
 * <p>The teardown is best-effort: a failure (kubectl missing, cluster unreachable, RBAC denied, the
 * 60s timeout firing) is logged at {@code WARNING} and swallowed. The stage's terminal status is
 * <em>not</em> rewritten — a successful stage stays {@code SUCCESS} even if its teardown failed,
 * matching design/62 §3 ("teardown FAILURE must NOT mark the stage as failed").
 *
 * <p>Idempotent: re-running for the same stage with no matching resources is a clean exit 0 from
 * {@code kubectl delete --ignore-not-found}.
 */
public final class StageTeardownService {

  private static final Logger LOGGER = Logger.getLogger(StageTeardownService.class.getName());

  /** The {@code descriptorId} of the step whose presence triggers teardown. */
  private static final String K8S_APPLY = "k8sApply";

  /** Resource kinds swept; mirrors what {@code k8sApply} can create. */
  private static final String SWEEP_KINDS = "all,configmap,secret";

  /** Total wall-clock budget for the kubectl-delete process. */
  private static final long TIMEOUT_SECONDS = 60L;

  private final KubectlRunner runner;

  /** Production constructor — shells out to {@code kubectl} on PATH. */
  public StageTeardownService() {
    this(new ProcessKubectlRunner());
  }

  /** Test seam — inject a recording runner to assert behaviour without a real cluster. */
  public StageTeardownService(@NonNull KubectlRunner runner) {
    this.runner = runner;
  }

  /**
   * Tear down stage-labelled k8s resources if any step in the stage was {@code k8sApply}.
   *
   * @param buildId the owning build id (label component)
   * @param stage the stage that just transitioned to a terminal state
   */
  public void teardownIfNeeded(long buildId, @NonNull StageModel stage) {
    if (!hasK8sApplyStep(stage)) {
      // Adversarial guard: never shell out kubectl for a stage with no k8s steps. Workers that
      // do not have kubectl on PATH must remain unaffected by stage completion in this case.
      return;
    }
    String label = labelValue(buildId, stage.getId());
    try {
      RunResult result = runner.run(label, TIMEOUT_SECONDS, TimeUnit.SECONDS);
      if (result.exitCode == 0) {
        LOGGER.log(
            Level.INFO,
            "[titan] build {0}: stage ''{1}'' k8s teardown swept resources with label {2}",
            new Object[] {buildId, stage.getId(), label});
      } else {
        LOGGER.log(
            Level.WARNING,
            "[titan] build {0}: stage ''{1}'' k8s teardown exited {2} (label {3}); stage status"
                + " unchanged. stderr: {4}",
            new Object[] {buildId, stage.getId(), result.exitCode, label, result.stderrTail});
      }
    } catch (RuntimeException | IOException | InterruptedException e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      LOGGER.log(
          Level.WARNING,
          "[titan] build {0}: stage ''{1}'' k8s teardown failed (label {2}); stage status"
              + " unchanged. cause: {3}",
          new Object[] {buildId, stage.getId(), label, e.getMessage()});
    }
  }

  /** True iff at least one step in the stage is a {@code k8sApply}. */
  static boolean hasK8sApplyStep(@NonNull StageModel stage) {
    for (StepModel step : stage.getSteps()) {
      if (K8S_APPLY.equals(step.getDescriptorId())) {
        return true;
      }
    }
    return false;
  }

  /** The label value the k8sApply handler stamps — must match {@code K8sApplyStepHandler}. */
  @NonNull
  static String labelValue(long buildId, @NonNull String stageId) {
    return sanitiseLabel(buildId + "-" + stageId);
  }

  /** K8s label values: alphanumeric, '-', '_', '.'; ≤ 63 chars. Mirror of the handler. */
  @NonNull
  static String sanitiseLabel(@NonNull String raw) {
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

  /** Pluggable runner — production wraps {@code ProcessBuilder}; tests inject a recorder. */
  public interface KubectlRunner {
    @NonNull
    RunResult run(@NonNull String labelSelector, long timeout, @NonNull TimeUnit unit)
        throws IOException, InterruptedException;
  }

  /**
   * Outcome of one {@code kubectl delete} invocation.
   *
   * @param exitCode kubectl's exit code ({@code 0} = success, including "nothing matched" via
   *     {@code --ignore-not-found})
   * @param stderrTail the last chunk of stderr (for logging on non-zero exit)
   */
  public record RunResult(int exitCode, @NonNull String stderrTail) {}

  /** Default runner — invokes the {@code kubectl} binary on PATH. */
  static final class ProcessKubectlRunner implements KubectlRunner {

    @Override
    @NonNull
    public RunResult run(@NonNull String labelSelector, long timeout, @NonNull TimeUnit unit)
        throws IOException, InterruptedException {
      List<String> argv =
          List.of(
              "kubectl",
              "delete",
              SWEEP_KINDS,
              "-l",
              "titan.stage=" + labelSelector,
              "--ignore-not-found",
              "--timeout=" + unit.toSeconds(timeout) + "s");
      Process process =
          new ProcessBuilder(argv)
              .redirectErrorStream(false)
              .redirectOutput(ProcessBuilder.Redirect.DISCARD)
              .start();
      StringBuilder stderr = new StringBuilder();
      try (BufferedReader reader =
          new BufferedReader(
              new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
        String line;
        while ((line = reader.readLine()) != null) {
          if (stderr.length() < 2048) {
            stderr.append(line).append('\n');
          }
        }
      }
      // Add a small fudge above the inner --timeout so kubectl gets a chance to surface its own
      // timeout message before we hard-kill it.
      if (!process.waitFor(unit.toSeconds(timeout) + 10L, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        return new RunResult(
            124, "kubectl delete exceeded the " + unit.toSeconds(timeout) + "s budget");
      }
      return new RunResult(process.exitValue(), stderr.toString());
    }
  }
}
