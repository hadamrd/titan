package io.adaptiq.titan.worker.step;

import java.util.List;

/**
 * The declarative description of a step type — what a {@link StepHandler} advertises about itself
 * (Chunk 32A — design/32 §3.1/§8; the grammar fragment of design/42 §4.6).
 *
 * <p>One metadata source, several consumers (built in Chunk 32F): the JSON Schema that drives
 * editor autocomplete + validation, the step palette / snippet generator, and the worker-side
 * argument validator (design/42 §4.5). A handler that fills this in honestly gets a good authoring
 * UX for free; the engine never hand-writes per-step UI.
 *
 * <p><strong>The descriptor <em>is</em> the step's grammar (design/42 §4.6).</strong> A step ships
 * its argument grammar declaratively — as data on this record — rather than as imperative parse
 * code, because the parser ({@code TitanYamlParser}) runs on the controller, which holds no step
 * jars. The generic parser folds a bare scalar step value into a conventional {@code value}
 * argument with zero per-step knowledge; the {@link #scalarShorthandKey()} below tells the
 * <em>worker</em> (where the jars live — design/42 §4.5 / Chunk 42-V) which named argument that
 * generic {@code value} fold is the shorthand for.
 *
 * @param descriptorId the step's id — must equal {@link StepHandler#descriptorId()}
 * @param displayName a human label for the palette
 * @param help one-paragraph description of what the step does
 * @param parameters the arguments the step accepts
 * @param scalarShorthandKey the parameter name a bare scalar step value (e.g. {@code sh: echo hi})
 *     folds into — {@code "script"} for {@code sh}, {@code "url"} for {@code git}, and so on; or
 *     {@code null} if the step accepts no scalar shorthand. The parser is intentionally unaware of
 *     this field: it always writes a bare scalar into the conventional {@code value} key, and the
 *     worker resolves {@code value} to this named key when present (design/42 §4.6).
 */
public record StepDescriptor(
    String descriptorId,
    String displayName,
    String help,
    List<ParamSpec> parameters,
    String scalarShorthandKey) {

  public StepDescriptor {
    parameters = parameters == null ? List.of() : List.copyOf(parameters);
  }

  /**
   * Backward-compatible constructor — a descriptor with no scalar shorthand (design/42 §4.6: the
   * {@code scalarShorthandKey} field is additive and optional).
   */
  public StepDescriptor(
      String descriptorId, String displayName, String help, List<ParamSpec> parameters) {
    this(descriptorId, displayName, help, parameters, null);
  }
}
