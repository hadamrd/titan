package io.adaptiq.titan.flow.crypto;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Authenticated symmetric encryption for secrets that must survive at rest — design/39 §3.1.
 *
 * <p><strong>Why this exists.</strong> A Titan credential binding is resolved on the controller and
 * the resolved secret is delivered to the worker inside the {@code EXECUTE_COMMAND} task payload
 * (design/39 §3, §4). That payload is <em>persisted</em> — it is a row in {@code
 * titan.task_queue.payload_json}. A plaintext secret in a durable queue row would be readable by
 * anyone with database access, a backup, or a replication stream. So the credential bundle is
 * sealed with this cipher before it touches the database; the row holds ciphertext only.
 *
 * <p><strong>The construction.</strong> AES-256 in GCM mode — authenticated encryption: GCM's
 * 128-bit tag means a tampered or truncated ciphertext fails to decrypt rather than yielding
 * garbage. Every {@link #seal} draws a fresh random 96-bit nonce (the size NIST SP 800-38D
 * recommends for GCM) and prepends it to the output, so the same key never encrypts two payloads
 * under the same nonce. Additional authenticated data (AAD) is folded in but not encrypted: the
 * caller binds a sealed blob to its context (e.g. {@code buildId:nodeId}) so a blob lifted from one
 * task fails authentication in another.
 *
 * <p><strong>The key.</strong> A 256-bit key, the same on the controller and every worker,
 * provisioned out-of-band (Infisical — the project secret manager) as {@code TITAN_CREDENTIAL_KEY}
 * and rotatable. The key lives in process configuration, never in the database — ciphertext and key
 * are separate stores, the property that makes at-rest encryption meaningful.
 *
 * <p>Wire format of a sealed value: {@code base64( nonce[12] || ciphertext || tag[16] )}.
 *
 * <p>Stateless and thread-safe — every call creates its own {@link Cipher}.
 */
public final class SecretCipher {

  /** AES-256 — the key length this cipher requires, in bytes. */
  public static final int KEY_LENGTH_BYTES = 32;

  private static final String TRANSFORMATION = "AES/GCM/NoPadding";
  private static final int NONCE_LENGTH_BYTES = 12;
  private static final int TAG_LENGTH_BITS = 128;
  private static final SecureRandom RANDOM = new SecureRandom();

  private SecretCipher() {}

  /** Thrown when sealing or unsealing fails — a bad key, a tampered blob, a wrong AAD. */
  public static final class CipherException extends RuntimeException {
    public CipherException(@NonNull String message, @NonNull Throwable cause) {
      super(message, cause);
    }

    public CipherException(@NonNull String message) {
      super(message);
    }
  }

  /**
   * Seal {@code plaintext} into a self-describing base64 blob — {@code base64(nonce || ciphertext
   * || tag)}.
   *
   * @param plaintext the value to protect (UTF-8)
   * @param key a {@value #KEY_LENGTH_BYTES}-byte AES-256 key
   * @param aad context bound into the authentication tag but not encrypted; the same value must be
   *     supplied to {@link #unseal}. Never {@code null} — pass {@code ""} for no binding, though
   *     binding to the task context is strongly preferred.
   * @return the sealed blob, safe to persist
   */
  @NonNull
  public static String seal(@NonNull String plaintext, @NonNull byte[] key, @NonNull String aad) {
    requireKey(key);
    try {
      byte[] nonce = new byte[NONCE_LENGTH_BYTES];
      RANDOM.nextBytes(nonce);
      Cipher cipher = Cipher.getInstance(TRANSFORMATION);
      cipher.init(
          Cipher.ENCRYPT_MODE,
          new SecretKeySpec(key, "AES"),
          new GCMParameterSpec(TAG_LENGTH_BITS, nonce));
      cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
      byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
      byte[] out = new byte[nonce.length + ciphertext.length];
      System.arraycopy(nonce, 0, out, 0, nonce.length);
      System.arraycopy(ciphertext, 0, out, nonce.length, ciphertext.length);
      return Base64.getEncoder().encodeToString(out);
    } catch (GeneralSecurityException e) {
      throw new CipherException("failed to seal secret payload", e);
    }
  }

  /**
   * Unseal a blob produced by {@link #seal}. Fails — rather than returning corrupt data — if the
   * blob was tampered with, truncated, sealed under a different key, or sealed with a different
   * {@code aad}.
   *
   * @param sealed the base64 blob from {@link #seal}
   * @param key the {@value #KEY_LENGTH_BYTES}-byte AES-256 key the blob was sealed with
   * @param aad the exact context passed to {@link #seal}
   * @return the recovered plaintext
   * @throws CipherException on any authentication or format failure
   */
  @NonNull
  public static String unseal(@NonNull String sealed, @NonNull byte[] key, @NonNull String aad) {
    requireKey(key);
    byte[] blob;
    try {
      blob = Base64.getDecoder().decode(sealed);
    } catch (IllegalArgumentException e) {
      throw new CipherException("sealed payload is not valid base64", e);
    }
    if (blob.length <= NONCE_LENGTH_BYTES) {
      throw new CipherException("sealed payload is too short to contain a nonce");
    }
    try {
      byte[] nonce = Arrays.copyOfRange(blob, 0, NONCE_LENGTH_BYTES);
      Cipher cipher = Cipher.getInstance(TRANSFORMATION);
      cipher.init(
          Cipher.DECRYPT_MODE,
          new SecretKeySpec(key, "AES"),
          new GCMParameterSpec(TAG_LENGTH_BITS, nonce));
      cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
      byte[] plaintext = cipher.doFinal(blob, NONCE_LENGTH_BYTES, blob.length - NONCE_LENGTH_BYTES);
      return new String(plaintext, StandardCharsets.UTF_8);
    } catch (GeneralSecurityException e) {
      // AEADBadTagException lands here — a tampered blob, wrong key, or wrong AAD.
      throw new CipherException(
          "failed to unseal secret payload — wrong key, wrong " + "context, or tampered ciphertext",
          e);
    }
  }

  /**
   * Decode and validate a base64-encoded AES-256 key — the form {@code TITAN_CREDENTIAL_KEY} takes
   * in configuration.
   *
   * @throws CipherException if the value is not base64 or not exactly {@value #KEY_LENGTH_BYTES}
   *     bytes
   */
  @NonNull
  public static byte[] decodeKey(@NonNull String base64Key) {
    byte[] key;
    try {
      key = Base64.getDecoder().decode(base64Key.trim());
    } catch (IllegalArgumentException e) {
      throw new CipherException("credential key is not valid base64", e);
    }
    requireKey(key);
    return key;
  }

  /**
   * Generate a fresh random AES-256 key, base64-encoded — for provisioning and for tests. The
   * controller and every worker must then share this one value.
   */
  @NonNull
  public static String newKeyBase64() {
    byte[] key = new byte[KEY_LENGTH_BYTES];
    RANDOM.nextBytes(key);
    return Base64.getEncoder().encodeToString(key);
  }

  private static void requireKey(@NonNull byte[] key) {
    if (key.length != KEY_LENGTH_BYTES) {
      throw new CipherException(
          "credential key must be " + KEY_LENGTH_BYTES + " bytes (AES-256); got " + key.length);
    }
  }
}
