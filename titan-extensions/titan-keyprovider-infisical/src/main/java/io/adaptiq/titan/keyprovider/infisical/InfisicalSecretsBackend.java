package io.adaptiq.titan.keyprovider.infisical;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.credentials.Credential;
import io.adaptiq.titan.credentials.CredentialUpdate;
import io.adaptiq.titan.credentials.NewCredentialRequest;
import io.adaptiq.titan.credentials.SecretsBackend;
import io.adaptiq.titan.flow.crypto.SecretProvider;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@link SecretsBackend} that sources pipeline credentials from <a
 * href="https://infisical.com">Infisical</a>, so a pipeline {@code credentials:} binding can
 * resolve a secret that lives in Infisical (issue #1227).
 *
 * <p>This is the thin adapter the SPI needs: the Infisical HTTP details already live in {@link
 * InfisicalSecretProvider} / {@link InfisicalClient} (the synthesis-time {@code library(...,
 * credential:)} path, design/40 §3). That provider is a {@link SecretProvider} — a typed,
 * name→value seam with a deterministic fake for tests — and this backend wraps it so the engine's
 * credential-resolution path ({@code CredentialResolver → CredentialsService.resolvePlaintext})
 * reaches Infisical exactly the way it reaches {@code db-envelope} or {@code vault}.
 *
 * <h2>Read-only by design</h2>
 *
 * <p>Infisical owns the secret lifecycle; Titan only <em>reads</em>. Listing metadata ({@link
 * #listAll} / {@link #listByScope}) returns empty and mutation ({@link #create} / {@link #update})
 * throws {@link UnsupportedOperationException} — manage the secret in Infisical, then reference it
 * from a pipeline by name. {@link #delete} is a no-op to honour the SPI's idempotent-delete
 * contract. This mirrors {@code VaultSecretsBackend}'s "upstream owns it" posture.
 *
 * <h2>id ↔ (scope, key) mapping</h2>
 *
 * <p>Infisical has no integer ids, so the SPI's {@code long id} surface is satisfied with a stable
 * 63-bit hash of ({@code scope}, {@code key}) (SHA-256, first 8 bytes, sign bit masked) — the same
 * scheme {@code VaultSecretsBackend} uses. {@link #findById} returns empty (no reverse index is
 * needed for the credential-binding path, which addresses by scope/key).
 *
 * <h2>Resolution</h2>
 *
 * <p>{@link #resolvePlaintext} maps the binding's {@code key} to the Infisical <em>secret name</em>
 * and asks the wrapped {@link SecretProvider}. A {@code null} / blank result (secret absent, or
 * Infisical not configured — e.g. a missing token) becomes {@link Optional#empty()}, which the
 * {@code CredentialResolver} turns into a structured {@code CredentialResolutionException} naming
 * only the credential id — never the value, never a stack trace carrying the secret.
 *
 * <h2>Discovery</h2>
 *
 * <p>{@code META-INF/services/io.adaptiq.titan.credentials.SecretsBackend} ships this class as a
 * {@link java.util.ServiceLoader} entry. {@link #name()} returns {@value #NAME}; that is the
 * literal value an operator sets {@code TITAN_SECRETS_BACKEND} to.
 */
public final class InfisicalSecretsBackend implements SecretsBackend {

  private static final Logger LOGGER = Logger.getLogger(InfisicalSecretsBackend.class.getName());

  /** Identifier matched against the {@code TITAN_SECRETS_BACKEND} env var. */
  public static final String NAME = "infisical";

  /** Discriminator value for the {@code type:} field in the backend config. */
  public static final String TYPE = "infisical";

  /**
   * Synthetic-metadata timestamp. Infisical does not expose create/update times to the resolution
   * path, and a synthetic credential carries no real history, so {@link Instant#EPOCH} honestly
   * signals "not a Titan-owned row".
   */
  private static final Instant SYNTHETIC = Instant.EPOCH;

  @NonNull private final SecretProvider provider;

  /** ServiceLoader ctor — wraps the env-configured {@link InfisicalSecretProvider}. */
  public InfisicalSecretsBackend() {
    this(new InfisicalSecretProvider());
  }

  /**
   * Test / DI ctor — an explicit {@link SecretProvider} (a fake in tests, the real Infisical
   * provider in production). The provider is the typed external boundary; injecting a fake keeps
   * this backend's logic unit-testable without a live Infisical.
   */
  public InfisicalSecretsBackend(@NonNull SecretProvider provider) {
    this.provider = provider;
    LOGGER.log(Level.INFO, "[titan] InfisicalSecretsBackend bound to {0}", provider.describe());
  }

  @Override
  @NonNull
  public String name() {
    return NAME;
  }

  // ── reads (metadata) ───────────────────────────────────────────────────────

  @Override
  @NonNull
  public Optional<Credential> findById(long id) {
    // Infisical addresses by name, not by integer id; the binding path uses
    // findByScopeAndKey. No reverse index is maintained.
    return Optional.empty();
  }

  /**
   * Reports a synthetic {@code STRING} credential for any ({@code scope}, {@code key}) so the
   * engine's {@code CredentialResolver} can proceed to {@link #resolvePlaintext}. Existence is
   * gated there (a missing Infisical secret resolves empty and fails the binding closed) — not
   * here, to avoid a redundant remote round-trip on every lookup. Infisical secrets are opaque
   * strings, so {@code STRING} is the only meaningful kind.
   */
  @Override
  @NonNull
  public Optional<Credential> findByScopeAndKey(@NonNull String scope, @NonNull String key) {
    return Optional.of(
        new Credential(
            stableId(scope, key), Credential.KIND_STRING, scope, key, SYNTHETIC, SYNTHETIC));
  }

  @Override
  @NonNull
  public List<Credential> listAll() {
    // Enumerating an Infisical project is upstream-owned and out of scope for the
    // credential-binding path (it addresses by name). A UI listing follow-up can add it.
    return List.of();
  }

  @Override
  @NonNull
  public List<Credential> listByScope(@NonNull String scope) {
    return List.of();
  }

  // ── writes (read-only backend) ──────────────────────────────────────────────

  @Override
  @NonNull
  public Credential create(@NonNull NewCredentialRequest request) {
    throw new UnsupportedOperationException(
        "the infisical secrets backend is read-only — create the secret in Infisical, then"
            + " reference it from a pipeline by its (scope, key) name");
  }

  @Override
  @NonNull
  public Credential update(long id, @NonNull CredentialUpdate update) {
    throw new UnsupportedOperationException(
        "the infisical secrets backend is read-only — update the secret in Infisical");
  }

  @Override
  public void delete(long id) {
    // SPI contract: delete is idempotent. Infisical owns the lifecycle, so this is a no-op,
    // never an error.
    LOGGER.log(
        Level.FINE,
        "[titan] infisical delete: id={0} is a no-op (Infisical owns the lifecycle)",
        Long.toUnsignedString(id));
  }

  // ── resolve ────────────────────────────────────────────────────────────────

  /**
   * Resolve the Infisical secret named by {@code key}. {@code scope} is Titan-internal addressing;
   * the Infisical project / environment / path coordinates come from the provider's own
   * configuration. Returns {@link Optional#empty()} when the secret is absent or Infisical is not
   * configured — the caller fails the binding closed. The value is never logged.
   */
  @Override
  @NonNull
  public Optional<String> resolvePlaintext(@NonNull String scope, @NonNull String key) {
    String value = provider.secret(key);
    boolean found = value != null && !value.isBlank();
    LOGGER.log(
        Level.FINE,
        "[titan] infisical resolve: scope={0} key={1} found={2}",
        new Object[] {scope, key, found});
    return found ? Optional.of(value) : Optional.empty();
  }

  @Override
  public int rotateKek() {
    // Infisical owns its own key lifecycle — there is no Titan KEK to re-wrap.
    return 0;
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  /**
   * Stable, deterministic positive long id derived from ({@code scope}, {@code key}). SHA-256 first
   * 8 bytes, sign bit masked — the same scheme as {@code VaultSecretsBackend} so the surfaces
   * match.
   */
  static long stableId(@NonNull String scope, @NonNull String key) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      md.update(scope.getBytes(StandardCharsets.UTF_8));
      md.update((byte) 0);
      md.update(key.getBytes(StandardCharsets.UTF_8));
      byte[] h = md.digest();
      long v = 0L;
      for (int i = 0; i < 8; i++) {
        v = (v << 8) | (h[i] & 0xFFL);
      }
      return v & 0x7FFFFFFFFFFFFFFFL;
    } catch (NoSuchAlgorithmException e) {
      // Every JVM ships SHA-256; this is unreachable.
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }
}
