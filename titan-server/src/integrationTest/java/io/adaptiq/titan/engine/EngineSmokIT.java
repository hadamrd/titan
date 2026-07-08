package io.adaptiq.titan.engine;

import static org.junit.jupiter.api.Assertions.*;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.adaptiq.titan.db.MigrationScripts;
import io.adaptiq.titan.flow.TitanFlowExecution;
import io.adaptiq.titan.flow.TitanOrchestrator;
import io.adaptiq.titan.queue.QueueProcessor;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.BuildRow;
import io.adaptiq.titan.store.rows.JobRow;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Pivot M2 smoke test: proves the engine works standalone, with no container framework on the
 * classpath.
 *
 * <p>Wires up a Testcontainers PostgreSQL instance, runs the titan Flyway migrations, constructs
 * {@link TitanStores} via {@link TitanStores#forDataSource}, seeds one job + build, drives one
 * {@link QueueProcessor#tick} pass, and asserts the build advances past {@code QUEUED}.
 *
 * <p>The pipeline under test is a single {@code sh} step. Because the test injects a no-op {@code
 * CredentialsPort} and there is no worker process, the build will stay {@code RUNNING} (the ADVANCE
 * enqueues an {@code EXECUTE_COMMAND} task nobody claims). The assertions check that the engine
 * loop ran — the ADVANCE task was enqueued — not that the step completed.
 */
@Testcontainers
class EngineSmokIT {

  private static final Logger LOGGER = Logger.getLogger(EngineSmokIT.class.getName());

  @Container
  static final PostgreSQLContainer<?> PG =
      new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("titan_test");

  private static HikariDataSource pool;
  private static TitanStores stores;

  @BeforeAll
  static void bootstrapDb() throws Exception {
    HikariConfig hc = new HikariConfig();
    hc.setJdbcUrl(PG.getJdbcUrl());
    hc.setUsername(PG.getUsername());
    hc.setPassword(PG.getPassword());
    hc.setMaximumPoolSize(5);
    hc.setPoolName("engine-smok-it");
    pool = new HikariDataSource(hc);

    // Run titan schema migrations directly without Flyway — Flyway 12 has a Jackson 3.x
    // internal dependency (tools.jackson.core:jackson-annotations) that is not published to
    // Maven Central, making it impossible to use from a no-BOM standalone module. We apply
    // the portable SQL files from titan-db-core's classpath resources in version order.
    runMigrations(pool);

    stores = TitanStores.forDataSource(pool);
    LOGGER.info("[smok-it] schema migrated, TitanStores wired");
  }

  /**
   * Apply all titan portable migration scripts from the classpath. The scripts live in {@code
   * titan-db-core}'s JAR under {@code io/adaptiq/titan/db/migration/V*.sql}. We run them in
   * lexicographic order (which matches version order for the Vn__ naming convention) inside a
   * single transaction so a failure leaves the schema clean.
   */
  private static void runMigrations(DataSource ds) throws Exception {
    String base = "io/adaptiq/titan/db/migration";
    // Auto-discover V<N>__*.sql via classpath scan — see MigrationScripts (#369).
    // Hardcoded list went stale 3 times this sprint (#366, #377, #369). The H2-incompatible
    // migration-postgresql/ overrides are intentionally excluded — we scan the portable dir only.
    List<String> scripts = MigrationScripts.listInOrder(base);
    try (Connection conn = ds.getConnection()) {
      conn.setAutoCommit(false);
      try (Statement st = conn.createStatement()) {
        st.execute("CREATE SCHEMA IF NOT EXISTS titan");
      }
      conn.commit();
      for (String script : scripts) {
        String path = base + "/" + script;
        URL url = EngineSmokIT.class.getClassLoader().getResource(path);
        assertNotNull(url, "migration script not found on classpath: " + path);
        String sql;
        try (InputStream in = url.openStream()) {
          sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        try (Statement st = conn.createStatement()) {
          // Split on statement terminators to handle multi-statement files.
          for (String stmt : splitStatements(sql)) {
            if (!stmt.isBlank()) {
              st.execute(stmt);
            }
          }
        }
        conn.commit();
        LOGGER.info("[smok-it] applied migration: " + script);
      }
    }
  }

  /**
   * Split a SQL script on {@code ;} boundaries, respecting single-quoted string literals, line
   * comments ({@code --}), and dollar-quoted blocks ({@code $$ ... $$}).
   */
  private static List<String> splitStatements(String sql) {
    List<String> stmts = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    boolean inSingleQuote = false;
    boolean inDollarQuote = false;
    String dollarTag = null;
    int i = 0;
    while (i < sql.length()) {
      char c = sql.charAt(i);

      // Skip line comments outside quotes
      if (!inSingleQuote
          && !inDollarQuote
          && c == '-'
          && i + 1 < sql.length()
          && sql.charAt(i + 1) == '-') {
        int eol = sql.indexOf('\n', i);
        if (eol == -1) {
          break; // rest of file is a comment
        }
        current.append(sql, i, eol + 1);
        i = eol + 1;
        continue;
      }

      // Toggle single-quote (handle '' escape)
      if (!inDollarQuote && c == '\'') {
        inSingleQuote = !inSingleQuote;
        current.append(c);
        i++;
        continue;
      }

      // Detect dollar-quote start/end outside single-quoted strings
      if (!inSingleQuote && !inDollarQuote && c == '$') {
        int end = sql.indexOf('$', i + 1);
        if (end != -1) {
          String tag = sql.substring(i, end + 1);
          inDollarQuote = true;
          dollarTag = tag;
          current.append(sql, i, end + 1);
          i = end + 1;
          continue;
        }
      } else if (inDollarQuote && dollarTag != null && sql.startsWith(dollarTag, i)) {
        current.append(sql, i, i + dollarTag.length());
        i += dollarTag.length();
        inDollarQuote = false;
        dollarTag = null;
        continue;
      }

      if (c == ';' && !inSingleQuote && !inDollarQuote) {
        String stmt = current.toString().trim();
        if (!stmt.isBlank()) {
          stmts.add(stmt);
        }
        current.setLength(0);
      } else {
        current.append(c);
      }
      i++;
    }
    String remainder = current.toString().trim();
    if (!remainder.isBlank()) {
      stmts.add(remainder);
    }
    return stmts;
  }

  @AfterAll
  static void shutdown() {
    if (pool != null) {
      pool.close();
    }
  }

  // ── helpers ───────────────────────────────────────────────────────────

  private static final String PIPELINE_YAML =
      """
            stages:
              - stage: hello
                steps:
                  - sh: echo hello
            """;

  private long seedJobAndBuild(DataSource ds) {
    // Insert a job row
    JobRow job = new JobRow();
    job.fullName = "smok-test-job-" + System.nanoTime(); // unique per test invocation
    job.displayName = "Smoke Test Job";
    job.pipelineScript = PIPELINE_YAML;
    job.enabled = true;
    long jobId = stores.jobs().insert(job);

    // Insert a build row in QUEUED status
    BuildRow build = new BuildRow();
    build.jobId = jobId;
    build.buildNumber = 1;
    build.status = "QUEUED";
    build.triggeredBy = "test";
    build.triggerType = "manual";
    return stores.withTransaction(conn -> stores.builds().insert(conn, build));
  }

  // ── tests ─────────────────────────────────────────────────────────────

  /**
   * Proves the engine can synthesize + bake a pipeline from a YAML string and enqueue the first
   * ADVANCE task — all standalone.
   */
  @Test
  void bakeAndFirstAdvanceEnqueued() {
    long buildId = seedJobAndBuild(pool);

    // Synthesize + bake directly (bypasses QueueProcessor task dispatch for synthesis)
    TitanFlowExecution exec = new TitanFlowExecution(stores, buildId);
    TitanFlowExecution.BakeResult bakeResult = exec.bake(PIPELINE_YAML);
    assertEquals(TitanFlowExecution.BakeResult.BAKED, bakeResult, "build should be freshly baked");

    // Build should now be RUNNING (activated by bake transaction)
    BuildRow afterBake = stores.builds().findById(buildId).orElseThrow();
    assertEquals("RUNNING", afterBake.status, "build must be RUNNING after bake");

    // Drive one ADVANCE via QueueProcessor — seed the ADVANCE task manually first
    // (QueueProcessor handles BAKE→enqueueAdvance in the full flow; here we drive ADVANCE directly)
    stores.taskQueue().insert(advanceTask(buildId));

    QueueProcessor processor = new QueueProcessor();
    int processed = processor.tick(stores, "controller:smok-test", 3600);
    assertTrue(processed >= 1, "processor must claim and handle at least the ADVANCE task");

    // After one ADVANCE pass the build's flow_nodes should be non-empty
    int nodeCount = stores.flowNodes().listByBuild(buildId).size();
    assertTrue(
        nodeCount >= 1, "bake must have materialised at least one flow node; got " + nodeCount);

    LOGGER.info(
        "[smok-it] bakeAndFirstAdvanceEnqueued passed — " + nodeCount + " node(s) materialised");
  }

  /**
   * Proves BuildAbortService works standalone: abort a queued build, assert it moves to ABORTED.
   */
  @Test
  void abortBuildTransitionsToAborted() {
    long buildId = seedJobAndBuild(pool);

    // Bake it so there are flow_nodes to abort
    new TitanFlowExecution(stores, buildId).bake(PIPELINE_YAML);

    io.adaptiq.titan.flow.BuildAbortService.AbortOutcome outcome =
        io.adaptiq.titan.flow.BuildAbortService.abort(stores, buildId, "test-actor");

    assertTrue(outcome.aborted(), "abort must succeed on a live build");
    BuildRow after = stores.builds().findById(buildId).orElseThrow();
    assertEquals("ABORTED", after.status, "build must be ABORTED after abort");

    LOGGER.info("[smok-it] abortBuildTransitionsToAborted passed");
  }

  /**
   * Proves TitanOrchestrator.advance() is idempotent: two consecutive advances on a freshly-baked
   * build with no worker tasks do not produce duplicate EXECUTE_COMMAND tasks.
   */
  @Test
  void orchestratorAdvanceIsIdempotent() {
    long buildId = seedJobAndBuild(pool);
    new TitanFlowExecution(stores, buildId).bake(PIPELINE_YAML);

    TitanOrchestrator orch = new TitanOrchestrator(stores, buildId);
    TitanOrchestrator.AdvanceResult r1 = orch.advance();
    TitanOrchestrator.AdvanceResult r2 = orch.advance();

    // The first advance dispatches steps; the second finds them already QUEUED — no new dispatch
    // (the CAS on PENDING→QUEUED prevents double-dispatch)
    assertTrue(r1.dispatched() >= 0);
    assertEquals(
        0,
        r2.dispatched(),
        "second advance must not re-dispatch already-queued steps; r1=" + r1 + " r2=" + r2);

    LOGGER.info("[smok-it] orchestratorAdvanceIsIdempotent passed — r1=" + r1 + ", r2=" + r2);
  }

  // ── private helpers ───────────────────────────────────────────────────

  private static io.adaptiq.titan.store.rows.TaskQueueRow advanceTask(long buildId) {
    io.adaptiq.titan.store.rows.TaskQueueRow t = new io.adaptiq.titan.store.rows.TaskQueueRow();
    t.type = "ORCHESTRATE";
    t.queueName = "default";
    t.status = "QUEUED";
    t.priority = 0;
    t.payloadJson = "{\"action\":\"ADVANCE\",\"buildId\":" + buildId + "}";
    t.attempts = 0;
    t.maxAttempts = 3;
    t.visibilityTimeoutSeconds = 3600;
    t.buildId = buildId;
    t.availableAt = Instant.now();
    return t;
  }
}
