package io.adaptiq.titan.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Response of {@code POST /api/v1/pipeline/validate} (closes #745).
 *
 * <p>Always 200 on a parseable request body — the verdict is in {@code valid}. The discriminated
 * union: when {@code valid == true}, {@code summary} is non-null and {@code errors} is empty; when
 * {@code valid == false}, {@code errors} is non-empty and {@code summary} is null.
 *
 * <p>{@code summary} is deliberately bounded (stage name + step count, trigger type + expression) —
 * the parsed model can be arbitrarily large; the validate endpoint MUST NOT echo it whole or it
 * becomes a self-DoS surface.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PipelineValidationResult(
    boolean valid, List<PipelineValidationError> errors, PipelineModelSummary summary) {

  public static PipelineValidationResult ok(PipelineModelSummary summary) {
    return new PipelineValidationResult(true, List.of(), summary);
  }

  public static PipelineValidationResult fail(List<PipelineValidationError> errors) {
    return new PipelineValidationResult(false, errors, null);
  }
}
