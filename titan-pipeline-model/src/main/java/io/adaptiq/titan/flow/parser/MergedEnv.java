package io.adaptiq.titan.flow.parser;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Resolves the effective environment for one step by merging the three env layers declared in a
 * Titan pipeline (GH #239).
 *
 * <p>Resolution rule: <strong>pipeline env ← stage env ← step env</strong>. A key present in
 * multiple layers takes the value from the innermost (step-closest) layer. Any layer may be {@code
 * null} (absent at that level in the YAML) — a {@code null} layer contributes nothing. An empty map
 * contributes nothing but is distinct from {@code null} (it means {@code env: {}} was explicitly
 * declared).
 *
 * <p>This is a pure function: no I/O, no state, safe to call from any thread. The returned map is a
 * fresh, mutable {@link LinkedHashMap} — callers may safely modify it without affecting the input
 * layers.
 *
 * <p>Runtime hookup (actually passing the merged env to the worker subprocess) is a follow-up
 * engine task tracked after GH #239 lands.
 */
public final class MergedEnv {

  private MergedEnv() {}

  /**
   * Merge the three env layers into one effective map for a step.
   *
   * @param pipelineEnv the pipeline-level {@code env:} map, or {@code null} if not declared
   * @param stageEnv the stage-level {@code env:} map, or {@code null} if not declared
   * @param stepEnv the step-level {@code env:} map, or {@code null} if not declared
   * @return the merged map; may be empty but is never {@code null}
   */
  @NonNull
  public static Map<String, String> merge(
      @Nullable Map<String, String> pipelineEnv,
      @Nullable Map<String, String> stageEnv,
      @Nullable Map<String, String> stepEnv) {
    Map<String, String> result = new LinkedHashMap<>();
    if (pipelineEnv != null) {
      result.putAll(pipelineEnv);
    }
    if (stageEnv != null) {
      result.putAll(stageEnv); // stage overrides pipeline
    }
    if (stepEnv != null) {
      result.putAll(stepEnv); // step overrides stage (and pipeline)
    }
    return result;
  }

  /**
   * Convenience overload for the common single-level case — returns the map itself when only one
   * layer is non-null, or an empty map when all are null. Equivalent to calling the three-argument
   * overload, but avoids constructing a new map when there is nothing to merge.
   */
  @NonNull
  public static Map<String, String> merge(@Nullable Map<String, String> only) {
    return only != null ? new LinkedHashMap<>(only) : Collections.emptyMap();
  }
}
