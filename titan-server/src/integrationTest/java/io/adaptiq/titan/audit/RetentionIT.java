package io.adaptiq.titan.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.adaptiq.titan.store.TitanStores;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Issue #1104 — nightly audit-log retention purge against real PostgreSQL + real Flyway migrations
 * (V42 seed included).
 *
 * <p>Scenarios:
 *
 * <ul>
 *   <li>Default horizon (90d): a generic kind's rows older than 90 days are purged; recent rows
 *       survive.
 *   <li>Per-kind override (365d): a security kind (PAT_CREATE) keeps rows up to 365 days but purges
 *       beyond — kind-specific &gt; default, end-to-end through the seeded policy table.
 *   <li>Batching: more old rows than one batch are still fully drained (the loop, not one delete).
 *   <li>Idempotent: a second pass deletes nothing.
 *   <li>Adversarial: a 0-day policy is refused at the persistence boundary by the DB CHECK
 *       constraint (the operator-misconfig guard's last line of defence).
 * </ul>
 */
@Testcontainers
class RetentionIT {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");

  private HikariDataSource ds;
  private TitanStores stores;
  private Instant now;

  @BeforeEach
  void setUp() throws Exception {
    HikariConfig cfg = new HikariConfig();
    cfg.setJdbcUrl(POSTGRES.getJdbcUrl());
    cfg.setUsername(POSTGRES.getUsername());
    cfg.setPassword(POSTGRES.getPassword());
    cfg.setDriverClassName("org.postgresql.Driver");
    cfg.setMaximumPoolSize(4);
    ds = new HikariDataSource(cfg);

    try (Connection c = ds.getConnection();
        Statement st = c.createStatement()) {
      st.execute("DROP SCHEMA IF EXISTS titan CASCADE");
      st.execute("CREATE SCHEMA titan");
    }
    Flyway.configure(getClass().getClassLoader())
        .dataSource(ds)
        .schemas("titan")
        .defaultSchema("titan")
        .locations(
            "classpath:io/adaptiq/titan/db/migration",
            "classpath:io/adaptiq/titan/db/migration-postgresql")
        .load()
        .migrate();
    stores = TitanStores.forDataSource(ds);
    now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
  }

  @AfterEach
  void tearDown() {
    if (ds != null) {
      ds.close();
    }
  }

  @Test
  void purgeDropsRowsOlderThanDefaultHorizonAndKeepsRecent() throws Exception {
    // Default policy from the V42 seed is 90 days for JOB_CREATE (no per-kind override).
    insertAudit("JOB_CREATE", now.minus(120, ChronoUnit.DAYS)); // old → purged
    insertAudit("JOB_CREATE", now.minus(91, ChronoUnit.DAYS)); // just over → purged
    insertAudit("JOB_CREATE", now.minus(89, ChronoUnit.DAYS)); // just under → kept
    insertAudit("JOB_CREATE", now.minus(1, ChronoUnit.DAYS)); // recent → kept
    assertEquals(4, count("action = 'JOB_CREATE'"));

    RetentionJob job = new RetentionJob(stores, 10_000);
    RetentionJob.PurgePass pass = job.purge(now);

    assertEquals(2, pass.rowsDeleted(), "the two rows older than 90 days are purged");
    assertEquals(2, count("action = 'JOB_CREATE'"), "the two recent rows survive");
    assertEquals(
        0,
        count(
            "action = 'JOB_CREATE' AND occurred_at < '"
                + ts(now.minus(90, ChronoUnit.DAYS))
                + "'"));
  }

  @Test
  void perKindOverrideKeepsSecurityRowsLongerThanDefault() throws Exception {
    // PAT_CREATE has a seeded 365-day policy; a JOB_CREATE control uses the 90-day default.
    insertAudit("PAT_CREATE", now.minus(200, ChronoUnit.DAYS)); // < 365 → kept (would die at 90)
    insertAudit("PAT_CREATE", now.minus(400, ChronoUnit.DAYS)); // > 365 → purged
    insertAudit("JOB_CREATE", now.minus(200, ChronoUnit.DAYS)); // > 90 → purged

    RetentionJob job = new RetentionJob(stores, 10_000);
    job.purge(now);

    assertEquals(
        1,
        count("action = 'PAT_CREATE'"),
        "the 200-day PAT_CREATE survives under the 365-day override; the 400-day one is purged");
    assertEquals(
        1,
        count(
            "action = 'PAT_CREATE' AND occurred_at >= '"
                + ts(now.minus(365, ChronoUnit.DAYS))
                + "'"));
    assertEquals(
        0,
        count("action = 'JOB_CREATE'"),
        "the same-age JOB_CREATE dies under the 90-day default — kind-specific > default");
  }

  @Test
  void purgeDrainsAcrossMultipleBatches() throws Exception {
    // Batch size 5, seed 23 old rows of one kind → 5 batches (5+5+5+5+3). All must go.
    for (int i = 0; i < 23; i++) {
      insertAudit("BUILD_TRIGGER", now.minus(100 + i, ChronoUnit.DAYS));
    }
    insertAudit("BUILD_TRIGGER", now.minus(2, ChronoUnit.DAYS)); // recent survivor
    assertEquals(24, count("action = 'BUILD_TRIGGER'"));

    RetentionJob job = new RetentionJob(stores, 5);
    RetentionJob.PurgePass pass = job.purge(now);

    assertEquals(23, pass.rowsDeleted(), "all 23 old rows drained across batches");
    assertEquals(1, count("action = 'BUILD_TRIGGER'"), "only the recent row remains");
  }

  @Test
  void purgeIsIdempotent() throws Exception {
    insertAudit("JOB_CREATE", now.minus(120, ChronoUnit.DAYS));
    insertAudit("JOB_CREATE", now.minus(1, ChronoUnit.DAYS));

    RetentionJob job = new RetentionJob(stores, 10_000);
    assertEquals(1, job.purge(now).rowsDeleted());
    assertEquals(0, job.purge(now).rowsDeleted(), "second pass finds nothing to delete");
    assertEquals(1, count("action = 'JOB_CREATE'"));
  }

  @Test
  void zeroDayPolicyIsRefusedAtThePersistenceBoundary() {
    // Operator-misconfig guard, last line of defence: even a direct upsert of a 0-day policy is
    // rejected by the DB CHECK (ck_audit_retention_max_age_positive). The job therefore can never
    // observe a 0-day row that would purge a kind's entire history.
    //
    // Strict assertion (#1196 review): a vague "some message or cause exists" would also pass for a
    // stray NullPointerException from a code bug and would NOT prove the defence-in-depth claim. We
    // require the failure to be the actual PostgreSQL check_violation — SQLState 23514 raised by
    // the
    // ck_audit_retention_max_age_positive constraint — somewhere in the cause chain.
    RuntimeException ex =
        assertThrows(
            RuntimeException.class, () -> stores.auditRetentionPolicy().upsert("JOB_CREATE", 0));

    SQLException checkViolation = findCheckViolation(ex);
    assertNotNull(
        checkViolation,
        "expected a SQLState 23514 (check_violation) in the cause chain, got: " + describe(ex));
    assertEquals(
        "23514",
        checkViolation.getSQLState(),
        "the 0-day upsert must fail the DB CHECK, not some other error");
    assertTrue(
        chainText(ex).contains("ck_audit_retention_max_age_positive"),
        "the violation must name the retention CHECK constraint, got: " + describe(ex));
  }

  /** Walk the cause chain for a SQL check-constraint violation (PostgreSQL SQLState 23514). */
  private static SQLException findCheckViolation(Throwable t) {
    for (Throwable c = t; c != null; c = c.getCause()) {
      if (c instanceof SQLException sql && "23514".equals(sql.getSQLState())) {
        return sql;
      }
      if (c.getCause() == c) {
        break;
      }
    }
    return null;
  }

  /** Concatenate the message of every throwable in the cause chain (constraint name lives here). */
  private static String chainText(Throwable t) {
    StringBuilder sb = new StringBuilder();
    for (Throwable c = t; c != null && c.getCause() != c; c = c.getCause()) {
      sb.append(c).append(" | ");
    }
    return sb.toString();
  }

  private static String describe(Throwable t) {
    return chainText(t);
  }

  @Test
  void newlySeededSecurityKindsGetThe365DayTrail() throws Exception {
    // #1196 review (sev2/product): the V42 seed now covers admin SSO role-config changes and the
    // RBAC-decision actions too, not just the PAT_* / RBAC_CHECK set. Prove one of the added kinds
    // (SSO_MAPPING_CREATE) keeps a 200-day-old row that the 90-day default would have purged.
    insertAudit("SSO_MAPPING_CREATE", now.minus(200, ChronoUnit.DAYS)); // < 365 → kept
    insertAudit("SSO_MAPPING_CREATE", now.minus(400, ChronoUnit.DAYS)); // > 365 → purged
    insertAudit("JOB_CREATE", now.minus(200, ChronoUnit.DAYS)); // control: 90-day default → purged

    new RetentionJob(stores, 10_000).purge(now);

    assertEquals(
        1,
        count("action = 'SSO_MAPPING_CREATE'"),
        "the 200-day SSO mapping change survives under the seeded 365-day trail; the 400-day one"
            + " is purged");
    assertEquals(
        0,
        count("action = 'JOB_CREATE'"),
        "the same-age JOB_CREATE dies at the 90-day default — proves SSO_MAPPING_CREATE was the"
            + " 365 override, not the default");
  }

  @Test
  void overrideViaDaoThenPurgeHonoursTheNewHorizon() throws Exception {
    // Operator override path: set JOB_CREATE to 30 days, then a 45-day-old row must be purged even
    // though the default is 90.
    stores.auditRetentionPolicy().upsert("JOB_CREATE", 30);
    insertAudit("JOB_CREATE", now.minus(45, ChronoUnit.DAYS)); // > 30 → purged
    insertAudit("JOB_CREATE", now.minus(20, ChronoUnit.DAYS)); // < 30 → kept

    new RetentionJob(stores, 10_000).purge(now);

    assertEquals(1, count("action = 'JOB_CREATE'"), "override horizon (30d) applied, not default");
  }

  // ---------- helpers ----------

  private void insertAudit(String action, Instant occurredAt) throws Exception {
    try (Connection c = ds.getConnection();
        PreparedStatement ps =
            c.prepareStatement(
                "INSERT INTO titan.audit_log (occurred_at, actor, action, target_type, target_id) "
                    + "VALUES (?, 'tester', ?, 'JOB', '1')")) {
      ps.setTimestamp(1, Timestamp.from(occurredAt));
      ps.setString(2, action);
      ps.executeUpdate();
    }
  }

  private static String ts(Instant i) {
    return Timestamp.from(i).toString();
  }

  private int count(String where) throws Exception {
    try (Connection c = ds.getConnection();
        Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM titan.audit_log WHERE " + where)) {
      rs.next();
      return rs.getInt(1);
    }
  }
}
