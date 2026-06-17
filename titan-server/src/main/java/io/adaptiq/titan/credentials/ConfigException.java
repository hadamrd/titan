package io.adaptiq.titan.credentials;

/**
 * Thrown when the credential-subsystem configuration is invalid in a way that must abort startup
 * (e.g. a dev-only KEK provider construction attempted in a non-dev profile, or a prod boot with no
 * KEK provider configured at all). Unchecked so it propagates cleanly through CDI producers and
 * aborts Quarkus bootstrap.
 *
 * <p>Distinct from {@link IllegalStateException} so log scrapers and SREs can grep for the precise
 * class name in boot logs.
 */
public class ConfigException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public ConfigException(String message) {
    super(message);
  }

  public ConfigException(String message, Throwable cause) {
    super(message, cause);
  }
}
