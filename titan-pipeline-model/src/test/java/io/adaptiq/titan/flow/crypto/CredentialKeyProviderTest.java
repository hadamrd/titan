package io.adaptiq.titan.flow.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for the {@link CredentialKeyProvider} SPI and its built-in {@link EnvCredentialKeyProvider}
 * (design/39 §3.1). The behaviour that matters: a configured key is decoded to a usable AES-256
 * key, and an <em>unconfigured</em> provider returns {@code null} so the caller can fail closed
 * rather than dispatch an unencrypted credential payload.
 */
class CredentialKeyProviderTest {

  @AfterEach
  void clearProperty() {
    System.clearProperty(EnvCredentialKeyProvider.SYSTEM_PROPERTY);
  }

  @Test
  void envProviderReturnsTheConfiguredKey() {
    String key = SecretCipher.newKeyBase64();
    System.setProperty(EnvCredentialKeyProvider.SYSTEM_PROPERTY, key);

    byte[] resolved = new EnvCredentialKeyProvider().credentialKey();
    assertNotNull(resolved);
    assertEquals(SecretCipher.KEY_LENGTH_BYTES, resolved.length);
  }

  @Test
  void envProviderReturnsNullWhenUnconfigured() {
    // No TITAN_CREDENTIAL_KEY env var and no system property — the caller must fail closed.
    assertNull(new EnvCredentialKeyProvider().credentialKey());
  }

  @Test
  void envProviderDescribesItsSourceWithoutLeakingTheKey() {
    assertEquals("env:TITAN_CREDENTIAL_KEY", new EnvCredentialKeyProvider().describe());
  }

  @Test
  void activeFallsBackToTheEnvProviderWhenNoServiceIsRegistered() {
    // No ServiceLoader-contributed provider on the test classpath — the env default is used.
    CredentialKeyProvider active = CredentialKeyProvider.active();
    assertNotNull(active);
    assertEquals(new EnvCredentialKeyProvider().describe(), active.describe());
  }

  @Test
  void aKeyRoundTripsThroughTheProviderIntoTheCipher() {
    System.setProperty(EnvCredentialKeyProvider.SYSTEM_PROPERTY, SecretCipher.newKeyBase64());
    byte[] key = CredentialKeyProvider.active().credentialKey();
    assertNotNull(key);
    String sealed = SecretCipher.seal("secret", key, "build-1:step-1");
    assertEquals("secret", SecretCipher.unseal(sealed, key, "build-1:step-1"));
  }

  // ── the provider chain (ChainedCredentialKeyProvider) ────────────────────
  // A configured-but-keyless provider returns null; it must NOT shadow a working link.

  @Test
  void chainFallsThroughANullLinkToTheNextProvider() {
    byte[] key = new byte[SecretCipher.KEY_LENGTH_BYTES];
    CredentialKeyProvider chain =
        new ChainedCredentialKeyProvider(
            List.of(provider(null, "kms:keyless"), provider(key, "env:fallback")));
    assertNotNull(chain.credentialKey(), "a null first link must fall through to the next");
    assertEquals(SecretCipher.KEY_LENGTH_BYTES, chain.credentialKey().length);
  }

  @Test
  void chainSkipsAThrowingProvider() {
    byte[] key = new byte[SecretCipher.KEY_LENGTH_BYTES];
    CredentialKeyProvider chain =
        new ChainedCredentialKeyProvider(
            List.of(throwingProvider("kms:broken"), provider(key, "env:fallback")));
    // A provider that blows up must not block a working fallback link.
    assertNotNull(chain.credentialKey());
  }

  @Test
  void chainReturnsNullWhenNoLinkYieldsAKey() {
    CredentialKeyProvider chain =
        new ChainedCredentialKeyProvider(
            List.of(provider(null, "kms:keyless"), provider(null, "env:unset")));
    // No link has a key — the caller must fail closed (design/39 §5).
    assertNull(chain.credentialKey());
  }

  @Test
  void chainDescribeListsEveryLink() {
    CredentialKeyProvider chain =
        new ChainedCredentialKeyProvider(
            List.of(provider(null, "kms:keyless"), provider(null, "env:TITAN_CREDENTIAL_KEY")));
    assertEquals("chain[kms:keyless -> env:TITAN_CREDENTIAL_KEY]", chain.describe());
  }

  /**
   * A test provider that yields {@code key} (possibly {@code null}) and describes as {@code desc}.
   */
  private static CredentialKeyProvider provider(byte[] key, String desc) {
    return new CredentialKeyProvider() {
      @Override
      public byte[] credentialKey() {
        return key;
      }

      @Override
      public String describe() {
        return desc;
      }
    };
  }

  /** A test provider whose {@code credentialKey()} throws — a misconfigured link. */
  private static CredentialKeyProvider throwingProvider(String desc) {
    return new CredentialKeyProvider() {
      @Override
      public byte[] credentialKey() {
        throw new IllegalStateException("provider " + desc + " is misconfigured");
      }

      @Override
      public String describe() {
        return desc;
      }
    };
  }
}
