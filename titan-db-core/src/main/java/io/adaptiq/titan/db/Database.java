package io.adaptiq.titan.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;

/**
 * Singleton owner of the shared JDBC {@link DataSource} and Flyway migrations.
 *
 * <p><b>Standalone Titan (post-Phase-3).</b> The production HTTP server runs on Quarkus and lets
 * Agroal own the pool + {@code quarkus-flyway} run migrations — so this class is now exercised
 * almost exclusively by tests, the dying legacy {@code TitanStores.get()} singleton, and
 * out-of-Quarkus embedded use. It used to carry historical CI-platform coupling (a JCasC DataBound
 * bean reaching for a global singleton); that coupling was the last surviving external dependency
 * in the repo and has been removed (issue #328).
 *
 * <p><b>Schema-neutral.</b> This class does not know about any particular schema. Each consumer
 * registers its {@code (schemaName, migrationLocations)} pair via {@link #registerSchema}; {@link
 * #reconfigure} iterates the registry, creating every registered schema and running its Flyway
 * baseline. A consumer that registers no schema gets a bare pool — no foreign schema is ever
 * created.
 *
 * <p><b>No pool opens until config is supplied.</b> {@link #dataSource()} does NOT lazily boot a
 * connection pool: a pool is created only by an explicit {@link #reconfigure}. If a build/test
 * environment legitimately wants the embedded default it must say so via {@link
 * #reconfigureWithEmbeddedDefault()} — the fallback is never automatic. This avoids the historical
 * "lazy H2 bootstrap pool" bug, where any caller touching the DB before the real reconfigure
 * silently spun up a throwaway embedded pool that had to be closed and replaced moments later.
 *
 * <p>The pooled connections carry no default schema — every consumer schema-qualifies its SQL or
 * pins the schema on the borrowed connection itself. This keeps the shared pool neutral so two
 * schema owners can coexist on it.
 */
public final class Database {

  private static final Logger LOGGER = Logger.getLogger(Database.class.getName());

  private static final Object LOCK = new Object();
  private static volatile HikariDataSource dataSource;
  private static volatile Config activeConfig;

  /**
   * Registry of schemas to create + migrate, keyed by schema name. Insertion-ordered so migration
   * order is deterministic. Populated by {@link #registerSchema} before {@link #reconfigure}.
   */
  private static final Map<String, SchemaRegistration> SCHEMAS = new LinkedHashMap<>();

  private Database() {}

  /**
   * Plain-data database configuration — JDBC URL, optional credentials, pool size.
   *
   * <p>Replaces the historical {@code DatabaseConfig} JCasC DataBound bean. Build instances with
   * the static factories ({@link #of}, {@link #embedded(java.io.File)}, {@link #embeddedDefault()})
   * or the chained setters; the type is intentionally small.
   */
  public static final class Config {

    @Nullable private String jdbcUrl;
    @Nullable private String username;
    @Nullable private String password;
    private int maxPoolSize = 10;

    public Config() {}

    /** Convenience: a Config with just a JDBC URL (no credentials). */
    @NonNull
    public static Config of(@NonNull String jdbcUrl) {
      return new Config().setJdbcUrl(jdbcUrl);
    }

    /**
     * The embedded H2 default — file-mode H2 under {@code <dir>/titan-db/titan-db}. Used by
     * non-Quarkus embedded callers and by tests that want the legacy default location. The Quarkus
     * server never goes through this path.
     */
    @NonNull
    public static Config embedded(@NonNull java.io.File dir) {
      return Config.of(
          "jdbc:h2:file:"
              + new java.io.File(dir, "titan-db/titan-db").getAbsolutePath()
              + ";AUTO_SERVER=TRUE;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE");
    }

    /** Embedded H2 rooted at {@code ./titan-db/} in the current working directory. */
    @NonNull
    public static Config embeddedDefault() {
      return embedded(new java.io.File("."));
    }

    @Nullable
    public String getJdbcUrl() {
      return jdbcUrl;
    }

    @NonNull
    public Config setJdbcUrl(@Nullable String jdbcUrl) {
      this.jdbcUrl = jdbcUrl;
      return this;
    }

    @Nullable
    public String getUsername() {
      return username;
    }

    @NonNull
    public Config setUsername(@Nullable String username) {
      this.username = username;
      return this;
    }

    @Nullable
    public String getPassword() {
      return password;
    }

    @NonNull
    public Config setPassword(@Nullable String password) {
      this.password = password;
      return this;
    }

    public int getMaxPoolSize() {
      return maxPoolSize <= 0 ? 10 : maxPoolSize;
    }

    @NonNull
    public Config setMaxPoolSize(int maxPoolSize) {
      this.maxPoolSize = maxPoolSize;
      return this;
    }
  }

  /** A schema this {@code Database} must create and run Flyway migrations for. */
  public static final class SchemaRegistration {
    /** The SQL schema name (e.g. {@code titan}). */
    @NonNull public final String schemaName;

    /**
     * Flyway {@code classpath:} migration locations. The first is the portable baseline; further
     * entries are layered on (e.g. vendor-specific refinements).
     */
    @NonNull public final String[] portableLocations;

    /**
     * Locations applied <em>only</em> when the backing database is PostgreSQL (partial indexes,
     * etc.). May be empty.
     */
    @NonNull public final String[] postgresOnlyLocations;

    public SchemaRegistration(
        @NonNull String schemaName,
        @NonNull String[] portableLocations,
        @NonNull String[] postgresOnlyLocations) {
      this.schemaName = schemaName;
      this.portableLocations = portableLocations;
      this.postgresOnlyLocations = postgresOnlyLocations;
    }
  }

  /**
   * Register a schema for creation + migration. Idempotent per schema name (re-registration with
   * the same name replaces the prior entry). Call this before {@link #reconfigure}.
   */
  public static void registerSchema(@NonNull SchemaRegistration registration) {
    synchronized (LOCK) {
      SCHEMAS.put(registration.schemaName, registration);
      LOGGER.log(Level.FINE, "[titan-db] registered schema ''{0}''", registration.schemaName);
    }
  }

  /** Convenience: register a schema with only a portable baseline location. */
  public static void registerSchema(@NonNull String schemaName, @NonNull String portableLocation) {
    registerSchema(
        new SchemaRegistration(schemaName, new String[] {portableLocation}, new String[0]));
  }

  /**
   * Clear the schema registry. Test-only — the registry is process-global static state, so a test
   * exercising the "no schemas registered" path must reset it. Production code never calls this.
   */
  static void clearRegistryForTests() {
    synchronized (LOCK) {
      SCHEMAS.clear();
    }
  }

  /**
   * The live {@link DataSource}.
   *
   * <p><b>Does not lazy-initialize.</b> A pool is opened only by an explicit {@link
   * #reconfigure(Config)}; calling this before {@code reconfigure} is a programming/ordering error
   * and throws {@link IllegalStateException} rather than silently spinning up a throwaway embedded
   * H2 pool.
   *
   * @throws IllegalStateException if no pool has been configured yet
   */
  @NonNull
  public static DataSource dataSource() {
    HikariDataSource ds = dataSource;
    if (ds != null && !ds.isClosed()) {
      return ds;
    }
    synchronized (LOCK) {
      if (dataSource == null || dataSource.isClosed()) {
        throw new IllegalStateException(
            "titan-db: no database pool configured yet — "
                + "Database.reconfigure(...) must run before the DataSource is borrowed. "
                + "A caller touching the DB before that boot milestone is an ordering bug. "
                + "Tests/embedded use must call Database.reconfigure(config) (or "
                + "reconfigureWithEmbeddedDefault()) explicitly first.");
      }
      return dataSource;
    }
  }

  /** True once a (non-closed) pool has been opened by {@link #reconfigure}. */
  public static boolean isConfigured() {
    HikariDataSource ds = dataSource;
    return ds != null && !ds.isClosed();
  }

  /** Borrow a connection. Caller closes (try-with-resources). */
  @NonNull
  public static Connection connection() throws SQLException {
    return dataSource().getConnection();
  }

  /**
   * (Re)build the pool with the supplied config and run any pending Flyway migrations for every
   * registered schema. Synchronized so a config-reload during a request doesn't race with workers.
   *
   * <p>Callers are responsible for resetting any DAO/store façades that cache the {@link
   * DataSource} after this returns — {@code Database} is pure substrate.
   *
   * <p>A {@code null} config opens the embedded H2 default — equivalent to {@link
   * #reconfigureWithEmbeddedDefault()}.
   */
  public static void reconfigure(@Nullable Config config) {
    synchronized (LOCK) {
      initialize(config);
    }
  }

  /**
   * Deliberately open the embedded H2 default pool. This is the <em>explicit</em> fallback for
   * environments with no external database (pure unit tests, embedded single-process installs) —
   * equivalent to {@code reconfigure(null)} but named so the intent is unmistakable at the call
   * site. {@link #dataSource()} never opens this pool on its own.
   *
   * <p>Both this entrypoint and {@code reconfigure(null)} emit a {@link Level#WARNING}-level boot
   * log on the {@code Database} logger so embedded H2 in a production-shaped deployment is loud in
   * operator logs (issue #934).
   */
  public static void reconfigureWithEmbeddedDefault() {
    reconfigure(null);
  }

  /** Close the pool. Used by tests + the shutdown listener. */
  public static void shutdown() {
    synchronized (LOCK) {
      if (dataSource != null && !dataSource.isClosed()) {
        LOGGER.info("[titan-db] closing JDBC pool");
        dataSource.close();
      }
      dataSource = null;
      activeConfig = null;
    }
  }

  @Nullable
  public static Config activeConfig() {
    return activeConfig;
  }

  private static void initialize(@Nullable Config config) {
    Config effective = config != null ? config : Config.embeddedDefault();
    if (config == null) {
      // Issue #934 / V1 bar 5 audit row 6: the explicit embedded fallback is correctly named
      // but emits no loud signal. A production-shaped deployment that lands on this path
      // (a server module wiring this in by mistake, an admin tool, a test harness leaking
      // into prod) would otherwise boot silently against a throwaway H2 file. The WARN here
      // surfaces it in operator logs.
      LOGGER.log(
          Level.WARNING,
          "[titan-db] embedded H2 selected ({0}) — not for production. "
              + "This path is reached only via the explicit named entrypoints "
              + "Database.reconfigure(null) or Database.reconfigureWithEmbeddedDefault(). "
              + "If you see this in a production-shaped deployment, a caller has wired the "
              + "embedded fallback by mistake — set Config.jdbcUrl to point at the real "
              + "database (issue #934).",
          effective.getJdbcUrl());
    }
    String url = resolveJdbcUrl(effective);

    // Idempotency guard: if the pool is already open against the same URL,
    // skip the close/reopen dance. A repeated reconfigure with identical
    // settings (which historical JCasC reload paths produced) would otherwise
    // close the pool from under any in-flight DAO callers.
    //
    // We still re-run migrations: a second consumer may have registered its
    // schema after the first already opened the pool, so the same-URL case
    // must still pick up newly registered schemas.
    if (dataSource != null && !dataSource.isClosed() && url.equals(dataSource.getJdbcUrl())) {
      activeConfig = effective;
      ensureSchemas(url, effective.getUsername(), effective.getPassword());
      runMigrations(dataSource);
      return;
    }

    if (dataSource != null && !dataSource.isClosed()) {
      LOGGER.fine("[titan-db] closing existing JDBC pool before reconfigure");
      dataSource.close();
      dataSource = null;
    }
    HikariConfig hc = new HikariConfig();
    hc.setJdbcUrl(url);
    // Explicit driver class — defensive against ServiceLoader-isolated
    // classloaders and harmless on a plain JVM where DriverManager would
    // auto-discover the same driver.
    String driverClass = inferDriverClass(url);
    if (driverClass != null) {
      hc.setDriverClassName(driverClass);
    }
    String username = effective.getUsername();
    if (username != null && !username.isEmpty()) {
      hc.setUsername(username);
    }
    String password = effective.getPassword();
    if (password != null && !password.isEmpty()) {
      hc.setPassword(password);
    }
    // The pool is schema-neutral: it carries NO default schema. Each consumer's
    // DAO layer either schema-qualifies its SQL (the Titan engine DAOs use
    // `titan.*`) or pins the schema on the borrowed connection itself. This is
    // what lets two schema owners share one pool.
    hc.setPoolName("titan-db");
    hc.setMaximumPoolSize(effective.getMaxPoolSize());
    hc.setMinimumIdle(Math.min(2, effective.getMaxPoolSize()));
    hc.setConnectionTimeout(10_000);
    hc.setLeakDetectionThreshold(60_000);
    // Auto-commit on (default) — explicit transactions wrap multi-statement work.
    hc.setAutoCommit(true);

    // Pre-create every registered schema with a one-shot direct connection,
    // before the pool is built and before Flyway runs.
    ensureSchemas(url, username, password);

    LOGGER.log(Level.INFO, "[titan-db] opening JDBC pool: {0}", hc.getJdbcUrl());
    HikariDataSource ds = new HikariDataSource(hc);

    runMigrations(ds);

    dataSource = ds;
    activeConfig = effective;
  }

  /**
   * Resolve the effective JDBC URL. Ensures the parent directory of an H2 file URL exists (H2 does
   * not create it).
   *
   * <p><strong>Fail-closed (issue #926 — V1 bar 5):</strong> a non-null {@link Config} whose {@code
   * jdbcUrl} is {@code null} or blank is a programming error and throws {@link
   * IllegalStateException}. The previous behaviour silently substituted {@link
   * Config#embeddedDefault()} (file-mode embedded H2), which is the dev-only escape hatch this
   * guard removes. The two named entrypoints to the embedded H2 path — {@link #reconfigure(Config)}
   * with a {@code null} argument and {@link #reconfigureWithEmbeddedDefault()} — handle the
   * legitimate "no external DB" fallback in {@link #initialize(Config)} before this method is
   * reached, so this guard only fires when a caller hands in a {@code Config} they forgot to
   * populate.
   */
  @NonNull
  private static String resolveJdbcUrl(@NonNull Config config) {
    String raw = config.getJdbcUrl();
    if (raw == null || raw.trim().isEmpty()) {
      throw new IllegalStateException(
          "titan-db: Config.jdbcUrl is required and was null/blank. Embedded H2 fallback is "
              + "available only via the explicit named entrypoints "
              + "Database.reconfigure(null) or Database.reconfigureWithEmbeddedDefault() — "
              + "silent substitution of embedded H2 for a half-populated Config has been "
              + "removed (issue #926). Set Config.jdbcUrl explicitly, or call the named "
              + "embedded entrypoint if H2 is what you wanted.");
    }
    raw = raw.trim();
    if (raw.startsWith("jdbc:h2:file:")) {
      int afterPrefix = "jdbc:h2:file:".length();
      int semi = raw.indexOf(';', afterPrefix);
      String pathPart = semi >= 0 ? raw.substring(afterPrefix, semi) : raw.substring(afterPrefix);
      java.io.File dbFile = new java.io.File(pathPart);
      java.io.File parent = dbFile.getParentFile();
      if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) {
        LOGGER.log(Level.WARNING, "[titan-db] could not create DB directory: {0}", parent);
      }
    }
    return raw;
  }

  /**
   * Create every registered schema if it doesn't exist, using a single direct (non-pooled)
   * connection. Must run before Flyway. A no-op when nothing is registered yet.
   */
  private static void ensureSchemas(
      @NonNull String url, @Nullable String username, @Nullable String password) {
    if (SCHEMAS.isEmpty()) {
      return;
    }
    try (Connection c = java.sql.DriverManager.getConnection(url, username, password);
        java.sql.Statement st = c.createStatement()) {
      for (String schema : SCHEMAS.keySet()) {
        st.execute("CREATE SCHEMA IF NOT EXISTS " + schema);
      }
    } catch (SQLException e) {
      throw new IllegalStateException("titan-db: failed to create database schemas", e);
    }
  }

  /**
   * Run the Flyway baseline for every registered schema. Each schema lives in its own schema with
   * its own Flyway history table, so the registered consumers are versioned independently.
   */
  private static void runMigrations(HikariDataSource ds) {
    boolean isPostgres = ds.getJdbcUrl() != null && ds.getJdbcUrl().startsWith("jdbc:postgresql:");
    for (SchemaRegistration reg : SCHEMAS.values()) {
      migrateSchema(ds, reg.schemaName, effectiveLocations(reg, isPostgres));
    }
  }

  /** Portable locations, plus the PostgreSQL-only ones when the backend is PostgreSQL. */
  @NonNull
  private static String[] effectiveLocations(@NonNull SchemaRegistration reg, boolean isPostgres) {
    if (isPostgres && reg.postgresOnlyLocations.length > 0) {
      String[] all = new String[reg.portableLocations.length + reg.postgresOnlyLocations.length];
      System.arraycopy(reg.portableLocations, 0, all, 0, reg.portableLocations.length);
      System.arraycopy(
          reg.postgresOnlyLocations,
          0,
          all,
          reg.portableLocations.length,
          reg.postgresOnlyLocations.length);
      return all;
    }
    return reg.portableLocations;
  }

  private static void migrateSchema(HikariDataSource ds, String schema, String[] locations) {
    Flyway flyway =
        Flyway.configure(Database.class.getClassLoader())
            .dataSource(ds)
            .schemas(schema)
            .defaultSchema(schema)
            .createSchemas(true)
            .locations(locations)
            .baselineOnMigrate(true)
            .load();
    var result = flyway.migrate();
    if (!result.success) {
      throw new IllegalStateException(
          "titan-db Flyway migration failed for schema '"
              + schema
              + "' (initial="
              + result.initialSchemaVersion
              + ", target="
              + result.targetSchemaVersion
              + ")");
    }
    LOGGER.log(
        Level.INFO,
        "[titan-db] schema ''{0}'' at version {1} (applied {2} migrations)",
        new Object[] {schema, result.targetSchemaVersion, result.migrationsExecuted});
  }

  /**
   * Explicit driver-class hint — defensive against classloader-isolated environments where {@link
   * java.sql.DriverManager}'s ServiceLoader scan would miss a driver shipped on a non-system
   * classloader.
   */
  @Nullable
  private static String inferDriverClass(@NonNull String jdbcUrl) {
    if (jdbcUrl.startsWith("jdbc:h2:")) {
      return "org.h2.Driver";
    }
    if (jdbcUrl.startsWith("jdbc:postgresql:")) {
      return "org.postgresql.Driver";
    }
    if (jdbcUrl.startsWith("jdbc:mysql:") || jdbcUrl.startsWith("jdbc:mariadb:")) {
      return "org.mariadb.jdbc.Driver";
    }
    return null;
  }
}
