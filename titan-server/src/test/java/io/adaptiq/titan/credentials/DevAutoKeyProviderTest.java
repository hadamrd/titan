package io.adaptiq.titan.credentials;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pure-JVM contract tests for {@link DevAutoKeyProvider} covering the V1-bar #5 fail-closed gate
 * (issue #1076).
 *
 * <p>These tests scope the JVM-wide {@code titan.profile} sysprop tightly: {@link #setup()} saves
 * the value, every test sets the precise profile it wants, and {@link #teardown()} restores. We
 * never read the env var {@code TITAN_PROFILE} in tests because there is no portable way to set env
 * in-JVM; the sysprop {@code titan.profile} is the documented alternate channel.
 */
class DevAutoKeyProviderTest {

  private String savedProfile;
  private String savedQuarkusProfile;
  private String savedAllowSys;
  private String savedFlagSys;

  @BeforeEach
  void setup() {
    savedProfile = System.getProperty(DevAutoKeyProvider.PROFILE_SYSPROP);
    savedQuarkusProfile = System.getProperty(DevAutoKeyProvider.QUARKUS_PROFILE_SYSPROP);
    savedAllowSys = System.getProperty(DevAutoKeyProvider.ALLOW_SYSPROP);
    savedFlagSys = System.getProperty(DevAutoKeyProvider.FLAG_SYSPROP);
    // Clear everything before each test so we start from a defined state.
    System.clearProperty(DevAutoKeyProvider.PROFILE_SYSPROP);
    System.clearProperty(DevAutoKeyProvider.QUARKUS_PROFILE_SYSPROP);
    System.clearProperty(DevAutoKeyProvider.ALLOW_SYSPROP);
    System.clearProperty(DevAutoKeyProvider.FLAG_SYSPROP);
  }

  @AfterEach
  void teardown() {
    restore(DevAutoKeyProvider.PROFILE_SYSPROP, savedProfile);
    restore(DevAutoKeyProvider.QUARKUS_PROFILE_SYSPROP, savedQuarkusProfile);
    restore(DevAutoKeyProvider.ALLOW_SYSPROP, savedAllowSys);
    restore(DevAutoKeyProvider.FLAG_SYSPROP, savedFlagSys);
  }

  private static void restore(String key, String value) {
    if (value == null) {
      System.clearProperty(key);
    } else {
      System.setProperty(key, value);
    }
  }

  // ── Acceptance #1: construction throws in non-dev profile ──────────────────

  @Test
  void noArgConstructor_throwsConfigException_whenProfileIsProd() {
    System.setProperty(DevAutoKeyProvider.PROFILE_SYSPROP, "prod");

    ConfigException ex = assertThrows(ConfigException.class, DevAutoKeyProvider::new);
    // Message contract is part of the V1-bar acceptance — verify the exact phrasing so log
    // scrapers and runbooks can grep it stably.
    assertTrue(
        ex.getMessage().contains("DevAutoKeyProvider only valid in dev profile"),
        "must surface the canonical refusal message; got: " + ex.getMessage());
    assertTrue(
        ex.getMessage().contains("configure a real KEK for prod"),
        "must tell the operator what to do instead; got: " + ex.getMessage());
    assertTrue(
        ex.getMessage().contains("docs/operations/runbooks/kek-config.md"),
        "must point at the runbook; got: " + ex.getMessage());
  }

  @Test
  void noArgConstructor_throwsConfigException_whenProfileUnset() {
    // No TITAN_PROFILE / titan.profile / quarkus.profile — and LaunchMode.current() is NORMAL
    // outside Quarkus boot. This is the "ambiguous deployment" case that the gate must reject.
    System.clearProperty(DevAutoKeyProvider.PROFILE_SYSPROP);
    System.clearProperty(DevAutoKeyProvider.QUARKUS_PROFILE_SYSPROP);

    assertThrows(ConfigException.class, DevAutoKeyProvider::new);
  }

  // ── Acceptance #1b: construction succeeds in dev ───────────────────────────

  @Test
  void noArgConstructor_succeeds_whenTitanProfileIsDev() {
    System.setProperty(DevAutoKeyProvider.PROFILE_SYSPROP, "dev");

    DevAutoKeyProvider provider =
        assertDoesNotThrow(
            (org.junit.jupiter.api.function.ThrowingSupplier<DevAutoKeyProvider>)
                DevAutoKeyProvider::new);
    // No allow-flag was set, so the bean must be inert. This is the same dual-gate the existing
    // DevAutoKekIT.FlagOffIT proves at the CDI seam.
    assertNull(provider.credentialKey(), "fail-closed default: no allow-var → inert");
  }

  @Test
  void noArgConstructor_succeeds_whenQuarkusProfileIsDev() {
    // Defence in depth: the local rig may set quarkus.profile via -Dquarkus.profile=dev rather
    // than the titan-specific sysprop. The gate must honour Quarkus' own channel too.
    System.setProperty(DevAutoKeyProvider.QUARKUS_PROFILE_SYSPROP, "dev");

    assertDoesNotThrow(
        (org.junit.jupiter.api.function.ThrowingSupplier<DevAutoKeyProvider>)
            DevAutoKeyProvider::new);
  }

  // ── Acceptance #4 (adversarial): allow-var set in prod → still refuses ─────

  @Test
  void noArgConstructor_throws_evenWhenAllowVarIsSetInProd() {
    // The threat: a misconfigured deployment exports LOOP_TITAN_ALLOW_DEV_KEK=1 in prod, hoping
    // to unblock something. The profile gate must NOT be bypassable by setting the allow var; the
    // profile is the gate, not the allow var.
    System.setProperty(DevAutoKeyProvider.PROFILE_SYSPROP, "prod");
    System.setProperty(DevAutoKeyProvider.ALLOW_SYSPROP, "1");

    assertThrows(ConfigException.class, DevAutoKeyProvider::new);
  }

  @Test
  void noArgConstructor_throws_evenWhenLegacyFlagIsSetInProd() {
    // Same adversarial shape against the legacy TITAN_CREDENTIALS_DEV_AUTO_KEK sysprop — the gate
    // must close that hole too, otherwise a deployment carrying old env vars from a #930-era
    // rollout would still mint a host-local KEK in prod.
    System.setProperty(DevAutoKeyProvider.PROFILE_SYSPROP, "production");
    System.setProperty(DevAutoKeyProvider.FLAG_SYSPROP, "true");

    assertThrows(ConfigException.class, DevAutoKeyProvider::new);
  }

  // ── Acceptance #3: LOOP_TITAN_ALLOW_DEV_KEK=1 toggles the bean in dev ─────

  @Test
  void allowVar_enablesProviderInDevProfile() {
    // The packaged constructor exposes the readEnabledFlag() result via the second-arg `enabled`
    // toggle. We assert against the no-arg path so the test exercises the actual ALLOW_SYSPROP
    // wiring rather than reaching past it.
    System.setProperty(DevAutoKeyProvider.PROFILE_SYSPROP, "dev");
    System.setProperty(DevAutoKeyProvider.ALLOW_SYSPROP, "1");
    System.setProperty(
        DevAutoKeyProvider.PATH_SYSPROP,
        // Throwaway path inside the build dir so the test does not need root.
        Path.of(System.getProperty("java.io.tmpdir"), "titan-test-allowvar-" + System.nanoTime())
            .toString());

    DevAutoKeyProvider provider = new DevAutoKeyProvider();
    byte[] key = provider.credentialKey();
    org.junit.jupiter.api.Assertions.assertNotNull(
        key, "LOOP_TITAN_ALLOW_DEV_KEK=1 in dev must produce a key");
    org.junit.jupiter.api.Assertions.assertEquals(32, key.length, "AES-256 key length");
  }

  // ── isDevProfile() unit coverage ───────────────────────────────────────────

  @Test
  void isDevProfile_truthTable() {
    // Empty → falls back to LaunchMode.current(), which is NORMAL outside Quarkus boot → false.
    assertFalse(DevAutoKeyProvider.isDevProfile(), "no profile signal → not dev");

    System.setProperty(DevAutoKeyProvider.PROFILE_SYSPROP, "dev");
    assertTrue(DevAutoKeyProvider.isDevProfile(), "titan.profile=dev → dev");

    System.setProperty(DevAutoKeyProvider.PROFILE_SYSPROP, "DEV");
    assertTrue(DevAutoKeyProvider.isDevProfile(), "case-insensitive match");

    System.setProperty(DevAutoKeyProvider.PROFILE_SYSPROP, "prod");
    assertFalse(DevAutoKeyProvider.isDevProfile(), "titan.profile=prod → not dev");

    // Whitespace edge case: trim should make this a dev match (a misconfigured chart could yield
    // a trailing newline on the env value).
    System.setProperty(DevAutoKeyProvider.PROFILE_SYSPROP, "  dev  ");
    assertTrue(DevAutoKeyProvider.isDevProfile(), "trimmed match");
  }
}
