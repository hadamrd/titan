package io.adaptiq.titan.flow.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link SecretCipher} — the AES-256-GCM seal protecting credential payloads at rest
 * (design/39 §3.1). The properties under test are the ones the credential security depends on: a
 * round-trip recovers the secret; the ciphertext leaks neither the plaintext nor a repeat under a
 * fixed key; and tampering, a wrong key, or a wrong context all fail loudly rather than yielding
 * corrupt data.
 */
class SecretCipherTest {

  private final byte[] key = SecretCipher.decodeKey(SecretCipher.newKeyBase64());

  @Test
  void sealThenUnsealRecoversThePlaintext() {
    String secret = "s3cr3t-registry-password";
    String sealed = SecretCipher.seal(secret, key, "build-42:deploy-s0");
    assertEquals(secret, SecretCipher.unseal(sealed, key, "build-42:deploy-s0"));
  }

  @Test
  void theSealedBlobDoesNotContainThePlaintext() {
    String secret = "PLAINTEXT-SECRET-VALUE";
    String sealed = SecretCipher.seal(secret, key, "ctx");
    assertTrue(sealed.length() > 0);
    assertEquals(
        -1,
        new String(Base64.getDecoder().decode(sealed), java.nio.charset.StandardCharsets.ISO_8859_1)
            .indexOf(secret));
  }

  @Test
  void sealingTheSameValueTwiceGivesDifferentBlobs() {
    // A fresh nonce per seal — no deterministic ciphertext, no repeat to correlate.
    String sealedA = SecretCipher.seal("same", key, "ctx");
    String sealedB = SecretCipher.seal("same", key, "ctx");
    assertNotEquals(sealedA, sealedB);
    assertEquals("same", SecretCipher.unseal(sealedA, key, "ctx"));
    assertEquals("same", SecretCipher.unseal(sealedB, key, "ctx"));
  }

  @Test
  void unsealWithTheWrongKeyFails() {
    String sealed = SecretCipher.seal("secret", key, "ctx");
    byte[] otherKey = SecretCipher.decodeKey(SecretCipher.newKeyBase64());
    assertThrows(
        SecretCipher.CipherException.class, () -> SecretCipher.unseal(sealed, otherKey, "ctx"));
  }

  @Test
  void unsealWithTheWrongAadFails() {
    // AAD binds a blob to its task context — a blob lifted into another task is rejected.
    String sealed = SecretCipher.seal("secret", key, "build-42:deploy-s0");
    assertThrows(
        SecretCipher.CipherException.class,
        () -> SecretCipher.unseal(sealed, key, "build-99:other-s0"));
  }

  @Test
  void unsealOfATamperedBlobFails() {
    String sealed = SecretCipher.seal("secret", key, "ctx");
    byte[] raw = Base64.getDecoder().decode(sealed);
    raw[raw.length - 1] ^= 0x01; // flip a bit in the GCM tag
    String tampered = Base64.getEncoder().encodeToString(raw);
    assertThrows(
        SecretCipher.CipherException.class, () -> SecretCipher.unseal(tampered, key, "ctx"));
  }

  @Test
  void unsealRejectsGarbageInput() {
    assertThrows(
        SecretCipher.CipherException.class,
        () -> SecretCipher.unseal("not-base64-!!!", key, "ctx"));
    assertThrows(
        SecretCipher.CipherException.class,
        () -> SecretCipher.unseal("YWJj", key, "ctx")); // valid base64, too short
  }

  @Test
  void decodeKeyRejectsAWrongLengthKey() {
    String shortKey = Base64.getEncoder().encodeToString(new byte[16]); // AES-128, not 256
    assertThrows(SecretCipher.CipherException.class, () -> SecretCipher.decodeKey(shortKey));
  }

  @Test
  void generatedKeysAreDistinctAndCorrectLength() {
    assertNotEquals(SecretCipher.newKeyBase64(), SecretCipher.newKeyBase64());
    assertEquals(
        SecretCipher.KEY_LENGTH_BYTES, SecretCipher.decodeKey(SecretCipher.newKeyBase64()).length);
  }
}
