package io.adaptiq.titan.credentials;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.adaptiq.titan.flow.crypto.CredentialKeyProvider;
import io.adaptiq.titan.flow.crypto.SecretCipher;
import io.adaptiq.titan.store.TitanStores;
import java.lang.reflect.Method;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CredentialsServiceImpl} backed by an in-memory H2 (via reflection into the
 * test-scope {@code FakeTitanStores}).
 *
 * <p>Covers the load-bearing seal/unseal round-trip, scope matching, kind validation, the
 * fail-closed branch when no credential key is configured, and the not-found cases.
 */
class CredentialsServiceImplTest {

  /** A stable, deterministic key for tests — different from anything an env would supply. */
  private static final byte[] TEST_KEY = freshAesKey();

  private static final CredentialKeyProvider FIXED_KEY =
      new CredentialKeyProvider() {
        @Override
        public byte[] credentialKey() {
          return TEST_KEY.clone();
        }

        @Override
        public String describe() {
          return "test:fixed";
        }
      };

  private static final CredentialKeyProvider NULL_KEY =
      new CredentialKeyProvider() {
        @Override
        public byte[] credentialKey() {
          return null;
        }

        @Override
        public String describe() {
          return "test:null";
        }
      };

  private TitanStores stores;
  private CredentialsServiceImpl service;

  @BeforeEach
  void setUp() throws Exception {
    // Reflectively call FakeTitanStores.create() (package-private in io.adaptiq.titan.api).
    Class<?> fake = Class.forName("io.adaptiq.titan.api.FakeTitanStores");
    Method create = fake.getDeclaredMethod("create");
    create.setAccessible(true);
    stores = (TitanStores) create.invoke(null);
    service = new CredentialsServiceImpl(new DbEnvelopeBackend(stores, FIXED_KEY));
  }

  // ── round-trip ──────────────────────────────────────────────────────────────

  @Test
  void createAndResolve_roundTripsPlaintext() {
    Credential created =
        service.create(
            new NewCredentialRequest(Credential.KIND_STRING, "global", "deploy-token", "s3kret!"));

    assertNotNull(created.id());
    assertEquals(Credential.KIND_STRING, created.kind());
    assertEquals("global", created.scope());
    assertEquals("deploy-token", created.key());

    // The stored sealed blob in the row is NOT the plaintext. Read the row directly — the
    // domain Credential record intentionally does not expose sealedValue (defense in depth so
    // a future REST serialiser cannot leak it).
    var row = stores.credentials().findById(created.id()).orElseThrow();
    assertNotEquals("s3kret!", row.sealedValue);
    assertFalse(row.sealedValue.contains("s3kret!"));
    // Envelope columns must be populated for V11 rows.
    assertNotNull(row.wrappedDek);
    assertNotEquals(row.sealedValue, row.wrappedDek);

    Optional<String> roundTrip = service.resolvePlaintext("global", "deploy-token");
    assertTrue(roundTrip.isPresent());
    assertEquals("s3kret!", roundTrip.get());
  }

  @Test
  void createBindsAadToRowId_notTheCreatePlaceholder() {
    Credential c =
        service.create(new NewCredentialRequest(Credential.KIND_STRING, "global", "tok", "x"));
    var row = stores.credentials().findById(c.id()).orElseThrow();
    assertEquals("credentials:" + c.id(), row.aad);
    // And the round-trip through the row's AAD still works.
    assertEquals("x", service.resolvePlaintext("global", "tok").orElseThrow());
  }

  @Test
  void update_reSealsUnderFreshDekAndPayload() {
    Credential created =
        service.create(new NewCredentialRequest(Credential.KIND_STRING, "global", "tok", "old"));
    var rowBefore = stores.credentials().findById(created.id()).orElseThrow();
    Credential updated =
        service.update(created.id(), new CredentialUpdate(Credential.KIND_STRING, "new"));
    var rowAfter = stores.credentials().findById(updated.id()).orElseThrow();

    assertEquals(created.id(), updated.id());
    // Both the sealed payload and the wrapped DEK change on update — a fresh DEK is generated
    // every time, so an old DEK leak cannot decrypt the new value.
    assertNotEquals(rowBefore.sealedValue, rowAfter.sealedValue);
    assertNotEquals(rowBefore.wrappedDek, rowAfter.wrappedDek);
    assertEquals("new", service.resolvePlaintext("global", "tok").orElseThrow());
  }

  @Test
  void delete_removesTheRow() {
    Credential c =
        service.create(new NewCredentialRequest(Credential.KIND_STRING, "global", "tok", "x"));
    service.delete(c.id());
    assertTrue(service.findById(c.id()).isEmpty());
    assertTrue(service.resolvePlaintext("global", "tok").isEmpty());
  }

  // ── scope / uniqueness ─────────────────────────────────────────────────────

  @Test
  void create_rejectsDuplicateScopeKey() {
    service.create(new NewCredentialRequest(Credential.KIND_STRING, "global", "dup", "a"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.create(new NewCredentialRequest(Credential.KIND_STRING, "global", "dup", "b")));
  }

  @Test
  void create_allowsSameKeyInDifferentScopes() {
    service.create(new NewCredentialRequest(Credential.KIND_STRING, "global", "tok", "g"));
    service.create(new NewCredentialRequest(Credential.KIND_STRING, "job:foo", "tok", "f"));
    assertEquals("g", service.resolvePlaintext("global", "tok").orElseThrow());
    assertEquals("f", service.resolvePlaintext("job:foo", "tok").orElseThrow());
  }

  @Test
  void listByScope_filtersCorrectly() {
    service.create(new NewCredentialRequest(Credential.KIND_STRING, "global", "g1", "x"));
    service.create(new NewCredentialRequest(Credential.KIND_STRING, "global", "g2", "x"));
    service.create(new NewCredentialRequest(Credential.KIND_STRING, "job:foo", "j1", "x"));
    assertEquals(2, service.listByScope("global").size());
    assertEquals(1, service.listByScope("job:foo").size());
    assertEquals(0, service.listByScope("nope").size());
  }

  // ── error / fail-closed paths ──────────────────────────────────────────────

  @Test
  void resolvePlaintext_emptyWhenNotFound() {
    assertTrue(service.resolvePlaintext("global", "nope").isEmpty());
  }

  @Test
  void findById_emptyWhenNotFound() {
    assertTrue(service.findById(99999).isEmpty());
  }

  @Test
  void update_throwsWhenIdMissing() {
    assertThrows(
        CredentialNotFoundException.class,
        () -> service.update(424242L, new CredentialUpdate(Credential.KIND_STRING, "x")));
  }

  @Test
  void create_rejectsUnknownKind() {
    assertThrows(
        IllegalArgumentException.class,
        () -> service.create(new NewCredentialRequest("MAGIC", "global", "x", "x")));
  }

  @Test
  void create_failsClosedWhenNoKeyConfigured() {
    CredentialsServiceImpl noKey =
        new CredentialsServiceImpl(new DbEnvelopeBackend(stores, NULL_KEY));
    assertThrows(
        SecretCipher.CipherException.class,
        () -> noKey.create(new NewCredentialRequest(Credential.KIND_STRING, "global", "tok", "x")));
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  private static byte[] freshAesKey() {
    // 32 zero bytes is a valid AES-256 key for unit tests; SecretCipher only checks the length.
    return new byte[32];
  }
}
