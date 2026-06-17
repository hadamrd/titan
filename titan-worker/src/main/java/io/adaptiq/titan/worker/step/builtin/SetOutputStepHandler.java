package io.adaptiq.titan.worker.step.builtin;

import io.adaptiq.titan.worker.step.ParamSpec;
import io.adaptiq.titan.worker.step.StepDescriptor;
import io.adaptiq.titan.worker.step.StepHandler;
import io.adaptiq.titan.worker.step.StepRequest;
import io.adaptiq.titan.worker.step.StepResult;
import java.util.List;
import java.util.Map;

/**
 * The built-in {@code setOutput} step — publishes named key/value state into the pipeline.
 *
 * <p>A declarative pipeline's only other way to set step outputs is a Groovy {@code script} body
 * calling the {@code setOutput(...)} binding — a whole GroovyClassLoader + shared-library load to
 * do an assignment. This step is that, declaratively, in two forms:
 *
 * <pre>{@code
 * # one value
 * - setOutput: { name: deployEnv, value: production }
 *
 * # several at once — native key/value state
 * - setOutput:
 *     values:
 *       deployEnv: production
 *       version: "1.2.3"
 *       region: eu-west-1
 * }</pre>
 *
 * <p>A downstream stage reads any of them with {@code ${{ steps['…'].outputs.<name> }}}. Values are
 * ordinary arguments, so a {@code ${{ … }}} expression in one is resolved at dispatch — which makes
 * {@code setOutput} a clean way to compute a value once and thread it through the DAG.
 *
 * <p>Both {@code name}/{@code value} and {@code values} are declared parameters so the engine's
 * argument validator never mistakes a deliberate key for a typo. Idempotent by construction
 * (design/30): re-running publishes the same state.
 */
public final class SetOutputStepHandler implements StepHandler {

  @Override
  public String descriptorId() {
    return "setOutput";
  }

  @Override
  public StepDescriptor descriptor() {
    return new StepDescriptor(
        "setOutput",
        "Set output",
        "Publishes named key/value state into the pipeline, readable downstream as "
            + "${{ steps['…'].outputs.<name> }}. Use name/value for one, or values "
            + "for a map of several.",
        List.of(
            ParamSpec.optional("name", "string", "Output key (single-value form)."),
            ParamSpec.optional("value", "string", "Output value (single-value form)."),
            ParamSpec.optional(
                "values", "object", "A map of output key/value pairs (multi-value form).")));
  }

  @Override
  public StepResult execute(StepRequest request) throws Exception {
    // Multi-value form: `values:` is a map — publish every entry.
    Object valuesArg = request.arguments().get("values");
    if (valuesArg instanceof Map<?, ?> values) {
      if (values.isEmpty()) {
        return StepResult.failed("setOutput: 'values' is empty");
      }
      for (Map.Entry<?, ?> entry : values.entrySet()) {
        String key = entry.getKey() == null ? "" : String.valueOf(entry.getKey());
        if (key.isBlank()) {
          return StepResult.failed("setOutput: a 'values' key is blank");
        }
        request.outputs().put(key, entry.getValue());
      }
      // Log the keys only — a value may be a secret resolved from a ${{ … }} reference.
      request
          .log()
          .system("setOutput: published " + values.size() + " output(s): " + values.keySet());
      return StepResult.success();
    }
    if (valuesArg != null) {
      return StepResult.failed("setOutput: 'values' must be a map of key/value pairs");
    }

    // Single-value form: name + value.
    String name = request.argString("name");
    if (name != null && !name.isBlank()) {
      // Read the raw argument, not argString — a boolean / number keeps its type.
      Object value = request.arguments().get("value");
      if (value == null) {
        return StepResult.failed("setOutput step has no 'value' for '" + name + "'");
      }
      request.outputs().put(name, value);
      request.log().system("setOutput: published '" + name + "'");
      return StepResult.success();
    }
    return StepResult.failed("setOutput step needs either 'values' (a map) or 'name' + 'value'");
  }
}
