package io.adaptiq.titan.credentials;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.flow.crypto.CredentialKeyProvider;
import io.adaptiq.titan.flow.crypto.SecretCipher;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.CredentialRow;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Default {@link SecretsBackend} — envelope-encrypted secrets stored in {@code titan.credentials}.
 *
 * <h2>Two-key envelope</h2>
 *
 * Each row carries its own AES-256 data-encryption key (DEK). The DEK seals the secret value
 * ({@code sealed_value}); the DEK itself is wrapped under the KEK supplied by {@link
 * CredentialKeyProvider} ({@code wrapped_dek}). Two layers, two independent keys per row. See
 * {@link EnvelopeCipher} for the primitives.
 *
 * <h2>KEK rotation</h2>
 *
 * Rotating the KEK is a single batch pass through {@code titan.credentials} that re-wraps every
 * row's {@code wrapped_dek} blob under the new KEK — the {@code sealed_value} bytes (which may be
 * megabytes for {@code FILE} credentials) are never re-encrypted. This is the operational reason
 * envelope encryption is industry-standard: KEK rotation is O(rows) at constant tiny cost, not
 * O(bytes).
 *
 * <h2>V10 backward compatibility</h2>
 *
 * Rows from V10 (sealed directly under the KEK, no DEK) keep working: {@link #resolveRow}
 * dispatches on the presence of {@code wrapped_dek}. The next {@link #update(long,
 * CredentialUpdate)} of a legacy row migrates it into envelope form. A future migration may rewrite
 * all V10 rows in bulk; until then both shapes coexist.
 *
 * <p>Constructor-injection only.
 */
public class DbEnvelopeBackend implements SecretsBackend {

  private static final Logger LOGGER = Logger.getLogger(DbEnvelopeBackend.class.getName());

  /** Identifier matched against the {@code TITAN_SECRETS_BACKEND} env var. */
  public static final String NAME = "db-envelope";

  private static final Set<String> ALLOWED_KINDS =
      Set.of(
          Credential.KIND_USERNAME_PASSWORD,
          Credential.KIND_SSH_KEY,
          Credential.KIND_STRING,
          Credential.KIND_FILE);

  /** AAD used at create time, before an id has been assigned. */
  static final String AAD_NEW = "credentials:new";

  /**
   * KEK version reported by the current {@link CredentialKeyProvider}. The provider is the source
   * of truth for the active version; the backend persists whatever {@link
   * CredentialKeyProvider#credentialKeyVersion()} returns. (Providers that do not version their
   * keys may return {@code 1} forever — KEK rotation through this backend then becomes a no-op,
   * which is correct.)
   */
  private final TitanStores stores;

  private final CredentialKeyProvider keyProvider;

  public DbEnvelopeBackend(TitanStores stores, CredentialKeyProvider keyProvider) {
    this.stores = stores;
    this.keyProvider = keyProvider;
  }

  @Override
  @NonNull
  public String name() {
    return NAME;
  }

  // ── reads (metadata only) ──────────────────────────────────────────────────

  @Override
  @NonNull
  public Optional<Credential> findById(long id) {
    return stores.credentials().findById(id).map(DbEnvelopeBackend::toDomain);
  }

  @Override
  @NonNull
  public Optional<Credential> findByScopeAndKey(@NonNull String scope, @NonNull String key) {
    return stores.credentials().findByScopeAndKey(scope, key).map(DbEnvelopeBackend::toDomain);
  }

  @Override
  @NonNull
  public List<Credential> listAll() {
    return stores.credentials().listAll().stream().map(DbEnvelopeBackend::toDomain).toList();
  }

  @Override
  @NonNull
  public List<Credential> listByScope(@NonNull String scope) {
    return stores.credentials().listByScope(scope).stream()
        .map(DbEnvelopeBackend::toDomain)
        .toList();
  }

  // ── writes ─────────────────────────────────────────────────────────────────

  @Override
  @NonNull
  public Credential create(@NonNull NewCredentialRequest request) {
    requireKind(request.kind());
    if (stores.credentials().findByScopeAndKey(request.scope(), request.key()).isPresent()) {
      throw new IllegalArgumentException(
          "credential ("
              + request.scope()
              + ", "
              + request.key()
              + ") already exists — delete or update it");
    }

    byte[] kek = activeKek();
    int kekVersion = keyProvider.credentialKeyVersion();

    // Step 1: insert with a placeholder under AAD_NEW so the id is generated. We re-seal under
    // the id-bound AAD on step 2 — credential identity is not knowable until the row exists.
    EnvelopeCipher.Sealed placeholder =
        EnvelopeCipher.seal(request.plaintext(), kek, kekVersion, AAD_NEW, 0L);

    CredentialRow row = new CredentialRow();
    row.kind = request.kind();
    row.scope = request.scope();
    row.credKey = request.key();
    row.sealedValue = placeholder.sealedValue();
    row.aad = AAD_NEW;
    row.wrappedDek = placeholder.wrappedDek();
    row.kekVersion = kekVersion;
    row.dekVersion = 1;

    long id = stores.credentials().insert(row);

    // Step 2: re-seal under the id-bound AAD with a fresh DEK so the placeholder DEK is
    // discarded together with the AAD_NEW binding.
    EnvelopeCipher.Sealed rebound =
        EnvelopeCipher.seal(request.plaintext(), kek, kekVersion, aadFor(id), id);
    CredentialRow inserted =
        stores
            .credentials()
            .findById(id)
            .orElseThrow(
                () -> new IllegalStateException("inserted credential " + id + " not retrievable"));
    inserted.sealedValue = rebound.sealedValue();
    inserted.wrappedDek = rebound.wrappedDek();
    inserted.kekVersion = rebound.kekVersion();
    inserted.dekVersion = 1;
    inserted.aad = aadFor(id);
    inserted.kind = request.kind();
    stores.credentials().update(inserted);

    return toDomain(
        stores
            .credentials()
            .findById(id)
            .orElseThrow(
                () -> new IllegalStateException("rebound credential " + id + " not retrievable")));
  }

  @Override
  @NonNull
  public Credential update(long id, @NonNull CredentialUpdate update) {
    requireKind(update.kind());
    CredentialRow existing =
        stores.credentials().findById(id).orElseThrow(() -> new CredentialNotFoundException(id));
    byte[] kek = activeKek();
    int kekVersion = keyProvider.credentialKeyVersion();

    // A new DEK is generated on every update — old DEK is discarded together with the old
    // plaintext. This is intentional: a leaked DEK from a prior version of the secret no longer
    // unlocks the current value.
    EnvelopeCipher.Sealed sealed =
        EnvelopeCipher.seal(update.plaintext(), kek, kekVersion, aadFor(id), id);
    existing.kind = update.kind();
    existing.sealedValue = sealed.sealedValue();
    existing.wrappedDek = sealed.wrappedDek();
    existing.kekVersion = sealed.kekVersion();
    existing.dekVersion = 1;
    existing.aad = aadFor(id);
    stores.credentials().update(existing);

    return toDomain(
        stores.credentials().findById(id).orElseThrow(() -> new CredentialNotFoundException(id)));
  }

  @Override
  public void delete(long id) {
    stores.credentials().delete(id);
  }

  // ── resolve ────────────────────────────────────────────────────────────────

  @Override
  @NonNull
  public Optional<String> resolvePlaintext(@NonNull String scope, @NonNull String key) {
    return stores.credentials().findByScopeAndKey(scope, key).map(this::resolveRow);
  }

  /**
   * Recover the plaintext from a row. Dispatches on shape:
   *
   * <ul>
   *   <li>Envelope (V11+) — {@code wrapped_dek} non-null: unwrap DEK with KEK, then unseal payload.
   *   <li>Legacy (V10) — {@code wrapped_dek} null: unseal payload directly with KEK.
   * </ul>
   */
  @NonNull
  private String resolveRow(@NonNull CredentialRow row) {
    byte[] kek = activeKek();
    if (row.wrappedDek != null) {
      int rowKekVersion = row.kekVersion != null ? row.kekVersion : 1;
      return EnvelopeCipher.unseal(
          row.sealedValue, row.wrappedDek, kek, rowKekVersion, row.aad, row.id);
    }
    // Legacy V10 row: sealed directly under the KEK with the row's AAD.
    return SecretCipher.unseal(row.sealedValue, kek, row.aad);
  }

  // ── KEK rotation ───────────────────────────────────────────────────────────

  @Override
  public int rotateKek() {
    byte[] newKek = activeKek();
    int newKekVersion = keyProvider.credentialKeyVersion();
    List<CredentialRow> rows = stores.credentials().listAll();
    int rewrapped = 0;
    for (CredentialRow row : rows) {
      if (row.wrappedDek == null) {
        // Legacy V10 row — there is no separate DEK to re-wrap. The next update() of this row
        // migrates it into envelope form automatically.
        continue;
      }
      int rowKekVersion = row.kekVersion != null ? row.kekVersion : 1;
      if (rowKekVersion == newKekVersion) {
        continue; // already on the active KEK version
      }
      // We don't have the OLD KEK on hand — the provider only exposes the active key. KEK
      // rotation through this backend therefore requires the operator to expose the old KEK
      // alongside the new one (CredentialKeyProvider supplies both via credentialKey() and
      // credentialKeyByVersion(...)). Providers that do not version their keys cannot rotate
      // through this path — they must rotate via a deliberate re-create of every row, which is
      // the V10 fallback above.
      byte[] oldKek = keyProvider.credentialKeyByVersion(rowKekVersion);
      if (oldKek == null) {
        LOGGER.warning(
            "[titan] cannot re-wrap credential "
                + row.id
                + ": prior KEK v"
                + rowKekVersion
                + " is no longer available from CredentialKeyProvider");
        continue;
      }
      String reWrapped =
          EnvelopeCipher.rewrapDek(
              row.wrappedDek, oldKek, rowKekVersion, newKek, newKekVersion, row.id);
      stores.credentials().updateWrappedDek(row.id, reWrapped, newKekVersion);
      rewrapped++;
    }
    return rewrapped;
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  @NonNull
  private byte[] activeKek() {
    byte[] k = keyProvider.credentialKey();
    if (k == null) {
      throw new SecretCipher.CipherException(
          "no credential key configured (CredentialKeyProvider.active() returned null) — "
              + "the secrets store fails closed rather than persist plaintext");
    }
    return k;
  }

  private static void requireKind(@NonNull String kind) {
    if (!ALLOWED_KINDS.contains(kind)) {
      throw new IllegalArgumentException(
          "unsupported credential kind '" + kind + "'; allowed: " + ALLOWED_KINDS);
    }
  }

  /** AAD bound to a credential's identity once an id is assigned. */
  @NonNull
  static String aadFor(long id) {
    return "credentials:" + id;
  }

  @NonNull
  static Credential toDomain(@NonNull CredentialRow row) {
    return new Credential(row.id, row.kind, row.scope, row.credKey, row.createdAt, row.updatedAt);
  }
}
