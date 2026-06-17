package io.adaptiq.titan.build;

import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;

/**
 * Input record for {@link BuildService#update(long, BuildUpdate)}: the mutable subset of a {@code
 * titan.builds} row's lifecycle fields. Mirrors {@link
 * io.adaptiq.titan.store.BuildDao#updateStatus} — the engine writes status + timing as a build
 * progresses through {@code QUEUED → RUNNING → terminal}.
 */
public record BuildUpdate(
    String status,
    @Nullable Instant startedAt,
    @Nullable Instant finishedAt,
    @Nullable Long durationMs,
    @Nullable String errorMessage) {}
