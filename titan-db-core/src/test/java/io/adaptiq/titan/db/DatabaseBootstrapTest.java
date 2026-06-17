package io.adaptiq.titan.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for {@link DatabaseBootstrap} — the generated SQL statement list, no database.
 *
 * <p>Adversarial intent: the audited least-privilege grant set is a security boundary, so these
 * tests assert both the positive (the seven tables are granted exactly their audited privileges)
 * and the negative (a forbidden table NEVER appears in any GRANT, and the worker role is never
 * given anything beyond LOGIN). A regression that widens the worker's reach should fail here long
 * before it reaches a database.
 */
class DatabaseBootstrapTest {

  private static final String SCHEMA = "titan";
  private static final String PW = "s3cr3t-pw";

  /** The seven tables the worker is allowed to touch. */
  private static final List<String> ALLOWED =
      List.of(
          "agents", "task_queue", "builds", "logs", "artifact", "fingerprint", "fingerprint_ref");

  /** Tables the worker must NEVER be granted anything on. */
  private static final List<String> FORBIDDEN =
      List.of(
          "jobs",
          "task_archive",
          "flow_nodes",
          "job_triggers",
          "discovery_sources",
          "discovery_events",
          "timers");

  private static List<String> sql() {
    return DatabaseBootstrap.roleStatements(SCHEMA, PW);
  }

  /** Returns just the GRANT … ON titan.<table> statements (not GRANT CONNECT / USAGE). */
  private static List<String> tableGrants() {
    return sql().stream().filter(s -> s.startsWith("GRANT ") && s.contains(SCHEMA + ".")).toList();
  }

  @Test
  void createsRoleWithNoAdministrativePower() {
    String createRole =
        sql().stream()
            .filter(s -> s.contains("CREATE ROLE"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no CREATE ROLE statement generated"));
    assertTrue(createRole.contains(DatabaseBootstrap.WORKER_ROLE), "role name");
    assertTrue(createRole.contains("LOGIN"), "must be a login role");
    assertTrue(createRole.contains("NOSUPERUSER"), "must be NOSUPERUSER");
    assertTrue(createRole.contains("NOCREATEDB"), "must be NOCREATEDB");
    assertTrue(createRole.contains("NOCREATEROLE"), "must be NOCREATEROLE");
    assertTrue(
        createRole.contains("duplicate_object"),
        "CREATE ROLE must be wrapped in a duplicate_object handler so a re-run is a no-op");
  }

  @Test
  void setsPasswordFromConfigNotHardcoded() {
    String alter =
        sql().stream()
            .filter(s -> s.startsWith("ALTER ROLE"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no ALTER ROLE … PASSWORD statement"));
    assertTrue(
        alter.contains("PASSWORD '" + PW + "'"), "password literal must come from the argument");
  }

  @Test
  void passwordLiteralIsSqlEscaped() {
    // A password with an embedded single quote must not break out of the string literal.
    List<String> stmts =
        DatabaseBootstrap.roleStatements(SCHEMA, "pw'; DROP TABLE titan.builds;--");
    String alter = stmts.stream().filter(s -> s.startsWith("ALTER ROLE")).findFirst().orElseThrow();
    assertTrue(
        alter.contains("PASSWORD 'pw''; DROP TABLE titan.builds;--'"),
        "single quote must be doubled");
  }

  @Test
  void revokesEverythingBeforeGranting() {
    List<String> stmts = sql();
    int revokeAllTables = indexOfContaining(stmts, "REVOKE ALL ON ALL TABLES IN SCHEMA " + SCHEMA);
    assertTrue(revokeAllTables >= 0, "must REVOKE ALL on all tables");
    int firstTableGrant = -1;
    for (int i = 0; i < stmts.size(); i++) {
      if (stmts.get(i).startsWith("GRANT ") && stmts.get(i).contains(SCHEMA + ".")) {
        firstTableGrant = i;
        break;
      }
    }
    assertTrue(
        firstTableGrant > revokeAllTables, "the blanket REVOKE must run BEFORE any table GRANT");
  }

  @Test
  void grantsConnectAndSchemaUsage() {
    List<String> stmts = sql();
    assertTrue(
        stmts.stream().anyMatch(s -> s.contains("GRANT CONNECT ON DATABASE")),
        "worker role must be able to connect to the database");
    assertTrue(
        stmts.stream()
            .anyMatch(
                s ->
                    s.equals(
                        "GRANT USAGE ON SCHEMA "
                            + SCHEMA
                            + " TO "
                            + DatabaseBootstrap.WORKER_ROLE)),
        "worker role must have USAGE on the schema");
  }

  @Test
  void everyAllowedTableGetsExactlyItsAuditedGrant() {
    List<String> grants = tableGrants();
    assertEquals(privilegesFor(grants, "agents"), "SELECT, INSERT, UPDATE");
    assertEquals(privilegesFor(grants, "artifact"), "SELECT, INSERT, UPDATE");
    assertEquals(privilegesFor(grants, "task_queue"), "SELECT, UPDATE");
    assertEquals(privilegesFor(grants, "builds"), "SELECT, UPDATE");
    assertEquals(privilegesFor(grants, "fingerprint"), "SELECT, INSERT");
    assertEquals(privilegesFor(grants, "fingerprint_ref"), "SELECT, INSERT");
    assertEquals(privilegesFor(grants, "logs"), "INSERT");
  }

  @Test
  void onConflictTablesAllCarrySelect() {
    // artifact / fingerprint / fingerprint_ref are upserted via INSERT … ON CONFLICT, which
    // PostgreSQL requires SELECT for. This must never be "optimized" away.
    List<String> grants = tableGrants();
    for (String t : List.of("artifact", "fingerprint", "fingerprint_ref")) {
      assertTrue(privilegesFor(grants, t).contains("SELECT"), t + " needs SELECT for ON CONFLICT");
    }
  }

  @Test
  void noForbiddenTableIsEverGranted() {
    List<String> grants = tableGrants();
    for (String forbidden : FORBIDDEN) {
      for (String g : grants) {
        assertFalse(
            g.contains(SCHEMA + "." + forbidden + " ") || g.endsWith(SCHEMA + "." + forbidden),
            "forbidden table '" + forbidden + "' must not appear in any GRANT: " + g);
      }
    }
  }

  @Test
  void allSevenAllowedTablesAreCovered() {
    List<String> grants = tableGrants();
    for (String t : ALLOWED) {
      assertFalse(
          privilegesFor(grants, t).isEmpty(), "allowed table '" + t + "' must have a grant");
    }
  }

  @Test
  void generatedSqlIsDeterministic() {
    // Idempotency at the SQL level: two calls with the same input yield byte-identical SQL,
    // so re-running the bootstrap every boot is a stable no-op.
    assertEquals(sql(), sql());
    assertEquals(
        DatabaseBootstrap.roleStatements(SCHEMA, PW), DatabaseBootstrap.roleStatements(SCHEMA, PW));
  }

  private static int indexOfContaining(List<String> stmts, String needle) {
    for (int i = 0; i < stmts.size(); i++) {
      if (stmts.get(i).contains(needle)) {
        return i;
      }
    }
    return -1;
  }

  /**
   * Extract the privilege list a given table is granted, by parsing the {@code GRANT <privs> ON
   * titan.<t>, …} statements. Returns an empty string when the table appears in no grant.
   */
  private static String privilegesFor(List<String> grants, String table) {
    String target = SCHEMA + "." + table;
    for (String g : grants) {
      // GRANT <privs> ON <tables> TO titan_worker
      int on = g.indexOf(" ON ");
      int to = g.lastIndexOf(" TO ");
      if (on < 0 || to < 0) {
        continue;
      }
      String privs = g.substring("GRANT ".length(), on);
      String tablesPart = g.substring(on + " ON ".length(), to);
      for (String tbl : tablesPart.split(",")) {
        if (tbl.trim().equals(target)) {
          return privs;
        }
      }
    }
    return "";
  }
}
