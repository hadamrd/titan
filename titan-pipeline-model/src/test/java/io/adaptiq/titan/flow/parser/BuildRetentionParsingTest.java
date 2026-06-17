package io.adaptiq.titan.flow.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.adaptiq.titan.flow.model.PipelineModel;
import org.junit.jupiter.api.Test;

/**
 * Tests {@code buildRetention:} parsing (#640) — the per-job override of the daily build-history
 * prune.
 *
 * <p>Shape: a closed object {@code { keepLast: <integer >= 0> }} at the pipeline root. Absent → the
 * server-wide {@code TITAN_JOB_BUILD_RETENTION} default applies; present with {@code keepLast: 0} →
 * per-job opt-out (full history kept regardless of the global default).
 */
class BuildRetentionParsingTest {

  private static PipelineModel parse(String yaml) {
    return TitanYamlParser.parse(yaml);
  }

  @Test
  void absentBuildRetentionLeavesOverrideNull() {
    PipelineModel model =
        parse("stages:\n" + "  - stage: build\n" + "    steps:\n" + "      - sh: echo hi\n");
    assertNull(model.getBuildRetentionKeepLast(), "absent buildRetention → null override");
  }

  @Test
  void buildRetentionKeepLastIsParsedAsInteger() {
    PipelineModel model =
        parse(
            "buildRetention:\n"
                + "  keepLast: 5\n"
                + "stages:\n"
                + "  - stage: build\n"
                + "    steps:\n"
                + "      - sh: echo hi\n");
    assertNotNull(model.getBuildRetentionKeepLast());
    assertEquals(5, model.getBuildRetentionKeepLast().intValue());
  }

  @Test
  void buildRetentionZeroIsPreservedAsPerJobOptOut() {
    PipelineModel model =
        parse(
            "buildRetention:\n"
                + "  keepLast: 0\n"
                + "stages:\n"
                + "  - stage: build\n"
                + "    steps:\n"
                + "      - sh: echo hi\n");
    assertNotNull(model.getBuildRetentionKeepLast());
    assertEquals(0, model.getBuildRetentionKeepLast().intValue(), "0 = per-job opt-out");
  }

  @Test
  void buildRetentionInsideLegacyTitanWrapperIsAccepted() {
    PipelineModel model =
        parse(
            "titan:\n"
                + "  buildRetention:\n"
                + "    keepLast: 42\n"
                + "  stages:\n"
                + "    - stage: build\n"
                + "      steps:\n"
                + "        - sh: echo hi\n");
    assertEquals(42, model.getBuildRetentionKeepLast().intValue());
  }

  @Test
  void missingKeepLastIsRejected() {
    PipelineParseException ex =
        assertThrows(
            PipelineParseException.class,
            () ->
                parse(
                    "buildRetention: {}\n"
                        + "stages:\n"
                        + "  - stage: build\n"
                        + "    steps:\n"
                        + "      - sh: echo hi\n"));
    assertTrue(ex.getMessage().contains("keepLast"), ex.getMessage());
  }

  @Test
  void negativeKeepLastIsRejected() {
    PipelineParseException ex =
        assertThrows(
            PipelineParseException.class,
            () ->
                parse(
                    "buildRetention:\n"
                        + "  keepLast: -1\n"
                        + "stages:\n"
                        + "  - stage: build\n"
                        + "    steps:\n"
                        + "      - sh: echo hi\n"));
    assertTrue(ex.getMessage().contains(">= 0"), ex.getMessage());
  }

  @Test
  void nonIntegerKeepLastIsRejected() {
    PipelineParseException ex =
        assertThrows(
            PipelineParseException.class,
            () ->
                parse(
                    "buildRetention:\n"
                        + "  keepLast: \"ten\"\n"
                        + "stages:\n"
                        + "  - stage: build\n"
                        + "    steps:\n"
                        + "      - sh: echo hi\n"));
    assertTrue(ex.getMessage().contains("integer"), ex.getMessage());
  }

  @Test
  void unknownKeyOnBuildRetentionIsRejected() {
    PipelineParseException ex =
        assertThrows(
            PipelineParseException.class,
            () ->
                parse(
                    "buildRetention:\n"
                        + "  keepLast: 5\n"
                        + "  keepDays: 30\n"
                        + "stages:\n"
                        + "  - stage: build\n"
                        + "    steps:\n"
                        + "      - sh: echo hi\n"));
    assertTrue(ex.getMessage().contains("keepDays"), ex.getMessage());
  }

  @Test
  void buildRetentionPresentButNullIsRejected() {
    PipelineParseException ex =
        assertThrows(
            PipelineParseException.class,
            () ->
                parse(
                    "buildRetention: ~\n"
                        + "stages:\n"
                        + "  - stage: build\n"
                        + "    steps:\n"
                        + "      - sh: echo hi\n"));
    assertTrue(ex.getMessage().contains("buildRetention"), ex.getMessage());
  }
}
