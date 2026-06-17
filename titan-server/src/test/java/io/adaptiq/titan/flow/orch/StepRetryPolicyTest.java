package io.adaptiq.titan.flow.orch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.adaptiq.titan.flow.model.RetryPolicy;
import io.adaptiq.titan.store.rows.TaskQueueRow;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pure-policy tests for {@link StepRetryPolicy} — the static decision helpers (no DB, no fake
 * stores). The orchestrated {@code retryStep} path is covered end-to-end by {@code
 * TitanOrchestratorRetryIT} (the regression oracle for this refactor).
 */
class StepRetryPolicyTest {

  @Test
  void exitCodeOfReturnsTheParsedIntegerOrNullOnAbsence() {
    assertEquals(0, StepRetryPolicy.exitCodeOf("{\"exitCode\":0}"));
    assertEquals(2, StepRetryPolicy.exitCodeOf("{\"exitCode\":2}"));
    assertNull(StepRetryPolicy.exitCodeOf(null));
    assertNull(StepRetryPolicy.exitCodeOf(""));
    assertNull(StepRetryPolicy.exitCodeOf("{}"), "no exitCode key → null");
    assertNull(StepRetryPolicy.exitCodeOf("not-json"), "garbage → null, never throws");
  }

  @Test
  void isRetryableIsTrueForInfraFailuresAndAnyNonZeroByDefault() {
    RetryPolicy policy = new RetryPolicy();
    // No exit code (worker died / reaper) → retryable (bounded by maxAttempts upstream).
    assertTrue(StepRetryPolicy.isRetryable(policy, null));
    assertTrue(StepRetryPolicy.isRetryable(policy, "{\"error\":\"worker died\"}"));
    // Empty allowlist → any non-zero exit retries.
    assertTrue(StepRetryPolicy.isRetryable(policy, "{\"exitCode\":7}"));
    // exit 0 is success — never retry.
    assertFalse(StepRetryPolicy.isRetryable(policy, "{\"exitCode\":0}"));
  }

  @Test
  void isRetryableHonoursTheAllowlistWhenNonEmpty() {
    RetryPolicy policy = new RetryPolicy();
    policy.setRetryableExitCodes(List.of(42));

    assertTrue(StepRetryPolicy.isRetryable(policy, "{\"exitCode\":42}"));
    assertFalse(
        StepRetryPolicy.isRetryable(policy, "{\"exitCode\":1}"),
        "1 is not in the allowlist → fail fast");
  }

  @Test
  void backoffMillisIsCappedAtMaxAndScalesExponentially() {
    RetryPolicy policy = new RetryPolicy();
    policy.getBackoff().setInitialMillis(1_000L);
    policy.getBackoff().setMultiplier(2.0);
    policy.getBackoff().setMaxMillis(8_000L);

    // attempt 1 → initial * 2^0 = 1000
    assertEquals(1_000L, StepRetryPolicy.backoffMillis(policy, 1));
    // attempt 2 → 2000, attempt 3 → 4000, attempt 4 → 8000, attempt 5 → capped at 8000
    assertEquals(2_000L, StepRetryPolicy.backoffMillis(policy, 2));
    assertEquals(4_000L, StepRetryPolicy.backoffMillis(policy, 3));
    assertEquals(8_000L, StepRetryPolicy.backoffMillis(policy, 4));
    assertEquals(8_000L, StepRetryPolicy.backoffMillis(policy, 5));
    assertEquals(8_000L, StepRetryPolicy.backoffMillis(policy, 10));
  }

  @Test
  void stepExitCategoryAndReasonReflectExitCodePresence() {
    TaskQueueRow ranAndExited = task("{\"exitCode\":3}");
    assertEquals("STEP_EXIT", StepRetryPolicy.stepExitCategory(ranAndExited));
    assertTrue(StepRetryPolicy.stepExitReason(ranAndExited).contains("exited 3"));

    TaskQueueRow infra = task("{\"error\":\"worker died\"}");
    assertEquals("TIMEOUT", StepRetryPolicy.stepExitCategory(infra));
    assertTrue(StepRetryPolicy.stepExitReason(infra).contains("worker died"));

    TaskQueueRow noResult = task(null);
    assertEquals("TIMEOUT", StepRetryPolicy.stepExitCategory(noResult));
    assertTrue(StepRetryPolicy.stepExitReason(noResult).contains("reaped"));
  }

  private static TaskQueueRow task(String resultJson) {
    TaskQueueRow t = new TaskQueueRow();
    t.resultJson = resultJson;
    return t;
  }
}
