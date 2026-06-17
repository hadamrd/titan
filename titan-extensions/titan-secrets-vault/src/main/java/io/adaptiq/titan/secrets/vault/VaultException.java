package io.adaptiq.titan.secrets.vault;

/**
 * Runtime exception for Vault wire failures. Distinct type so callers (the orchestrator's
 * credential-resolution path) can grep "Vault" in logs without coupling to {@code IOException}.
 *
 * <p>The exception message MUST NOT contain a token or a secret value. Construct only with
 * scope/path/HTTP status info.
 */
public class VaultException extends RuntimeException {

  public VaultException(String message) {
    super(message);
  }

  public VaultException(String message, Throwable cause) {
    super(message, cause);
  }
}
