package io.adaptiq.titan.db;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.sql.DataSource;

/**
 * Controller-side "app-managed database" bootstrap — the Develocity-style pattern.
 *
 * <p>The Titan controller connects to PostgreSQL with a DDL-capable account (it runs Flyway). The
 * Titan <em>worker</em> must NOT have that power: it issues a small, audited set of SQL statements
 * (only through {@code titan-worker/.../worker/WorkerDb.java}) and nothing else. This class
 * provisions a dedicated least-privilege login role — {@code titan_worker} — and applies exactly
 * the grants that audited statement set needs.
 *
 * <p>It runs in-process on the controller, immediately AFTER Flyway completes, on every boot. The
 * re-run is intentional: it self-heals the grant set after a future migration adds a table or
 * column — the worker role never silently drifts to over- or under-privileged.
 *
 * <p><b>The audited grant set</b> (schema {@code titan}). The worker may touch only these seven
 * tables:
 *
 * <table>
 *   <caption>least-privilege grants</caption>
 *   <tr><th>table</th><th>grant</th></tr>
 *   <tr><td>agents</td><td>SELECT, INSERT, UPDATE</td></tr>
 *   <tr><td>task_queue</td><td>SELECT, UPDATE</td></tr>
 *   <tr><td>builds</td><td>SELECT, UPDATE</td></tr>
 *   <tr><td>logs</td><td>INSERT</td></tr>
 *   <tr><td>artifact</td><td>SELECT, INSERT, UPDATE</td></tr>
 *   <tr><td>fingerprint</td><td>SELECT, INSERT</td></tr>
 *   <tr><td>fingerprint_ref</td><td>SELECT, INSERT</td></tr>
 * </table>
 *
 * <p>Everything else gets nothing — {@code jobs}, {@code task_archive}, {@code flow_nodes}, {@code
 * job_triggers}, {@code discovery_sources}, {@code discovery_events} (and any future table such as
 * {@code timers}) stay off-limits. The construction is "revoke ALL on ALL tables, then grant the
 * seven", so a newly-migrated table is denied by default until it is explicitly added here.
 *
 * <p><b>Why SELECT on the INSERT-only-looking tables.</b> PostgreSQL's {@code INSERT … ON CONFLICT}
 * — both {@code DO UPDATE} and {@code DO NOTHING} — requires SELECT on the target table to evaluate
 * the conflict arbiter. {@code artifact}, {@code fingerprint} and {@code fingerprint_ref} therefore
 * carry SELECT even though the worker only ever upserts into them. Do NOT "optimize" SELECT away.
 *
 * <p><b>PostgreSQL only.</b> Roles + GRANTs are a PostgreSQL concept; on the embedded H2 default
 * there is no separate worker process and no role to provision, so {@link #applyWorkerRole} is a
 * no-op for any non-PostgreSQL URL. The class never fails controller startup: a missing password or
 * a non-Postgres backend is logged and skipped.
 */
public final class DatabaseBootstrap {

  private static final Logger LOGGER = Logger.getLogger(DatabaseBootstrap.class.getName());

  /** The least-privilege login role the worker authenticates as. */
  public static final String WORKER_ROLE = "titan_worker";

  /** Tables the worker may read AND write (SELECT + INSERT + UPDATE). */
  private static final List<String> READ_WRITE = List.of("agents", "artifact");

  /** Tables the worker may read + UPDATE (no INSERT). */
  private static final List<String> READ_UPDATE = List.of("task_queue", "builds");

  /** Tables the worker may read + INSERT (no UPDATE). The SELECT covers {@code ON CONFLICT}. */
  private static final List<String> READ_INSERT = List.of("fingerprint", "fingerprint_ref");

  /** Tables the worker may only INSERT into (append-only — never read or update). */
  private static final List<String> INSERT_ONLY = List.of("logs");

  private DatabaseBootstrap() {}

  /**
   * Provision (or reconcile) the least-privilege {@code titan_worker} role and its grants. Safe and
   * correct to call on every controller boot — fully idempotent.
   *
   * <p>Skipped (logged, not failed) when: the backend is not PostgreSQL, or {@code workerPassword}
   * is null/blank (no shared secret configured).
   *
   * @param dataSource the controller's DDL-capable pool (post-Flyway)
   * @param schema the schema whose tables the worker role is scoped to (e.g. {@code titan})
   * @param workerPassword the shared worker DB password; the worker authenticates with the same
   *     value. Null/blank → bootstrap skipped.
   */
  public static void applyWorkerRole(
      @NonNull DataSource dataSource, @NonNull String schema, @Nullable String workerPassword) {
    String jdbcUrl = jdbcUrlOf(dataSource);
    if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:postgresql:")) {
      LOGGER.log(
          Level.FINE,
          "[titan-db] worker-role bootstrap skipped: backend is not PostgreSQL ({0})",
          jdbcUrl);
      return;
    }
    if (workerPassword == null || workerPassword.isBlank()) {
      LOGGER.warning(
          "[titan-db] worker-role bootstrap SKIPPED: no worker DB password configured "
              + "(set TITAN_WORKER_DB_PASSWORD). The titan_worker least-privilege role will NOT be "
              + "created/reconciled — the worker must then connect with another account.");
      return;
    }
    try (Connection c = dataSource.getConnection()) {
      boolean priorAutoCommit = c.getAutoCommit();
      c.setAutoCommit(false);
      try (Statement st = c.createStatement()) {
        for (String sql : roleStatements(schema, workerPassword)) {
          st.execute(sql);
        }
        c.commit();
      } catch (SQLException e) {
        c.rollback();
        throw e;
      } finally {
        c.setAutoCommit(priorAutoCommit);
      }
      LOGGER.log(
          Level.INFO,
          "[titan-db] least-privilege worker role ''{0}'' provisioned/reconciled on schema ''{1}''",
          new Object[] {WORKER_ROLE, schema});
    } catch (SQLException e) {
      // A failure here must not take down the controller: the controller
      // itself is fully functional; only the least-privilege worker login
      // is unprovisioned. Log loudly so the operator can investigate.
      LOGGER.log(
          Level.SEVERE,
          "[titan-db] worker-role bootstrap FAILED — the titan_worker role may be missing or "
              + "stale. The controller continues; investigate the worker DB grants.",
          e);
    }
  }

  /**
   * The ordered, idempotent statement list the bootstrap executes. Exposed package-private so a
   * unit test can assert the generated SQL without a database.
   *
   * <p>The {@code CREATE ROLE} uses a {@code DO $$ … $$} block because PostgreSQL has no {@code
   * CREATE ROLE IF NOT EXISTS}; the {@code duplicate_object} handler makes a re-run a no-op.
   */
  @NonNull
  static List<String> roleStatements(@NonNull String schema, @NonNull String workerPassword) {
    String quotedPw = quoteLiteral(workerPassword);
    return List.of(
        // 1. Create the login role if absent. NOSUPERUSER/NOCREATEDB/NOCREATEROLE — a pure
        //    application login, no administrative power. The exception handler swallows a
        //    concurrent/prior creation, making the block idempotent.
        "DO $$ BEGIN "
            + "CREATE ROLE "
            + WORKER_ROLE
            + " LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE; "
            + "EXCEPTION WHEN duplicate_object THEN NULL; END $$",
        // 2. Set/refresh the password every boot — the controller and the worker share this
        //    one secret. ALTER ROLE is idempotent.
        "ALTER ROLE " + WORKER_ROLE + " WITH LOGIN PASSWORD " + quotedPw,
        // 3. The role must be able to connect to this database and resolve the schema.
        //    GRANT CONNECT needs a literal database name (no expression accepted), and the
        //    bootstrap does not parse the JDBC URL — so the database name is resolved at
        //    run time inside a DO block via format()/EXECUTE against current_database().
        "DO $$ BEGIN "
            + "EXECUTE format('GRANT CONNECT ON DATABASE %I TO "
            + WORKER_ROLE
            + "', "
            + "current_database()); END $$",
        "GRANT USAGE ON SCHEMA " + schema + " TO " + WORKER_ROLE,
        // 4. Revoke EVERYTHING first, then grant only the audited seven. This is what makes
        //    a newly-migrated table denied-by-default: it is covered by the blanket revoke
        //    and gets no grant unless it is explicitly listed below.
        "REVOKE ALL ON ALL TABLES IN SCHEMA " + schema + " FROM " + WORKER_ROLE,
        "REVOKE ALL ON ALL SEQUENCES IN SCHEMA " + schema + " FROM " + WORKER_ROLE,
        // 5. The seven audited table grants.
        grant("SELECT, INSERT, UPDATE", schema, READ_WRITE),
        grant("SELECT, UPDATE", schema, READ_UPDATE),
        grant("SELECT, INSERT", schema, READ_INSERT),
        grant("INSERT", schema, INSERT_ONLY),
        // 6. INSERT into a table with a generated identity/serial PK needs USAGE on the
        //    backing sequence. Grant USAGE on every sequence in the schema — a sequence is
        //    not a table, so it is not a privilege-escalation surface, and scoping it
        //    per-table would be fragile against migrations. SELECT/UPDATE on sequences is
        //    NOT granted (that would let the worker skip/reset counters).
        "GRANT USAGE ON ALL SEQUENCES IN SCHEMA " + schema + " TO " + WORKER_ROLE);
  }

  /** {@code GRANT <privs> ON titan.<t1>, titan.<t2>, … TO titan_worker}. */
  @NonNull
  private static String grant(
      @NonNull String privileges, @NonNull String schema, @NonNull List<String> tables) {
    StringBuilder sb = new StringBuilder("GRANT ").append(privileges).append(" ON ");
    for (int i = 0; i < tables.size(); i++) {
      if (i > 0) {
        sb.append(", ");
      }
      sb.append(schema).append('.').append(tables.get(i));
    }
    return sb.append(" TO ").append(WORKER_ROLE).toString();
  }

  /** Single-quote a SQL string literal, doubling any embedded single quotes. */
  @NonNull
  private static String quoteLiteral(@NonNull String value) {
    return "'" + value.replace("'", "''") + "'";
  }

  @Nullable
  private static String jdbcUrlOf(@NonNull DataSource dataSource) {
    if (dataSource instanceof com.zaxxer.hikari.HikariDataSource hds) {
      return hds.getJdbcUrl();
    }
    try (Connection c = dataSource.getConnection()) {
      return c.getMetaData().getURL();
    } catch (SQLException e) {
      LOGGER.log(
          Level.FINE, "[titan-db] could not determine JDBC URL for worker-role bootstrap", e);
      return null;
    }
  }
}
