package io.adaptiq.titan.artifact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.adaptiq.titan.api.FakeTitanStores;
import io.adaptiq.titan.store.TitanStores;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Adversarial unit tests for {@link ArtifactDownloadSigner} (closes #849).
 *
 * <p>The bug being fixed allowed iframe / anchor download navigations to 401 because they could not
 * carry a bearer header. The fix mints short-lived HMAC-signed URLs; these tests pin down the
 * threat-model invariants:
 *
 * <ul>
 *   <li>cross-artifact replay — a token for artifact A used for artifact B → rejected.
 *   <li>tampered MAC — flip one byte of the signature → rejected (and via constant-time compare).
 *   <li>expired token — past the {@code expEpochSec} → rejected.
 *   <li>wrong version prefix → rejected (forward-compat hardening).
 * </ul>
 *
 * <p>Each test runs against a fresh H2-backed {@link TitanStores} so the secret-seeding path
 * round-trips through real SQL (the {@code titan.system_secret} row from the V30 migration).
 */
class ArtifactDownloadSignerTest {

  private TitanStores stores;
  private MutableClock clock;
  private ArtifactDownloadSigner signer;

  @BeforeEach
  void setUp() {
    stores = FakeTitanStores.create();
    clock = new MutableClock(Instant.parse("2026-05-24T12:00:00Z"));
    signer = newSigner(Optional.empty());
    signer.init();
  }

  private ArtifactDownloadSigner newSigner(Optional<String> configuredSecret) {
    return new ArtifactDownloadSigner(stores, configuredSecret, clock);
  }

  // ── happy path ────────────────────────────────────────────────────────────

  @Test
  void sign_thenVerify_succeeds() {
    ArtifactDownloadSigner.SignedDownloadToken signed = signer.sign(42L);

    assertNotNull(signed.token());
    assertEquals(clock.instant().plus(ArtifactDownloadSigner.DEFAULT_TTL), signed.expiresAt());
    assertTrue(signer.verify(42L, signed.token()));
  }

  @Test
  void sign_emitsVersionPrefixedToken() {
    ArtifactDownloadSigner.SignedDownloadToken signed = signer.sign(7L);
    assertTrue(
        signed.token().startsWith(ArtifactDownloadSigner.TOKEN_VERSION + "."),
        "token must carry the v1. discriminator; was: " + signed.token());
  }

  // ── threat-model invariants ───────────────────────────────────────────────

  @Test
  void verify_crossArtifactReplay_isRejected() {
    String token = signer.sign(1L).token();
    // Use the SAME token's MAC against a different artifactId path → must fail.
    assertFalse(signer.verify(2L, token));
  }

  @Test
  void verify_tamperedMac_isRejected() {
    String token = signer.sign(99L).token();
    // Flip the last character of the MAC (the trailing base64url byte).
    char last = token.charAt(token.length() - 1);
    char swapped = (last == 'A') ? 'B' : 'A';
    String tampered = token.substring(0, token.length() - 1) + swapped;
    assertFalse(signer.verify(99L, tampered));
  }

  @Test
  void verify_tamperedExpiry_isRejected() {
    // Try to extend the TTL by editing the literal in the URL — MAC is bound to it, so the
    // edit must invalidate.
    String token = signer.sign(33L, Duration.ofSeconds(60)).token();
    String[] parts = token.split("\\.");
    long extended = Long.parseLong(parts[2]) + 86400L;
    String tampered = parts[0] + "." + parts[1] + "." + extended + "." + parts[3];
    assertFalse(signer.verify(33L, tampered));
  }

  @Test
  void verify_expiredToken_isRejected() {
    ArtifactDownloadSigner.SignedDownloadToken signed = signer.sign(7L, Duration.ofSeconds(10));
    // Advance the clock past the expiry.
    clock.advance(Duration.ofSeconds(11));
    assertFalse(signer.verify(7L, signed.token()));
  }

  @Test
  void verify_unknownVersionPrefix_isRejected() {
    // A future v2 token must NOT validate against v1 secret logic.
    String token = signer.sign(11L).token();
    String v2 = "v2" + token.substring(2);
    assertFalse(signer.verify(11L, v2));
  }

  @Test
  void verify_malformedToken_isRejected() {
    assertFalse(signer.verify(1L, null));
    assertFalse(signer.verify(1L, ""));
    assertFalse(signer.verify(1L, "garbage"));
    assertFalse(signer.verify(1L, "v1.notanumber.0.mac"));
    assertFalse(signer.verify(1L, "v1.1.notanumber.mac"));
  }

  // ── secret-lifecycle ──────────────────────────────────────────────────────

  @Test
  void configuredSecret_winsOverDbSeed() {
    // Boot a signer with an operator-pinned secret, mint a token; a second signer with the SAME
    // pinned secret must verify it. A third signer with NO pinned secret will use the DB-seeded
    // secret instead and MUST NOT verify the first signer's token.
    ArtifactDownloadSigner pinnedA =
        new ArtifactDownloadSigner(stores, Optional.of("operator-pinned"), clock);
    pinnedA.init();
    String tokenA = pinnedA.sign(5L).token();

    ArtifactDownloadSigner pinnedB =
        new ArtifactDownloadSigner(stores, Optional.of("operator-pinned"), clock);
    pinnedB.init();
    assertTrue(pinnedB.verify(5L, tokenA), "same pinned secret must verify same token");

    ArtifactDownloadSigner unpinned = new ArtifactDownloadSigner(stores, Optional.empty(), clock);
    unpinned.init();
    assertFalse(
        unpinned.verify(5L, tokenA),
        "DB-seeded secret must NOT verify a token minted with a different (pinned) secret");
  }

  @Test
  void dbSeed_isStableAcrossSignerInstances() {
    // Two signers wired to the SAME TitanStores must agree on the secret (race-safe ON CONFLICT
    // DO NOTHING + SELECT pattern). This is the cross-replica invariant that lets one server pod
    // verify a token minted by another.
    ArtifactDownloadSigner s1 = newSigner(Optional.empty());
    s1.init();
    ArtifactDownloadSigner s2 = newSigner(Optional.empty());
    s2.init();
    String token = s1.sign(123L).token();
    assertTrue(s2.verify(123L, token));
  }

  @Test
  void signing_isDeterministicForFixedClockAndSecret_butVariesOnRotation() {
    // Sanity: with the secret fixed and the clock fixed, two signatures should be byte-identical.
    String t1 = signer.sign(1L).token();
    String t2 = signer.sign(1L).token();
    assertEquals(t1, t2);

    // After (notional) rotation — fresh DB-seeded secret on a fresh store — the same artifactId
    // mints a different token.
    TitanStores otherStores = FakeTitanStores.create();
    ArtifactDownloadSigner otherSigner =
        new ArtifactDownloadSigner(otherStores, Optional.empty(), clock);
    otherSigner.init();
    String t3 = otherSigner.sign(1L).token();
    assertNotEquals(t1, t3);
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  /** Mutable {@link Clock} so tests can move past a token's expiry deterministically. */
  private static final class MutableClock extends Clock {
    private Instant now;

    MutableClock(Instant initial) {
      this.now = initial;
    }

    void advance(Duration d) {
      this.now = this.now.plus(d);
    }

    @Override
    public Instant instant() {
      return now;
    }

    @Override
    public java.time.ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }
  }
}
