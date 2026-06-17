package io.adaptiq.titan.credentials;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.adaptiq.titan.flow.crypto.SecretCipher;
import java.util.Base64;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link EnvKeyProvider}.
 *
 * <p>Tests use the {@code titan.kek} system-property fallback rather than environment variables —
 * JVM-internal env-var mutation is not portable, and the production code paths handle both sources
 * identically (env preferred, sys-property as fallback).
 */
class EnvKeyProviderTest {

  private String savedProperty;

  @BeforeEach
  void saveAndClear() {
    savedProperty = System.getProperty(EnvKeyProvider.SYSTEM_PROPERTY);
    System.clearProperty(EnvKeyProvider.SYSTEM_PROPERTY);
  }

  @AfterEach
  void restore() {
    if (savedProperty == null) {
      System.clearProperty(EnvKeyProvider.SYSTEM_PROPERTY);
    } else {
      System.setProperty(EnvKeyProvider.SYSTEM_PROPERTY, savedProperty);
    }
  }

  @Test
  void credentialKey_returnsDecodedBytes_whenSystemPropertySet() {
    byte[] raw = new byte[SecretCipher.KEY_LENGTH_BYTES];
    for (int i = 0; i < raw.length; i++) {
      raw[i] = (byte) i;
    }
    System.setProperty(EnvKeyProvider.SYSTEM_PROPERTY, Base64.getEncoder().encodeToString(raw));

    byte[] out = new EnvKeyProvider().credentialKey();

    assertArrayEquals(raw, out, "decoded KEK must match original bytes");
  }

  @Test
  void credentialKey_returnsNull_whenUnset() {
    // System.getenv(TITAN_KEK) is presumed unset in CI; if a developer has it set locally this
    // test is correctly skipped via the early-return — we still assert *something* non-toxic.
    if (System.getenv(EnvKeyProvider.ENV_VAR) != null
        && !System.getenv(EnvKeyProvider.ENV_VAR).isBlank()) {
      // Developer has TITAN_KEK exported — provider will read the env, not null. Skip.
      return;
    }
    assertNull(new EnvKeyProvider().credentialKey());
  }

  @Test
  void credentialKey_returnsNull_whenBlank() {
    System.setProperty(EnvKeyProvider.SYSTEM_PROPERTY, "   ");
    assertNull(new EnvKeyProvider().credentialKey());
  }

  @Test
  void credentialKey_returnsNull_whenInvalidBase64() {
    // A misconfigured TITAN_KEK is a deployment bug — the provider logs and fails closed by
    // returning null so the chain falls through rather than crashing the request.
    System.setProperty(EnvKeyProvider.SYSTEM_PROPERTY, "!!!not-base64!!!");
    assertNull(new EnvKeyProvider().credentialKey());
  }

  @Test
  void credentialKey_returnsNull_whenWrongLength() {
    // A correctly-base64-encoded but wrong-length key — SecretCipher.decodeKey will throw and
    // EnvKeyProvider must swallow + return null (fail closed).
    System.setProperty(
        EnvKeyProvider.SYSTEM_PROPERTY,
        Base64.getEncoder().encodeToString(new byte[16])); // 128-bit, not 256
    assertNull(new EnvKeyProvider().credentialKey());
  }

  @Test
  void describe_isNotSensitive() {
    String d = new EnvKeyProvider().describe();
    assertEquals("env:" + EnvKeyProvider.ENV_VAR, d);
  }
}
