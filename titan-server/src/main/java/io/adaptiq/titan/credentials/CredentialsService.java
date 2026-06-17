package io.adaptiq.titan.credentials;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Optional;

/**
 * Domain-facing API for Titan's encrypted secrets store (closes #274) — CRUD over the {@code
 * titan.credentials} table plus the unseal path that the engine uses at dispatch time.
 *
 * <p>On the dispatch path: a stored value is {@link io.adaptiq.titan.flow.crypto.SecretCipher#seal
 * sealed} at write time with a key drawn from {@link
 * io.adaptiq.titan.flow.crypto.CredentialKeyProvider#active() CredentialKeyProvider.active()}, and
 * only this service ever unseals it.
 *
 * <p>API responses (see {@code CredentialsApi}) never include {@code sealedValue} or plaintext;
 * only domain metadata flows out.
 */
public interface CredentialsService {

  @NonNull
  Optional<Credential> findById(long id);

  /**
   * Resolve a credential by its addressing tuple. Returns the domain record (which still carries
   * the sealed blob — call {@link #resolvePlaintext} to unseal).
   */
  @NonNull
  Optional<Credential> findByScopeAndKey(@NonNull String scope, @NonNull String key);

  /** Every credential row, ordered by ({@code scope}, {@code key}). */
  @NonNull
  List<Credential> listAll();

  /** Every credential row in {@code scope}, ordered by {@code key}. */
  @NonNull
  List<Credential> listByScope(@NonNull String scope);

  /**
   * Materialise a new credential row. Seals {@link NewCredentialRequest#plaintext()} on the
   * caller's thread before any database write. Returns the persisted record with database- assigned
   * id and timestamps.
   *
   * @throws IllegalArgumentException if {@code request.kind} is not a recognised kind, or if {@code
   *     (scope, key)} already exists (the unique index rejects duplicates as well — this is the
   *     friendlier pre-flight check).
   * @throws io.adaptiq.titan.flow.crypto.SecretCipher.CipherException if no credential key is
   *     configured (i.e. {@code CredentialKeyProvider.active().credentialKey()} is {@code null}) —
   *     the service fails closed rather than persist plaintext.
   */
  @NonNull
  Credential create(@NonNull NewCredentialRequest request);

  /**
   * Replace the secret value (and optionally the kind) for an existing credential. The new value is
   * re-sealed under the active key. {@code scope} and {@code key} are immutable.
   *
   * @throws CredentialNotFoundException if no row exists for {@code id}.
   */
  @NonNull
  Credential update(long id, @NonNull CredentialUpdate update);

  /** Remove a credential row. No-op if absent. */
  void delete(long id);

  /**
   * Unseal a stored credential by {@code (scope, key)} and return its plaintext.
   *
   * <p><strong>Internal use only</strong> — the REST API never exposes this. Called by the engine
   * at dispatch time when a step declares a {@code credentials:} binding.
   *
   * @return the plaintext, or {@link Optional#empty()} if no row matches.
   * @throws io.adaptiq.titan.flow.crypto.SecretCipher.CipherException on a tampered blob, the wrong
   *     AAD, or a key change without re-seal.
   */
  @NonNull
  Optional<String> resolvePlaintext(@NonNull String scope, @NonNull String key);

  /**
   * Re-wrap every stored DEK under the currently-active KEK. Operator handle for KEK rotation:
   * after the {@link io.adaptiq.titan.flow.crypto.CredentialKeyProvider} starts reporting a new key
   * version, call this once to migrate the database. The {@code sealed_value} bytes are untouched —
   * only the small {@code wrapped_dek} blob is re-encrypted. Idempotent.
   *
   * <p>Backends that do not own a KEK (e.g. Vault, AWS Secrets Manager — the upstream owns
   * rotation) MAY return {@code 0}.
   *
   * @return the number of credentials whose DEK was re-wrapped.
   */
  int rotateKek();

  /** Diagnostic — short identifier of the currently-bound backend (e.g. {@code "db-envelope"}). */
  @NonNull
  String backendName();
}
