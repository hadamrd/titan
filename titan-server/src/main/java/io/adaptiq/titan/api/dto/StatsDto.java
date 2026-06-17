package io.adaptiq.titan.api.dto;

/**
 * Response body for {@code GET /api/v1/stats} — Overview-page KPI tiles (closes #346).
 *
 * <p>Three numbers, never null:
 *
 * <ul>
 *   <li>{@code buildsToday} — count of builds queued since start-of-UTC-day.
 *   <li>{@code successRate} — terminal-SUCCESS / terminal-any over the last 24h, in {@code [0.0,
 *       1.0]}. {@code 0.0} when no terminal builds in the window.
 *   <li>{@code medianDurationMs} — median of {@code finished_at - started_at} over terminal builds
 *       in the last 24h. {@code 0} when no terminal builds in the window.
 * </ul>
 */
public record StatsDto(int buildsToday, double successRate, long medianDurationMs) {}
