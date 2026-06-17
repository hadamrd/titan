package io.adaptiq.titan.credentials;

/**
 * Thrown by {@link SecretsBackend#update(long, CredentialUpdate)} (and the host {@code
 * CredentialsService}) when no row exists for the given id. Mirrors the host's {@code
 * JobNotFoundException}.
 */
public class CredentialNotFoundException extends RuntimeException {
  public CredentialNotFoundException(long id) {
    super("credential " + id + " not found");
  }
}
