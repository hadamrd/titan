package io.adaptiq.titan.api.dto;

import java.util.List;

/**
 * Bounded summary of a successfully-parsed pipeline returned by {@code POST
 * /api/v1/pipeline/validate} (closes #745). Carries only the structural shape the UI needs to
 * reassure the author "yes, this parses, and here is what the engine sees" — never the full {@link
 * io.adaptiq.titan.flow.model.PipelineModel}, to keep the response bounded.
 */
public record PipelineModelSummary(
    List<PipelineStageSummaryDto> stages, List<PipelineTriggerSummaryDto> triggers) {}
