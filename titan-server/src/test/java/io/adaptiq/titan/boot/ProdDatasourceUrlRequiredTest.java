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
 * Boot guard for {@code quarkus.datasource.jdbc.url} (issue #932 — V1 bar 5).
 *
 * <p>{@code titan-server}'s main {@code application.properties} previously shipped a {@code
 * jdbc:postgresql://localhost:5432/titan} default at the base level. In a misconfigured prod pod
 * (e.g. ExternalSecret didn't sync, env var typo'd) the server would silently dial localhost:5432
 * and time out with an opaque pool-exhausted error. The fix removes the base-level default and
 * keeps the localhost convenience URL ONLY in the {@code %dev} profile. In {@code %prod} the
 * operator MUST set {@code TITAN_DB_URL} or SmallRye Config refuses to resolve the property —
 * Quarkus then refuses to boot with a clear "property is required" message that names the missing
 * key.
 *
 * <p>We assert this at the SmallRye-Config layer rather than via a heavyweight {@code @QuarkusTest}
 * because the latter would need to set up (and tear down) the entire Postgres / OIDC test machinery
 * just to observe a startup-time config-resolution failure. SmallRye's resolver is the exact same
 * engine Quarkus uses at boot, so the assertion is faithful.
 *
 * <p>The test loads the MAIN {@code application.properties} explicitly (bypassing the test-scope
 * file at {@code src/test/resources/application.properties}, which pins H2). This is what
 * production Quarkus sees on the deployed JAR.
 */
class ProdDatasourceUrlRequiredTest {

  /**
   * URL of the SHIPPING {@code application.properties} — the file embedded in the deployed JAR.
   * Resolved by walking up the classpath URL of a server-main class so we are independent of CWD
   * (the Gradle test JVM CWD is the module root, but defensive guard).
   */
  private static URL mainApplicationProperties() {
    // ClockProducer is a server-main class with no test-scope shadow, so its classpath URL
    // resolves into titan-server/build/resources/main, alongside application.properties.
    URL clockUrl = ClockProducer.class.getResource("ClockProducer.class");
    if (clockUrl == null) {
      throw new IllegalStateException("Cannot locate ClockProducer on classpath");
    }
    String s = clockUrl.toString();
    // …/build/classes/java/main/io/adaptiq/titan/boot/ClockProducer.class
    //   -> …/build/resources/main/application.properties
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
  void prodProfile_missingTitanDbUrl_failsToResolveJdbcUrl() {
    // No TITAN_DB_URL in the test JVM environment. Under %prod, the base-level expression
    // `${TITAN_DB_URL}` has no fallback, so SmallRye must throw NoSuchElementException —
    // exactly what Quarkus surfaces at boot as a fail-fast startup error.
    SmallRyeConfig config = loadMainPropertiesWithProfile("prod");
    NoSuchElementException ex =
        assertThrows(
            NoSuchElementException.class,
            () -> config.getValue("quarkus.datasource.jdbc.url", String.class));
    assertTrue(
        ex.getMessage().contains("TITAN_DB_URL"),
        "Failure message must name the missing env var so an SRE knows what to set; got: "
            + ex.getMessage());
  }

  @Test
  void devProfile_missingTitanDbUrl_fallsBackToLocalhost() {
    // Convenience: `task dev:titan` and bare `./gradlew quarkusDev` must still boot without the
    // operator setting anything. The %dev profile override carries the localhost default.
    SmallRyeConfig config = loadMainPropertiesWithProfile("dev");
    assertEquals(
        "jdbc:postgresql://localhost:5432/titan",
        config.getValue("quarkus.datasource.jdbc.url", String.class));
  }

  @Test
  void prodProfile_withTitanDbUrlSet_usesOperatorProvidedValue() {
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
                      java.util.Map.of(
                          "TITAN_DB_URL", "jdbc:postgresql://db.prod.internal:5432/titan"),
                      "test-env-override",
                      1000))
              .build();
    } catch (java.io.IOException e) {
      throw new IllegalStateException("Failed to read main application.properties", e);
    }
    assertEquals(
        "jdbc:postgresql://db.prod.internal:5432/titan",
        config.getValue("quarkus.datasource.jdbc.url", String.class));
  }
}
