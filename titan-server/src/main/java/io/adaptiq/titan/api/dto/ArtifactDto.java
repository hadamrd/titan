package io.adaptiq.titan.api.dto;

import static com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL;

import com.fasterxml.jackson.annotation.JsonInclude;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.adaptiq.titan.store.rows.ArtifactRow;
import java.time.Instant;

/**
 * REST projection of one row in {@code titan.artifact}.
 *
 * <p><strong>Wire-format invariant:</strong> the internal storage backend identifier ({@code
 * storage}, {@code storage_ref}), the executor node id and the {@code kind} discriminator are never
 * emitted. A client only sees: the synthetic id, the user-supplied file name, the size, the SHA-256
 * digest, the upload timestamp, and a backend-relative download URL.
 *
 * <p>{@link #contentType} is reserved for a future schema extension (issue #298-style follow-up);
 * the current {@code titan.artifact} table has no such column, so it is always {@code null} today
 * and is stripped from the JSON wire format via {@link JsonInclude#NON_NULL}.
 *
 * <p>{@link #downloadUrl} points at {@code /api/v1/artifacts/{id}/download} — a follow-up endpoint
 * that is not yet implemented; the URL is exposed now so the UI can wire its anchors against the
 * stable shape.
 */
@JsonInclude(NON_NULL)
public record ArtifactDto(
    long id,
    String name,
    long sizeBytes,
    String sha256,
    @Nullable String contentType,
    Instant uploadedAt,
    String downloadUrl) {

  public static ArtifactDto from(ArtifactRow row) {
    return new ArtifactDto(
        row.id,
        row.name,
        row.sizeBytes,
        row.sha256,
        null,
        row.createdAt,
        "/api/v1/artifacts/" + row.id + "/download");
  }
}
