package io.adaptiq.titan.api.dto;

/**
 * Response row for {@code GET /api/v1/jobs/top-failing} (closes #769).
 *
 * <p>One row per job ranked by failure rate (descending) over the requested time window. Only jobs
 * with at least 3 terminal builds in the window are returned — the goal is to surface flaky / sick
 * pipelines, not penalise jobs that ran once and failed.
 *
 * <ul>
 *   <li>{@code jobId} — internal job id (path-segment for {@code /jobs/{id}}).
 *   <li>{@code jobName} — display name when set, otherwise full name. Never blank.
 *   <li>{@code totalBuilds} — terminal builds counted in the window.
 *   <li>{@code failedBuilds} — subset of {@code totalBuilds} with {@code status='FAILED'}.
 *   <li>{@code failureRate} — {@code failedBuilds / totalBuilds} ∈ {@code [0.0, 1.0]}.
 *   <li>{@code lastFailedBuildId} — optional, may be {@code null} (defensively: a row can still
 *       qualify even if the latest FAILED build was pruned). UI links to {@code /builds/{id}}.
 * </ul>
 */
public record TopFailingJobDto(
    long jobId,
    String jobName,
    long totalBuilds,
    long failedBuilds,
    double failureRate,
    Long lastFailedBuildId) {}
