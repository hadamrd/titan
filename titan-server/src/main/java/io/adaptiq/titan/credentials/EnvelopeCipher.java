package io.adaptiq.titan.credentials;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.flow.crypto.SecretCipher;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Envelope encryption primitives for the Titan secrets store.
 *
 * <p><strong>The model.</strong> Every secret carries its own randomly generated 256-bit
 * <em>data-encryption key</em> (DEK). The DEK seals the secret value with AES-256-GCM. The DEK
 * itself is then sealed (<em>wrapped</em>) with the operator's <em>key-encryption key</em> (KEK)
 * supplied by {@link io.adaptiq.titan.flow.crypto.CredentialKeyProvider}. Two layers, two
 * independent keys per secret.
 *
 * <p><strong>Why.</strong> Rotating the KEK requires re-wrapping the small DEK blobs only — never
 * the secret payload, which may be megabytes for {@code FILE} credentials. Compromise of one DEK
 * exposes one secret; compromise of the KEK alone exposes nothing (the wrapped DEKs are AES-GCM
 * blobs the KEK would need to unseal).
 *
 * <p><strong>AAD binding.</strong> The DEK is sealed under AAD {@code "dek:<credId>:v<kekVersion>"}
 * so a wrapped DEK from credential A's row cannot be lifted into credential B's row, nor can a blob
 * from KEK version 1 be replayed as if it were under version 2. The payload is sealed under AAD
 * {@code "credentials:<credId>"} — unchanged from the pre-envelope design, so the integrity binding
 * to the row's identity is preserved.
 *
 * <p>Pure functions, no I/O, thread-safe.
 */
public final class EnvelopeCipher {

  /** AES-256 keys are 32 bytes. */
  public static final int DEK_LENGTH_BYTES = 32;

  private static final SecureRandom RNG = new SecureRandom();

  private EnvelopeCipher() {}

  /** Generate a fresh, cryptographically random DEK. */
  @NonNull
  public static byte[] newDek() {
    byte[] dek = new byte[DEK_LENGTH_BYTES];
    RNG.nextBytes(dek);
    return dek;
  }

  /**
   * Seal a plaintext under a per-secret DEK and wrap the DEK under the KEK. Returns the pair as a
   * single {@link Sealed} value — neither member is meaningful on its own.
   */
  @NonNull
  public static Sealed seal(
      @NonNull String plaintext,
      @NonNull byte[] kek,
      int kekVersion,
      @NonNull String payloadAad,
      long credentialId) {
    byte[] dek = newDek();
    try {
      String sealedValue = SecretCipher.seal(plaintext, dek, payloadAad);
      String wrappedDek = wrapDek(dek, kek, kekVersion, credentialId);
      return new Sealed(sealedValue, wrappedDek, kekVersion);
    } finally {
      // Zeroise the DEK in memory the moment we are done with it — it has been
      // both sealed into the payload and wrapped under the KEK, so there is no
      // further use for the plaintext key in this JVM.
      java.util.Arrays.fill(dek, (byte) 0);
    }
  }

  /** Inverse of {@link #seal}: unwrap the DEK with the KEK, then unseal the payload. */
  @NonNull
  public static String unseal(
      @NonNull String sealedValue,
      @NonNull String wrappedDek,
      @NonNull byte[] kek,
      int kekVersion,
      @NonNull String payloadAad,
      long credentialId) {
    byte[] dek = unwrapDek(wrappedDek, kek, kekVersion, credentialId);
    try {
      return SecretCipher.unseal(sealedValue, dek, payloadAad);
    } finally {
      java.util.Arrays.fill(dek, (byte) 0);
    }
  }

  /**
   * Re-wrap an existing DEK under a new KEK. Used by KEK rotation: we never touch the payload's
   * sealed_value — only the small wrapped-DEK blob is re-encrypted. This is the central reason
   * envelope encryption beats single-key sealing for operations.
   */
  @NonNull
  public static String rewrapDek(
      @NonNull String wrappedDek,
      @NonNull byte[] oldKek,
      int oldKekVersion,
      @NonNull byte[] newKek,
      int newKekVersion,
      long credentialId) {
    byte[] dek = unwrapDek(wrappedDek, oldKek, oldKekVersion, credentialId);
    try {
      return wrapDek(dek, newKek, newKekVersion, credentialId);
    } finally {
      java.util.Arrays.fill(dek, (byte) 0);
    }
  }

  // ── internals ────────────────────────────────────────────────────────────────

  @NonNull
  private static String wrapDek(byte[] dek, byte[] kek, int kekVersion, long credentialId) {
    // SecretCipher.seal expects the secret as a String. The DEK is raw bytes — base64-encode
    // first so we can round-trip through the existing GCM helper without adding a parallel
    // byte-API. The base64 form is purely a wire representation; only the wrapped ciphertext is
    // ever written to the database.
    String dekB64 = Base64.getEncoder().encodeToString(dek);
    return SecretCipher.seal(dekB64, kek, dekAad(credentialId, kekVersion));
  }

  @NonNull
  private static byte[] unwrapDek(
      @NonNull String wrappedDek, @NonNull byte[] kek, int kekVersion, long credentialId) {
    String dekB64 = SecretCipher.unseal(wrappedDek, kek, dekAad(credentialId, kekVersion));
    return Base64.getDecoder().decode(dekB64);
  }

  @NonNull
  private static String dekAad(long credentialId, int kekVersion) {
    return "dek:" + credentialId + ":v" + kekVersion;
  }

  /**
   * Output of {@link #seal}. {@code sealedValue} is the payload ciphertext; {@code wrappedDek} is
   * the DEK encrypted under the KEK. Both are required for {@link #unseal}; neither carries any
   * useful information on its own.
   */
  public record Sealed(@NonNull String sealedValue, @NonNull String wrappedDek, int kekVersion) {}
}
