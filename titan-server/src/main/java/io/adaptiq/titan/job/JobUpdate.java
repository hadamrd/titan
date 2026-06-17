package io.adaptiq.titan.job;

import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Input record for {@link JobService#update(long, JobUpdate)}: the mutable subset of a {@code
 * titan.jobs} row. {@code fullName} and {@code createdBy}/{@code createdAt} are immutable post
 * creation; an update bumps {@code updated_at} via the SQL default.
 */
public record JobUpdate(
    @Nullable String displayName,
    @Nullable String folderPath,
    String pipelineScript,
    String configJson,
    boolean enabled) {}
