package io.adaptiq.titan.flow.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.adaptiq.titan.flow.model.NotifyHook;
import io.adaptiq.titan.flow.model.PipelineModel;
import io.adaptiq.titan.flow.model.StageModel;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@code notify:} scope (#245). Adversarial — pipeline-root + stage form;
 * type/url/on validation; the slack reservation; the "credentials NEVER inline" rule.
 */
class NotifyScopeTest {

  @Test
  void parsesPipelineRootNotify() {
    String yaml =
        ""
            + "notify:\n"
            + "  - type: webhook\n"
            + "    on: [failure]\n"
            + "    url: http://example.com/hook\n"
            + "stages:\n"
            + "  - stage: Build\n"
            + "    steps:\n"
            + "      - sh: make\n";
    PipelineModel model = TitanYamlParser.parse(yaml);
    List<NotifyHook> hooks = model.getNotify();
    assertEquals(1, hooks.size());
    NotifyHook h = hooks.get(0);
    assertEquals("webhook", h.getType());
    assertEquals(List.of("failure"), h.getOn());
    assertEquals("http://example.com/hook", h.getUrl());
    assertNull(h.getCredentialsId());
    assertTrue(h.firesOnFailure());
    // 'failure' alone does NOT fire on success.
    assertTrue(!h.firesOnSuccess(), "an 'on: [failure]' hook must not fire on success");
  }

  @Test
  void parsesStageLevelNotify() {
    String yaml =
        ""
            + "stages:\n"
            + "  - stage: Build\n"
            + "    notify:\n"
            + "      - type: webhook\n"
            + "        on: [success, failure]\n"
            + "        url: http://stage.example.com\n"
            + "    steps:\n"
            + "      - sh: make\n";
    PipelineModel model = TitanYamlParser.parse(yaml);
    StageModel stage = model.getStage("build");
    assertEquals(1, stage.getNotify().size());
    assertEquals("http://stage.example.com", stage.getNotify().get(0).getUrl());
  }

  @Test
  void emptyOnDefaultsToAlways() {
    String yaml =
        ""
            + "notify:\n"
            + "  - type: webhook\n"
            + "    url: http://example.com\n"
            + "stages:\n"
            + "  - stage: Build\n"
            + "    steps:\n"
            + "      - sh: make\n";
    PipelineModel model = TitanYamlParser.parse(yaml);
    NotifyHook h = model.getNotify().get(0);
    assertTrue(h.getOn().isEmpty());
    assertTrue(h.firesOnSuccess());
    assertTrue(h.firesOnFailure());
  }

  @Test
  void slackTypeRequiresCredentialsIdAndForbidsInlineUrl() {
    // (#358) An inline 'url:' on a slack hook is a token leak — must be rejected.
    String yaml =
        ""
            + "notify:\n"
            + "  - type: slack\n"
            + "    url: https://hooks.slack.com/services/T/B/SECRETTOKEN\n"
            + "    credentialsId: slack-prod\n"
            + "stages:\n"
            + "  - stage: Build\n"
            + "    steps:\n"
            + "      - sh: make\n";
    PipelineParseException e =
        assertThrows(PipelineParseException.class, () -> TitanYamlParser.parse(yaml));
    assertTrue(
        e.getMessage().contains("url"),
        "the slack inline-url rejection must mention url: " + e.getMessage());
    assertTrue(
        e.getMessage().contains("CredentialsService") || e.getMessage().contains("credentialsId"),
        "the message must explain WHY (token routing): " + e.getMessage());
  }

  @Test
  void slackTypeWithCredentialsIdParsesCleanly() {
    String yaml =
        ""
            + "notify:\n"
            + "  - type: slack\n"
            + "    on: [failure]\n"
            + "    credentialsId: slack-prod\n"
            + "    channel: '#deploys'\n"
            + "stages:\n"
            + "  - stage: Build\n"
            + "    steps:\n"
            + "      - sh: make\n";
    PipelineModel model = TitanYamlParser.parse(yaml);
    NotifyHook h = model.getNotify().get(0);
    assertEquals("slack", h.getType());
    assertEquals("slack-prod", h.getCredentialsId());
    assertEquals("#deploys", h.getChannel());
    assertNull(h.getUrl(), "slack must never carry an inline url");
    assertTrue(h.firesOnFailure());
  }

  @Test
  void slackTypeWithoutCredentialsIdIsRejected() {
    // (#358) type: slack without credentialsId would mean nothing to dispatch — reject at parse
    // time with a clear message pointing at CredentialsService.
    String yaml =
        ""
            + "notify:\n"
            + "  - type: slack\n"
            + "    on: [failure]\n"
            + "stages:\n"
            + "  - stage: Build\n"
            + "    steps:\n"
            + "      - sh: make\n";
    PipelineParseException e =
        assertThrows(PipelineParseException.class, () -> TitanYamlParser.parse(yaml));
    assertTrue(
        e.getMessage().contains("credentialsId"),
        "the message must demand credentialsId: " + e.getMessage());
    assertTrue(
        e.getMessage().toLowerCase().contains("slack"),
        "the message must mention slack: " + e.getMessage());
  }

  @Test
  void webhookHookAcceptsAnyUrlIncludingSlackShaped() {
    // (#358) Regression-guard against the URL-sniffing bandaid: the schema's `type:` discriminator
    // is the contract. We do NOT inspect URL shape to "detect" misuse. If a user writes
    // `type: webhook` with a hooks.slack.com URL, that's their config — it parses. The CTO
    // explicitly rejected runtime URL-shape inference (design/58).
    String yaml =
        ""
            + "notify:\n"
            + "  - type: webhook\n"
            + "    url: https://hooks.slack.com/services/T/B/SOMEPATH\n"
            + "stages:\n"
            + "  - stage: Build\n"
            + "    steps:\n"
            + "      - sh: make\n";
    PipelineModel model = TitanYamlParser.parse(yaml);
    NotifyHook h = model.getNotify().get(0);
    assertEquals("webhook", h.getType());
    assertEquals("https://hooks.slack.com/services/T/B/SOMEPATH", h.getUrl());
  }

  @Test
  void channelIsForbiddenOnNonSlackHooks() {
    String yaml =
        ""
            + "notify:\n"
            + "  - type: webhook\n"
            + "    url: http://example.com\n"
            + "    channel: '#deploys'\n"
            + "stages:\n"
            + "  - stage: Build\n"
            + "    steps:\n"
            + "      - sh: make\n";
    PipelineParseException e =
        assertThrows(PipelineParseException.class, () -> TitanYamlParser.parse(yaml));
    assertTrue(e.getMessage().contains("channel"), e.getMessage());
  }

  @Test
  void unknownTypeIsRejected() {
    String yaml =
        ""
            + "notify:\n"
            + "  - type: pagerduty\n"
            + "    url: http://example.com\n"
            + "stages:\n"
            + "  - stage: Build\n"
            + "    steps:\n"
            + "      - sh: make\n";
    assertThrows(PipelineParseException.class, () -> TitanYamlParser.parse(yaml));
  }

  @Test
  void urlIsRequiredForWebhook() {
    String yaml =
        ""
            + "notify:\n"
            + "  - type: webhook\n"
            + "    on: [failure]\n"
            + "stages:\n"
            + "  - stage: Build\n"
            + "    steps:\n"
            + "      - sh: make\n";
    PipelineParseException e =
        assertThrows(PipelineParseException.class, () -> TitanYamlParser.parse(yaml));
    assertTrue(e.getMessage().contains("url"), e.getMessage());
  }

  @Test
  void presentButNullNotifyIsALocatedError() {
    String yaml =
        ""
            + "notify: ~\n"
            + "stages:\n"
            + "  - stage: Build\n"
            + "    steps:\n"
            + "      - sh: make\n";
    PipelineParseException e =
        assertThrows(PipelineParseException.class, () -> TitanYamlParser.parse(yaml));
    assertTrue(
        e.getMessage().toLowerCase().contains("null") || e.getMessage().contains("notify"),
        e.getMessage());
  }

  @Test
  void unknownKeyOnHookIsRejected() {
    String yaml =
        ""
            + "notify:\n"
            + "  - type: webhook\n"
            + "    url: http://example.com\n"
            + "    token: SECRET\n"
            + "stages:\n"
            + "  - stage: Build\n"
            + "    steps:\n"
            + "      - sh: make\n";
    PipelineParseException e =
        assertThrows(PipelineParseException.class, () -> TitanYamlParser.parse(yaml));
    assertTrue(
        e.getMessage().contains("token"),
        "a plaintext 'token' key MUST be rejected so secrets never land in config_json: "
            + e.getMessage());
  }

  @Test
  void unknownOnValueIsRejected() {
    String yaml =
        ""
            + "notify:\n"
            + "  - type: webhook\n"
            + "    url: http://example.com\n"
            + "    on: [maybe]\n"
            + "stages:\n"
            + "  - stage: Build\n"
            + "    steps:\n"
            + "      - sh: make\n";
    assertThrows(PipelineParseException.class, () -> TitanYamlParser.parse(yaml));
  }

  @Test
  void notifyKeyIsNotValidOnAStep() {
    String yaml =
        ""
            + "stages:\n"
            + "  - stage: Build\n"
            + "    steps:\n"
            + "      - sh: make\n"
            + "        notify:\n"
            + "          - type: webhook\n"
            + "            url: http://example.com\n";
    // notify is not a step-level scope key — it should be parsed as a (bogus) second descriptor.
    assertThrows(PipelineParseException.class, () -> TitanYamlParser.parse(yaml));
  }

  // ── #1102 — `recovery` predicate (fail→success transition) ──────────────

  @Test
  void parsesRecoveryPredicate() {
    String yaml =
        ""
            + "notify:\n"
            + "  - type: slack\n"
            + "    on: [failure, recovery]\n"
            + "    credentialsId: slack-prod\n"
            + "    channel: '#oncall'\n"
            + "stages:\n"
            + "  - stage: Build\n"
            + "    steps:\n"
            + "      - sh: make\n";
    PipelineModel model = TitanYamlParser.parse(yaml);
    NotifyHook h = model.getNotify().get(0);
    assertEquals(List.of("failure", "recovery"), h.getOn());
    assertTrue(h.firesOnFailure());
    assertTrue(h.firesOnRecovery());
    assertTrue(!h.firesOnSuccess(), "[failure, recovery] must NOT fire on every green build");
  }

  @Test
  void rejectsUnknownOnValue() {
    String yaml =
        ""
            + "notify:\n"
            + "  - type: webhook\n"
            + "    on: [failure, banana]\n"
            + "    url: http://example.com\n"
            + "stages:\n"
            + "  - stage: Build\n"
            + "    steps:\n"
            + "      - sh: make\n";
    assertThrows(PipelineParseException.class, () -> TitanYamlParser.parse(yaml));
  }
}
