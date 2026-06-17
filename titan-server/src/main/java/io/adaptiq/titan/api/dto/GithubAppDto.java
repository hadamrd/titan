package io.adaptiq.titan.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.store.rows.GithubAppRow;
import java.time.Instant;

/**
 * Wire representation of the registered GitHub App (#832).
 *
 * <p><strong>Hard invariant:</strong> the PEM, the webhook secret, and any of their sealed-blob
 * components are NEVER projected onto this record. {@link #from(GithubAppRow)} only copies the
 * metadata columns. Tests assert that the JSON serialisation contains none of the secret-field
 * names; CONSTITUTION §6 ban on plaintext secrets in any response or log.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GithubAppDto(
    long appId, String name, String slug, String htmlUrl, Instant createdAt, Instant updatedAt) {

  @NonNull
  public static GithubAppDto from(@NonNull GithubAppRow row) {
    return new GithubAppDto(
        row.appId, row.name, row.slug, row.htmlUrl, row.createdAt, row.updatedAt);
  }
}
