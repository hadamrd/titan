package io.adaptiq.titan.artifact;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.adaptiq.titan.store.TitanStores;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Mints and verifies short-lived HMAC-signed download tokens for {@code GET
 * /api/v1/artifacts/{id}/download} (closes #849).
 *
 * <p><strong>Why this exists.</strong> The download endpoint is bearer-gated; browser-native
 * navigation (an {@code <iframe src=>} preview or an {@code <a href=… download>} anchor) cannot
 * carry the SPA's {@code Authorization} header, so every Preview / Get click 401s. The fix mirrors
 * the S3 presigned-URL shape: the SPA first POSTs to {@code /sign-download} (which IS bearer-gated)
 * and gets back a URL whose authentication is a query-string HMAC. The browser then navigates that
 * URL directly with no header gymnastics. Five-minute TTL caps replay risk to roughly the duration
 * of a Preview-then-Get round-trip on a slow link.
 *
 * <p><strong>Token shape (frozen wire contract).</strong> {@code
 * v1.<artifactId>.<expEpochSec>.<b64urlMac>}
 *
 * <ul>
 *   <li>Discriminator-prefix typed (NO URL-shape sniffing): the {@code v1.} version tag pins the
 *       format so a future v2 can land without ambiguity.
 *   <li>{@code artifactId} is bound INTO the MAC payload — a token minted for artifact A cannot be
 *       replayed against artifact B.
 *   <li>{@code expEpochSec} is also bound INTO the payload — an attacker cannot extend the TTL by
 *       editing the literal in the URL.
 *   <li>{@code mac} is base64url(HmacSHA256(secret, "<artifactId>:<expEpochSec>")).
 * </ul>
 *
 * <p><strong>User-binding deliberately omitted.</strong> The mint endpoint enforces RBAC (READ_JOB
 * / TRIGGER_BUILD / ADMIN); a holder of a valid bearer is already authorised to download. Binding
 * to the {@code sub} would couple the URL to the minting browser session and break the legitimate
 * "open Preview in new tab" flow when a session refresh changes the access token. The 5-minute TTL
 * is the bound on abuse: a leaked URL works for at most 5 minutes for the specific artifact it was
 * minted for. If the threat model tightens, the payload can grow a {@code sub} field without
 * breaking the wire format (it would land as a {@code v2.} variant).
 *
 * <p><strong>Constant-time verification.</strong> {@link MessageDigest#isEqual} is used for the MAC
 * compare — the loop does not short-circuit on the first differing byte, so a timing attacker
 * cannot probe the secret byte-by-byte.
 *
 * <p><strong>Secret lifecycle.</strong>
 *
 * <ol>
 *   <li>Operator-pinned: if {@code titan.artifact.download.secret} is set (env / properties), that
 *       wins. Multi-node deployments use this so every replica signs/verifies with the same key.
 *   <li>Auto-seeded: otherwise the signer reads a single row from {@code titan.system_secret} keyed
 *       by {@code 'artifact-download-hmac'}. If missing, it generates 32 bytes via {@link
 *       SecureRandom} and INSERTs them; a concurrent replica losing the {@code PRIMARY KEY (name)}
 *       race catches the unique-violation and re-reads the winner's value (so every node ends up
 *       with the same secret on first boot). The secret is loaded at startup; rotation is "DELETE
 *       the row and restart".
 * </ol>
 *
 * <p>The secret itself is NEVER logged or returned over the wire.
 */
@ApplicationScoped
public class ArtifactDownloadSigner {

  private static final Logger LOGGER = Logger.getLogger(ArtifactDownloadSigner.class.getName());

  /** Token-format discriminator. A future incompatible change lands as {@code v2.}. */
  static final String TOKEN_VERSION = "v1";

  /** Default TTL for a freshly minted token. */
  public static final Duration DEFAULT_TTL = Duration.ofMinutes(5);

  /** Name key under which the auto-seeded secret is persisted in {@code titan.system_secret}. */
  static final String SECRET_NAME = "artifact-download-hmac";

  /** Auto-seeded secret strength — 256 bits, matches HmacSHA256 block size. */
  private static final int SECRET_BYTES = 32;

  /**
   * Cached cryptographic RNG used to seed the HMAC secret. Held as a class-level field so SpotBugs
   * does not flag a single-use {@code new SecureRandom()} at the call site
   * (DMI_RANDOM_USED_ONLY_ONCE), and so the OS entropy pool is hit at most once per JVM rather than
   * once per replica boot.
   */
  private static final SecureRandom SECURE_RANDOM = new SecureRandom();

  private final TitanStores stores;
  private final Optional<String> configuredSecret;
  private final Clock clock;

  /** Resolved at {@link #init()}; never null after construction succeeds. */
  private byte[] secretBytes;

  @Inject
  ArtifactDownloadSigner(
      TitanStores stores,
      @ConfigProperty(name = "titan.artifact.download.secret") Optional<String> configuredSecret) {
    this(stores, configuredSecret, Clock.systemUTC());
  }

  // Test-only ctor: pin the clock for deterministic expiry assertions.
  ArtifactDownloadSigner(TitanStores stores, Optional<String> configuredSecret, Clock clock) {
    this.stores = stores;
    this.configuredSecret = configuredSecret;
    this.clock = clock;
  }

  @PostConstruct
  void init() {
    this.secretBytes = resolveSecret();
    // Never log the secret — only that we have one and which path provided it.
    String source =
        configuredSecret.isPresent() && !configuredSecret.get().isBlank() ? "config" : "db-seed";
    LOGGER.log(
        Level.INFO, "[titan] artifact-download signer initialised: secret source={0}", source);
  }

  // ── public API ────────────────────────────────────────────────────────────

  /**
   * Mint a token authorising download of {@code artifactId} until {@code now + DEFAULT_TTL}.
   *
   * @return a {@link SignedDownloadToken} carrying both the raw token (to embed as {@code ?token=})
   *     and the absolute {@link Instant} at which it expires (so the caller can surface "expires in
   *     N seconds" without parsing the token).
   */
  @NonNull
  public SignedDownloadToken sign(long artifactId) {
    return sign(artifactId, DEFAULT_TTL);
  }

  /**
   * Mint a token with an explicit TTL. Public so adversarial tests (Artifact REST tests) can
   * fabricate already-expired tokens to assert the 401 branch.
   */
  @NonNull
  public SignedDownloadToken sign(long artifactId, @NonNull Duration ttl) {
    Instant expiresAt = clock.instant().plus(ttl);
    long expEpochSec = expiresAt.getEpochSecond();
    String payload = payload(artifactId, expEpochSec);
    String mac = hmacB64Url(payload);
    String token = TOKEN_VERSION + "." + artifactId + "." + expEpochSec + "." + mac;
    return new SignedDownloadToken(token, expiresAt);
  }

  /**
   * Verify a token presented as the {@code ?token=} query param.
   *
   * @param artifactId the artifact id pulled from the URL path; MUST match the token's bound id
   *     (defends against cross-artifact replay).
   * @param token the raw query-string token; never null.
   * @return {@code true} iff the token is well-formed, bound to the given artifact, not expired,
   *     and the MAC matches.
   */
  public boolean verify(long artifactId, @Nullable String token) {
    if (token == null || token.isEmpty()) {
      return false;
    }
    String[] parts = token.split("\\.", 4);
    if (parts.length != 4 || !TOKEN_VERSION.equals(parts[0])) {
      return false;
    }
    long boundArtifactId;
    long expEpochSec;
    try {
      boundArtifactId = Long.parseLong(parts[1]);
      expEpochSec = Long.parseLong(parts[2]);
    } catch (NumberFormatException e) {
      return false;
    }
    // Cross-artifact replay defence: the URL path's id is the source of truth, the token's bound
    // id MUST match it.
    if (boundArtifactId != artifactId) {
      return false;
    }
    if (clock.instant().getEpochSecond() >= expEpochSec) {
      return false;
    }
    String expectedMac = hmacB64Url(payload(boundArtifactId, expEpochSec));
    // Constant-time compare (MessageDigest.isEqual does not short-circuit).
    return MessageDigest.isEqual(
        expectedMac.getBytes(StandardCharsets.UTF_8), parts[3].getBytes(StandardCharsets.UTF_8));
  }

  // ── internals ─────────────────────────────────────────────────────────────

  private static String payload(long artifactId, long expEpochSec) {
    return artifactId + ":" + expEpochSec;
  }

  private String hmacB64Url(String payload) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secretBytes, "HmacSHA256"));
      byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    } catch (Exception e) {
      // The JCA provider for HmacSHA256 is mandatory on every JRE we ship to; failure here is
      // fatal.
      throw new IllegalStateException("HmacSHA256 unavailable", e);
    }
  }

  private byte[] resolveSecret() {
    if (configuredSecret.isPresent() && !configuredSecret.get().isBlank()) {
      // Operator-pinned: take the raw UTF-8 bytes. We deliberately do NOT b64-decode so the
      // config value can be any printable secret (a passphrase, a base64 blob, anything).
      return configuredSecret.get().getBytes(StandardCharsets.UTF_8);
    }
    // DB-seeded: race-safe across replicas via INSERT … ON CONFLICT DO NOTHING.
    return loadOrSeedFromDatabase();
  }

  private byte[] loadOrSeedFromDatabase() {
    return stores.withTransaction(
        c -> {
          try {
            Optional<String> existing = readSecret(c);
            if (existing.isPresent()) {
              return Base64.getUrlDecoder().decode(existing.get());
            }
            byte[] fresh = new byte[SECRET_BYTES];
            SECURE_RANDOM.nextBytes(fresh);
            String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(fresh);
            // Portable upsert: try INSERT, fall back to "another replica seeded first" on a
            // unique-constraint violation. Avoids the H2/Postgres `ON CONFLICT` dialect split.
            // The PRIMARY KEY on `name` is the race-safety boundary: at most one INSERT wins
            // across all replicas at first boot; the loser re-reads the winner's value below.
            try (PreparedStatement ps =
                c.prepareStatement(
                    "INSERT INTO titan.system_secret(name, secret_value) VALUES (?, ?)")) {
              ps.setString(1, SECRET_NAME);
              ps.setString(2, encoded);
              ps.executeUpdate();
            } catch (SQLException raceLoss) {
              // SQLState 23xxx is the integrity-violation class (23505 unique violation on
              // Postgres; H2 uses 23505 / 23001). We deliberately swallow only that class —
              // anything else (e.g. connectivity loss) propagates.
              String state = raceLoss.getSQLState();
              if (state == null || !state.startsWith("23")) {
                throw raceLoss;
              }
            }
            return Base64.getUrlDecoder()
                .decode(
                    readSecret(c)
                        .orElseThrow(
                            () ->
                                new IllegalStateException(
                                    "system_secret row vanished immediately after INSERT")));
          } catch (SQLException e) {
            throw new IllegalStateException("failed to load/seed artifact-download secret", e);
          }
        });
  }

  private static Optional<String> readSecret(Connection c) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement("SELECT secret_value FROM titan.system_secret WHERE name = ?")) {
      ps.setString(1, SECRET_NAME);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) {
          return Optional.of(rs.getString(1));
        }
        return Optional.empty();
      }
    }
  }

  /** Typed return shape for {@link #sign(long)}. */
  public record SignedDownloadToken(String token, Instant expiresAt) {}
}
