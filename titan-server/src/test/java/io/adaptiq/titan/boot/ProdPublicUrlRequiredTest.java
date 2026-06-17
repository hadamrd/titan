package io.adaptiq.titan.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import java.net.URL;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.Test;

/**
 * Boot guard for {@code titan.public-url} (issue #935 — V1 bar 5 audit row 8).
 *
 * <p>{@code titan-server}'s main {@code application.properties} previously shipped a {@code
 * http://localhost:8080} default at the base level. In a misconfigured prod pod (env var typo'd,
 * ExternalSecret missing) the server would silently emit GitHub commit-status / Check-Run /
 * PR-comment {@code target_url}s pointing at {@code http://localhost:8080/builds/{id}} — broken
 * badge links on every PR. Cosmetic-only, not a security risk, but exactly the kind of escape hatch
 * V1 bar #5 forbids. The fix removes the base-level default and keeps the localhost convenience URL
 * ONLY in the {@code %dev} and {@code %test} profiles. In {@code %prod} the operator MUST set
 * {@code TITAN_PUBLIC_URL} or SmallRye Config refuses to resolve the property — Quarkus then
 * refuses to boot with a clear "property is required" message naming the missing key.
 *
 * <p>Mirrors {@link ProdDatasourceUrlRequiredTest} — same SmallRye-Config-direct strategy, avoiding
 * the @QuarkusTest weight just to observe a config-resolution failure at boot.
 */
class ProdPublicUrlRequiredTest {

  /**
   * URL of the SHIPPING {@code application.properties} — the file embedded in the deployed JAR.
   * Resolved by walking up the classpath URL of a server-main class so we are independent of CWD.
   */
  private static URL mainApplicationProperties() {
    URL clockUrl = ClockProducer.class.getResource("ClockProducer.class");
    if (clockUrl == null) {
      throw new IllegalStateException("Cannot locate ClockProducer on classpath");
    }
    String s = clockUrl.toString();
    String classesMain = "/build/classes/java/main/";
    int idx = s.indexOf(classesMain);
    if (idx < 0) {
      throw new IllegalStateException(
          "Unexpected classpath layout (no build/classes/java/main/ segment): " + s);
    }
    String resourcesMain = s.substring(0, idx) + "/build/resources/main/application.properties";
    try {
      return new java.net.URI(resourcesMain).toURL();
    } catch (Exception e) {
      throw new IllegalStateException("Bad URL: " + resourcesMain, e);
    }
  }

  private static SmallRyeConfig loadMainPropertiesWithProfile(String profile) {
    URL props = mainApplicationProperties();
    try {
      return new SmallRyeConfigBuilder()
          .addDefaultInterceptors()
          .addDiscoveredConverters()
          .withProfile(profile)
          .withSources(new PropertiesConfigSource(props))
          .build();
    } catch (java.io.IOException e) {
      throw new IllegalStateException("Failed to read main application.properties: " + props, e);
    }
  }

  @Test
  void prodProfile_missingTitanPublicUrl_failsToResolveProperty() {
    // No TITAN_PUBLIC_URL in the test JVM environment. Under %prod, the base-level expression
    // `${TITAN_PUBLIC_URL}` has no fallback, so SmallRye must throw NoSuchElementException —
    // exactly what Quarkus surfaces at boot as a fail-fast startup error.
    SmallRyeConfig config = loadMainPropertiesWithProfile("prod");
    NoSuchElementException ex =
        assertThrows(
            NoSuchElementException.class, () -> config.getValue("titan.public-url", String.class));
    assertTrue(
        ex.getMessage().contains("TITAN_PUBLIC_URL"),
        "Failure message must name the missing env var so an SRE knows what to set; got: "
            + ex.getMessage());
  }

  @Test
  void devProfile_missingTitanPublicUrl_fallsBackToLocalhost() {
    // Convenience: `task dev:titan` and bare `./gradlew quarkusDev` must still boot without the
    // operator setting anything. The %dev profile override carries the localhost default.
    SmallRyeConfig config = loadMainPropertiesWithProfile("dev");
    assertEquals("http://localhost:8080", config.getValue("titan.public-url", String.class));
  }

  @Test
  void testProfile_missingTitanPublicUrl_fallsBackToLocalhost() {
    // Sanity: unit tests boot the Quarkus runtime in %test mode without setting TITAN_PUBLIC_URL.
    // The %test override mirrors %dev so existing tests (SystemUiConfigApiTest et al that DO set
    // an explicit value continue to win on top, and the ones that don't get a sane localhost).
    SmallRyeConfig config = loadMainPropertiesWithProfile("test");
    assertEquals("http://localhost:8080", config.getValue("titan.public-url", String.class));
  }

  @Test
  void prodProfile_withTitanPublicUrlSet_usesOperatorProvidedValue() {
    // Sanity: when the operator DOES supply the env (the production path), the value flows
    // through unchanged. Threaded via an extra config source at higher ordinal.
    URL props = mainApplicationProperties();
    SmallRyeConfig config;
    try {
      config =
          new SmallRyeConfigBuilder()
              .addDefaultInterceptors()
              .addDiscoveredConverters()
              .withProfile("prod")
              .withSources(new PropertiesConfigSource(props))
              .withSources(
                  new PropertiesConfigSource(
                      java.util.Map.of("TITAN_PUBLIC_URL", "https://titan.prod.example.com"),
                      "test-env-override",
                      1000))
              .build();
    } catch (java.io.IOException e) {
      throw new IllegalStateException("Failed to read main application.properties", e);
    }
    assertEquals(
        "https://titan.prod.example.com", config.getValue("titan.public-url", String.class));
  }
}
