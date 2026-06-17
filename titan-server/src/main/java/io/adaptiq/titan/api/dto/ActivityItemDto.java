package io.adaptiq.titan.api.dto;

import java.time.Instant;

/**
 * One item on the Overview-page activity feed ({@code GET /api/v1/activity}, closes #304).
 *
 * <p>v1 derives feed items entirely from terminal-state builds — there is no separate events table
 * yet. {@code type} is the literal string {@code "build.terminal"}: kept on the wire so the UI can
 * already switch on a discriminator when future event sources (worker connect/disconnect, gate
 * decisions, etc.) land without changing the JSON shape.
 *
 * <ul>
 *   <li>{@code id} — stable per-row id, e.g. {@code "build-123"}.
 *   <li>{@code type} — {@code "build.terminal"} in v1.
 *   <li>{@code ts} — ISO-8601 instant the build finished (drives ordering + the cursor).
 *   <li>{@code jobName} — job's {@code full_name} (e.g. {@code "release/main"}).
 *   <li>{@code buildId} — build row id (numeric) for UI deep-links.
 *   <li>{@code status} — terminal status: {@code SUCCESS / FAILED / ABORTED / UNSTABLE}.
 *   <li>{@code durationMs} — {@code finished_at - started_at} in ms; {@code 0} when unknown.
 * </ul>
 */
public record ActivityItemDto(
    String id,
    String type,
    Instant ts,
    String jobName,
    long buildId,
    String status,
    long durationMs) {}
