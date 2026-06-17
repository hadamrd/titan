package io.adaptiq.titan.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.adaptiq.titan.flow.model.ParameterModel;
import java.util.List;

/**
 * Wire shape for a single declared pipeline parameter (closes #774). Surfaced by {@code GET
 * /api/v1/jobs/{jobId}/parameters} so the UI can render a typed input per declared parameter in the
 * trigger-with-params modal.
 *
 * <p>Type discriminator is the {@code type} string from {@link ParameterModel} — one of {@code
 * "string"}, {@code "boolean"}, {@code "number"}, {@code "choice"}. The UI maps {@code boolean} →
 * checkbox, {@code choice} → select-of-{@link #choices}, everything else → text input.
 *
 * <p>{@code defaultValue} is the raw value from the YAML default (preserved primitive type — string
 * stays string, boolean stays boolean). The UI pre-fills the input with it.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PipelineParameterDto(
    String name,
    String type,
    Object defaultValue,
    String description,
    boolean required,
    List<String> choices) {

  public static PipelineParameterDto from(ParameterModel p) {
    return new PipelineParameterDto(
        p.getName(),
        p.getType(),
        p.getDefaultValue(),
        p.getDescription(),
        p.isRequired(),
        // Always emit the list (empty for non-choice types) — the UI key on type, not on null.
        p.getChoices() == null ? List.of() : List.copyOf(p.getChoices()));
  }
}
