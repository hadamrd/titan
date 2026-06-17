package io.adaptiq.titan.store.rows;

import java.time.Instant;

/**
 * Mutable POJO mapping to a row in {@code titan.credentials} — the encrypted secrets store (closes
 * #274).
 *
 * <p>{@code sealedValue} is the {@code SecretCipher.seal()} blob (AES-256-GCM, design/39 §3.1).
 * Plaintext never lives here; it is recovered only at {@code CredentialsService.resolve()} time
 * with the key from {@code CredentialKeyProvider.active().credentialKey()}.
 *
 * <p>{@code aad} is the authenticated-additional-data bound into the GCM tag — typically {@code
 * "credentials:<id>"}. Stored so a future re-seal (after key rotation) can be replayed without
 * guessing the original AAD.
 */
public class CredentialRow {
  public long id;
  public String kind;
  public String scope;
  public String credKey;
  public String sealedValue;
  public String aad;
  public Instant createdAt;
  public Instant updatedAt;

  /**
   * Envelope-encryption columns added in V11. When {@code wrappedDek} is non-null the row is in
   * envelope form: {@code sealedValue} is AES-GCM(plaintext, DEK, AAD=credentials:&lt;id&gt;) and
   * {@code wrappedDek} is AES-GCM(DEK, KEK, AAD=dek:&lt;id&gt;:v&lt;kekVersion&gt;). When {@code
   * wrappedDek} is null the row is in legacy V10 form: {@code sealedValue} is AES-GCM directly
   * under the KEK with AAD {@code credentials:<id>}.
   */
  public String wrappedDek;

  public Integer kekVersion;
  public Integer dekVersion;
}
