package io.adaptiq.titan.flow.orch;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.flow.model.StepModel;
import io.adaptiq.titan.store.FlowNodeDao;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.TaskQueueRow;
import io.adaptiq.titan.store.rows.TimerRow;
import io.adaptiq.titan.timer.TimerService;
import java.time.Instant;
import java.util.List;

/**
 * Arms TIMEOUT timers when a step is dispatched and enforces fired timers on a later tick (#244 +
 * #357 decomposition). Extracted verbatim from {@code TitanOrchestrator}.
 */
public final class TimeoutEnforcer {

  private final TitanStores daos;
  private final long buildId;
  private final TimerService timerService;

  public TimeoutEnforcer(
      @NonNull TitanStores daos, long buildId, @NonNull TimerService timerService) {
    this.daos = daos;
    this.buildId = buildId;
    this.timerService = timerService;
  }

  /**
   * Arm a TIMEOUT timer for a step that declares one. Idempotent — a re-dispatch re-arms
   * harmlessly.
   */
  public void armTimeout(@NonNull StepModel step) {
    Long timeoutMillis = step.getTimeoutMillis();
    if (timeoutMillis != null) {
      timerService.arm(
          TimerService.Kind.TIMEOUT,
          buildId,
          step.getId(),
          Instant.now().plusMillis(timeoutMillis),
          null);
    }
  }

  /**
   * Enforce fired TIMEOUT timers — a step still QUEUED whose deadline passed is failed and its
   * worker task cancelled (the worker's cancellation poll turns that into a real process kill).
   *
   * <p>A timed-out step is failed terminally — it is <strong>not</strong> retried, even under a
   * {@code retry:} policy: a hung step that already burned its full deadline should not silently
   * burn another. The node goes straight to {@code FAILED} with category {@code TIMEOUT}.
   */
  public int enforceTimeouts(@NonNull FlowNodeDao flowNodes, @NonNull List<TaskQueueRow> tasks) {
    int enforced = 0;
    for (TimerRow timer : daos.timers().listByBuild(buildId)) {
      if (!"TIMEOUT".equals(timer.kind) || !"FIRED".equals(timer.status)) {
        continue;
      }
      int won =
          flowNodes.compareAndSetStatus(
              buildId, timer.nodeId, "QUEUED", "FAILED", null, Instant.now(), null, null);
      if (won == 1) {
        flowNodes.updateFailure(buildId, timer.nodeId, "TIMEOUT", "step exceeded its timeout");
        for (TaskQueueRow t : tasks) {
          if ("EXECUTE_COMMAND".equals(t.type) && timer.nodeId.equals(t.nodeId)) {
            daos.taskQueue().cancel(t.id);
          }
        }
        enforced++;
      }
    }
    return enforced;
  }
}
