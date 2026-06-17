package io.adaptiq.titan.keyprovider.infisical;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.adaptiq.titan.flow.crypto.SecretProvider;

/**
 * A {@link SecretProvider} that resolves a synthesis-time credential from <a
 * href="https://infisical.com">Infisical</a> at runtime (design/40 §3).
 *
 * <p>This is the worker-side sibling of {@link InfisicalCredentialKeyProvider}: when a Groovy
 * synthesis program calls {@code library('<git-url>@<ref>', credential: '<secret-name>')} to pull
 * in a <strong>private</strong> shared library, {@link io.adaptiq.titan.flow.parser.LibraryFetcher}
 * asks {@link SecretProvider#active()} for the named secret and authenticates the git fetch with
 * it. Drop this jar on the worker's classpath and a plain {@link java.util.ServiceLoader} discovers
 * it — no engine change, exactly the {@code CredentialKeyProvider} story.
 *
 * <p><strong>One Infisical integration, two uses.</strong> The Infisical HTTP details live in the
 * shared {@link InfisicalClient}; this provider and {@link InfisicalCredentialKeyProvider} are its
 * two callers. The key provider fetches the one secret holding the AES key; this provider fetches
 * whatever secret a {@code library()} call names.
 *
 * <p><strong>Configuration</strong> — the {@link InfisicalClient} environment variables ({@code
 * INFISICAL_TOKEN} / {@code INFISICAL_TOKEN_FILE}, {@code INFISICAL_PROJECT_ID}, {@code
 * INFISICAL_ENV}, {@code INFISICAL_SECRET_PATH}, {@code INFISICAL_API_URL}). The worker's token is
 * least-privilege — a scoped, read-only token that can read only the secrets a synthesis program is
 * expected to name (design/40 §3).
 *
 * <p>When Infisical is not configured this provider is inert: {@link #secret(String)} returns
 * {@code null}, and a {@code library(..., credential:)} call then fails synthesis closed (design/40
 * §4) — never a silent unauthenticated fetch. A configured-but-failing fetch also returns {@code
 * null} (logged), and likewise fails the synthesis closed.
 */
public final class InfisicalSecretProvider implements SecretProvider {

  /** {@code null} when Infisical is not configured — the provider then holds nothing. */
  @Nullable private final InfisicalClient client;

  /** The no-arg constructor {@link java.util.ServiceLoader} uses — reads configuration from env. */
  public InfisicalSecretProvider() {
    this(InfisicalClient.fromEnvironment());
  }

  /**
   * Test/embedding constructor — an explicit client (or {@code null} for the unconfigured case).
   */
  InfisicalSecretProvider(@Nullable InfisicalClient client) {
    this.client = client;
  }

  @Override
  @Nullable
  public String secret(@NonNull String name) {
    if (client == null) {
      return null; // not configured — a credential: library() then fails closed
    }
    return client.fetchSecret(name);
  }

  @Override
  @NonNull
  public String describe() {
    return client == null ? "infisical:unconfigured" : client.describe();
  }
}
