package io.adaptiq.titan.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.TaskQueueRow;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Postgres-backed integration test for the admin queue controls (closes #347).
 *
 * <p>Pins down the DAO-level contract that backs {@link AdminQueueApi#drain()} and {@link
 * AdminQueueApi#reorder}: real PostgreSQL semantics for the {@code UPDATE ... WHERE status =
 * 'QUEUED'} drain sweep and the per-row {@code setPriority} mutation. The OIDC / JAX-RS glue is
 * unit-test territory ({@code AdminQueueApiTest}). Same shape as {@code StatsApiIT}.
 */
@Testcontainers
class AdminQueueApiIT {

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
    cfg.setMaximumPoolSize(8);
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
  void drain_marksAllQueuedAsCancelled() {
    long a = enqueue("drain-a", 0);
    long b = enqueue("drain-b", 0);
    long c = enqueue("drain-c", 0);

    int n = stores.taskQueue().drainAllQueued();

    assertEquals(3, n, "all three QUEUED tasks drained");
    assertEquals("CANCELLED", stores.taskQueue().findById(a).orElseThrow().status);
    assertEquals("CANCELLED", stores.taskQueue().findById(b).orElseThrow().status);
    assertEquals("CANCELLED", stores.taskQueue().findById(c).orElseThrow().status);

    // Idempotent: a second pass affects zero rows.
    assertEquals(0, stores.taskQueue().drainAllQueued());
  }

  @Test
  void reorder_changesPriorityOrder() {
    long a = enqueue("reorder-a", 5);
    long b = enqueue("reorder-b", 5);
    long c = enqueue("reorder-c", 5);

    // Baseline: priorities are equal, so claim order is by created_at (a, b, c).
    List<TaskQueueRow> before = stores.taskQueue().listQueued(0, 10);
    assertTrue(before.stream().anyMatch(r -> r.id == a), "baseline includes seeded task a");

    // Reorder head-first: c, a, b — assign descending priorities.
    assertEquals(1, stores.taskQueue().setPriority(c, 1_000_000));
    assertEquals(1, stores.taskQueue().setPriority(a, 999_999));
    assertEquals(1, stores.taskQueue().setPriority(b, 999_998));

    List<TaskQueueRow> after = stores.taskQueue().listQueued(0, 10);
    // The queue listing is priority DESC, created_at ASC — first three must now be c, a, b.
    assertEquals(c, after.get(0).id, "c is now head");
    assertEquals(a, after.get(1).id, "a is middle");
    assertEquals(b, after.get(2).id, "b is tail");
  }

  @Test
  void setPriority_nonQueuedTaskIsRejected() {
    long a = enqueue("nonq-a", 0);
    stores.taskQueue().cancel(a);

    int updated = stores.taskQueue().setPriority(a, 1234);
    assertEquals(0, updated, "setPriority must not touch non-QUEUED tasks");
  }

  // ── helpers ─────────────────────────────────────────────────────────────

  private long enqueue(String queueName, int priority) {
    TaskQueueRow t = new TaskQueueRow();
    t.type = "ORCHESTRATE";
    t.queueName = queueName;
    t.status = "QUEUED";
    t.priority = priority;
    t.payloadJson = "{}";
    t.attempts = 0;
    t.maxAttempts = 3;
    t.visibilityTimeoutSeconds = 3600;
    t.availableAt = Instant.now();
    return stores.taskQueue().insert(t);
  }
}
