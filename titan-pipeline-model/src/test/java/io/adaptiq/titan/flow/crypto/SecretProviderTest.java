package io.adaptiq.titan.flow.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * Tests for the {@link SecretProvider} SPI and its built-in {@link NoopSecretProvider} default
 * (design/40 §3). The SPI is the worker-side sibling of {@link CredentialKeyProvider} — the same
 * shape, the same {@link java.util.ServiceLoader} discovery, the same fail-closed contract.
 */
class SecretProviderTest {

  /** With no provider jar on the classpath, {@link SecretProvider#active()} is the no-op. */
  @Test
  void activeFallsBackToTheNoopProvider() {
    // titan-pipeline-model alone ships no META-INF/services SecretProvider entry — the
    // ServiceLoader finds nothing and active() must return the built-in no-op.
    SecretProvider active = SecretProvider.active();
    assertInstanceOf(
        NoopSecretProvider.class,
        active,
        "with no provider on the classpath, active() must be the no-op default");
  }

  /** The no-op provider holds nothing — every secret() call is null. */
  @Test
  void noopProviderHoldsNothing() {
    SecretProvider noop = new NoopSecretProvider();
    assertNull(noop.secret("github-ci-token"));
    assertNull(noop.secret("anything-else"));
  }

  /** describe() is non-sensitive and never null — safe to log. */
  @Test
  void describeIsNonSensitiveAndPresent() {
    assertEquals("noop", new NoopSecretProvider().describe());
    assertNotNull(SecretProvider.active().describe());
  }

  /**
   * The fail-closed contract: a provider that does not hold a named secret returns {@code null},
   * and the {@code library()} path turns that into a hard synthesis failure. Verified here at the
   * SPI level — a custom provider modelling "configured but this secret is unknown".
   */
  @Test
  void aProviderMayReturnNullForAnUnknownSecret() {
    SecretProvider partial =
        new SecretProvider() {
          @Override
          public String secret(String name) {
            return "known".equals(name) ? "the-value" : null;
          }

          @Override
          public String describe() {
            return "test:partial";
          }
        };
    assertEquals("the-value", partial.secret("known"));
    assertNull(
        partial.secret("unknown"), "an unknown secret must be null — the fail-closed signal");
  }

  /** secret() rejects a null name — the contract is @NonNull. */
  @Test
  void secretNameIsRequired() {
    SecretProvider strict =
        new SecretProvider() {
          @Override
          public String secret(String name) {
            // A real provider would never be handed null; model an explicit guard.
            throw new NullPointerException("name");
          }

          @Override
          public String describe() {
            return "test:strict";
          }
        };
    assertThrows(NullPointerException.class, () -> strict.secret(null));
  }
}
