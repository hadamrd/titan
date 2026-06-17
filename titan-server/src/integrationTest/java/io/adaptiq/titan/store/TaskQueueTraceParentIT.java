package io.adaptiq.titan.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.adaptiq.titan.store.rows.TaskQueueRow;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.Optional;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Postgres-backed integration test for the W3C {@code trace_parent} column on {@code
 * titan.task_queue} (issue #314).
 *
 * <p>Pins down three properties of the DAO round-trip:
 *
 * <ol>
 *   <li>A row inserted with a {@code traceParent} value retrieves the same value byte-for-byte — so
 *       the column survives the JDBI INSERT + SELECT + FieldMapper hop.
 *   <li>A row inserted with {@code traceParent} unset stores SQL {@code NULL} (not the literal
 *       string {@code "null"}, not the all-zeros sentinel) — so an enqueue without an active OTel
 *       span carries no trace context, never a false one.
 *   <li>The {@code VARCHAR(64)} column accepts the canonical 55-char W3C v0 traceparent — the exact
 *       length the bridge in {@code TraceContext} produces.
 * </ol>
 *
 * <p>Mirrors {@code StatsApiIT}'s Testcontainers + Flyway-from-classpath shape so the migrations
 * applied here are exactly the ones the server boots with — that is what makes V17 itself
 * load-bearing in this test.
 */
@Testcontainers
class TaskQueueTraceParentIT {

  /** Canonical W3C v0 traceparent — fixture from the W3C trace-context spec. */
  private static final String SAMPLE_TRACEPARENT =
      "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

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
  void traceParent_roundTripsThroughInsertAndSelect() {
    TaskQueueRow row = orchestrateRow();
    row.traceParent = SAMPLE_TRACEPARENT;
    long id = stores.taskQueue().insert(row);

    Optional<TaskQueueRow> readBack = stores.taskQueue().findById(id);

    assertTrue(readBack.isPresent(), "row should be readable after insert");
    assertEquals(
        SAMPLE_TRACEPARENT,
        readBack.get().traceParent,
        "traceparent must survive the DAO round-trip byte-for-byte");
    assertEquals(
        55, readBack.get().traceParent.length(), "stored value is the 55-char W3C v0 string");
  }

  @Test
  void traceParent_unset_persistsAsNull() {
    TaskQueueRow row = orchestrateRow();
    row.traceParent = null; // simulates an enqueue without an active OTel span
    long id = stores.taskQueue().insert(row);

    Optional<TaskQueueRow> readBack = stores.taskQueue().findById(id);

    assertTrue(readBack.isPresent());
    assertNull(
        readBack.get().traceParent,
        "absent trace context must be SQL NULL — never the literal 'null' or a zero-sentinel");
  }

  @Test
  void traceParent_otherFieldsUnchanged_byTheColumnAddition() {
    // A guard against future migrations that inadvertently break the existing column mapping —
    // if V17 (or any successor) silently re-orders or drops a column the round-trip will fail.
    TaskQueueRow row = orchestrateRow();
    row.traceParent = SAMPLE_TRACEPARENT;
    long id = stores.taskQueue().insert(row);

    TaskQueueRow readBack = stores.taskQueue().findById(id).orElseThrow();
    assertEquals(row.type, readBack.type);
    assertEquals(row.queueName, readBack.queueName);
    assertEquals(row.status, readBack.status);
    assertEquals(row.priority, readBack.priority);
    assertEquals(row.payloadJson, readBack.payloadJson);
    assertEquals(row.maxAttempts, readBack.maxAttempts);
    assertEquals(row.visibilityTimeoutSeconds, readBack.visibilityTimeoutSeconds);
    assertNotNull(readBack.taskToken, "task_token defaulted by the DB");
    assertNotNull(readBack.createdAt, "created_at defaulted by the DB");
  }

  private static TaskQueueRow orchestrateRow() {
    TaskQueueRow row = new TaskQueueRow();
    row.type = "ORCHESTRATE";
    row.queueName = "default";
    row.status = "QUEUED";
    row.priority = 0;
    row.payloadJson = "{\"action\":\"ADVANCE\",\"buildId\":1}";
    row.attempts = 0;
    row.maxAttempts = 3;
    row.visibilityTimeoutSeconds = 3600;
    row.availableAt = Instant.now();
    return row;
  }
}
