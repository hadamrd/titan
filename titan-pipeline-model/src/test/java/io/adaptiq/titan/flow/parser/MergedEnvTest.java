package io.adaptiq.titan.flow.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link MergedEnv} — the pure merge function for pipeline/stage/step env layers (GH #239).
 *
 * <p>Resolution rule: pipeline env ← stage env ← step env. A key present in multiple layers takes
 * the value from the innermost (step-closest) layer. {@code null} layers contribute nothing. An
 * empty map contributes nothing but is a valid (distinct from absent) layer.
 */
class MergedEnvTest {

  // ── precedence rule ───────────────────────────────────────────────────────

  @Test
  void stepEnvOverridesStageThatOverridesPipeline() {
    Map<String, String> pipeline = Map.of("KEY", "pipeline", "ONLY_PIPELINE", "p");
    Map<String, String> stage = Map.of("KEY", "stage", "ONLY_STAGE", "s");
    Map<String, String> step = Map.of("KEY", "step", "ONLY_STEP", "t");

    Map<String, String> merged = MergedEnv.merge(pipeline, stage, step);

    assertEquals("step", merged.get("KEY"), "step value wins on KEY clash");
    assertEquals("p", merged.get("ONLY_PIPELINE"), "pipeline-only key is present");
    assertEquals("s", merged.get("ONLY_STAGE"), "stage-only key is present");
    assertEquals("t", merged.get("ONLY_STEP"), "step-only key is present");
    assertEquals(4, merged.size());
  }

  @Test
  void stepEnvOverridesPipelineWhenStageIsNull() {
    Map<String, String> pipeline = Map.of("KEY", "pipeline");
    Map<String, String> step = Map.of("KEY", "step");

    Map<String, String> merged = MergedEnv.merge(pipeline, null, step);

    assertEquals("step", merged.get("KEY"));
  }

  @Test
  void stageEnvOverridesPipelineWhenStepIsNull() {
    Map<String, String> pipeline = Map.of("KEY", "pipeline");
    Map<String, String> stage = Map.of("KEY", "stage");

    Map<String, String> merged = MergedEnv.merge(pipeline, stage, null);

    assertEquals("stage", merged.get("KEY"));
  }

  // ── null-layer cases ──────────────────────────────────────────────────────

  @Test
  void allNullLayersYieldEmptyMap() {
    assertTrue(MergedEnv.merge(null, null, null).isEmpty());
  }

  @Test
  void onlyPipelineLayerPresent() {
    Map<String, String> merged = MergedEnv.merge(Map.of("A", "1"), null, null);
    assertEquals(Map.of("A", "1"), merged);
  }

  @Test
  void onlyStageLayerPresent() {
    Map<String, String> merged = MergedEnv.merge(null, Map.of("B", "2"), null);
    assertEquals(Map.of("B", "2"), merged);
  }

  @Test
  void onlyStepLayerPresent() {
    Map<String, String> merged = MergedEnv.merge(null, null, Map.of("C", "3"));
    assertEquals(Map.of("C", "3"), merged);
  }

  // ── empty-layer cases ─────────────────────────────────────────────────────

  @Test
  void emptyPipelineLayerContributesNothing() {
    Map<String, String> merged = MergedEnv.merge(Map.of(), Map.of("K", "v"), null);
    assertEquals(Map.of("K", "v"), merged);
  }

  @Test
  void emptyStepLayerContributesNothing() {
    Map<String, String> merged = MergedEnv.merge(Map.of("K", "v"), null, Map.of());
    assertEquals(Map.of("K", "v"), merged);
  }

  // ── result is independent copy ────────────────────────────────────────────

  @Test
  void resultIsAFreshMapNotAliasedToInput() {
    Map<String, String> pipeline = Map.of("K", "v");
    Map<String, String> merged = MergedEnv.merge(pipeline, null, null);
    assertNotSame(pipeline, merged, "merge result must be a new map, not the input");
  }

  // ── single-argument convenience overload ─────────────────────────────────

  @Test
  void singleArgOverloadWithNonNullInput() {
    Map<String, String> only = Map.of("X", "y");
    Map<String, String> merged = MergedEnv.merge(only);
    assertEquals(Map.of("X", "y"), merged);
    assertNotSame(only, merged);
  }

  @Test
  void singleArgOverloadWithNullReturnsEmpty() {
    assertTrue(MergedEnv.merge((Map<String, String>) null).isEmpty());
  }
}
