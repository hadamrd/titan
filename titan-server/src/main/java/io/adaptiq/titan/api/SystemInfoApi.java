package io.adaptiq.titan.api;

import io.adaptiq.titan.api.dto.ServerInfoDto;
import io.adaptiq.titan.api.dto.SystemInfoDto;
import io.adaptiq.titan.api.dto.SystemInfoDto.DbStatus;
import io.adaptiq.titan.api.dto.SystemInfoDto.MigrationStatus;
import io.adaptiq.titan.auth.Roles;
import io.adaptiq.titan.store.TitanStores;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.io.IOException;
import java.net.URL;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;

/**
 * Jakarta REST resource: {@code GET /api/v1/system/info} — engine health dashboard payload (closes
 * #676, extended for #790).
 *
 * <p>Returns a typed {@link SystemInfoDto} aggregating: build version + git SHA (read once at
 * startup from {@code META-INF/titan-build-info.properties}), DB connectivity (a 1-second isValid()
 * probe on a borrowed connection), queue depth (same DAO that backs the {@code titan_queue_depth}
 * Prometheus gauge), online worker count (agents in {@code ONLINE} or {@code BUSY} — {@code
 * DRAINING}/{@code OFFLINE} are excluded because they are not available to accept work), and Flyway
 * migration drift status (current applied version + list of bundled-but-not-applied migrations —
 * surfaces the failure mode that #790 hit blind). Single round-trip from the UI's POV.
 *
 * <p><strong>Auth model.</strong> Same read-tier guard as {@link StatsApi} and {@link QueueApi} —
 * {@code READ_JOB}, {@code TRIGGER_BUILD}, or {@code ADMIN}. The /system page is not public; an
 * unauthenticated client gets 401 via the OIDC filter before the resource is reached.
 *
 * <p><strong>Degraded DB contract.</strong> When the DB is unreachable the endpoint MUST still
 * return 200 with {@code dbStatus=DOWN} (best-effort zeros for the counters that depend on a DB
 * read; {@link MigrationStatus#UNKNOWN} for the migration probe). A 500 would defeat the
 * dashboard's primary purpose: telling the SRE the DB is down. The IT {@code
 * SystemInfoApiIT.degradedDb_returnsDbStatusDown_withoutCrashing} pins this.
 */
@Path("/api/v1/system")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class SystemInfoApi {

  private static final Logger LOGGER = Logger.getLogger(SystemInfoApi.class.getName());

  /**
   * Connection-validity timeout in seconds for the DB ping. Generous enough to absorb a hot-spare
   * failover, tight enough that a partitioned-from-the-primary controller doesn't make the
   * dashboard hang. JDBC spec: {@code Connection.isValid(int)} accepts seconds.
   */
  static final int DB_PROBE_TIMEOUT_SECONDS = 1;

  /** Set of agent statuses we count as "online" for the dashboard tile. */
  static final String[] ONLINE_STATUSES = {"ONLINE", "BUSY"};

  /**
   * Classpath root scanned for bundled Flyway migrations. Must match {@code
   * quarkus.flyway.locations} in {@code application.properties}; if the two drift, the migration
   * status will undercount and the dashboard will lie.
   */
  static final String MIGRATION_RESOURCE_ROOT = "io/adaptiq/titan/db/migration";

  /** Filename pattern for Flyway versioned scripts: {@code V<n>__<desc>.sql}. */
  private static final Pattern V_SCRIPT = Pattern.compile("V(\\d+)__[^/]+\\.sql$");

  private final TitanStores stores;
  private final DataSource dataSource;
  private final ServerInfoDto buildInfo;

  SystemInfoApi(TitanStores stores, DataSource dataSource) {
    this.stores = stores;
    this.dataSource = dataSource;
    // Read-once: the build-info file is immutable for the lifetime of the JVM. Same source +
    // fallback as InfoApi so version/SHA stay consistent across the two endpoints.
    this.buildInfo = InfoApi.readBuildInfo();
  }

  @GET
  @Path("/info")
  @RolesAllowed({Roles.READ_JOB, Roles.TRIGGER_BUILD, Roles.ADMIN})
  public SystemInfoDto info() {
    DbStatus dbStatus = probeDb();
    int queueDepth = safeCountQueueDepth();
    int workersOnline = safeCountWorkersOnline();
    MigrationStatus migrationStatus = probeMigrationStatus();
    return new SystemInfoDto(
        buildInfo.version(),
        buildInfo.commit(),
        dbStatus,
        queueDepth,
        workersOnline,
        Instant.now(),
        migrationStatus);
  }

  private DbStatus probeDb() {
    try (var c = dataSource.getConnection()) {
      return c.isValid(DB_PROBE_TIMEOUT_SECONDS) ? DbStatus.UP : DbStatus.DOWN;
    } catch (Exception e) {
      LOGGER.log(Level.FINE, "[titan-api] db probe failed: {0}", e.getMessage());
      return DbStatus.DOWN;
    }
  }

  private int safeCountQueueDepth() {
    try {
      return stores.taskQueue().countQueued();
    } catch (RuntimeException e) {
      LOGGER.log(Level.FINE, "[titan-api] queue depth read failed: {0}", e.getMessage());
      return 0;
    }
  }

  private int safeCountWorkersOnline() {
    int total = 0;
    for (String status : ONLINE_STATUSES) {
      try {
        total += stores.agents().countByStatus(status);
      } catch (RuntimeException e) {
        LOGGER.log(Level.FINE, "[titan-api] worker count read failed: {0}", e.getMessage());
        // Fall through — partial counts are honest; a 500 here would hide a partial outage.
      }
    }
    return total;
  }

  /**
   * Compute the Flyway drift snapshot. Reads {@code titan.flyway_schema_history} for the applied
   * set, scans the classpath under {@link #MIGRATION_RESOURCE_ROOT} for the bundled set, and
   * returns the difference. Any failure (DB down, history table missing on a fresh-baseline rig,
   * classpath unreadable) collapses to {@link MigrationStatus#UNKNOWN} — never throws.
   */
  private MigrationStatus probeMigrationStatus() {
    Set<String> applied;
    String current;
    try (var c = dataSource.getConnection();
        var st = c.createStatement();
        var rs =
            st.executeQuery(
                "SELECT version FROM titan.flyway_schema_history WHERE success = true")) {
      applied = new HashSet<>();
      while (rs.next()) {
        String v = rs.getString(1);
        if (v != null && !v.isEmpty()) {
          applied.add(v);
        }
      }
      current = highestNumericVersion(applied);
    } catch (Exception e) {
      LOGGER.log(Level.FINE, "[titan-api] migration probe failed: {0}", e.getMessage());
      return MigrationStatus.UNKNOWN;
    }

    Set<String> bundled = scanBundledMigrations();
    List<String> pending = new ArrayList<>();
    for (String v : new TreeSet<>(bundled)) {
      if (!applied.contains(v)) {
        pending.add(v);
      }
    }
    return new MigrationStatus(current, Collections.unmodifiableList(pending));
  }

  /**
   * Highest version string in the set, compared numerically. {@code null} when the set is empty.
   */
  private static String highestNumericVersion(Set<String> versions) {
    String best = null;
    long bestN = Long.MIN_VALUE;
    for (String v : versions) {
      try {
        long n = Long.parseLong(v);
        if (n > bestN) {
          bestN = n;
          best = v;
        }
      } catch (NumberFormatException ignored) {
        // Non-numeric Flyway versions (e.g. "1.1") fall back to lexical comparison.
        if (best == null || v.compareTo(best) > 0) {
          best = v;
        }
      }
    }
    return best;
  }

  /**
   * Scan the classpath for {@code V<n>__*.sql} resources under {@link #MIGRATION_RESOURCE_ROOT}.
   * Returns the set of version strings (e.g. {@code "26"}). Best-effort: tolerates jar:// and
   * file:// URLs; any IO error yields an empty set rather than failing the dashboard.
   */
  private static Set<String> scanBundledMigrations() {
    Set<String> versions = new HashSet<>();
    try {
      ClassLoader cl = SystemInfoApi.class.getClassLoader();
      Enumeration<URL> roots = cl.getResources(MIGRATION_RESOURCE_ROOT);
      while (roots.hasMoreElements()) {
        URL root = roots.nextElement();
        collectFromUrl(root, versions);
      }
    } catch (IOException e) {
      LOGGER.log(Level.FINE, "[titan-api] migration scan failed: {0}", e.getMessage());
    }
    return versions;
  }

  private static void collectFromUrl(URL root, Set<String> versions) {
    String proto = root.getProtocol();
    try {
      if ("file".equals(proto)) {
        java.io.File dir = new java.io.File(root.toURI());
        if (dir.isDirectory()) {
          String[] names = dir.list();
          if (names != null) {
            for (String name : names) {
              collectFromName(name, versions);
            }
          }
        }
      } else if ("jar".equals(proto)) {
        // jar:file:/path/to.jar!/io/adaptiq/titan/db/migration
        String spec = root.toString();
        int bang = spec.indexOf("!/");
        if (bang < 0) {
          return;
        }
        String jarPath = spec.substring("jar:file:".length(), bang);
        String entryPrefix = spec.substring(bang + 2) + "/";
        try (var jar = new java.util.jar.JarFile(jarPath)) {
          var entries = jar.entries();
          while (entries.hasMoreElements()) {
            String name = entries.nextElement().getName();
            if (name.startsWith(entryPrefix) && !name.endsWith("/")) {
              collectFromName(name.substring(entryPrefix.length()), versions);
            }
          }
        }
      }
    } catch (Exception e) {
      LOGGER.log(Level.FINE, "[titan-api] migration scan element failed: {0}", e.getMessage());
    }
  }

  private static void collectFromName(String name, Set<String> versions) {
    Matcher m = V_SCRIPT.matcher(name);
    if (m.find()) {
      versions.add(m.group(1));
    }
  }
}
