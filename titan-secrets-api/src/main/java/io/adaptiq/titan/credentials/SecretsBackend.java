package io.adaptiq.titan.credentials;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Optional;

/**
 * Pluggable backend for the Titan secrets store.
 *
 * <p>The default implementation, {@code DbEnvelopeBackend} (in titan-server), stores
 * envelope-encrypted secrets in the {@code titan.credentials} table. Alternative backends —
 * HashiCorp Vault, AWS Secrets Manager, GCP Secret Manager, Azure Key Vault — implement this
 * interface and ship as separate jars. Discovery is {@link java.util.ServiceLoader}-based via
 * {@code META-INF/services/io.adaptiq.titan.credentials.SecretsBackend}, mirroring the {@code
 * ArtifactStoreProvider} SPI (design/41 §8.6, design/46).
 *
 * <p>Only one backend is active per controller. Selection is driven by the {@code
 * TITAN_SECRETS_BACKEND} env var (default: {@code db-envelope}). A backend whose {@link #name()}
 * matches that value is bound; the others are ignored.
 *
 * <p><strong>Contract.</strong> Every implementation MUST:
 *
 * <ul>
 *   <li>Never return plaintext or sealed blobs from any list / get operation that names the
 *       <em>metadata</em> (id, scope, key, kind, timestamps). Only {@link #resolvePlaintext(String,
 *       String)} — the engine's narrow read path — may return plaintext.
 *   <li>Bind authentication-additional-data (AAD) to {@code credentials:<id>} on the payload so a
 *       sealed value cannot be cross-row-substituted.
 *   <li>Fail closed (throw) when the configured key/credential is missing, never silently fall back
 *       to plaintext storage.
 *   <li>Be idempotent on {@link #delete(long)} — deleting an absent id is a no-op, never an error.
 * </ul>
 *
 * <p><strong>Threading.</strong> Implementations must be safe for concurrent use across request
 * threads; all four mutating methods may be called in parallel.
 */
public interface SecretsBackend {

  /**
   * Short identifier used to select this backend at runtime. Must match the {@code
   * TITAN_SECRETS_BACKEND} env var. Lowercase, kebab-case (e.g. {@code db-envelope}, {@code
   * vault-kv}, {@code aws-secrets-manager}).
   */
  @NonNull
  String name();

  /** Metadata-only read by id. Returns empty when no such credential exists. */
  @NonNull
  Optional<Credential> findById(long id);

  /** Metadata-only read by ({@code scope}, {@code key}). */
  @NonNull
  Optional<Credential> findByScopeAndKey(@NonNull String scope, @NonNull String key);

  /** All credentials, metadata only — never plaintext. */
  @NonNull
  List<Credential> listAll();

  /** All credentials in {@code scope}, metadata only. */
  @NonNull
  List<Credential> listByScope(@NonNull String scope);

  /** Create a new credential. Returns the persisted metadata. */
  @NonNull
  Credential create(@NonNull NewCredentialRequest request);

  /** Replace the value (and optionally kind) of an existing credential. */
  @NonNull
  Credential update(long id, @NonNull CredentialUpdate update);

  /** Delete by id. Idempotent. */
  void delete(long id);

  /**
   * Resolve the plaintext for ({@code scope}, {@code key}) — the engine's only path to a secret's
   * value. Returns empty when no row exists; throws when the row exists but the backend cannot
   * decrypt (missing KEK, missing transit secret, network failure to the remote provider).
   */
  @NonNull
  Optional<String> resolvePlaintext(@NonNull String scope, @NonNull String key);

  /**
   * Re-wrap all stored data-encryption keys under the new KEK reported by the host's {@code
   * CredentialKeyProvider} (in titan-server). Implementations that do not own a KEK (Vault, AWS
   * Secrets Manager — the upstream owns rotation) MAY make this a no-op and return {@code 0}; the
   * in-tree {@code DbEnvelopeBackend} re-wraps every {@code titan.credentials} row's {@code
   * wrapped_dek} column.
   *
   * @return the number of credentials whose DEK was re-wrapped.
   */
  int rotateKek();
}
