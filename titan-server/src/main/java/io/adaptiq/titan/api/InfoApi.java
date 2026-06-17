package io.adaptiq.titan.api;

import io.adaptiq.titan.api.dto.ServerInfoDto;
import jakarta.annotation.security.PermitAll;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.Properties;

/**
 * Jakarta REST resource: {@code GET /api/v1/info} — public server-identity tile (Forge-loop tick
 * #48). Returns Titan's running version, the build's git SHA, the build timestamp, and JVM uptime.
 * The Settings page renders this in the "About this Titan" card; replaces the hardcoded {@code
 * TITAN_UI_VERSION} literal in the UI bundle.
 *
 * <p><strong>Auth model.</strong> {@code @PermitAll} — the version + commit-sha + uptime are not
 * secrets, and the Settings page reads this before the OIDC redirect-callback completes in some
 * code paths (a future support page may render it at the login screen).
 *
 * <p><strong>Build-info source.</strong> Reads {@code META-INF/titan-build-info.properties} off the
 * classpath. Gradle writes this file at compile time (see {@code titan-server/build.gradle.kts}).
 * When the file is absent (e.g. an IDE-only classpath, or the wiring hasn't landed yet), all three
 * build-time fields fall back to {@code "unknown"} — uptime is always computed live from the JVM's
 * {@link ManagementFactory#getRuntimeMXBean() RuntimeMXBean}.
 */
@Path("/api/v1/info")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
@PermitAll
public class InfoApi {

  static final String RESOURCE_PATH = "META-INF/titan-build-info.properties";
  static final String UNKNOWN = "unknown";

  /** Cached at first read — the properties file is immutable for the lifetime of the JVM. */
  private final ServerInfoDto buildInfo;

  public InfoApi() {
    this.buildInfo = readBuildInfo();
  }

  @GET
  public ServerInfoDto info() {
    long uptimeSeconds =
        Duration.ofMillis(ManagementFactory.getRuntimeMXBean().getUptime()).toSeconds();
    return new ServerInfoDto(
        buildInfo.version(), buildInfo.commit(), buildInfo.builtAt(), uptimeSeconds);
  }

  /**
   * Read the build-time properties off the classpath. Package-private for unit-test override via a
   * stubbed classloader resource.
   */
  static ServerInfoDto readBuildInfo() {
    Properties props = new Properties();
    try (InputStream in =
        Thread.currentThread().getContextClassLoader().getResourceAsStream(RESOURCE_PATH)) {
      if (in != null) {
        props.load(in);
      }
    } catch (IOException ignored) {
      // Fall through to defaults — a corrupted/missing resource is not a 500-worthy event.
    }
    return new ServerInfoDto(
        props.getProperty("version", UNKNOWN),
        props.getProperty("commit", UNKNOWN),
        props.getProperty("builtAt", UNKNOWN),
        0L);
  }
}
