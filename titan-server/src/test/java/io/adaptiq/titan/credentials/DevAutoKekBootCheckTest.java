package io.adaptiq.titan.credentials;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for {@link DevAutoKekBootCheck} — covers the prod-profile refusal contract added
 * by #930 (V1 bar #5 audit row 1) without spinning up a Quarkus runtime.
 *
 * <p>Constructor-based dependency injection so we can pin both the flag and whether a dev profile
 * is active ({@code devProfile} mirrors {@link DevAutoKeyProvider#isDevProfile()}: false = prod
 * launch, true = dev/test).
 */
class DevAutoKekBootCheckTest {

  @Test
  void prodMode_flagOn_throwsWithEnvVarPointer() {
    DevAutoKekBootCheck check = new DevAutoKekBootCheck(true, false);
    IllegalStateException ex = assertThrows(IllegalStateException.class, check::check);
    assertTrue(
        ex.getMessage().contains(DevAutoKeyProvider.FLAG_ENV),
        "error must name the offending env var so an SRE knows what to unset; got: "
            + ex.getMessage());
    assertTrue(
        ex.getMessage().toLowerCase().contains("prod"),
        "error must mention prod profile; got: " + ex.getMessage());
  }

  @Test
  void prodMode_flagOff_passes() {
    // The fail-closed default: when the flag is off, the provider is already inert and prod boot
    // must continue. This is the load-bearing safety property — do not regress.
    DevAutoKekBootCheck check = new DevAutoKekBootCheck(false, false);
    assertDoesNotThrow(check::check);
  }

  @Test
  void devMode_flagOn_permitted() {
    // The whole point of the dev-auto-kek flag is local-rig boot. %dev MUST NOT throw.
    DevAutoKekBootCheck check = new DevAutoKekBootCheck(true, true);
    assertDoesNotThrow(check::check);
  }

  @Test
  void devMode_flagOff_passes() {
    DevAutoKekBootCheck check = new DevAutoKekBootCheck(false, true);
    assertDoesNotThrow(check::check);
  }

  @Test
  void testMode_flagOn_permitted() {
    // Quarkus integration tests run in TEST mode and may flip the flag on (see DevAutoKekIT).
    // The boot check must not interfere.
    DevAutoKekBootCheck check = new DevAutoKekBootCheck(true, true);
    assertDoesNotThrow(check::check);
  }

  @Test
  void prodMode_flagOn_errorMessageMentionsKmsAlternative() {
    // Adversarial: an operator hitting this error needs to know what to do INSTEAD. The message
    // must point at the KMS-backed provider path, otherwise they'll just unset the flag and ship
    // a server that can't issue credentials.
    DevAutoKekBootCheck check = new DevAutoKekBootCheck(true, false);
    IllegalStateException ex = assertThrows(IllegalStateException.class, check::check);
    assertTrue(
        ex.getMessage().toLowerCase().contains("kms"),
        "error must point operators at the KMS alternative; got: " + ex.getMessage());
  }
}
