package io.adaptiq.titan.api;

import io.adaptiq.titan.flow.crypto.CredentialKeyProvider;
import io.quarkus.test.Mock;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

/**
 * Test-scope CDI alternative producer: replaces the production {@link
 * io.adaptiq.titan.boot.CredentialKeyProviderProducer} during {@code @QuarkusTest} runs with a
 * fixed, deterministic 32-byte key.
 *
 * <p>Real key-source decisions (env / Infisical) are not exercised in HTTP-layer tests — only the
 * service contract is, so a constant key removes the need to seed an env var into the JVM under
 * test.
 */
@Mock
@ApplicationScoped
public class FixedCredentialKeyProviderProducer {

  // 32 bytes — the AES-256 key length SecretCipher requires.
  private static final byte[] KEY = new byte[32];

  @Produces
  @ApplicationScoped
  public CredentialKeyProvider credentialKeyProvider() {
    return new CredentialKeyProvider() {
      @Override
      public byte[] credentialKey() {
        return KEY.clone();
      }

      @Override
      public String describe() {
        return "test:fixed";
      }
    };
  }
}
