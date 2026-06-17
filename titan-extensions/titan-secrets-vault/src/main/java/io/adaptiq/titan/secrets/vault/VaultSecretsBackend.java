package io.adaptiq.titan.secrets.vault;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.credentials.Credential;
import io.adaptiq.titan.credentials.CredentialNotFoundException;
import io.adaptiq.titan.credentials.CredentialUpdate;
import io.adaptiq.titan.credentials.NewCredentialRequest;
import io.adaptiq.titan.credentials.SecretsBackend;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@link SecretsBackend} that resolves credentials from <a
 * href="https://www.vaultproject.io/">HashiCorp Vault</a>.
 *
 * <h2>Status</h2>
 *
 * <p><strong>#593: read path (AppRole / Token / Kubernetes auth modes) + write path (create /
 * update / delete against KV v2) wired.</strong> Metadata listing ({@link #findById}, {@link
 * #findByScopeAndKey}, {@link #listAll}, {@link #listByScope}) is filed as a separate follow-up;
 * Vault's {@code LIST /v1/{mount}/metadata/...} requires recursion and a paging contract distinct
 * from the in-tree {@code db-envelope} backend.
 *
 * <h2>id ↔ (scope, key) mapping</h2>
 *
 * <p>Vault has no integer ids — KV v2 is identified by ({@code path}, {@code field}). The SPI
 * forces a {@code long id} surface, so the backend derives a stable id by hashing ({@code scope},
 * {@code key}) into a 63-bit positive long (SHA-256, first 8 bytes, sign bit masked). The mapping
 * is registered in an in-process table on every {@code create} / {@code update} / {@code resolve}
 * so that {@code delete(long id)} and {@code update(long id, ...)} can find the corresponding Vault
 * path. Re-creating a credential with the same ({@code scope}, {@code key}) yields the same id —
 * deterministic and stable across restarts.
 *
 * <h3>Caveat</h3>
 *
 * <p>If the controller restarts and a {@code delete(id)} arrives before any matching {@code create}
 * / {@code resolve} (so the in-memory map is empty), the delete throws {@link
 * CredentialNotFoundException}. The metadata-listing follow-up will close this gap by walking
 * Vault's metadata tree on startup. For now, the orchestrator always resolves before deleting in
 * its known flows, so this is acceptable in practice.
 *
 * <h2>Kind storage</h2>
 *
 * <p>{@link NewCredentialRequest#kind} is written into the secret as a sibling field {@code
 * __kind}; {@link #findById} (once implemented) would read it back. We do not put kind on the Vault
 * metadata's custom-metadata block because that path requires a separate write and breaks
 * atomicity; sibling-field is good-enough for the SPI round-trip.
 *
 * <h2>Delete semantics</h2>
 *
 * <p>{@link #delete} performs a <strong>soft delete</strong> by default ({@code POST
 * /v1/{mount}/delete/{path}}). The latest version is hidden; version history and audit trail
 * remain; operationally reversible via {@code undelete}. Hard delete (purges all versions +
 * metadata) is filed as a future operator-controlled toggle — see follow-up.
 *
 * <h2>Configuration</h2>
 *
 * <p>Read by {@link VaultConfig#fromEnv()}:
 *
 * <ul>
 *   <li>{@code VAULT_ADDR} — e.g. {@code https://vault.example.com}
 *   <li>{@code VAULT_ROLE_ID} — AppRole role-id (AppRole mode)
 *   <li>{@code VAULT_SECRET_ID} — AppRole secret-id (AppRole mode)
 *   <li>{@code VAULT_TOKEN} — pre-existing Vault token (token mode). Mutually exclusive with the
 *       AppRole pair. Wins if both are set in the environment.
 *   <li>{@code VAULT_ROLE} — Vault role bound to the kubernetes auth backend (kubernetes mode).
 *       Mutually exclusive with the AppRole pair and {@code VAULT_TOKEN}.
 *   <li>{@code VAULT_K8S_SA_TOKEN_PATH} — override the SA-token mount path (default {@code
 *       /var/run/secrets/kubernetes.io/serviceaccount/token}); only meaningful in kubernetes mode.
 *   <li>{@code VAULT_KV_MOUNT} — KV v2 mount path (default {@code secret})
 *   <li>{@code VAULT_NAMESPACE} — optional, Vault Enterprise
 * </ul>
 *
 * <p>If {@code VAULT_ADDR} is unset the backend constructs in a "not configured" state — every
 * {@link #resolvePlaintext} call throws a clear {@link IllegalStateException} pointing the operator
 * at the env var. This is preferable to crashing at controller startup because the operator who
 * picked {@code TITAN_SECRETS_BACKEND=vault} deserves a clear error message in the build log, not a
 * JVM-won't-start failure.
 *
 * <h2>Discovery</h2>
 *
 * <p>{@code META-INF/services/io.adaptiq.titan.credentials.SecretsBackend} ships this class as a
 * ServiceLoader entry. {@code name()} returns {@value #NAME}; that is the literal value an operator
 * sets {@code TITAN_SECRETS_BACKEND} to.
 *
 * <h2>Secret hygiene</h2>
 *
 * <ul>
 *   <li>Token, role-id, secret-id are NEVER logged.
 *   <li>Resolved or written plaintext is NEVER logged — only the requested scope/path is logged.
 *   <li>{@link VaultConfig} has a custom {@code toString} that redacts both ids.
 * </ul>
 */
public final class VaultSecretsBackend implements SecretsBackend {

  private static final Logger LOGGER = Logger.getLogger(VaultSecretsBackend.class.getName());

  /** Identifier matched against the {@code TITAN_SECRETS_BACKEND} env var. */
  public static final String NAME = "vault";

  /** Discriminator value for the {@code type:} field in the backend config (see CONSTITUTION). */
  public static final String TYPE = "vault";

  /** Sibling field inside the KV v2 secret that stores the credential kind. */
  static final String KIND_FIELD = "__kind";

  // Lazily initialised; null when VAULT_ADDR is unset (see class Javadoc).
  private final VaultHttpClient client;

  /**
   * In-process id → (scope, key) registry, populated on every create / update / resolve. See class
   * Javadoc for the rationale and the restart-window caveat.
   */
  private final Map<Long, ScopeKey> idIndex = new ConcurrentHashMap<>();

  /** ServiceLoader ctor — reads config from environment. */
  public VaultSecretsBackend() {
    VaultConfig cfg = VaultConfig.fromEnv();
    if (cfg == null) {
      LOGGER.log(
          Level.WARNING,
          "[titan] VaultSecretsBackend loaded but VAULT_ADDR/VAULT_ROLE_ID/VAULT_SECRET_ID are"
              + " unset — resolve() will throw until env is configured");
      this.client = null;
    } else {
      LOGGER.log(Level.INFO, "[titan] VaultSecretsBackend configured: {0}", cfg);
      this.client = new VaultHttpClient(cfg);
    }
  }

  /**
   * Test / DI ctor — explicit config, no env lookup. The IT uses this to point the backend at a
   * Testcontainer.
   */
  public VaultSecretsBackend(@NonNull VaultConfig config) {
    LOGGER.log(Level.INFO, "[titan] VaultSecretsBackend configured: {0}", config);
    this.client = new VaultHttpClient(config);
  }

  @Override
  @NonNull
  public String name() {
    return NAME;
  }

  // ── reads (metadata) ───────────────────────────────────────────────────────
  // Empty rather than throw — a UI rendering an empty credentials list on a
  // freshly wired backend is the correct UX; metadata listing lands in a
  // separate PR (vault-listing follow-up).

  @Override
  @NonNull
  public Optional<Credential> findById(long id) {
    return Optional.empty();
  }

  @Override
  @NonNull
  public Optional<Credential> findByScopeAndKey(@NonNull String scope, @NonNull String key) {
    return Optional.empty();
  }

  @Override
  @NonNull
  public List<Credential> listAll() {
    return List.of();
  }

  @Override
  @NonNull
  public List<Credential> listByScope(@NonNull String scope) {
    return List.of();
  }

  // ── writes ─────────────────────────────────────────────────────────────────

  @Override
  @NonNull
  public Credential create(@NonNull NewCredentialRequest request) {
    requireConfigured(request.scope(), request.key());
    long id = stableId(request.scope(), request.key());
    Map<String, String> data =
        Map.of(request.key(), request.plaintext(), KIND_FIELD, request.kind());
    LOGGER.log(
        Level.FINE,
        "[titan] vault create: scope={0} key={1} kind={2}",
        new Object[] {request.scope(), request.key(), request.kind()});
    client.putSecret(request.scope(), data);
    idIndex.put(id, new ScopeKey(request.scope(), request.key(), request.kind()));
    Instant now = Instant.now();
    return new Credential(id, request.kind(), request.scope(), request.key(), now, now);
  }

  @Override
  @NonNull
  public Credential update(long id, @NonNull CredentialUpdate update) {
    ScopeKey sk = idIndex.get(id);
    if (sk == null) {
      // See class-Javadoc caveat: until the metadata-listing follow-up lands, an update by id that
      // has not been touched in this process lifetime cannot map back to a Vault path.
      throw new CredentialNotFoundException(id);
    }
    requireConfigured(sk.scope, sk.key);
    Map<String, String> data = Map.of(sk.key, update.plaintext(), KIND_FIELD, update.kind());
    LOGGER.log(
        Level.FINE,
        "[titan] vault update: scope={0} key={1} kind={2}",
        new Object[] {sk.scope, sk.key, update.kind()});
    client.putSecret(sk.scope, data);
    idIndex.put(id, new ScopeKey(sk.scope, sk.key, update.kind()));
    Instant now = Instant.now();
    return new Credential(id, update.kind(), sk.scope, sk.key, now, now);
  }

  @Override
  public void delete(long id) {
    ScopeKey sk = idIndex.get(id);
    if (sk == null) {
      // SPI contract: delete is idempotent. Absent id = no-op, not an error.
      LOGGER.log(
          Level.FINE,
          "[titan] vault delete: id={0} not in index (no-op)",
          Long.toUnsignedString(id));
      return;
    }
    if (client == null) {
      // Defensive: idIndex should be empty when client is null, but be explicit.
      throw new IllegalStateException("VaultSecretsBackend is not configured");
    }
    LOGGER.log(
        Level.FINE,
        "[titan] vault delete (soft): scope={0} key={1}",
        new Object[] {sk.scope, sk.key});
    client.deleteSecret(sk.scope, /* soft= */ true);
    idIndex.remove(id);
  }

  // ── resolve ────────────────────────────────────────────────────────────────

  /**
   * Resolve a KV v2 secret. {@code scope} is the Vault path (e.g. {@code secret/test/integration}
   * or {@code test/integration}); {@code key} is the field name inside the secret JSON.
   *
   * <p>Returns {@link Optional#empty()} if the path is absent. Throws {@link VaultException} on
   * transport / auth failures so the orchestrator surfaces a clear "vault unavailable" build
   * failure instead of silently running the build without its credential.
   */
  @Override
  @NonNull
  public Optional<String> resolvePlaintext(@NonNull String scope, @NonNull String key) {
    if (client == null) {
      throw new IllegalStateException(
          "VaultSecretsBackend is not configured — set VAULT_ADDR plus EITHER VAULT_ROLE_ID +"
              + " VAULT_SECRET_ID (AppRole) OR VAULT_TOKEN (direct token) env vars. Requested:"
              + " scope="
              + scope
              + " key="
              + key);
    }
    LOGGER.log(Level.FINE, "[titan] vault resolve: scope={0} key={1}", new Object[] {scope, key});
    Optional<String> result = client.readKvV2(scope, key);
    if (result.isPresent()) {
      // Index so a subsequent delete(id) can find this path.
      idIndex.putIfAbsent(stableId(scope, key), new ScopeKey(scope, key, /* kind= */ null));
    }
    LOGGER.log(
        Level.FINE,
        "[titan] vault resolve outcome: scope={0} found={1}",
        new Object[] {scope, result.isPresent()});
    return result;
  }

  // ── KEK rotation ───────────────────────────────────────────────────────────

  @Override
  public int rotateKek() {
    // Vault owns its own key lifecycle (transit / barrier seal) — there is no
    // Titan KEK to re-wrap. Per the SPI contract, return 0.
    return 0;
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  private void requireConfigured(@NonNull String scope, @NonNull String key) {
    if (client == null) {
      throw new IllegalStateException(
          "VaultSecretsBackend is not configured — set VAULT_ADDR plus an auth mode env var."
              + " Requested: scope="
              + scope
              + " key="
              + key);
    }
  }

  /**
   * Stable, deterministic positive long id derived from ({@code scope}, {@code key}). SHA-256 first
   * 8 bytes, sign bit masked. Collisions in a 63-bit space across realistic credential counts are
   * astronomically unlikely (birthday bound ~3 billion before any collision is expected).
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

  /**
   * Internal record used by the {@link #idIndex}. {@code kind} may be null on resolve-only entries.
   */
  private record ScopeKey(@NonNull String scope, @NonNull String key, String kind) {}
}
