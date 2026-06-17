package io.adaptiq.titan.api.dto;

/**
 * One stage's bounded summary inside {@link PipelineValidationResult} — name and step count only.
 * We deliberately do NOT echo the full {@link io.adaptiq.titan.flow.model.StageModel} so the
 * response cannot grow unbounded for a pathological pipeline (closes #745 DoS guard).
 */
public record PipelineStageSummaryDto(String name, int stepCount) {}
