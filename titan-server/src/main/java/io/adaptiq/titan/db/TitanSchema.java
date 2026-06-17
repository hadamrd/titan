package io.adaptiq.titan.db;

/**
 * Registers the {@code titan} engine schema with the shared {@link Database}.
 *
 * <p>The JDBC pool + Flyway runner live in the schema-neutral {@code titan-db-core} jar — that jar
 * does not know which schemas exist. The Titan plugin owns the {@code titan} schema (7 engine
 * tables) and is responsible for telling {@code Database} about it via {@link
 * Database#registerSchema}.
 *
 * <p>Registration must happen before any {@link Database#reconfigure} call. There are two such
 * call-sites: {@code TitanDatabaseBootstrap} at boot, and {@code TitanConfiguration#setDatabase}
 * during a JCasC apply / config-form submit (which can fire before the boot initializer). Both call
 * {@link #register()} first; it is idempotent and cheap.
 */
public final class TitanSchema {

  /** The {@code titan} schema name. */
  public static final String SCHEMA = "titan";

  /** Titan execution-engine schema: 7 engine tables in the {@code titan} schema. */
  private static final String MIGRATIONS_LOCATION = "classpath:io/adaptiq/titan/db/migration";

  /**
   * PostgreSQL-only refinements of the Titan schema (e.g. the partial claimable index). Kept in a
   * sibling directory rather than a {@code migration/postgresql} subfolder because Flyway scans
   * locations recursively — a subfolder would also be picked up by the portable H2 path.
   */
  private static final String MIGRATIONS_LOCATION_PG =
      "classpath:io/adaptiq/titan/db/migration-postgresql";

  private TitanSchema() {}

  /** Register the {@code titan} schema with {@link Database}. Idempotent. */
  public static void register() {
    Database.registerSchema(
        new Database.SchemaRegistration(
            SCHEMA, new String[] {MIGRATIONS_LOCATION}, new String[] {MIGRATIONS_LOCATION_PG}));
  }
}
