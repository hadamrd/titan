/*
 * JsonLoggerTest — wiring test for the quarkus-logging-json console formatter.
 *
 * <p>Why config-binding instead of stdout capture: Quarkus' JBoss LogManager installs
 * its console handler very early — before user-registered {@code java.util.logging}
 * handlers can intercept records — so capturing the formatted JSON from inside a
 * @QuarkusTest is brittle. The brief explicitly authorises this fallback: verify the
 * configuration resolves to the expected values per profile. That's the actual
 * contract — Quarkus owns the formatter implementation.
 *
 * <p>What this asserts:
 *
 * <ul>
 *   <li>Under the default test profile ({@code %test}), JSON console output is OFF so local
 *       terminal output stays readable. See {@link DefaultTestProfilePart}.
 *   <li>Under a profile that forces it on (mirroring prod), JSON console output is ON and the
 *       static {@code service.name} additional field is bound to {@code titan-server}. See
 *       {@link ForcedJsonProfilePart}.
 * </ul>
 *
 * <p>Both parts run under the JUnit selector {@code --tests JsonLoggerTest} because that matches
 * the inner classes' simple names ({@code JsonLoggerTest$...}).
 *
 * <p>Follow-ups (do NOT add here): #314 (OTLP exporter), #315 (worker logging).
 */
package io.adaptiq.titan.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.util.Map;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;

class JsonLoggerTest {

  /**
   * Default-test-profile case: {@code %test.quarkus.log.console.json=false} must win, so the test
   * runner keeps human-readable logs even though prod is JSON-on.
   */
  @QuarkusTest
  static class DefaultTestProfilePart {

    @Test
    void consoleJsonIsDisabledUnderTestProfile() {
      boolean json =
          ConfigProvider.getConfig()
              .getOptionalValue("quarkus.log.console.json", Boolean.class)
              .orElse(false);
      assertFalse(
          json,
          "quarkus.log.console.json must be FALSE under %test so terminal output stays readable");
    }
  }

  /**
   * Forced-on case: overrides the {@code %test} guard to simulate the prod wiring. Asserts the
   * structured-logger toggle and the static {@code service.name} additional field both resolve to
   * the values configured in application.properties.
   */
  @QuarkusTest
  @TestProfile(ForceJsonProfile.class)
  static class ForcedJsonProfilePart {

    @Test
    void consoleJsonIsEnabled() {
      boolean json =
          ConfigProvider.getConfig()
              .getOptionalValue("quarkus.log.console.json", Boolean.class)
              .orElse(false);
      assertTrue(json, "quarkus.log.console.json must be TRUE when the prod override is forced");
    }

    @Test
    void serviceNameAdditionalFieldIsBoundToTitanServer() {
      // Quarkus property: quarkus.log.console.json.additional-field."service.name".value
      // MicroProfile Config may expose quoted segments either with or without the quotes —
      // look both ways to be tolerant of how the runtime normalises the key.
      String quoted =
          ConfigProvider.getConfig()
              .getOptionalValue(
                  "quarkus.log.console.json.additional-field.\"service.name\".value", String.class)
              .orElse(null);
      String unquoted =
          ConfigProvider.getConfig()
              .getOptionalValue(
                  "quarkus.log.console.json.additional-field.service.name.value", String.class)
              .orElse(null);
      String value = quoted != null ? quoted : unquoted;
      assertEquals(
          "titan-server",
          value,
          "service.name additional-field must resolve to 'titan-server' (looked up quoted='"
              + quoted
              + "' unquoted='"
              + unquoted
              + "')");
    }
  }

  /** Forces the JSON-on toggle on inside a @QuarkusTest — mirrors %prod wiring. */
  public static class ForceJsonProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of("quarkus.log.console.json", "true");
    }
  }
}
