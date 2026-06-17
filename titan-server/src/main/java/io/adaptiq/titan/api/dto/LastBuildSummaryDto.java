package io.adaptiq.titan.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

/**
 * Compact wire summary of a job's most recent build — embedded in {@link JobDto#lastBuild()} so the
 * {@code /jobs} list can render a per-row status pill + duration + relative finish time at a glance
 * (issue #529).
 *
 * <p>This is intentionally a strict subset of {@link BuildDto}: only the fields the SRE needs on a
 * fleet-health glance. Heavier fields (error message, failure summary, parameters JSON) stay on the
 * per-build detail endpoint.
 *
 * <p>{@code finishedAt} and {@code durationMs} are nullable — a {@code RUNNING} or {@code QUEUED}
 * build has neither yet. {@code status} is always present (the row exists).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LastBuildSummaryDto(
    long id, int buildNumber, String status, Long durationMs, Instant finishedAt) {}
