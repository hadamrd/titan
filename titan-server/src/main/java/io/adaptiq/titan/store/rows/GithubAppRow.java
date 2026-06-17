package io.adaptiq.titan.store.rows;

import java.time.Instant;

/**
 * Mutable POJO mapping to a row in {@code titan.github_app} — the single registered GitHub App for
 * this Titan tenant (#832, design/63).
 *
 * <p><strong>Security invariants:</strong>
 *
 * <ul>
 *   <li>{@code pemSealedValue} + {@code pemWrappedDek} hold the App's RSA private key envelope-
 *       encrypted via {@link io.adaptiq.titan.credentials.EnvelopeCipher}. The plaintext PEM is
 *       transient — it lives in memory only long enough to sign a JWT, then it is discarded.
 *   <li>{@code webhookSecretSealedValue} + {@code webhookSecretWrappedDek} hold the App's HMAC
 *       webhook secret in the same envelope. Constant-time compare against incoming X-Hub-
 *       Signature-256 headers is the webhook handler's responsibility (Child C).
 *   <li>None of the {@code *_sealed_value} / {@code *_wrapped_dek} fields are projected onto any
 *       DTO — see {@link io.adaptiq.titan.api.dto.GithubAppDto}.
 * </ul>
 */
public class GithubAppRow {
  public long id;
  public long appId;
  public String name;
  public String slug;
  public String htmlUrl;
  public String pemSealedValue;
  public String pemWrappedDek;
  public int pemKekVersion;
  public String webhookSecretSealedValue;
  public String webhookSecretWrappedDek;
  public int webhookSecretKekVersion;
  public Instant createdAt;
  public Instant updatedAt;
}
