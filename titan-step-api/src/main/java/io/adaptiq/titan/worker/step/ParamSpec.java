package io.adaptiq.titan.worker.step;

import java.util.List;

/**
 * One parameter a step accepts — declarative metadata, part of a {@link StepDescriptor} (Chunk 32A
 * — design/32 §8).
 *
 * <p>This is Titan-native and JSON-serialisable. From a step's {@code ParamSpec}s, Titan generates
 * both the JSON Schema fragment for the pipeline linter/editor and the step palette ("pick a step,
 * fill the fields").
 *
 * @param name the argument key, as written in the pipeline YAML
 * @param type a schema type — {@code "string"}, {@code "boolean"}, {@code "number"}, {@code "list"}
 * @param required whether the step rejects a request that omits this argument
 * @param help one-line help text shown in the editor / palette
 * @param choices the allowed values for an enum-style parameter, or empty if unconstrained
 */
public record ParamSpec(
    String name, String type, boolean required, String help, List<String> choices) {

  public ParamSpec {
    choices = choices == null ? List.of() : List.copyOf(choices);
  }

  /** A required free-form parameter of the given type. */
  public static ParamSpec required(String name, String type, String help) {
    return new ParamSpec(name, type, true, help, List.of());
  }

  /** An optional free-form parameter of the given type. */
  public static ParamSpec optional(String name, String type, String help) {
    return new ParamSpec(name, type, false, help, List.of());
  }
}
