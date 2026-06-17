package io.adaptiq.titan.credentials;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Activation IT for {@link DevAutoKeyProvider} — closes GH #803 (test bedrock) and the activation
 * regression in GH #816.
 *
 * <h2>What this proves</h2>
 *
 * <ol>
 *   <li><strong>Flag-on path</strong> ({@code DevAutoKekFlagOnIT}): with the dev-auto-kek flag set
 *       (via {@link DevAutoKeyProvider#FLAG_SYSPROP} sysprop — the in-JVM test mirror of the env
 *       var the local rig exports), the {@link DevAutoKeyProvider} CDI bean must yield a non-null
 *       AES-256 key, and the KEK file must be persisted to {@link DevAutoKeyProvider#PATH_SYSPROP}
 *       so {@code dev:down && dev:titan} does not brick previously-encrypted rows.
 *   <li><strong>Flag-off path</strong> ({@code DevAutoKekFlagOffIT}): with the flag unset, the bean
 *       must stay inert ({@code credentialKey()} returns {@code null}) — the production safety
 *       contract from design/39 §5. If this ever flips to non-null, the production fail-closed
 *       property has regressed.
 * </ol>
 *
 * <h2>Why direct bean assertion, not POST /api/v1/credentials</h2>
 *
 * The HTTP-layer test infra ({@code FixedCredentialKeyProviderProducer} under {@code src/test/})
 * installs a {@link io.quarkus.test.Mock @Mock} {@link
 * io.adaptiq.titan.flow.crypto.CredentialKeyProvider} that displaces every other producer in
 * {@code @QuarkusTest} runs. That's appropriate for service-contract testing but it would also make
 * this activation IT tautological — the fixed mock would always satisfy {@code activeKek()},
 * regardless of whether {@code DevAutoKeyProvider} was wired in at all. So we assert the bean
 * itself, end-to-end at the CDI seam: that is the seam that #816 reported broken.
 *
 * <p>Two top-level test classes (rather than {@code @Nested}) because the dev-auto flag is read
 * once at bean construction; flipping it requires a Quarkus restart, which {@link TestProfile}
 * provides at the class boundary.
 */
class DevAutoKekIT {

  /** Profile installed by {@link DevAutoKekFlagOnIT} — seeds the sysprop the provider reads. */
  public static class FlagOnProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      try {
        Path keyFile = Files.createTempFile("titan-dev-auto-kek-on-", ".key");
        Files.deleteIfExists(keyFile); // let the provider create it
        System.setProperty(DevAutoKeyProvider.FLAG_SYSPROP, "true");
        System.setProperty(DevAutoKeyProvider.PATH_SYSPROP, keyFile.toString());
      } catch (Exception e) {
        throw new IllegalStateException("failed to seed dev-auto-kek temp path", e);
      }
      return Map.of();
    }
  }

  /** Profile installed by {@link DevAutoKekFlagOffIT} — explicitly clears the dev-auto flag. */
  public static class FlagOffProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      System.clearProperty(DevAutoKeyProvider.FLAG_SYSPROP);
      try {
        Path keyFile = Files.createTempFile("titan-dev-auto-kek-off-", ".key");
        Files.deleteIfExists(keyFile);
        System.setProperty(DevAutoKeyProvider.PATH_SYSPROP, keyFile.toString());
      } catch (Exception e) {
        throw new IllegalStateException("failed to seed temp path", e);
      }
      return Map.of();
    }
  }

  // ── Flag ON ────────────────────────────────────────────────────────────────

  @QuarkusTest
  @TestProfile(FlagOnProfile.class)
  public static class DevAutoKekFlagOnIT {

    @Inject DevAutoKeyProvider provider;

    @Test
    void beanYieldsKey_andKekFileIsPersisted() {
      // The bean MUST be a real CDI bean — proves #816's "Quarkus does not discover it" hypothesis
      // is no longer load-bearing for the dev rig: the producer wires it explicitly.
      assertNotNull(provider, "DevAutoKeyProvider must be CDI-discoverable");

      byte[] key = provider.credentialKey();
      assertNotNull(key, "DevAutoKeyProvider must yield a key when flag is on");
      assertEquals(32, key.length, "AES-256 key length");

      // Persistence: the KEK was written to the configured path, so `dev:down && dev:titan` does
      // not brick previously-encrypted rows.
      String path = System.getProperty(DevAutoKeyProvider.PATH_SYSPROP);
      assertTrue(Files.exists(Path.of(path)), "DEV KEK file must be created at " + path);

      // Second call returns an equal-by-content key (cached), proving the "regenerate every time"
      // bug from #816's neighborhood does not exist — a fresh key on every call would silently
      // brick previously-sealed rows mid-process.
      byte[] key2 = provider.credentialKey();
      assertNotNull(key2);
      org.junit.jupiter.api.Assertions.assertArrayEquals(key, key2, "KEK must be stable in-JVM");
    }
  }

  // ── Flag OFF ───────────────────────────────────────────────────────────────

  @QuarkusTest
  @TestProfile(FlagOffProfile.class)
  public static class DevAutoKekFlagOffIT {

    @Inject DevAutoKeyProvider provider;

    @Test
    void beanIsInert_whenFlagOff() {
      // Bean still exists in the CDI container — but it must return null from credentialKey().
      // The production safety contract: no opt-in, no key, no silent fallback. Asserting at the
      // bean level (not via HTTP) sidesteps the FixedCredentialKeyProviderProducer @Mock that
      // overrides the producer chain in @QuarkusTest runs.
      assertNotNull(provider, "bean still exists, just inert");
      assertNull(
          provider.credentialKey(),
          "fail-closed: DevAutoKeyProvider must yield null when flag is off");

      // Belt-and-braces: the @Inject is via CDI proxy; force the underlying instance via
      // CDI.current() to prove it's not a stale singleton from a prior profile.
      DevAutoKeyProvider direct = CDI.current().select(DevAutoKeyProvider.class).get();
      assertNull(direct.credentialKey(), "direct CDI lookup also yields null");
    }
  }
}
