package io.adaptiq.titan.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.adaptiq.titan.store.TitanStores;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Issue #633 — daily prune of {@code titan.task_archive} past the retention horizon.
 *
 * <p>{@code task_archive} otherwise grows unbounded (every terminal task ever processed lives there
 * forever). {@link TaskArchivePruner#prune(TitanStores, int)} deletes rows whose {@code
 * completed_at} is older than {@code retentionDays * 24h}. A {@code retentionDays} of {@code 0} is
 * the operator opt-out — the prune must be a no-op so a deployment that wants full history just
 * sets the env to 0.
 *
 * <p>Adversarial scenarios:
 *
 * <ul>
 *   <li>Mixed old/recent rows: only the old ones go.
 *   <li>Re-running the prune is a no-op (idempotent).
 *   <li>{@code retentionDays=0} touches nothing — operator opt-out is honoured.
 * </ul>
 */
@Testcontainers
class TaskArchivePruneIT {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");

  private HikariDataSource ds;
  private TitanStores stores;

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
  }

  @AfterEach
  void tearDown() {
    if (ds != null) {
      ds.close();
    }
  }

  @Test
  void pruneDeletesOnlyRowsOlderThanRetentionHorizon() throws Exception {
    Instant now = Instant.now();
    // 5 old rows (completed 60 days ago) + 5 recent rows (completed 5 days ago).
    try (Connection c = ds.getConnection()) {
      for (int i = 0; i < 5; i++) {
        insertArchive(c, "old-" + i, Timestamp.from(now.minusSeconds(60L * 86_400)));
      }
      for (int i = 0; i < 5; i++) {
        insertArchive(c, "recent-" + i, Timestamp.from(now.minusSeconds(5L * 86_400)));
      }
    }
    assertEquals(10, countArchive(), "all 10 rows seeded");

    // retentionDays=30 → the 5 old rows are 60d > 30d, the 5 recent are 5d < 30d.
    int pruned = TaskArchivePruner.prune(stores, 30);
    assertEquals(5, pruned, "exactly the 5 rows past the 30-day horizon are deleted");
    assertEquals(5, countArchive(), "the 5 recent rows survive");

    // Idempotent — a second prune at the same horizon is a no-op.
    int prunedAgain = TaskArchivePruner.prune(stores, 30);
    assertEquals(0, prunedAgain, "a re-run finds nothing past horizon — no-op");
    assertEquals(5, countArchive(), "row count unchanged on idempotent re-run");
  }

  @Test
  void retentionDaysZeroIsOperatorOptOutAndDeletesNothing() throws Exception {
    Instant now = Instant.now();
    try (Connection c = ds.getConnection()) {
      // Even rows from a year ago must survive when retention is disabled.
      for (int i = 0; i < 3; i++) {
        insertArchive(c, "ancient-" + i, Timestamp.from(now.minusSeconds(365L * 86_400)));
      }
    }
    assertEquals(3, countArchive());

    int pruned = TaskArchivePruner.prune(stores, 0);
    assertEquals(0, pruned, "retentionDays=0 is the opt-out — no deletes");
    assertEquals(3, countArchive(), "ancient rows preserved when pruning is disabled");
  }

  /** Insert a synthetic task_archive row with the supplied completed_at. */
  private static void insertArchive(Connection c, String marker, Timestamp completedAt)
      throws Exception {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO titan.task_archive (type, queue_name, status, priority, "
                + "payload_json, attempts, max_attempts, visibility_timeout_seconds, "
                + "task_token, created_at, completed_at) "
                + "VALUES ('EXECUTE_COMMAND', 'default', 'COMPLETED', 0, "
                + "?, 1, 3, 300, ?, ?, ?)",
            Statement.RETURN_GENERATED_KEYS)) {
      ps.setString(1, "{\"marker\":\"" + marker + "\"}");
      ps.setObject(2, UUID.randomUUID());
      ps.setTimestamp(3, completedAt);
      ps.setTimestamp(4, completedAt);
      ps.executeUpdate();
    }
  }

  private int countArchive() throws Exception {
    try (Connection c = ds.getConnection();
        Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM titan.task_archive")) {
      rs.next();
      return rs.getInt(1);
    }
  }
}
