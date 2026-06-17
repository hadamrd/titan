package io.adaptiq.titan.job;

import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;

/**
 * Plain domain record for a Titan job — the source of truth for a pipeline definition and its
 * config flags.
 *
 * <p>Mirrors the persistent state of {@code titan.jobs}. Code in {@code titan-server} flows through
 * this record, reading and writing {@code Job}s via {@link JobService}.
 *
 * <p>Fields {@code pipelineScript} and {@code configJson} carry the PDL text and the JSON-encoded
 * trigger config respectively (see design/50). They are stored as-is — interpretation lives
 * elsewhere.
 */
public record Job(
    long id,
    String fullName,
    @Nullable String displayName,
    @Nullable String folderPath,
    String pipelineScript,
    String configJson,
    @Nullable String createdBy,
    Instant createdAt,
    Instant updatedAt,
    boolean enabled) {}
