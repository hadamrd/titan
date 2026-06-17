package io.adaptiq.titan.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.adaptiq.titan.credentials.Credential;
import java.time.Instant;

/**
 * API response DTO for a single credential — metadata only.
 *
 * <p><strong>Never exposes plaintext, sealed value, or AAD.</strong> The wire format is
 * deliberately a strict subset of {@link Credential}: id, kind, scope, key, timestamps. The sealed
 * blob and the AAD bind the row's identity and would help an attacker correlate rows, so they stay
 * server-side. Plaintext is recovered only by {@code CredentialsService.resolvePlaintext} at engine
 * dispatch time — never over the REST surface.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CredentialDto(
    long id, String kind, String scope, String key, Instant createdAt, Instant updatedAt) {

  /** Map from a domain record — drops {@code sealedValue} and {@code aad} on purpose. */
  public static CredentialDto from(Credential c) {
    return new CredentialDto(c.id(), c.kind(), c.scope(), c.key(), c.createdAt(), c.updatedAt());
  }
}
