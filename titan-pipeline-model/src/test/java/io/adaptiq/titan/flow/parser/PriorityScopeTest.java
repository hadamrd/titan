package io.adaptiq.titan.flow.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.adaptiq.titan.flow.model.PipelineModel;
import io.adaptiq.titan.flow.model.PriorityConfig;
import org.junit.jupiter.api.Test;

/**
 * Tests for the {@code priority:} pipeline-root scope (issue #1100).
 *
 * <p>Covers the discriminated-union contract:
 *
 * <ul>
 *   <li>absent → {@code null} (effective NORMAL, weight 0 — backwards compatible);
 *   <li>{@code high} / {@code normal} / {@code low} → the matching {@link PriorityConfig};
 *   <li>case-insensitive accept;
 *   <li>bare integer rejected (THE adversarial path — issue calls this out explicitly);
 *   <li>unknown string rejected;
 *   <li>present-but-null rejected;
 *   <li>non-string (object / array) rejected.
 * </ul>
 */
class PriorityScopeTest {

  private static PipelineModel parse(String body) {
    return TitanYamlParser.parse(
        body + "stages:\n  - stage: build\n    steps:\n      - sh: echo hi\n");
  }

  @Test
  void absentPriorityLeavesConfigNull() {
    PipelineModel m = parse("");
    assertNull(m.getPriority(), "absent priority → null (effective NORMAL)");
    assertEquals(0, m.effectivePriorityWeight(), "absent → weight 0 (NORMAL default)");
  }

  @Test
  void highMapsTo10() {
    PipelineModel m = parse("priority: high\n");
    assertEquals(PriorityConfig.HIGH, m.getPriority());
    assertEquals(10, m.effectivePriorityWeight());
  }

  @Test
  void normalMapsTo0() {
    PipelineModel m = parse("priority: normal\n");
    assertEquals(PriorityConfig.NORMAL, m.getPriority());
    assertEquals(0, m.effectivePriorityWeight());
  }

  @Test
  void lowMapsToNegative10() {
    PipelineModel m = parse("priority: low\n");
    assertEquals(PriorityConfig.LOW, m.getPriority());
    assertEquals(-10, m.effectivePriorityWeight());
  }

  @Test
  void caseInsensitive() {
    assertEquals(PriorityConfig.HIGH, parse("priority: HIGH\n").getPriority());
    assertEquals(PriorityConfig.LOW, parse("priority: Low\n").getPriority());
  }

  // ── adversarial / sad path ────────────────────────────────────────────

  @Test
  void bareIntegerIsRejected() {
    // Issue #1100 explicitly: "Discriminated-union; not a free-form integer in PDL."
    PipelineParseException ex =
        assertThrows(PipelineParseException.class, () -> parse("priority: 10\n"));
    assertTrue(
        ex.getMessage().contains("'high', 'normal', or 'low'"),
        () -> "error must steer the user to the enum tiers, got: " + ex.getMessage());
  }

  @Test
  void unknownStringIsRejected() {
    PipelineParseException ex =
        assertThrows(PipelineParseException.class, () -> parse("priority: critical\n"));
    assertTrue(
        ex.getMessage().contains("critical"),
        () -> "error must name the offending token, got: " + ex.getMessage());
  }

  @Test
  void presentButNullIsRejected() {
    assertThrows(PipelineParseException.class, () -> parse("priority:\n"));
  }

  @Test
  void objectShapeIsRejected() {
    assertThrows(PipelineParseException.class, () -> parse("priority:\n  tier: high\n"));
  }

  @Test
  void booleanShapeIsRejected() {
    assertThrows(PipelineParseException.class, () -> parse("priority: true\n"));
  }

  // ── PriorityConfig.fromYaml — direct contract ─────────────────────────

  @Test
  void fromYamlMappingMatchesEnum() {
    assertEquals(PriorityConfig.HIGH, PriorityConfig.fromYaml("high", "ctx"));
    assertEquals(PriorityConfig.NORMAL, PriorityConfig.fromYaml(" normal ", "ctx"));
    assertEquals(PriorityConfig.LOW, PriorityConfig.fromYaml("LOW", "ctx"));
  }

  @Test
  void fromYamlRejectsBareIntegerString() {
    assertThrows(IllegalArgumentException.class, () -> PriorityConfig.fromYaml("10", "ctx"));
  }

  @Test
  void weightLadderIsHighOverNormalOverLow() {
    // Locks the strict weight ordering the task_queue claim depends on.
    assertTrue(PriorityConfig.HIGH.weight() > PriorityConfig.NORMAL.weight());
    assertTrue(PriorityConfig.NORMAL.weight() > PriorityConfig.LOW.weight());
  }
}
