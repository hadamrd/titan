package io.adaptiq.titan.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.adaptiq.titan.flow.model.NotifyHook;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Adversarial unit tests for the #1102 rich notification payload formatter.
 *
 * <p>Covers the three formatter cases in the issue's test matrix — plain {@code fail}, {@code
 * recovery}, and {@code first-failure-after-N-passes} — for BOTH the webhook envelope and the Slack
 * block-kit body, plus the {@code on: [recovery]} predicate semantics and the
 * "secrets-never-in-payload" contract.
 *
 * <p>These tests deliberately do NOT exercise network I/O — the network branch is covered by {@link
 * NotificationDispatcherTest} (existing) and by the integration test {@code
 * NotificationDispatcherSlackIT}.
 */
class NotificationContextFormatterTest {

  // ── webhook envelope ─────────────────────────────────────────────────────

  @Test
  void webhookPayload_failedBuild_carriesAllSreFields() {
    // deepLink is the FULLY-QUALIFIED build-detail URL per the NotificationContext contract —
    // BuildCloser appends "/builds/{id}" to titan.public-url BEFORE constructing the context;
    // the formatter emits it verbatim (issue #41: the old fixture passed the bare base URL and
    // expected the formatter to append the path, which was never the formatter's job).
    NotificationContext ctx =
        NotificationContext.of(
            42L,
            "FAILED",
            "FAILED",
            "team-app/release",
            72_345L,
            "Deploy to prod",
            "https://titan.example.com/builds/42");

    ObjectNode body = NotificationDispatcher.buildWebhookPayload(ctx);
    assertEquals("BUILD_FAILED", body.get("kind").asText());
    assertEquals(42L, body.get("buildId").asLong());
    assertEquals("FAILED", body.get("status").asText());
    assertEquals("FAILED", body.get("previousStatus").asText());
    assertEquals("team-app/release", body.get("jobName").asText());
    assertEquals(72_345L, body.get("durationMs").asLong());
    assertEquals("1m 12s", body.get("duration").asText());
    assertEquals("Deploy to prod", body.get("failStage").asText());
    assertEquals("https://titan.example.com/builds/42", body.get("url").asText());
  }

  @Test
  void webhookPayload_firstFailureAfterPasses_tagsKind() {
    NotificationContext ctx =
        NotificationContext.of(7L, "FAILED", "SUCCESS", "core/build", 9_000L, "Test", "u/b/7");

    ObjectNode body = NotificationDispatcher.buildWebhookPayload(ctx);
    assertEquals(
        "BUILD_FIRST_FAILURE",
        body.get("kind").asText(),
        "first failure after a SUCCESS must be distinct from BUILD_FAILED");
    // The fail->success transition test below is the mirror image.
  }

  @Test
  void webhookPayload_recovery_tagsKindAndDropsFailStage() {
    NotificationContext ctx =
        NotificationContext.of(8L, "SUCCESS", "FAILED", "core/build", 4_000L, null, "u/b/8");

    ObjectNode body = NotificationDispatcher.buildWebhookPayload(ctx);
    assertEquals("BUILD_RECOVERED", body.get("kind").asText());
    assertEquals("SUCCESS", body.get("status").asText());
    assertEquals("FAILED", body.get("previousStatus").asText());
    assertNull(body.get("failStage"), "no failed-stage on a SUCCESS payload");
  }

  @Test
  void webhookPayload_omitsNullFields() {
    // First-finished build of a job, no prior result, no stage info, no deep-link, no duration.
    NotificationContext ctx = NotificationContext.of(1L, "SUCCESS", null, null, null, null, null);
    ObjectNode body = NotificationDispatcher.buildWebhookPayload(ctx);
    assertEquals("BUILD_SUCCEEDED", body.get("kind").asText());
    assertNull(body.get("previousStatus"), "previousStatus omitted when unknown");
    assertNull(body.get("jobName"));
    assertNull(body.get("durationMs"));
    assertNull(body.get("failStage"));
    assertNull(body.get("url"), "deep-link omitted when titan.public-url unset");
    assertEquals("n/a", body.get("duration").asText());
  }

  // ── Slack block-kit body ─────────────────────────────────────────────────

  @Test
  void slackPayload_failedBuild_buildsBlockKitWithDeepLinkButton() {
    NotificationContext ctx =
        NotificationContext.of(
            42L, "FAILED", "SUCCESS", "team-app/release", 72_345L, "Deploy", "https://t.example/x");
    ObjectNode body = NotificationDispatcher.buildSlackPayloadRich("#oncall", ctx);

    assertEquals("#oncall", body.get("channel").asText());
    assertTrue(body.get("text").asText().contains("FAILED"));
    JsonNode blocks = body.get("blocks");
    assertNotNull(blocks);
    assertTrue(blocks.isArray());
    // Header + fields + actions = 3 blocks (deep-link present).
    assertTrue(blocks.size() >= 3, "expected >=3 blocks, got " + blocks.size());

    // Header carries the job name + build id + headline label.
    String headerText = blocks.get(0).get("text").get("text").asText();
    assertTrue(headerText.contains("team-app/release"));
    assertTrue(headerText.contains("#42"));
    assertTrue(headerText.contains("FAILED"));

    // Fields block carries Duration + Failed stage + Previous.
    JsonNode fields = blocks.get(1).get("fields");
    assertTrue(fields.isArray());
    boolean sawDuration = false;
    boolean sawStage = false;
    boolean sawPrevious = false;
    for (JsonNode f : fields) {
      String t = f.get("text").asText();
      if (t.contains("Duration") && t.contains("1m 12s")) sawDuration = true;
      if (t.contains("Failed stage") && t.contains("Deploy")) sawStage = true;
      if (t.contains("Previous") && t.contains("SUCCESS")) sawPrevious = true;
    }
    assertTrue(sawDuration, "fields block should carry Duration");
    assertTrue(sawStage, "fields block should carry Failed stage");
    assertTrue(sawPrevious, "fields block should carry Previous (SUCCESS — first-failure)");

    // Actions block carries the deep-link button.
    JsonNode actions = blocks.get(2);
    assertEquals("actions", actions.get("type").asText());
    JsonNode button = actions.get("elements").get(0);
    assertEquals("button", button.get("type").asText());
    assertEquals("https://t.example/x", button.get("url").asText());
  }

  @Test
  void slackPayload_recovery_headlineSaysRecovered() {
    NotificationContext ctx =
        NotificationContext.of(8L, "SUCCESS", "FAILED", "svc/api", 1_000L, null, "https://t/8");
    ObjectNode body = NotificationDispatcher.buildSlackPayloadRich(null, ctx);
    assertTrue(body.get("text").asText().contains("RECOVERED"));
    String headerText = body.get("blocks").get(0).get("text").get("text").asText();
    assertTrue(headerText.contains("RECOVERED"));
    assertTrue(headerText.contains("svc/api"));
  }

  @Test
  void slackPayload_omitsActionsBlockWhenNoDeepLink() {
    NotificationContext ctx =
        NotificationContext.of(9L, "FAILED", null, "svc/api", 500L, "Test", null);
    ObjectNode body = NotificationDispatcher.buildSlackPayloadRich(null, ctx);
    JsonNode blocks = body.get("blocks");
    for (JsonNode b : blocks) {
      assertFalse(
          "actions".equals(b.get("type").asText()),
          "no actions block must be emitted when deep-link is unknown");
    }
  }

  // ── duration rendering ───────────────────────────────────────────────────

  @Test
  void renderDuration_humanReadable() {
    assertEquals("n/a", ctx(null).renderDuration());
    assertEquals("n/a", ctx(0L).renderDuration(), "<=0 ms is treated as unknown");
    assertEquals("3s", ctx(3_000L).renderDuration());
    assertEquals("1m 12s", ctx(72_345L).renderDuration());
    assertEquals("1h 2m 3s", ctx(3_723_000L).renderDuration());
  }

  // ── recovery predicate (matchesRich) ─────────────────────────────────────

  @Test
  void recoveryHook_firesOnlyOnFailToSuccessTransition() {
    NotifyHook hook = new NotifyHook();
    hook.setType("webhook");
    hook.setOn(List.of("recovery"));
    hook.setUrl("http://nope");

    // Plain green (no prior failure) — must NOT fire.
    assertFalse(
        NotificationDispatcher.matchesRich(
            hook, NotificationContext.of(1L, "SUCCESS", "SUCCESS", null, null, null, null)),
        "recovery hook must NOT fire on ordinary green");
    // First-ever build, no previous — must NOT fire (no transition to recover from).
    assertFalse(
        NotificationDispatcher.matchesRich(
            hook, NotificationContext.of(1L, "SUCCESS", null, null, null, null, null)));
    // Fail->success — MUST fire.
    assertTrue(
        NotificationDispatcher.matchesRich(
            hook, NotificationContext.of(1L, "SUCCESS", "FAILED", null, null, null, null)),
        "recovery hook MUST fire on fail→success transition");
    // Failure with recovery-only predicate — must NOT fire.
    assertFalse(
        NotificationDispatcher.matchesRich(
            hook, NotificationContext.of(1L, "FAILED", "SUCCESS", null, null, null, null)));
  }

  @Test
  void successHookAlone_doesNotFireOnRecoveryOnly() {
    // Spec: a [success] hook should fire on every green build, recovery or not.
    NotifyHook hook = new NotifyHook();
    hook.setType("webhook");
    hook.setOn(List.of("success"));
    hook.setUrl("http://nope");
    assertTrue(
        NotificationDispatcher.matchesRich(
            hook, NotificationContext.of(1L, "SUCCESS", "FAILED", null, null, null, null)),
        "[success] should still fire on a recovery (recovery IS a SUCCESS)");
  }

  @Test
  void failureHook_firesOnFirstFailureAfterPasses() {
    NotifyHook hook = new NotifyHook();
    hook.setType("webhook");
    hook.setOn(List.of("failure"));
    hook.setUrl("http://nope");
    assertTrue(
        NotificationDispatcher.matchesRich(
            hook, NotificationContext.of(1L, "FAILED", "SUCCESS", null, null, null, null)));
  }

  // ── secret hygiene ───────────────────────────────────────────────────────

  @Test
  void webhookPayload_neverContainsCredentialsId() {
    // Defence-in-depth: the dispatcher's WEBHOOK envelope (the JSON that goes over the wire) must
    // never contain credentialsId / token / resolved-url fields — those are dispatcher-internal.
    NotificationContext ctx =
        NotificationContext.of(1L, "FAILED", "SUCCESS", "j", 1L, "s", "http://x");
    String body = NotificationDispatcher.buildWebhookPayload(ctx).toString();
    assertFalse(body.contains("credentialsId"), "credentialsId leaked into payload: " + body);
    assertFalse(body.contains("token"), "token leaked into payload: " + body);
    assertFalse(body.contains("hooks.slack.com"), "Slack URL leaked into payload: " + body);
  }

  // ── helpers ──────────────────────────────────────────────────────────────

  private static NotificationContext ctx(Long duration) {
    return NotificationContext.of(1L, "SUCCESS", null, null, duration, null, null);
  }
}
