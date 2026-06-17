package io.adaptiq.titan.flow.orch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.adaptiq.titan.flow.model.PipelineModel;
import io.adaptiq.titan.flow.model.StageModel;
import io.adaptiq.titan.flow.model.StepModel;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.BuildRow;
import io.adaptiq.titan.store.rows.FlowNodeRow;
import java.util.Map;

/**
 * Builds the {@code ${{ … }}} resolution context (design/29 §6) from a build's outputs. Extracted
 * from {@code TitanOrchestrator} (#357).
 */
public final class OutputContext {

  private static final ObjectMapper JSON = new ObjectMapper();

  private final TitanStores daos;
  private final long buildId;

  public OutputContext(@NonNull TitanStores daos, long buildId) {
    this.daos = daos;
    this.buildId = buildId;
  }

  /**
   * Build the build-wide portion of the {@code ${{ … }}} resolution context (design/29 §6): {@code
   * params} (build inputs), {@code steps} (each stage name → its merged step outputs), {@code
   * pipeline.outputs} (the build-global merge). A step publishes outputs by leaving an {@code
   * "outputs"} object in the {@code result_json} that completes its flow node.
   *
   * <p>The {@code env} namespace is intentionally <em>not</em> bound here: it is the only per-step
   * scope (pipeline ← stage ← step + engine-implicit), so {@link StepDispatcher} layers the merged
   * effective env onto this context per dispatch, just before resolving that step's templates
   * (#1212). That keeps {@code ${{ env.X }}} resolving to exactly the env the step runs with.
   */
  @NonNull
  public Map<String, Object> build(
      @NonNull PipelineModel model, @NonNull Map<String, FlowNodeRow> nodes) {
    Map<String, Object> steps = new java.util.HashMap<>();
    Map<String, Object> pipelineOutputs = new java.util.LinkedHashMap<>();
    for (StageModel stage : model.getStages()) {
      Map<String, Object> merged = new java.util.LinkedHashMap<>();
      for (StepModel step : stage.getSteps()) {
        FlowNodeRow node = nodes.get(step.getId());
        if (node != null) {
          merged.putAll(outputsOf(node.resultJson));
        }
      }
      steps.put(stage.getName(), Map.of("outputs", merged));
      pipelineOutputs.putAll(merged);
    }
    BuildRow build = daos.builds().findById(buildId).orElse(null);
    Map<String, Object> context = new java.util.HashMap<>();
    context.put("params", parseParams(build == null ? null : build.parametersJson));
    context.put("steps", steps);
    context.put("pipeline", Map.of("outputs", pipelineOutputs));
    return context;
  }

  /** Extract the {@code outputs} object a completed step left in its {@code result_json}. */
  @NonNull
  public static Map<String, Object> outputsOf(@Nullable String resultJson) {
    if (resultJson == null || resultJson.isBlank()) {
      return Map.of();
    }
    try {
      JsonNode outputs = JSON.readTree(resultJson).get("outputs");
      if (outputs != null && outputs.isObject()) {
        return JSON.convertValue(outputs, new com.fasterxml.jackson.core.type.TypeReference<>() {});
      }
    } catch (Exception ignored) {
      // a step result without a well-formed outputs object simply publishes nothing
    }
    return Map.of();
  }

  @NonNull
  public static Map<String, Object> parseParams(@Nullable String parametersJson) {
    if (parametersJson == null || parametersJson.isBlank()) {
      return Map.of();
    }
    try {
      Map<String, Object> parsed =
          JSON.readValue(parametersJson, new com.fasterxml.jackson.core.type.TypeReference<>() {});
      return parsed != null ? parsed : Map.of();
    } catch (Exception e) {
      return Map.of();
    }
  }
}
