package io.adaptiq.titan.scm.bitbucket;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Produces the {@code Authorization} header value for a single Bitbucket Cloud REST round-trip
 * (issue #1117).
 *
 * <p>Two auth modes are recognised:
 *
 * <ul>
 *   <li><b>App password</b> (wired now) — Bitbucket Cloud "app passwords" / repository access
 *       tokens authenticate with HTTP Basic ({@code Authorization: Basic
 *       base64(username:app_password)}). This is the only fully-functional path in this release.
 *   <li><b>OAuth2</b> (stubbed) — Bitbucket Cloud OAuth2 (client-credentials or authorization-code)
 *       will mint a bearer token. The interface exists so OAuth can be slotted in without refactor;
 *       {@link #authorizationHeaderValue()} throws {@link UnsupportedOperationException} until the
 *       OAuth ticket lands.
 * </ul>
 *
 * <h2>Secret hygiene — CRITICAL</h2>
 *
 * The header value is the ONE place the secret materialises. Implementations MUST redact the secret
 * from {@link #toString()} so a credential never leaks via an accidental {@code log.info(provider)}
 * or an exception that includes the provider in its message. The redaction is enforced here, at the
 * boundary, not at every call site (Titan manifesto: "Redaction is enforced at the log boundary").
 */
public interface BitbucketAuthProvider {

  /**
   * The full {@code Authorization} header value (e.g. {@code "Basic dXNlcjpwYXNz"}).
   *
   * @throws UnsupportedOperationException for the OAuth2 stub until that path is implemented.
   */
  @NonNull
  String authorizationHeaderValue();

  /** Stable discriminator of the auth mode — {@code "app-password"} or {@code "oauth2"}. */
  @NonNull
  Mode mode();

  /** Auth-mode discriminator (shared enum, never a stringly-typed token). */
  enum Mode {
    APP_PASSWORD,
    OAUTH2
  }

  // ── factories ──────────────────────────────────────────────────────────────

  /**
   * Basic-auth provider for an app password / repository access token.
   *
   * @param username Bitbucket account / workspace username.
   * @param appPassword the app password or access token (NEVER logged).
   */
  @NonNull
  static BitbucketAuthProvider appPassword(@NonNull String username, @NonNull String appPassword) {
    if (username.isBlank()) {
      throw new IllegalArgumentException(
          "Bitbucket app-password auth requires a non-blank username");
    }
    if (appPassword.isEmpty()) {
      throw new IllegalArgumentException("Bitbucket app-password auth requires a non-empty secret");
    }
    return new AppPasswordAuthProvider(username, appPassword);
  }

  /**
   * Build an app-password provider from a single stored credential plaintext of the form {@code
   * "username:app_password"} (the convention the Titan credentials store uses for the {@code
   * bitbucket-webhook} scope). The split is on the FIRST colon so an app password containing colons
   * is preserved.
   *
   * @throws IllegalArgumentException if {@code plaintext} has no colon separator.
   */
  @NonNull
  static BitbucketAuthProvider fromColonPair(@NonNull String plaintext) {
    int idx = plaintext.indexOf(':');
    if (idx <= 0 || idx == plaintext.length() - 1) {
      throw new IllegalArgumentException(
          "Bitbucket app-password credential must be stored as 'username:app_password'");
    }
    return appPassword(plaintext.substring(0, idx), plaintext.substring(idx + 1));
  }

  /**
   * OAuth2 provider — interface only in this release. {@link #authorizationHeaderValue()} throws
   * {@link UnsupportedOperationException} with a clear, actionable message.
   */
  @NonNull
  static BitbucketAuthProvider oauth2() {
    return new OAuth2AuthProvider();
  }

  // ── implementations ────────────────────────────────────────────────────────

  /** HTTP Basic auth from a username + app password / access token. */
  final class AppPasswordAuthProvider implements BitbucketAuthProvider {
    private final String headerValue;

    private AppPasswordAuthProvider(@NonNull String username, @NonNull String appPassword) {
      String raw = username + ":" + appPassword;
      this.headerValue =
          "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    @NonNull
    public String authorizationHeaderValue() {
      return headerValue;
    }

    @Override
    @NonNull
    public Mode mode() {
      return Mode.APP_PASSWORD;
    }

    /** Redacted — NEVER expose the base64 (it decodes straight back to the secret). */
    @Override
    public String toString() {
      return "BitbucketAuthProvider[mode=app-password, secret=***redacted***]";
    }
  }

  /** OAuth2 stub — the slot for a future client-credentials / authorization-code token mint. */
  final class OAuth2AuthProvider implements BitbucketAuthProvider {

    @Override
    @NonNull
    public String authorizationHeaderValue() {
      throw new UnsupportedOperationException(
          "Bitbucket Cloud OAuth2 auth is not yet wired (interface-only in this release). "
              + "Use app-password auth (BitbucketAuthProvider.appPassword/fromColonPair) until the "
              + "OAuth2 ticket lands.");
    }

    @Override
    @NonNull
    public Mode mode() {
      return Mode.OAUTH2;
    }

    @Override
    public String toString() {
      return "BitbucketAuthProvider[mode=oauth2, unimplemented]";
    }
  }
}
