package io.adaptiq.titan.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies that the schema-neutral {@link Database} only creates and migrates the schemas a plugin
 * explicitly registers — the core of the titan/split: a plugin installed alone must never bring up
 * a foreign plugin's schema.
 *
 * <p>{@code titan-db-core} bundles the {@code titan} migration scripts but NOT the {@code
 * releaseflow} ones (those ship in the release-flow plugin). So registering only the {@code titan}
 * schema here exercises exactly the "Titan installed standalone" case.
 */
class SchemaNeutralDatabaseTest {

  @org.junit.jupiter.api.BeforeEach
  void resetRegistry() {
    Database.clearRegistryForTests();
  }

  @AfterEach
  void tearDown() {
    Database.shutdown();
    Database.clearRegistryForTests();
  }

  private static Set<String> schemas(Connection c) throws Exception {
    Set<String> found = new HashSet<>();
    try (Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("SELECT schema_name FROM information_schema.schemata")) {
      while (rs.next()) {
        found.add(rs.getString(1).toLowerCase());
      }
    }
    return found;
  }

  @Test
  void registeringOnlyTitanMigratesOnlyTitanSchema(@TempDir Path tmp) throws Exception {
    Database.Config cfg =
        Database.Config.of(
            "jdbc:h2:file:"
                + tmp.resolve("standalone").toAbsolutePath()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE");

    // The Titan plugin's TitanSchema.register() equivalent: only `titan`.
    Database.registerSchema(
        new Database.SchemaRegistration(
            "titan",
            new String[] {"classpath:io/adaptiq/titan/db/migration"},
            new String[] {"classpath:io/adaptiq/titan/db/migration-postgresql"}));

    Database.reconfigure(cfg);

    try (Connection c = Database.connection()) {
      Set<String> present = schemas(c);
      assertTrue(present.contains("titan"), "titan schema must be created; got: " + present);
      assertFalse(
          present.contains("releaseflow"),
          "releaseflow schema must NOT be created when only titan is registered; got: " + present);

      // The titan engine tables landed in the titan schema.
      try (Statement st = c.createStatement();
          ResultSet rs =
              st.executeQuery(
                  "SELECT count(*) FROM information_schema.tables "
                      + "WHERE table_schema = 'titan'")) {
        assertTrue(rs.next());
        assertTrue(rs.getInt(1) > 0, "titan schema should carry engine tables");
      }
    }
  }

  @Test
  void emptyRegistryCreatesNoSchemas(@TempDir Path tmp) throws Exception {
    Database.Config cfg =
        Database.Config.of(
            "jdbc:h2:file:" + tmp.resolve("empty").toAbsolutePath() + ";MODE=PostgreSQL");

    Database.reconfigure(cfg);

    try (Connection c = Database.connection()) {
      Set<String> present = schemas(c);
      assertFalse(present.contains("titan"));
      assertFalse(present.contains("releaseflow"));
    }
  }

  @Test
  void poolCarriesNoDefaultSchema(@TempDir Path tmp) throws Exception {
    Database.Config cfg =
        Database.Config.of(
            "jdbc:h2:file:" + tmp.resolve("neutral").toAbsolutePath() + ";MODE=PostgreSQL");
    Database.registerSchema("titan", "classpath:io/adaptiq/titan/db/migration");
    Database.reconfigure(cfg);

    try (Connection c = Database.connection()) {
      // A schema-neutral pool: borrowed connections default to H2's PUBLIC,
      // never to a plugin-owned schema. Consumers schema-qualify or pin.
      assertEquals("public", c.getSchema().toLowerCase());
    }
  }
}
