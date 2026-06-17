package io.adaptiq.titan.flow.parser;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.adaptiq.titan.flow.model.PipelineModel;
import io.adaptiq.titan.flow.model.TriggerModel;
import org.junit.jupiter.api.Test;

/**
 * Parser coverage for the {@code triggers:} block (design/50 D7) — the PDL projection of build
 * triggers. The block is a ROOT grammar key, so design/48 parser strictness applies to it: a {@code
 * present-and-null} or wrong-typed {@code triggers:} is a located error, and a trigger entry's
 * required {@code cron} is enforced.
 */
class TriggersBlockParsingTest {

  private static final String STAGE = "stages:\n  - stage: S\n    steps:\n      - sh: hi\n";

  /** A well-formed {@code triggers:} block parses into {@link TriggerModel}s carrying the cron. */
  @Test
  void validTriggersBlockParses() {
    PipelineModel model =
        TitanYamlParser.parse("triggers:\n  - cron: \"H 2 * * *\"\n  - cron: \"@daily\"\n" + STAGE);
    assertEquals(2, model.getTriggers().size());
    assertEquals("H 2 * * *", model.getTriggers().get(0).getCron());
    assertEquals("@daily", model.getTriggers().get(1).getCron());
  }

  /** A pipeline with no {@code triggers:} key parses with an empty trigger list. */
  @Test
  void absentTriggersBlockIsEmpty() {
    PipelineModel model = assertDoesNotThrow(() -> TitanYamlParser.parse(STAGE));
    assertTrue(model.getTriggers().isEmpty());
  }

  /** design/48 D3: {@code triggers: null} is a present-and-null error naming the key. */
  @Test
  void presentAndNullTriggersIsRejected() {
    PipelineParseException e =
        assertThrows(
            PipelineParseException.class, () -> TitanYamlParser.parse("triggers: null\n" + STAGE));
    assertTrue(e.getMessage().contains("'triggers'"), e.getMessage());
    assertTrue(e.getMessage().contains("present but null"), e.getMessage());
  }

  /** design/48 D4: {@code triggers:} declared an array rejects a scalar, with a typed message. */
  @Test
  void scalarTriggersIsRejectedWithTypeMessage() {
    PipelineParseException e =
        assertThrows(
            PipelineParseException.class,
            () -> TitanYamlParser.parse("triggers: nightly\n" + STAGE));
    assertTrue(e.getMessage().contains("'triggers'"), e.getMessage());
    assertTrue(e.getMessage().contains("array"), e.getMessage());
  }

  /**
   * A trigger entry missing both discriminator keys ({@code cron}, {@code github}) is a located
   * error.
   */
  @Test
  void triggerEntryMissingDiscriminatorIsRejected() {
    PipelineParseException e =
        assertThrows(
            PipelineParseException.class,
            () -> TitanYamlParser.parse("triggers:\n  - {}\n" + STAGE));
    assertTrue(e.getMessage().contains("cron"), e.getMessage());
    assertTrue(e.getMessage().contains("github"), e.getMessage());
    assertTrue(e.getMessage().contains("triggers[0]"), e.getMessage());
  }

  /**
   * A trigger entry that mixes {@code cron:} and {@code github:} is a located error — the
   * discriminator must be unambiguous (issue #397).
   */
  @Test
  void triggerEntryWithBothDiscriminatorsIsRejected() {
    PipelineParseException e =
        assertThrows(
            PipelineParseException.class,
            () ->
                TitanYamlParser.parse(
                    "triggers:\n"
                        + "  - cron: \"@daily\"\n"
                        + "    github:\n"
                        + "      credentialsId: x\n"
                        + STAGE));
    assertTrue(e.getMessage().contains("exactly one"), e.getMessage());
    assertTrue(e.getMessage().contains("triggers[0]"), e.getMessage());
  }

  /** A well-formed {@code github:} trigger entry parses (issue #397). */
  @Test
  void validGithubTriggerParses() {
    PipelineModel model =
        TitanYamlParser.parse(
            "triggers:\n"
                + "  - github:\n"
                + "      branches: [trunk, 'feat/**']\n"
                + "      events: [push, pull_request]\n"
                + "      credentialsId: github-webhook-secret\n"
                + STAGE);
    assertEquals(1, model.getTriggers().size());
    TriggerModel t = model.getTriggers().get(0);
    assertEquals(null, t.getCron());
    assertTrue(t.getGithub() != null);
    assertEquals(java.util.List.of("trunk", "feat/**"), t.getGithub().getBranches());
    assertEquals(java.util.List.of("push", "pull_request"), t.getGithub().getEvents());
    assertEquals("github-webhook-secret", t.getGithub().getCredentialsId());
  }

  /** A {@code github:} entry without {@code credentialsId} is rejected — secret is required. */
  @Test
  void githubTriggerMissingCredentialsIdIsRejected() {
    PipelineParseException e =
        assertThrows(
            PipelineParseException.class,
            () ->
                TitanYamlParser.parse("triggers:\n  - github:\n      branches: [trunk]\n" + STAGE));
    assertTrue(e.getMessage().contains("credentialsId"), e.getMessage());
  }

  /** A {@code github:} entry whose value is null is a located error. */
  @Test
  void githubTriggerNullValueIsRejected() {
    PipelineParseException e =
        assertThrows(
            PipelineParseException.class,
            () -> TitanYamlParser.parse("triggers:\n  - github: null\n" + STAGE));
    assertTrue(
        e.getMessage().contains("github") && e.getMessage().contains("null"), e.getMessage());
  }

  /** A well-formed {@code bitbucket:} trigger entry parses (issue #1079). */
  @Test
  void validBitbucketTriggerParses() {
    PipelineModel model =
        TitanYamlParser.parse(
            "triggers:\n"
                + "  - bitbucket:\n"
                + "      branches: [trunk, 'feat/**']\n"
                + "      events: [push, pull_request]\n"
                + "      credentialsId: bitbucket-webhook-secret\n"
                + STAGE);
    assertEquals(1, model.getTriggers().size());
    TriggerModel t = model.getTriggers().get(0);
    assertEquals(null, t.getCron());
    assertTrue(t.getBitbucket() != null);
    assertEquals(java.util.List.of("trunk", "feat/**"), t.getBitbucket().getBranches());
    assertEquals(java.util.List.of("push", "pull_request"), t.getBitbucket().getEvents());
    assertEquals("bitbucket-webhook-secret", t.getBitbucket().getCredentialsId());
  }

  /** A {@code bitbucket:} entry without {@code credentialsId} is rejected — secret is required. */
  @Test
  void bitbucketTriggerMissingCredentialsIdIsRejected() {
    PipelineParseException e =
        assertThrows(
            PipelineParseException.class,
            () ->
                TitanYamlParser.parse(
                    "triggers:\n  - bitbucket:\n      branches: [trunk]\n" + STAGE));
    assertTrue(e.getMessage().contains("credentialsId"), e.getMessage());
  }

  /** A {@code bitbucket:} entry whose value is null is a located error. */
  @Test
  void bitbucketTriggerNullValueIsRejected() {
    PipelineParseException e =
        assertThrows(
            PipelineParseException.class,
            () -> TitanYamlParser.parse("triggers:\n  - bitbucket: null\n" + STAGE));
    assertTrue(
        e.getMessage().contains("bitbucket") && e.getMessage().contains("null"), e.getMessage());
  }

  /** An unknown key inside a trigger entry is rejected — the {@code trigger} context is closed. */
  @Test
  void triggerEntryWithUnknownKeyIsRejected() {
    PipelineParseException e =
        assertThrows(
            PipelineParseException.class,
            () -> TitanYamlParser.parse("triggers:\n  - cron: \"@daily\"\n    bogus: 1\n" + STAGE));
    assertTrue(e.getMessage().contains("bogus"), e.getMessage());
  }

  /** design/48 D4: a non-string {@code cron} value is rejected, not coerced. */
  @Test
  void nonStringCronIsRejected() {
    PipelineParseException e =
        assertThrows(
            PipelineParseException.class,
            () -> TitanYamlParser.parse("triggers:\n  - cron: 5\n" + STAGE));
    assertTrue(e.getMessage().contains("cron"), e.getMessage());
    assertTrue(e.getMessage().contains("string"), e.getMessage());
  }
}
