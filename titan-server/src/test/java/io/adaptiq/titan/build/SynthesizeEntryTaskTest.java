package io.adaptiq.titan.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.adaptiq.titan.store.rows.TaskQueueRow;
import org.junit.jupiter.api.Test;

/**
 * Pins the shared entry-task contract in {@link BuildEnqueuer#synthesizeEntryTask} — the regression
 * guard for issue #106.
 *
 * <p>Every cron-fired build used to fail-close in under a second because {@code
 * DbTriggerScope.fire()} hand-rolled its own ORCHESTRATE payload as {@code {"buildId":N}} — no
 * {@code "action"} key — and {@code QueueProcessor.dispatch} routes on {@code
 * payload.get("action")} ("unknown orchestration action 'null'"). The payload contract now lives in
 * exactly one method; this test makes any drift of that contract a loud unit failure instead of a
 * silent production-only fail-close.
 */
class SynthesizeEntryTaskTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void payloadCarriesTheSynthesizeActionKey() throws Exception {
    TaskQueueRow task = BuildEnqueuer.synthesizeEntryTask(1049L);

    assertNotNull(task.payloadJson, "the entry task must carry a payload");
    JsonNode payload = JSON.readTree(task.payloadJson);

    // THE #106 regression pin: no "action" key → QueueProcessor dispatches on null → the build
    // fail-closes before the worker ever sees it.
    assertTrue(payload.hasNonNull("action"), "payload MUST carry an 'action' key (issue #106)");
    assertEquals(
        "SYNTHESIZE",
        payload.get("action").asText(),
        "SYNTHESIZE is the design/38 §3 entry action — anything else dies in handleBake with"
            + " 'no synthesized model'");
    assertEquals(1049L, payload.get("buildId").asLong(), "payload must address the new build");
    assertEquals(2, payload.size(), "exactly {action, buildId} — no undeclared payload fields");
  }

  @Test
  void rowFieldsMatchTheReconciledEntryContract() {
    TaskQueueRow task = BuildEnqueuer.synthesizeEntryTask(42L);

    assertEquals("ORCHESTRATE", task.type, "entry tasks are controller-claimed ORCHESTRATE work");
    assertEquals("default", task.queueName, "controllers claim from the shared default queue");
    assertEquals("QUEUED", task.status);
    assertEquals(Long.valueOf(42L), task.buildId, "build_id column must mirror the payload");
    assertNotNull(task.availableAt, "entry tasks are claimable immediately");

    // Reconciled divergences from #106 (manual path 0/3600 vs cron path 5/300):
    assertEquals(
        0,
        task.priority,
        "priority 0 — cron builds must not jump ahead of manual/webhook builds; job-level"
            + " priority weight (#1100) is layered on post-synthesis");
    assertEquals(
        3600,
        task.visibilityTimeoutSeconds,
        "3600s lease — matches every other entry path and the reaper floor; a 300s lease risks"
            + " a double-claimed build coordinator (#106)");
    assertEquals(3, task.maxAttempts, "maxAttempts was 3 on both diverged paths — kept");
    assertEquals(0, task.attempts);
  }
}
