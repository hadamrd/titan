package io.adaptiq.titan.keyprovider.infisical;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.adaptiq.titan.flow.crypto.CredentialKeyProvider;
import io.adaptiq.titan.flow.crypto.SecretCipher;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ServiceLoader;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link InfisicalCredentialKeyProvider}.
 *
 * <p>Two of these are the case study for the {@code CredentialKeyProvider} SPI (design/39 §3.1):
 * {@link #serviceLoaderDiscoversThisProvider()} proves the drop-in jar is found with no engine
 * change, and {@link #fetchesTheKeyFromTheLiveInfisicalAccount()} proves the provider really pulls
 * a usable key from a live Infisical project. The live test self-skips when the Infisical service
 * token is not present (CI without the secret), so the suite stays green everywhere.
 */
class InfisicalCredentialKeyProviderTest {

  /** The Infisical service token the rig uses — see the secret-management reference. */
  private static final Path TOKEN_FILE =
      Path.of(System.getProperty("user.home"), ".infisical_svc_token");

  /** A placeholder id for the offline describe()/no-leak tests — never hits the network. */
  private static final String PROJECT_ID = "00000000-0000-0000-0000-000000000000";

  /**
   * The live tests resolve the real project id from this env var so no private id is hard-coded.
   * When unset (the default everywhere except a configured dev box) the live tests self-skip.
   */
  private static final String LIVE_PROJECT_ID = System.getenv("TITAN_INFISICAL_TEST_PROJECT_ID");

  @Test
  void anUnconfiguredProviderIsInert() {
    // No Infisical env vars in the test JVM — the provider must stay out of the way so
    // CredentialKeyProvider.active() can fall through to the built-in env provider.
    InfisicalCredentialKeyProvider provider =
        new InfisicalCredentialKeyProvider((InfisicalCredentialKeyProvider.Settings) null);
    assertNull(provider.credentialKey());
    assertEquals("infisical:unconfigured", provider.describe());
  }

  @Test
  void describeNeverLeaksTheTokenOrTheKey() {
    var settings =
        new InfisicalCredentialKeyProvider.Settings(
            "st.super-secret-token",
            PROJECT_ID,
            "prod",
            "/",
            "https://app.infisical.com",
            "TITAN_CREDENTIAL_KEY");
    String description = new InfisicalCredentialKeyProvider(settings).describe();
    assertEquals("infisical:" + PROJECT_ID + "/prod/#TITAN_CREDENTIAL_KEY", description);
  }

  @Test
  void serviceLoaderDiscoversThisProvider() {
    // The case study: this module ships only a META-INF/services entry — no engine change —
    // and ServiceLoader finds it on the classpath. (We check ServiceLoader directly rather
    // than CredentialKeyProvider.active(): active() returns a ChainedCredentialKeyProvider
    // wrapping every discovered provider plus the built-in env fallback, so the chain
    // contains Infisical but is not equal to it.)
    boolean found =
        ServiceLoader.load(CredentialKeyProvider.class).stream()
            .anyMatch(p -> p.type().equals(InfisicalCredentialKeyProvider.class));
    assertTrue(found, "the Infisical provider must be discovered via ServiceLoader");
  }

  @Test
  void fetchesTheKeyFromTheLiveInfisicalAccount() throws Exception {
    assumeTrue(Files.exists(TOKEN_FILE), "skipped — no Infisical service token at " + TOKEN_FILE);
    String token = Files.readString(TOKEN_FILE).strip();
    assumeTrue(!token.isEmpty(), "skipped — Infisical token file is empty");
    assumeTrue(
        LIVE_PROJECT_ID != null && !LIVE_PROJECT_ID.isBlank(),
        "skipped — set TITAN_INFISICAL_TEST_PROJECT_ID to run the live Infisical test");

    var settings =
        new InfisicalCredentialKeyProvider.Settings(
            token,
            LIVE_PROJECT_ID,
            "prod",
            "/",
            "https://app.infisical.com",
            "TITAN_CREDENTIAL_KEY");
    InfisicalCredentialKeyProvider provider = new InfisicalCredentialKeyProvider(settings);

    byte[] key = provider.credentialKey();
    assertNotNull(
        key, "expected a key from Infisical — is TITAN_CREDENTIAL_KEY set in the project?");
    assertEquals(SecretCipher.KEY_LENGTH_BYTES, key.length);

    // The fetched key must be a working AES-256 key — seal and unseal a round-trip with it.
    String sealed = SecretCipher.seal("case-study-secret", key, "build-1:step-1");
    assertEquals("case-study-secret", SecretCipher.unseal(sealed, key, "build-1:step-1"));
  }
}
