package io.adaptiq.titan.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.adaptiq.titan.api.FakeTitanStores;
import io.adaptiq.titan.store.PersonalAccessTokenDao;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.PersonalAccessTokenRow;
import io.quarkus.elytron.security.common.BcryptUtil;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PatTokenVerifier} — exercises every sad path enumerated in the security
 * brief (closes #477):
 *
 * <ul>
 *   <li>bad / malformed token
 *   <li>revoked token
 *   <li>wrong-prefix bearer (not a PAT — must NOT touch the DAO)
 *   <li>tampered bearer (same indexed prefix, BCrypt rejects)
 *   <li>{@code last_used_at} stamp on success
 * </ul>
 *
 * <p>Uses {@link FakeTitanStores} (H2 in-memory + real V18 migration) so the BCrypt path and the
 * SQL path are both exercised end-to-end without a Mockito dep on the module.
 */
class PatTokenVerifierTest {

  // 13 = "titanpat_".length() + 4 (indexed prefix len).
  private static final int PREFIX_LEN = 13;

  private TitanStores stores;
  private PersonalAccessTokenDao dao;
  private PatTokenVerifier verifier;

  @BeforeEach
  void setUp() {
    stores = FakeTitanStores.create();
    dao = stores.personalAccessTokens();
    verifier = new PatTokenVerifier(stores);
  }

  // ── shape gates ────────────────────────────────────────────────────────────

  @Test
  void looksLikePat_recognisesPrefix() {
    assertTrue(PatTokenVerifier.looksLikePat("titanpat_xyz"));
    assertFalse(PatTokenVerifier.looksLikePat("eyJhbGciOiJSUzI1NiJ9.foo.bar"));
    assertFalse(PatTokenVerifier.looksLikePat(null));
    assertFalse(PatTokenVerifier.looksLikePat(""));
    assertFalse(PatTokenVerifier.looksLikePat("Bearer titanpat_xyz"));
  }

  @Test
  void verify_wrongPrefix_returnsEmpty() {
    Optional<PatTokenVerifier.VerifiedPat> result = verifier.verify("eyJhbGciOiJSUzI1NiJ9.x.y");
    assertTrue(result.isEmpty());
  }

  @Test
  void verify_tooShort_returnsEmpty() {
    // "titanpat_" + 2 chars = 11 chars — below MIN_TOKEN_LEN (14).
    Optional<PatTokenVerifier.VerifiedPat> result = verifier.verify("titanpat_AB");
    assertTrue(result.isEmpty());
  }

  // ── DAO-miss path ──────────────────────────────────────────────────────────

  @Test
  void verify_noRowsForPrefix_returnsEmpty() {
    Optional<PatTokenVerifier.VerifiedPat> result =
        verifier.verify("titanpat_NOPEABCDEFGHIJKLMNOPQRSTUVWXY");
    assertTrue(result.isEmpty());
  }

  // ── hash compare ──────────────────────────────────────────────────────────

  @Test
  void verify_validToken_returnsSubject_andStampsLastUsed() {
    String plaintext = "titanpat_VALIDXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX";
    long id = seed("alice-sub", "valid-token", plaintext);

    Optional<PatTokenVerifier.VerifiedPat> result = verifier.verify(plaintext);

    assertTrue(result.isPresent());
    assertEquals(id, result.get().tokenId());
    assertEquals("alice-sub", result.get().userSubject());
    assertEquals(plaintext.substring(0, PREFIX_LEN), result.get().prefix());

    PersonalAccessTokenRow after = dao.findByIdForUser(id, "alice-sub").orElseThrow();
    assertNotNull(after.lastUsedAt, "last_used_at must be stamped on successful verify");
  }

  @Test
  void verify_tamperedToken_sameIndexedPrefix_returnsEmpty() {
    String plaintext = "titanpat_TAMPRXAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    long id = seed("bob-sub", "tampered", plaintext);

    // Flip the last character — same 13-char indexed prefix, BCrypt compare must reject.
    char last = plaintext.charAt(plaintext.length() - 1);
    char flipped = (last == 'A') ? 'B' : 'A';
    String tampered = plaintext.substring(0, plaintext.length() - 1) + flipped;

    Optional<PatTokenVerifier.VerifiedPat> result = verifier.verify(tampered);
    assertTrue(result.isEmpty());

    PersonalAccessTokenRow after = dao.findByIdForUser(id, "bob-sub").orElseThrow();
    assertNull(after.lastUsedAt, "last_used_at must NOT be stamped on a rejected verify");
  }

  // ── scopes (#500) ──────────────────────────────────────────────────────────

  @Test
  void verify_validToken_returnsPersistedScopes() {
    String plaintext = "titanpat_SCOPEDAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    long id = seedWithScopes("dave-sub", "scoped", plaintext, "[\"READ_JOB\"]");

    Optional<PatTokenVerifier.VerifiedPat> result = verifier.verify(plaintext);

    assertTrue(result.isPresent());
    assertEquals(id, result.get().tokenId());
    assertEquals(java.util.List.of("READ_JOB"), result.get().scopes());
  }

  @Test
  void verify_legacyTokenWithNullScopes_returnsNullScopes() {
    String plaintext = "titanpat_LEGACYBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB";
    long id = seedWithScopes("eve-sub", "legacy", plaintext, null);

    Optional<PatTokenVerifier.VerifiedPat> result = verifier.verify(plaintext);

    assertTrue(result.isPresent());
    assertEquals(id, result.get().tokenId());
    org.junit.jupiter.api.Assertions.assertNull(
        result.get().scopes(), "legacy null scopes_json must surface as null (inherit-all)");
  }

  // ── revoked path ───────────────────────────────────────────────────────────

  @Test
  void verify_revokedToken_returnsEmpty() {
    String plaintext = "titanpat_REVKDAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    long id = seed("carol-sub", "revoked", plaintext);
    int revoked = dao.revoke(id, "carol-sub");
    assertEquals(1, revoked, "precondition: row revoked");

    Optional<PatTokenVerifier.VerifiedPat> result = verifier.verify(plaintext);
    assertTrue(result.isEmpty());
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  private long seed(String subject, String name, String plaintext) {
    return seedWithScopes(subject, name, plaintext, null);
  }

  private long seedWithScopes(String subject, String name, String plaintext, String scopesJson) {
    PersonalAccessTokenRow row = new PersonalAccessTokenRow();
    row.userSubject = subject;
    row.name = name + "-" + System.nanoTime();
    row.tokenHash = BcryptUtil.bcryptHash(plaintext);
    row.prefix = plaintext.substring(0, PREFIX_LEN);
    row.scopesJson = scopesJson;
    return dao.insert(row);
  }
}
