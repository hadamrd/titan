package io.adaptiq.titan.keyprovider.infisical;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.adaptiq.titan.flow.crypto.CredentialKeyProvider;
import io.adaptiq.titan.flow.crypto.SecretCipher;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A {@link CredentialKeyProvider} that fetches the credential-sealing AES-256 key from <a
 * href="https://infisical.com">Infisical</a> at runtime (design/39 §3.1).
 *
 * <p><strong>This module is the proof that the SPI is real.</strong> The credential design names a
 * pluggable key source — the built-in provider reads an environment variable, and "a KMS-backed
 * provider is a drop-in jar with a {@code META-INF/services} entry, no engine change." This is that
 * jar. Put it on the controller's and the worker's classpath and {@link
 * CredentialKeyProvider#active()} — a plain {@link java.util.ServiceLoader} — discovers it; the
 * engine code is untouched.
 *
 * <p><strong>One Infisical integration, two uses.</strong> The Infisical HTTP details live in the
 * shared {@link InfisicalClient}; this provider is now one of its two callers — the other is {@link
 * InfisicalSecretProvider}, which fetches synthesis-time {@code library()} credentials (design/40
 * §3). This provider simply names the one secret holding the key and decodes it.
 *
 * <p><strong>Configuration</strong> — the {@link InfisicalClient} environment variables, plus one
 * provider-specific name:
 *
 * <table>
 *   <caption>Infisical key-provider configuration</caption>
 *   <tr><td>{@code INFISICAL_TOKEN} / {@code INFISICAL_TOKEN_FILE}</td><td>the API token</td></tr>
 *   <tr><td>{@code INFISICAL_PROJECT_ID}</td><td>the Infisical project (workspace) id</td></tr>
 *   <tr><td>{@code INFISICAL_ENV}</td><td>environment slug — default {@code prod}</td></tr>
 *   <tr><td>{@code INFISICAL_SECRET_PATH}</td><td>secret path — default {@code /}</td></tr>
 *   <tr><td>{@code INFISICAL_API_URL}</td><td>API base — default {@code https://app.infisical.com}</td></tr>
 *   <tr><td>{@code INFISICAL_KEY_SECRET_NAME}</td><td>the secret holding the base64 key — default
 *       {@code TITAN_CREDENTIAL_KEY}</td></tr>
 * </table>
 *
 * <p>The key is fetched once and cached for the JVM's lifetime — a key rotation is picked up on the
 * next restart, and the controller and workers are restarted together when the key rotates. If the
 * provider is not configured (no token, no project), {@link #credentialKey()} returns {@code null}
 * so {@link CredentialKeyProvider#active()} falls through to the built-in environment provider; a
 * configured-but-failing fetch also returns {@code null} (logged) so a step fails closed rather
 * than the orchestrator being destabilised by an exception.
 */
public final class InfisicalCredentialKeyProvider implements CredentialKeyProvider {

  private static final Logger LOGGER =
      Logger.getLogger(InfisicalCredentialKeyProvider.class.getName());

  static final String ENV_SECRET_NAME = "INFISICAL_KEY_SECRET_NAME";

  private static final String DEFAULT_SECRET_NAME = "TITAN_CREDENTIAL_KEY";

  /** {@code null} when Infisical is not configured — the provider then stays inert. */
  @Nullable private final InfisicalClient client;

  @NonNull private final String secretName;

  private volatile byte[] cachedKey;

  /** The no-arg constructor {@link java.util.ServiceLoader} uses — reads configuration from env. */
  public InfisicalCredentialKeyProvider() {
    this(Settings.fromEnvironment());
  }

  /** Test/embedding constructor — explicit settings, no environment read. */
  InfisicalCredentialKeyProvider(@Nullable Settings settings) {
    this.client = settings == null ? null : InfisicalClient.of(settings.connection());
    this.secretName = settings == null ? DEFAULT_SECRET_NAME : settings.secretName();
  }

  @Override
  @Nullable
  public byte[] credentialKey() {
    if (client == null) {
      return null; // not configured — active() falls through to the env provider
    }
    byte[] key = cachedKey;
    if (key != null) {
      return key.clone();
    }
    synchronized (this) {
      if (cachedKey == null) {
        cachedKey = fetchKey();
      }
      return cachedKey == null ? null : cachedKey.clone();
    }
  }

  @Override
  @NonNull
  public String describe() {
    if (client == null) {
      return "infisical:unconfigured";
    }
    // Project / env / path (from the client) plus the key secret name — never the token.
    return client.describe() + "#" + secretName;
  }

  /** Fetch the key from Infisical; returns {@code null} (logged) on any failure — fail closed. */
  @Nullable
  private byte[] fetchKey() {
    // Invariant: only called from credentialKey() after the client != null guard. Asserting it
    // here makes the contract explicit for SpotBugs (the @Nullable field is otherwise flagged).
    InfisicalClient c = Objects.requireNonNull(client, "fetchKey() requires a configured client");
    String value = c.fetchSecret(secretName);
    if (value == null || value.isBlank()) {
      return null;
    }
    byte[] key = SecretCipher.decodeKey(value);
    LOGGER.log(Level.INFO, "[titan] credential key loaded from Infisical ({0})", describe());
    return key;
  }

  /**
   * Resolved key-provider configuration: the shared {@link InfisicalClient.Settings} connection
   * plus the name of the secret holding the key. {@link #fromEnvironment()} returns {@code null}
   * when Infisical is not configured — the signal {@link #credentialKey()} uses to stay inert.
   *
   * <p>The constructor keeps its historical six-argument shape ({@code token, projectId,
   * environment, secretPath, apiUrl, secretName}) — it is what the provider's tests use, and the
   * generalisation onto {@link InfisicalClient} must not break a caller.
   */
  record Settings(
      @NonNull String token,
      @NonNull String projectId,
      @NonNull String environment,
      @NonNull String secretPath,
      @NonNull String apiUrl,
      @NonNull String secretName) {

    /** The shared-client connection settings carved out of this record. */
    @NonNull
    InfisicalClient.Settings connection() {
      return new InfisicalClient.Settings(token, projectId, environment, secretPath, apiUrl);
    }

    @Nullable
    static Settings fromEnvironment() {
      InfisicalClient.Settings connection = InfisicalClient.Settings.fromEnvironment();
      if (connection == null) {
        return null; // not configured
      }
      String secretName = System.getenv(ENV_SECRET_NAME);
      if (secretName == null || secretName.isBlank()) {
        secretName = DEFAULT_SECRET_NAME;
      }
      return new Settings(
          connection.token(),
          connection.projectId(),
          connection.environment(),
          connection.secretPath(),
          connection.apiUrl(),
          secretName.strip());
    }
  }
}
