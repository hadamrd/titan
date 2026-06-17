package io.adaptiq.titan.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One parser-located error surfaced by {@code POST /api/v1/pipeline/validate} (closes #745).
 *
 * <p>{@code line} / {@code column} are 1-based and present only when the underlying YAML library
 * supplied a location (most {@link com.fasterxml.jackson.core.JacksonException} causes do); a
 * structural error such as "pipeline definition must declare stages" carries only a {@code
 * message}. Optional fields are omitted from JSON via {@link JsonInclude.Include#NON_NULL}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PipelineValidationError(Integer line, Integer column, String message) {

  /** Convenience for a structural error with no location. */
  public static PipelineValidationError of(String message) {
    return new PipelineValidationError(null, null, message);
  }
}
