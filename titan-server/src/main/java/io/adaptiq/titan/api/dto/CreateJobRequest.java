package io.adaptiq.titan.api.dto;

import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Request body for {@code POST /api/v1/jobs} — the minimal set of fields a caller must supply to
 * create a new job (closes #507).
 *
 * <p>{@code fullName} and {@code pipelineScript} are required; everything else is optional. The
 * {@code createdBy} field of the persisted row is taken from the authenticated principal, not from
 * the body — the wire format does not carry it.
 */
public record CreateJobRequest(
    String fullName,
    @Nullable String displayName,
    @Nullable String folderPath,
    String pipelineScript,
    @Nullable String configJson,
    @Nullable Boolean enabled) {}
