package io.adaptiq.titan.credentials;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.adaptiq.titan.flow.crypto.CredentialKeyProvider;
import io.adaptiq.titan.flow.crypto.SecretCipher;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Production {@link CredentialKeyProvider} that reads the AES-256 credential KEK from the {@code
 * TITAN_KEK} environment variable (base64-encoded 32-byte key) — closes #874 demo-night bug #2.
 *
 * <p><strong>Why this exists alongside {@code EnvCredentialKeyProvider}.</strong> The
 * titan-pipeline-model module ships {@link io.adaptiq.titan.flow.crypto.EnvCredentialKeyProvider}
 * which reads {@code TITAN_CREDENTIAL_KEY}. The rig helm chart and the local docker-compose set
 * {@code TITAN_KEK} (see {@code rig/k3s/helm/titan/templates/titan-server.yaml} and {@code
 * rig/local/.env.example}). With no provider reading {@code TITAN_KEK}, the env value was being
 * dropped on the floor and the deployment had to fall back to {@code
 * TITAN_CREDENTIALS_DEV_AUTO_KEK=true} — which is dev-only.
 *
 * <p>This provider is wired via {@link java.util.ServiceLoader} ({@code
 * META-INF/services/io.adaptiq.titan.flow.crypto.CredentialKeyProvider}). It returns {@code null}
 * when {@code TITAN_KEK} is absent or blank, so the chain in {@link CredentialKeyProvider#active()}
 * falls through to the next provider — fail-closed contract preserved.
 *
 * <p>Key material is decoded once per invocation and never cached at this level; the upstream
 * {@code DbEnvelopeBackend} is the authority on caching the unwrapped DEKs (and never the KEK).
 */
public final class EnvKeyProvider implements CredentialKeyProvider {

  /** The environment variable holding the base64-encoded AES-256 KEK. */
  public static final String ENV_VAR = "TITAN_KEK";

  /** System-property fallback — primarily for tests, which cannot set environment variables. */
  public static final String SYSTEM_PROPERTY = "titan.kek";

  private static final Logger LOGGER = Logger.getLogger(EnvKeyProvider.class.getName());

  @Override
  @Nullable
  public byte[] credentialKey() {
    String encoded = System.getenv(ENV_VAR);
    if (encoded == null || encoded.isBlank()) {
      encoded = System.getProperty(SYSTEM_PROPERTY);
    }
    if (encoded == null || encoded.isBlank()) {
      return null;
    }
    try {
      return SecretCipher.decodeKey(encoded.trim());
    } catch (RuntimeException e) {
      // A misconfigured TITAN_KEK is a deployment bug — log and fail-closed (return null) so the
      // chain falls to the next provider rather than crashing the request.
      LOGGER.log(
          Level.SEVERE,
          "[titan][env-kek] {0} is set but is not a valid base64 AES-256 key: {1}",
          new Object[] {ENV_VAR, e.getMessage()});
      return null;
    }
  }

  @Override
  @NonNull
  public String describe() {
    return "env:" + ENV_VAR;
  }
}
