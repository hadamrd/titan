package io.adaptiq.titan.flow.crypto;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * The built-in {@link CredentialKeyProvider} — reads the AES-256 credential key from the {@code
 * TITAN_CREDENTIAL_KEY} environment variable (a base64-encoded 32-byte key), with the {@code
 * titan.credentialKey} system property as a fallback for tests.
 *
 * <p>This is the default for a single-team deployment: the key is provisioned to the controller and
 * every worker out-of-band — via Infisical, the project's secret manager — and rotated there. The
 * key lives in process configuration, never in the database; ciphertext and key are separate
 * stores, which is what makes the at-rest encryption meaningful.
 *
 * <p>Larger deployments replace this with a KMS-backed {@link CredentialKeyProvider} (see that
 * interface) — no engine change, just a jar on the classpath.
 */
public final class EnvCredentialKeyProvider implements CredentialKeyProvider {

  /** The environment variable holding the base64-encoded AES-256 key. */
  public static final String ENV_VAR = "TITAN_CREDENTIAL_KEY";

  /** System-property fallback — primarily for tests, which cannot set environment variables. */
  public static final String SYSTEM_PROPERTY = "titan.credentialKey";

  @Override
  @Nullable
  public byte[] credentialKey() {
    String encoded = System.getenv(ENV_VAR);
    if (encoded == null || encoded.isBlank()) {
      encoded = System.getProperty(SYSTEM_PROPERTY);
    }
    if (encoded == null || encoded.isBlank()) {
      return null; // not configured — the caller fails closed (design/39 §5)
    }
    return SecretCipher.decodeKey(encoded);
  }

  @Override
  @NonNull
  public String describe() {
    return "env:" + ENV_VAR;
  }
}
