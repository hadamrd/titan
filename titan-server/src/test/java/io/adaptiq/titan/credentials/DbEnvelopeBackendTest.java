package io.adaptiq.titan.credentials;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import edu.umd.cs.findbugs.annotations.Nullable;
import io.adaptiq.titan.flow.crypto.CredentialKeyProvider;
import io.adaptiq.titan.flow.crypto.SecretCipher;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.CredentialRow;
import java.lang.reflect.Method;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Adversarial tests for {@link DbEnvelopeBackend} — envelope encryption invariants + KEK rotation.
 *
 * <p>Where {@link CredentialsServiceImplTest} asserts the public service surface, this class pokes
 * the envelope-encryption primitives directly: every row must carry its own DEK, KEK rotation must
 * re-wrap that DEK without re-encrypting the payload, and a row sealed under KEK v1 must be
 * unsealable after a rotation to KEK v2 because the provider keeps v1 accessible during the
 * rotation pass.
 */
class DbEnvelopeBackendTest {

  private TitanStores stores;

  @BeforeEach
  void setUp() throws Exception {
    Class<?> fake = Class.forName("io.adaptiq.titan.api.FakeTitanStores");
    Method create = fake.getDeclaredMethod("create");
    create.setAccessible(true);
    stores = (TitanStores) create.invoke(null);
  }

  // ── envelope shape ─────────────────────────────────────────────────────────

  @Test
  void everySecretHasItsOwnDek() {
    VersionedKeyProvider kp = new VersionedKeyProvider(1, freshAes());
    DbEnvelopeBackend backend = new DbEnvelopeBackend(stores, kp);

    Credential a = backend.create(new NewCredentialRequest(Credential.KIND_STRING, "g", "a", "A"));
    Credential b = backend.create(new NewCredentialRequest(Credential.KIND_STRING, "g", "b", "B"));

    CredentialRow ra = stores.credentials().findById(a.id()).orElseThrow();
    CredentialRow rb = stores.credentials().findById(b.id()).orElseThrow();

    // Two distinct rows must carry two distinct wrapped DEK blobs even when sealed under the
    // same KEK. AES-GCM with a fresh random IV would make this true for the payload too — but
    // the test that ACTUALLY matters is the wrapped DEK: that's the unique-per-secret key.
    assertNotNull(ra.wrappedDek);
    assertNotNull(rb.wrappedDek);
    assertNotEquals(ra.wrappedDek, rb.wrappedDek);
    assertEquals(1, ra.kekVersion);
    assertEquals(1, rb.kekVersion);
  }

  // ── KEK rotation ───────────────────────────────────────────────────────────

  @Test
  void rotateKek_reWrapsExistingRowsAndKeepsPayloadDecryptable() {
    byte[] kekV1 = freshAes();
    byte[] kekV2 = freshAes();
    VersionedKeyProvider kp = new VersionedKeyProvider(1, kekV1);
    DbEnvelopeBackend backend = new DbEnvelopeBackend(stores, kp);

    Credential a =
        backend.create(new NewCredentialRequest(Credential.KIND_STRING, "g", "a", "secret-a"));
    Credential b =
        backend.create(new NewCredentialRequest(Credential.KIND_STRING, "g", "b", "secret-b"));

    String aPayloadBefore = stores.credentials().findById(a.id()).orElseThrow().sealedValue;
    String aWrappedBefore = stores.credentials().findById(a.id()).orElseThrow().wrappedDek;

    // Promote KEK v2; provider must still vend v1 to support the rotation pass.
    kp.installNewActive(2, kekV2);

    int rewrapped = backend.rotateKek();
    assertEquals(2, rewrapped);

    CredentialRow aAfter = stores.credentials().findById(a.id()).orElseThrow();
    // Payload bytes are UNTOUCHED — that's the central efficiency claim of envelope encryption.
    assertEquals(aPayloadBefore, aAfter.sealedValue);
    // Wrapped DEK is different — re-encrypted under the new KEK.
    assertNotEquals(aWrappedBefore, aAfter.wrappedDek);
    assertEquals(2, aAfter.kekVersion);

    // And the secrets still resolve under the new active KEK.
    assertEquals("secret-a", backend.resolvePlaintext("g", "a").orElseThrow());
    assertEquals("secret-b", backend.resolvePlaintext("g", "b").orElseThrow());

    // After v1 is withdrawn, a fresh rotation is a no-op (everything is on v2 already).
    kp.withdrawVersion(1);
    assertEquals(0, backend.rotateKek());
  }

  @Test
  void rotateKek_skipsRowsWhosePriorKekIsNoLongerAvailable() {
    byte[] kekV1 = freshAes();
    byte[] kekV2 = freshAes();
    VersionedKeyProvider kp = new VersionedKeyProvider(1, kekV1);
    DbEnvelopeBackend backend = new DbEnvelopeBackend(stores, kp);

    backend.create(new NewCredentialRequest(Credential.KIND_STRING, "g", "x", "v"));

    // Operator withdraws v1 BEFORE rotating — the row is now orphaned. The backend must skip
    // it rather than throwing; rotation is best-effort across the table.
    kp.installNewActive(2, kekV2);
    kp.withdrawVersion(1);

    int rewrapped = backend.rotateKek();
    assertEquals(0, rewrapped);
  }

  // ── fail-closed ────────────────────────────────────────────────────────────

  @Test
  void create_failsClosedWhenNoActiveKek() {
    VersionedKeyProvider kp = new VersionedKeyProvider(1, null);
    DbEnvelopeBackend backend = new DbEnvelopeBackend(stores, kp);

    assertThrows(
        SecretCipher.CipherException.class,
        () -> backend.create(new NewCredentialRequest(Credential.KIND_STRING, "g", "k", "v")));
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  private static byte[] freshAes() {
    byte[] k = new byte[SecretCipher.KEY_LENGTH_BYTES];
    new SecureRandom().nextBytes(k);
    return k;
  }

  /**
   * A {@link CredentialKeyProvider} that supports the version surface introduced for envelope
   * encryption: the active version + key, plus a history map so a rotation pass can resolve the
   * prior KEK to unwrap rows.
   */
  static final class VersionedKeyProvider implements CredentialKeyProvider {
    private int activeVersion;
    @Nullable private byte[] activeKey;
    private final Map<Integer, byte[]> history = new HashMap<>();

    VersionedKeyProvider(int version, @Nullable byte[] key) {
      this.activeVersion = version;
      this.activeKey = key;
      if (key != null) history.put(version, key);
    }

    void installNewActive(int version, byte[] key) {
      this.activeVersion = version;
      this.activeKey = key;
      history.put(version, key);
    }

    void withdrawVersion(int version) {
      history.remove(version);
    }

    @Override
    @Nullable
    public byte[] credentialKey() {
      return activeKey == null ? null : activeKey.clone();
    }

    @Override
    public String describe() {
      return "test:versioned";
    }

    @Override
    public int credentialKeyVersion() {
      return activeVersion;
    }

    @Override
    @Nullable
    public byte[] credentialKeyByVersion(int version) {
      byte[] k = history.get(version);
      return k == null ? null : k.clone();
    }
  }
}
