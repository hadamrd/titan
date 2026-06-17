package io.adaptiq.titan.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Discriminated-union trigger summary inside {@link PipelineValidationResult}.
 *
 * <p>{@code type} is the discriminator — {@code "cron"} or {@code "github"}. {@code expression} is
 * the cron string for cron triggers, omitted for github triggers (the UI can read structured
 * github-trigger detail by re-fetching the job if needed).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PipelineTriggerSummaryDto(String type, String expression) {

  public static PipelineTriggerSummaryDto cron(String expression) {
    return new PipelineTriggerSummaryDto("cron", expression);
  }

  public static PipelineTriggerSummaryDto github() {
    return new PipelineTriggerSummaryDto("github", null);
  }
}
