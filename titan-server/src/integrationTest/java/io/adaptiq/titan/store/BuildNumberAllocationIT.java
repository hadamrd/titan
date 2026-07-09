package io.adaptiq.titan.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.adaptiq.titan.db.TitanDataException;
import io.adaptiq.titan.store.rows.BuildRow;
import io.adaptiq.titan.store.rows.JobRow;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * True-concurrency contract for build-number allocation (issue #69) — real PostgreSQL, real
 * parallelism.
 *
 * <p>Before the fix, {@code BuildDao#nextBuildNumber(Connection, long)} was a bare {@code
 * MAX(build_number) + 1} read: two transactions racing on the same job both computed the same
 * number and one insert died on {@code uq_builds_job_number}. The webhook receivers surfaced that
 * as a dropped delivery (e2e spec 52 test2, 2/2 red in full-suite runs). The fix serializes
 * allocation on the {@code FOR UPDATE} job-row lock (the design/50 D4 mutex the trigger engine
 * already holds), so this IT asserts the post-fix contract:
 *
 * <ul>
 *   <li>N truly-parallel enqueue transactions → exactly N builds;
 *   <li>build numbers are unique AND contiguous (1..N) — no gaps from aborted retries;
 *   <li>zero unique-constraint violations surface to any caller.
 * </ul>
 *
 * <p>Mirrors the Testcontainers + Flyway-from-classpath shape of {@code TaskQueueCancelIntentIT}.
 */
@Testcontainers
class BuildNumberAllocationIT {

  /** Parallel allocators — the e2e repro needs 2; we push harder. */
  private static final int THREADS = 16;

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
    // One pooled connection per thread so all THREADS transactions are truly concurrent —
    // contention happens on the job-row lock, not on the pool.
    cfg.setMaximumPoolSize(THREADS);
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
    TitanStores.reset();
    if (ds != null) {
      ds.close();
    }
  }

  // ── the #69 adversarial case ──────────────────────────────────────────────

  @Test
  void parallelEnqueues_sameJob_allocateUniqueContiguousNumbers_noneDropped() throws Exception {
    long jobId = seedJob("org/spec52-concurrent");

    CyclicBarrier gate = new CyclicBarrier(THREADS);
    CountDownLatch done = new CountDownLatch(THREADS);
    ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
    ExecutorService pool = Executors.newFixedThreadPool(THREADS);
    try {
      for (int i = 0; i < THREADS; i++) {
        pool.submit(
            () -> {
              try {
                gate.await(30, TimeUnit.SECONDS); // fire all transactions in the same instant
                enqueueLikeAWebhook(jobId);
              } catch (Throwable t) {
                failures.add(t);
              } finally {
                done.countDown();
              }
            });
      }
      assertTrue(done.await(60, TimeUnit.SECONDS), "allocators deadlocked or hung");
    } finally {
      pool.shutdownNow();
    }

    assertTrue(
        failures.isEmpty(),
        "no allocation may surface an error to its caller (found "
            + failures.size()
            + "): "
            + failures.stream().map(String::valueOf).collect(Collectors.joining("; ")));

    List<BuildRow> builds = stores.builds().listByJob(jobId);
    assertEquals(THREADS, builds.size(), "every parallel enqueue must produce a build — #69");

    Set<Integer> numbers = builds.stream().map(b -> b.buildNumber).collect(Collectors.toSet());
    assertEquals(
        IntStream.rangeClosed(1, THREADS).boxed().collect(Collectors.toSet()),
        numbers,
        "build numbers must be unique and contiguous 1.." + THREADS);
  }

  // ── two jobs racing must not serialize each other spuriously ─────────────

  @Test
  void parallelEnqueues_differentJobs_doNotInterfere() throws Exception {
    long jobA = seedJob("org/job-a");
    long jobB = seedJob("org/job-b");

    CyclicBarrier gate = new CyclicBarrier(2);
    ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
    Thread ta = allocatorThread(jobA, gate, failures);
    Thread tb = allocatorThread(jobB, gate, failures);
    ta.start();
    tb.start();
    ta.join(TimeUnit.SECONDS.toMillis(30));
    tb.join(TimeUnit.SECONDS.toMillis(30));

    assertTrue(failures.isEmpty(), "cross-job allocation must never fail: " + failures);
    assertEquals(1, stores.builds().listByJob(jobA).size());
    assertEquals(1, stores.builds().listByJob(jobB).size());
  }

  // ── allocating for a vanished job is a loud failure, not a dangling row ──

  @Test
  void allocationForDeletedJob_throwsInsteadOfDeferringToFkViolation() {
    long jobId = seedJob("org/doomed");
    stores.jobs().delete(jobId);

    TitanDataException e =
        assertThrows(
            TitanDataException.class,
            () -> stores.withTransaction(conn -> stores.builds().nextBuildNumber(conn, jobId)));
    assertTrue(
        e.getMessage().contains("no longer exists"),
        "failure must name the missing job, got: " + e.getMessage());
  }

  // ── re-entrancy: a transaction already holding the job-row lock (the trigger
  //    engine path, design/50 D4) must not self-deadlock ─────────────────────

  @Test
  void allocationInsideAnAlreadyLockedScope_isReentrant() {
    long jobId = seedJob("org/pre-locked");
    Integer number =
        stores.withTransaction(
            conn -> {
              assertTrue(stores.jobs().lockForUpdate(conn, jobId), "outer lock must acquire");
              return stores.builds().nextBuildNumber(conn, jobId); // re-locks — must not hang
            });
    assertEquals(1, number);
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private long seedJob(String fullName) {
    JobRow r = new JobRow();
    r.fullName = fullName;
    r.pipelineScript = "steps: []";
    r.configJson = "{}";
    r.enabled = true;
    return stores.jobs().insert(r);
  }

  /**
   * The exact transaction shape every trigger path uses (webhooks, manual API, trigger engine,
   * BuildEnqueuer): allocate on the connection, insert on the same connection, commit.
   */
  private void enqueueLikeAWebhook(long jobId) {
    stores.withTransaction(
        conn -> {
          int buildNumber = stores.builds().nextBuildNumber(conn, jobId);
          BuildRow build = new BuildRow();
          build.jobId = jobId;
          build.buildNumber = buildNumber;
          build.status = "QUEUED";
          build.triggeredBy = "it:concurrency";
          build.triggerType = "github";
          build.queuedAt = Instant.now();
          return stores.builds().insert(conn, build);
        });
  }

  private Thread allocatorThread(
      long jobId, CyclicBarrier gate, ConcurrentLinkedQueue<Throwable> failures) {
    return new Thread(
        () -> {
          try {
            gate.await(30, TimeUnit.SECONDS);
            enqueueLikeAWebhook(jobId);
          } catch (Throwable t) {
            failures.add(t);
          }
        });
  }
}
