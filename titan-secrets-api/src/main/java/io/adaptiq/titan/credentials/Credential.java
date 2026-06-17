package io.adaptiq.titan.credentials;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;

/**
 * Plain domain record for a Titan credential — metadata only.
 *
 * <p>The sealed payload and the AAD that binds the GCM tag are intentionally absent: keeping them
 * off the domain type makes it structurally impossible to ship them out of an HTTP response (the
 * DTO mappers cannot serialise a field that doesn't exist). Plaintext is recovered through the
 * narrow {@code CredentialsService.resolvePlaintext(scope, key)} path, which goes straight from the
 * row to the unsealed string without ever staging the sealed blob in a domain value.
 */
public record Credential(
    long id,
    @NonNull String kind,
    @NonNull String scope,
    @NonNull String key,
    @NonNull Instant createdAt,
    @NonNull Instant updatedAt) {

  /** Credential kind: a username + password pair. */
  public static final String KIND_USERNAME_PASSWORD = "USERNAME_PASSWORD";

  /** Credential kind: an SSH private key (with optional passphrase). */
  public static final String KIND_SSH_KEY = "SSH_KEY";

  /** Credential kind: an opaque string (a token, an API key, etc.). */
  public static final String KIND_STRING = "STRING";

  /** Credential kind: an opaque byte blob (a file). Stored base64-encoded inside the seal. */
  public static final String KIND_FILE = "FILE";
}
