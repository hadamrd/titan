package io.adaptiq.titan.keyprovider.infisical;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.adaptiq.titan.flow.crypto.SecretProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link InfisicalSecretProvider} — the synthesis-time {@link SecretProvider} (design/40
 * §3). It is the worker-side sibling of {@link InfisicalCredentialKeyProvider}, and the proof that
 * the {@code SecretProvider} SPI is real: the drop-in jar is discovered with no engine change.
 *
 * <p>The live test self-skips when the Infisical service token is absent, so the suite stays green
 * everywhere.
 */
class InfisicalSecretProviderTest {

  /** The Infisical service token the rig uses — see the secret-management reference. */
  private static final Path TOKEN_FILE =
      Path.of(System.getProperty("user.home"), ".infisical_svc_token");

  /** A placeholder id for the offline describe()/no-leak tests — never hits the network. */
  private static final String PROJECT_ID = "00000000-0000-0000-0000-000000000000";

  /**
   * The live test resolves the real project id from this env var so no private id is hard-coded.
   * When unset (the default everywhere except a configured dev box) the live test self-skips.
   */
  private static final String LIVE_PROJECT_ID = System.getenv("TITAN_INFISICAL_TEST_PROJECT_ID");

  @Test
  void anUnconfiguredProviderIsInert() {
    // No Infisical client — secret() must return null so a credential: library() fails closed.
    InfisicalSecretProvider provider = new InfisicalSecretProvider((InfisicalClient) null);
    assertNull(provider.secret("github-ci-token"));
    assertEquals("infisical:unconfigured", provider.describe());
  }

  @Test
  void describeNeverLeaksTheToken() {
    InfisicalClient client =
        InfisicalClient.of(
            new InfisicalClient.Settings(
                "st.super-secret-token", PROJECT_ID, "prod", "/", "https://app.infisical.com"));
    String description = new InfisicalSecretProvider(client).describe();
    assertEquals("infisical:" + PROJECT_ID + "/prod/", description);
  }

  @Test
  void serviceLoaderDiscoversThisProvider() {
    // The case study: this module ships a META-INF/services SecretProvider entry — no engine
    // change — and SecretProvider.active() (a plain ServiceLoader) finds it on the classpath.
    SecretProvider active = SecretProvider.active();
    assertInstanceOf(
        InfisicalSecretProvider.class,
        active,
        "the Infisical SecretProvider must be discovered via ServiceLoader");
  }

  @Test
  void fetchesASynthesisSecretFromTheLiveInfisicalAccount() throws Exception {
    assumeTrue(Files.exists(TOKEN_FILE), "skipped — no Infisical service token at " + TOKEN_FILE);
    String token = Files.readString(TOKEN_FILE).strip();
    assumeTrue(!token.isEmpty(), "skipped — Infisical token file is empty");
    assumeTrue(
        LIVE_PROJECT_ID != null && !LIVE_PROJECT_ID.isBlank(),
        "skipped — set TITAN_INFISICAL_TEST_PROJECT_ID to run the live Infisical test");

    InfisicalClient client =
        InfisicalClient.of(
            new InfisicalClient.Settings(
                token, LIVE_PROJECT_ID, "prod", "/", "https://app.infisical.com"));
    InfisicalSecretProvider provider = new InfisicalSecretProvider(client);

    // TITAN_CREDENTIAL_KEY is the one secret the rig project is known to hold — fetching it
    // through the SecretProvider proves the by-name path works end-to-end.
    String value = provider.secret("TITAN_CREDENTIAL_KEY");
    assertNotNull(value, "expected a value from Infisical for TITAN_CREDENTIAL_KEY");

    // An unknown secret must come back null — the fail-closed signal for synthesis (design/40 §4).
    assertNull(provider.secret("titan-no-such-secret-xyz"), "an unknown secret must be null");
  }
}
