package io.adaptiq.titan.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.TaskQueueRow;
import java.sql.Connection;
import java.sql.Statement;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Issue #473 — multi-worker claim isolation on the public k3s rig.
 *
 * <p>The k3s chart now scales {@code titan-worker} horizontally via {@code titanWorker.replicas}.
 * All replicas poll the same {@code task_queue} on the same {@code queue_name}; the only thing that
 * keeps two workers from claiming and executing the same task is the {@code SELECT … FOR UPDATE
 * SKIP LOCKED} primitive in {@link io.adaptiq.titan.store.TaskQueueDao#selectClaimableId} (and its
 * {@code EXECUTE_COMMAND}-typed sibling). This IT hard-asserts that invariant against a real
 * PostgreSQL: spin up N concurrent claim loops, enqueue M tasks, and prove that every task was
 * claimed by <em>exactly one</em> worker, with no losses and no duplicates.
 *
 * <p>If anyone replaces the claim query with something dialect-portable but lock-less (e.g. a naive
 * {@code UPDATE … WHERE id = (SELECT … LIMIT 1)} without {@code FOR UPDATE SKIP LOCKED}), this test
 * fails — and the k3s public demo (docs/ops/runbooks/k3s-public-demo.md) is unsafe to ship until
 * it's green again.
 */
@Testcontainers
class MultiWorkerClaimIsolationIT {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");

  private static final int WORKERS = 3;
  private static final int TASKS = 30;
  private static final String QUEUE = "k3s-multi";

  private HikariDataSource ds;

  @BeforeEach
  void setUp() throws Exception {
    HikariConfig cfg = new HikariConfig();
    cfg.setJdbcUrl(POSTGRES.getJdbcUrl());
    cfg.setUsername(POSTGRES.getUsername());
    cfg.setPassword(POSTGRES.getPassword());
    cfg.setDriverClassName("org.postgresql.Driver");
    // One connection per worker + a couple for the enqueue + audit queries.
    cfg.setMaximumPoolSize(WORKERS + 4);
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
  }

  @AfterEach
  void tearDown() {
    if (ds != null) {
      ds.close();
    }
  }

  /**
   * Acceptance criterion #473 (Part B): enqueue 30 {@code EXECUTE_COMMAND} tasks, run 3 worker
   * loops in parallel, and prove every task ends up claimed by exactly one worker — no losses, no
   * duplicates, no overlapping claimants for the same {@code task_token}.
   */
  @Test
  void everyTaskIsClaimedByExactlyOneWorker() throws Exception {
    TitanStores enqueueStores = TitanStores.forDataSource(ds);

    // Seed: TASKS QUEUED EXECUTE_COMMAND tasks on the same queue. Distinct payloads make the
    // post-hoc audit query easy to read in a failure log.
    for (int i = 0; i < TASKS; i++) {
      enqueueStores
          .taskQueue()
          .enqueue("EXECUTE_COMMAND", QUEUE, 0, "{\"i\":" + i + "}", 1, 60, null, null, null);
    }

    // Worker loops — each owns its own TitanStores (shares the underlying Hikari pool, but each
    // claim call takes its own connection, which is what production does too). Every successful
    // claim records (taskId -> agentId) in a thread-safe map so we can hard-assert isolation.
    Map<Long, String> claimedBy = new ConcurrentHashMap<>();
    Map<String, Integer> perWorkerCount = new ConcurrentHashMap<>();
    AtomicBoolean stop = new AtomicBoolean(false);
    CountDownLatch done = new CountDownLatch(WORKERS);
    ExecutorService pool = Executors.newFixedThreadPool(WORKERS);

    for (int w = 0; w < WORKERS; w++) {
      final String agentId = "worker-" + w;
      pool.submit(
          () -> {
            try {
              TitanStores workerStores = TitanStores.forDataSource(ds);
              while (!stop.get()) {
                Optional<TaskQueueRow> claim =
                    workerStores.taskQueue().claimExecuteCommand(agentId, QUEUE, UUID.randomUUID());
                if (claim.isEmpty()) {
                  // Queue drained from this worker's perspective — back off briefly so all 3
                  // workers exit roughly together rather than one of them busy-looping.
                  Thread.sleep(20L);
                  // If everyone has seen the queue empty for a tick, stop.
                  if (claimedBy.size() >= TASKS) {
                    break;
                  }
                  continue;
                }
                TaskQueueRow row = claim.get();
                String prev = claimedBy.putIfAbsent(row.id, agentId);
                if (prev != null) {
                  // The whole point of this IT — if SKIP LOCKED ever breaks, two workers will
                  // see the same row id and the second putIfAbsent here will trip. We don't
                  // throw inside the worker (a thrown executor task just disappears); we record
                  // it and the post-loop assertion below will surface the collision.
                  claimedBy.put(
                      -row.id, "COLLISION:" + prev + "+" + agentId); // negative id = sentinel
                }
                perWorkerCount.merge(agentId, 1, Integer::sum);
                // Complete the task so the queue eventually drains.
                workerStores
                    .taskQueue()
                    .complete(row.id, row.claimToken, "COMPLETED", "{\"ok\":true}");
              }
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            } finally {
              done.countDown();
            }
          });
    }

    // Generous deadline; the 30-task drain across 3 workers on a local PG runs in well under 5s.
    boolean drained = done.await(60, TimeUnit.SECONDS);
    stop.set(true);
    pool.shutdownNow();
    assertTrue(drained, "the workers must drain the queue within the deadline");

    // ── Invariants ──────────────────────────────────────────────────────────

    // No collision sentinels — every taskId saw exactly one putIfAbsent winner.
    List<Long> collisions = claimedBy.keySet().stream().filter(id -> id < 0).toList();
    assertTrue(
        collisions.isEmpty(),
        "SKIP LOCKED isolation broken: two workers claimed the same task ids "
            + collisions
            + " — details: "
            + collisions.stream().map(id -> id + " -> " + claimedBy.get(id)).toList());

    // Every task was claimed by exactly one worker.
    assertEquals(TASKS, claimedBy.size(), "every enqueued task must be claimed exactly once");

    // Every task transitioned to COMPLETED (the lease token matched on complete → no zombies).
    int completed;
    try (Connection c = ds.getConnection();
        Statement st = c.createStatement();
        java.sql.ResultSet rs =
            st.executeQuery(
                "SELECT COUNT(*) FROM titan.task_queue "
                    + "WHERE queue_name = '"
                    + QUEUE
                    + "' AND status = 'COMPLETED'")) {
      rs.next();
      completed = rs.getInt(1);
    }
    assertEquals(
        TASKS, completed, "every task must be COMPLETED — no orphans, no double-completions");

    // Postgres-side audit: across the whole task_queue (including any pre-existing rows, but
    // there are none in this isolated test), no task_token was claimed by more than one agent.
    try (Connection c = ds.getConnection();
        Statement st = c.createStatement();
        java.sql.ResultSet rs =
            st.executeQuery(
                "SELECT task_token, COUNT(DISTINCT claimed_by) AS n "
                    + "FROM titan.task_queue WHERE claimed_by IS NOT NULL "
                    + "GROUP BY task_token HAVING COUNT(DISTINCT claimed_by) > 1")) {
      assertTrue(
          !rs.next(),
          "Postgres-side audit: at least one task_token was claimed by more than one worker — "
              + "FOR UPDATE SKIP LOCKED has regressed");
    }

    // Load was spread across workers — no single worker took all the tasks (sanity check that
    // we actually exercised concurrency rather than serialising on one connection). Any worker
    // that did claim work must have a non-zero count; we require at least 2 distinct workers
    // to have claimed work (PG's lock scheduler can starve the slowest worker in a 30-task
    // burst on a fast pool, so we don't require all 3).
    Set<String> distinctClaimants = new HashSet<>(claimedBy.values());
    assertTrue(
        distinctClaimants.size() >= 2,
        "expected at least 2 of "
            + WORKERS
            + " workers to claim work, got "
            + distinctClaimants
            + " (per-worker counts: "
            + new HashMap<>(perWorkerCount)
            + ")");
  }
}
