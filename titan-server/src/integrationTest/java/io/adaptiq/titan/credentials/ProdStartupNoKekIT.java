package io.adaptiq.titan.credentials;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.adaptiq.titan.boot.CredentialKeyProviderProducer;
import io.adaptiq.titan.flow.crypto.CredentialKeyProvider;
import io.adaptiq.titan.flow.crypto.EnvCredentialKeyProvider;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Boundary IT for the boot-time fail-fast added by issue #1076 (V1-bar #5).
 *
 * <p>The acceptance criterion is: "titan-server startup fails fast when no KEK configured AND
 * profile != dev." We exercise the producer directly rather than spinning up a full
 * {@code @QuarkusTest} because a Quarkus test that boots in TEST mode always satisfies {@link
 * DevAutoKeyProvider#isDevProfile()} (TEST counts as dev for the gate, by design — otherwise every
 * existing IT would refuse to boot). The producer-level test is the right granularity for the
 * non-dev / no-KEK refusal: it pins the exact contract a real prod boot will see, namely "no dev
 * bean available + no ServiceLoader provider + no TITAN_CREDENTIAL_KEY env → ConfigException".
 *
 * <p>Lives under {@code src/integrationTest/} because it sets the JVM-wide {@code titan.profile}
 * sysprop to {@code prod}; running it under {@code src/test/} alongside the other unit tests would
 * risk leaking that prod-profile state into peer tests if a teardown were ever skipped. The IT
 * sandbox runs in its own forked JVM so leakage is contained.
 */
class ProdStartupNoKekIT {

  private String savedProfile;
  private String savedQuarkus;
  private String savedAllow;
  private String savedFlag;
  private String savedKey;

  @BeforeEach
  void setup() {
    savedProfile = System.getProperty(DevAutoKeyProvider.PROFILE_SYSPROP);
    savedQuarkus = System.getProperty(DevAutoKeyProvider.QUARKUS_PROFILE_SYSPROP);
    savedAllow = System.getProperty(DevAutoKeyProvider.ALLOW_SYSPROP);
    savedFlag = System.getProperty(DevAutoKeyProvider.FLAG_SYSPROP);
    savedKey = System.getProperty("titan.credentials.key");
    // Make sure no profile / allow / key state leaks in from a peer test.
    System.clearProperty(DevAutoKeyProvider.PROFILE_SYSPROP);
    System.clearProperty(DevAutoKeyProvider.QUARKUS_PROFILE_SYSPROP);
    System.clearProperty(DevAutoKeyProvider.ALLOW_SYSPROP);
    System.clearProperty(DevAutoKeyProvider.FLAG_SYSPROP);
    System.clearProperty("titan.credentials.key");
  }

  @AfterEach
  void teardown() {
    restore(DevAutoKeyProvider.PROFILE_SYSPROP, savedProfile);
    restore(DevAutoKeyProvider.QUARKUS_PROFILE_SYSPROP, savedQuarkus);
    restore(DevAutoKeyProvider.ALLOW_SYSPROP, savedAllow);
    restore(DevAutoKeyProvider.FLAG_SYSPROP, savedFlag);
    restore("titan.credentials.key", savedKey);
  }

  private static void restore(String key, String value) {
    if (value == null) {
      System.clearProperty(key);
    } else {
      System.setProperty(key, value);
    }
  }

  @Test
  void prodProfile_noKekConfigured_producerThrowsConfigException() {
    System.setProperty(DevAutoKeyProvider.PROFILE_SYSPROP, "prod");

    // Stand the producer up by hand with a permanently-unsatisfied Instance — the same shape CDI
    // delivers in prod when DevAutoKeyProvider construction was refused.
    CredentialKeyProviderProducer producer =
        new CredentialKeyProviderProducer(new UnsatisfiedInstance<>());

    ConfigException ex = assertThrows(ConfigException.class, producer::credentialKeyProvider);
    assertTrue(
        ex.getMessage().contains("docs/operations/runbooks/kek-config.md"),
        "operator must see the runbook pointer; got: " + ex.getMessage());
    assertTrue(
        ex.getMessage().contains("titan.profile"),
        "error must name the offending config knob; got: " + ex.getMessage());
    assertTrue(
        ex.getMessage().contains("KMS-backed")
            || ex.getMessage().contains("titan-keyprovider-infisical"),
        "error must point at the prescribed prod fix; got: " + ex.getMessage());
  }

  @Test
  void prodProfile_envKekConfigured_producerSucceeds() {
    // The escape hatch: a prod deployment that does have TITAN_CREDENTIAL_KEY env (the
    // EnvCredentialKeyProvider tail of the chain) must NOT trip the fail-fast guard. This is the
    // safety boundary — we are refusing only the no-KEK-at-all case.
    System.setProperty(DevAutoKeyProvider.PROFILE_SYSPROP, "prod");
    // The EnvCredentialKeyProvider reads the sysprop fallback when the env var is unset (see its
    // own contract). A 32-byte AES-256 key encoded as base64 = 44 chars.
    System.setProperty(EnvCredentialKeyProvider.SYSTEM_PROPERTY, base64Aes256());

    CredentialKeyProviderProducer producer =
        new CredentialKeyProviderProducer(new UnsatisfiedInstance<>());
    CredentialKeyProvider chain = producer.credentialKeyProvider();

    assertNotNull(chain, "prod with env KEK must boot");
    assertNotNull(chain.credentialKey(), "chain must yield the env-supplied key");

    // Clean up the sysprop we set for this test only.
    System.clearProperty(EnvCredentialKeyProvider.SYSTEM_PROPERTY);
  }

  @Test
  void devProfile_noKekConfigured_producerSucceedsAndChainIsInert() {
    // In dev, the fail-fast guard is intentionally not armed: a developer running `task dev:titan`
    // without the allow flag should still get a booting server, with an inert chain that will
    // surface a precise error only when they try to seal an actual credential. (The full local
    // rig sets LOOP_TITAN_ALLOW_DEV_KEK so the chain is active there.)
    System.setProperty(DevAutoKeyProvider.PROFILE_SYSPROP, "dev");

    CredentialKeyProviderProducer producer =
        new CredentialKeyProviderProducer(new UnsatisfiedInstance<>());
    CredentialKeyProvider chain = producer.credentialKeyProvider();
    assertNotNull(chain);
    assertNull(chain.credentialKey(), "no KEK in dev → chain is inert, not throwing");
  }

  /** Minimal {@link Instance} that always reports unsatisfied — mirrors the prod CDI shape. */
  private static final class UnsatisfiedInstance<T> implements Instance<T> {
    @Override
    public Instance<T> select(java.lang.annotation.Annotation... qualifiers) {
      return this;
    }

    @Override
    public <U extends T> Instance<U> select(
        Class<U> subtype, java.lang.annotation.Annotation... q) {
      return new UnsatisfiedInstance<>();
    }

    @Override
    public <U extends T> Instance<U> select(
        jakarta.enterprise.util.TypeLiteral<U> subtype, java.lang.annotation.Annotation... q) {
      return new UnsatisfiedInstance<>();
    }

    @Override
    public boolean isUnsatisfied() {
      return true;
    }

    @Override
    public boolean isAmbiguous() {
      return false;
    }

    @Override
    public void destroy(T instance) {}

    @Override
    public jakarta.enterprise.inject.Instance.Handle<T> getHandle() {
      throw new UnsupportedOperationException();
    }

    @Override
    public Iterable<? extends jakarta.enterprise.inject.Instance.Handle<T>> handles() {
      return java.util.List.of();
    }

    @Override
    public java.util.Iterator<T> iterator() {
      return java.util.Collections.emptyIterator();
    }

    @Override
    public T get() {
      throw new UnsupportedOperationException("unsatisfied");
    }
  }

  private static String base64Aes256() {
    byte[] key = new byte[32];
    new java.security.SecureRandom().nextBytes(key);
    return java.util.Base64.getEncoder().encodeToString(key);
  }
}
