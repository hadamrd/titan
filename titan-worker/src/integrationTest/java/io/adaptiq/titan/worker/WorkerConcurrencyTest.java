package io.adaptiq.titan.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Concurrency tests for the multi-executor Titan Worker against real PostgreSQL via Testcontainers
 * — proves a worker with {@code TITAN_EXECUTORS > 1} runs several tasks at once, that the {@link
 * WorkerDb} connection pool serves N concurrent callers, and that two concurrent tasks never share
 * a workspace.
 *
 * <p>The {@code titan} schema is loaded from the plugin module's real {@code V*.sql} migrations so
 * this test can never drift from the production schema (same harness as {@link TitanWorkerTest}).
 */
@Testcontainers
class WorkerConcurrencyTest {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");

  private WorkerConfig cfg;
  private WorkerDb db;
  private TaskExecutor executor;
  private Path workspace;
  private Path libraries;

  @BeforeEach
  void setUp() throws Exception {
    Path migrations = Path.of("../titan-db-core/src/main/resources/io/adaptiq/titan/db/migration");
    assertTrue(
        Files.isDirectory(migrations),
        "engine migrations not found at " + migrations.toAbsolutePath());
    try (Connection c =
        DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
      TestMigrations.resetAndApply(c, migrations);
    }

    workspace = Files.createTempDirectory("titan-ws-conc");
    libraries = Files.createTempDirectory("titan-lib-conc");
    // numExecutors = 4 — a multi-executor worker.
    cfg =
        new WorkerConfig(
            POSTGRES.getJdbcUrl(),
            POSTGRES.getUsername(),
            POSTGRES.getPassword(),
            "conc-worker",
            "Concurrency Worker",
            "linux",
            4,
            "/titan",
            "NORMAL",
            "default",
            "synthesis",
            workspace,
            "",
            libraries,
            1000,
            10000,
            "",
            java.util.Map.of());
    db = new WorkerDb(cfg);
    executor = new TaskExecutor(db, workspace, "", libraries, "conc-worker", null);
  }

  @AfterEach
  void tearDown() throws Exception {
    if (db != null) {
      db.close();
    }
    for (Path root : new Path[] {workspace, libraries}) {
      if (root == null) {
        continue;
      }
      try (var paths = Files.walk(root)) {
        paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
      }
    }
  }

  private Connection conn() throws Exception {
    return DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  private long enqueueCommand(String payloadJson) throws Exception {
    try (Connection c = conn();
        PreparedStatement ps =
            c.prepareStatement(
                "INSERT INTO titan.task_queue (type, queue_name, status, payload_json) "
                    + "VALUES ('EXECUTE_COMMAND', 'default', 'QUEUED', ?) "
                    + "RETURNING id")) {
      ps.setString(1, payloadJson);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  private static boolean shAvailable() {
    try {
      return new ProcessBuilder("sh", "-c", "exit 0").start().waitFor() == 0;
    } catch (Exception e) {
      return false;
    }
  }

  private String statusOf(long id) throws Exception {
    try (Connection c = conn();
        PreparedStatement ps =
            c.prepareStatement("SELECT status FROM titan.task_queue WHERE id=?")) {
      ps.setLong(1, id);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getString(1);
      }
    }
  }

  /**
   * The pool size is {@code numExecutors + 2}: a connection per task thread, plus the poll thread
   * and the heartbeat thread. A busy worker must always have a connection free for its heartbeat
   * (design/26 Tier C).
   */
  @Test
  void connectionPoolIsSizedForConcurrency() {
    assertEquals(6, WorkerDb.poolSize(cfg), "4 executors + poll + heartbeat");
  }

  /**
   * The pool serves more concurrent callers than there are executors without deadlock or exhaustion
   * — N task threads each holding a connection while the heartbeat thread still gets one.
   */
  @Test
  void connectionPoolServesConcurrentCallers() throws Exception {
    int callers = WorkerDb.poolSize(cfg);
    ExecutorService pool = Executors.newFixedThreadPool(callers);
    CountDownLatch ready = new CountDownLatch(callers);
    CountDownLatch go = new CountDownLatch(1);
    AtomicInteger ok = new AtomicInteger();
    try {
      List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
      for (int i = 0; i < callers; i++) {
        futures.add(
            pool.submit(
                () -> {
                  ready.countDown();
                  go.await();
                  // Every caller registers/heartbeats at once — all must
                  // obtain a connection from the pool. Null samples are
                  // legal (#348 — agent could not read CPU/MEM/DISK); this
                  // test cares about pool contention, not the sample values.
                  db.heartbeat("conc-worker", null, null, null);
                  ok.incrementAndGet();
                  return null;
                }));
      }
      assertTrue(ready.await(10, TimeUnit.SECONDS), "callers must all start");
      db.register(cfg);
      go.countDown();
      for (var f : futures) {
        f.get(20, TimeUnit.SECONDS);
      }
      assertEquals(callers, ok.get(), "every concurrent caller must complete");
    } finally {
      pool.shutdownNow();
    }
  }

  /**
   * N tasks dispatched concurrently all run in parallel and all complete. A fixed pool of {@code
   * numExecutors} threads claims, runs, and completes each task on its own thread — the serial-loop
   * worker could not do this.
   */
  @Test
  void concurrentTasksAllRunAndComplete() throws Exception {
    assumeTrue(shAvailable(), "POSIX sh not on PATH — skipping");
    int n = 4;
    long[] ids = new long[n];
    for (int i = 0; i < n; i++) {
      // Each step sleeps, so genuine overlap is required to finish fast.
      ids[i] =
          enqueueCommand(
              "{\"stepDescriptor\":\"sh\",\"arguments\":{\"script\":\"sleep 1; echo done\"}}");
    }

    ExecutorService pool = Executors.newFixedThreadPool(n);
    long start = System.nanoTime();
    try {
      List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
      for (int i = 0; i < n; i++) {
        futures.add(
            pool.submit(
                () -> {
                  WorkerDb.ClaimedTask task = db.claim("conc-worker", "default").orElseThrow();
                  db.markProcessing(task.id(), task.claimToken());
                  TaskExecutor.Result r = executor.run(task);
                  assertTrue(r.success(), "each concurrent task must succeed");
                  assertTrue(
                      db.complete(task.id(), task.claimToken(), "COMPLETED", r.resultJson()));
                  return null;
                }));
      }
      for (var f : futures) {
        f.get(30, TimeUnit.SECONDS);
      }
    } finally {
      pool.shutdownNow();
    }
    long elapsedMs = (System.nanoTime() - start) / 1_000_000;

    for (long id : ids) {
      assertEquals("COMPLETED", statusOf(id), "task " + id + " must be COMPLETED");
    }
    // 4 tasks each sleeping 1s: serial would be >= 4s. Concurrent finishes
    // well under that — a generous 3.5s bound absorbs CI jitter.
    assertTrue(
        elapsedMs < 3500,
        "4 one-second tasks run concurrently must finish < 3.5s, took " + elapsedMs + "ms");
  }

  /**
   * Two concurrent tasks of <em>different builds</em> get isolated workspaces — the worker keys the
   * workspace by build id, so {@code build-<A>} ≠ {@code build-<B>} and one build can never see or
   * stomp another's files (design/43 W1). The build is the isolation unit; the prior per-task key
   * was a too-fine granularity.
   */
  @Test
  void concurrentTasksOfDifferentBuildsHaveIsolatedWorkspaces() throws Exception {
    assumeTrue(shAvailable(), "POSIX sh not on PATH — skipping");
    enqueueCommand(
        "{\"stepDescriptor\":\"writeFile\",\"buildId\":99,"
            + "\"arguments\":{\"file\":\"marker.txt\",\"text\":\"A\"}}");
    enqueueCommand(
        "{\"stepDescriptor\":\"writeFile\",\"buildId\":100,"
            + "\"arguments\":{\"file\":\"marker.txt\",\"text\":\"B\"}}");
    WorkerDb.ClaimedTask taskA = db.claim("conc-worker", "default").orElseThrow();
    WorkerDb.ClaimedTask taskB = db.claim("conc-worker", "default").orElseThrow();

    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      CountDownLatch done = new CountDownLatch(2);
      for (WorkerDb.ClaimedTask t : new WorkerDb.ClaimedTask[] {taskA, taskB}) {
        pool.submit(
            () -> {
              try {
                assertTrue(executor.run(t).success(), "writeFile task must succeed");
              } finally {
                done.countDown();
              }
            });
      }
      assertTrue(done.await(30, TimeUnit.SECONDS), "both tasks must finish");
    } finally {
      pool.shutdownNow();
    }

    Path wsA = workspace.resolve("build-99").resolve("marker.txt");
    Path wsB = workspace.resolve("build-100").resolve("marker.txt");
    assertTrue(Files.exists(wsA), "build 99's workspace holds its own file");
    assertTrue(Files.exists(wsB), "build 100's workspace holds its own file");
    assertEquals("A", Files.readString(wsA).trim(), "build 99's file is untouched by build 100");
    assertEquals("B", Files.readString(wsB).trim(), "build 100's file is untouched by build 99");
  }

  /**
   * Every step of <em>one</em> build shares that build's workspace (design/43 W1): a later step
   * sees the files an earlier step produced. This is the foundation {@code archiveArtifacts} /
   * {@code stash} / {@code junit} all stand on.
   */
  @Test
  void stepsOfOneBuildShareTheBuildWorkspace() throws Exception {
    assumeTrue(shAvailable(), "POSIX sh not on PATH — skipping");
    // Step 1 of build 77 writes a file.
    enqueueCommand(
        "{\"stepDescriptor\":\"writeFile\",\"buildId\":77,"
            + "\"arguments\":{\"file\":\"shared.txt\",\"text\":\"from step one\"}}");
    WorkerDb.ClaimedTask step1 = db.claim("conc-worker", "default").orElseThrow();
    assertTrue(executor.run(step1).success(), "step 1 must succeed");

    // Step 2 of the SAME build asserts the file is there — `test -f` exits non-zero,
    // failing the step, if the workspace were not shared.
    enqueueCommand(
        "{\"stepDescriptor\":\"sh\",\"buildId\":77,"
            + "\"arguments\":{\"script\":\"test -f shared.txt\"}}");
    WorkerDb.ClaimedTask step2 = db.claim("conc-worker", "default").orElseThrow();
    assertTrue(
        executor.run(step2).success(),
        "step 2 must see step 1's file — both share build-77's workspace");

    assertTrue(Files.exists(workspace.resolve("build-77").resolve("shared.txt")));
  }

  /**
   * A payload {@code workDir} is sandboxed under the build workspace — a {@code ../} escape attempt
   * is rejected, so a malformed payload cannot place a task outside its build's directory
   * (design/43 — the build base replaces the old per-task base; the guard stands).
   */
  @Test
  void payloadWorkDirCannotEscapeTheBuildWorkspace() throws Exception {
    assumeTrue(shAvailable(), "POSIX sh not on PATH — skipping");
    String payload =
        "{\"stepDescriptor\":\"sh\",\"buildId\":55,\"workDir\":\"../../escape\","
            + "\"arguments\":{\"script\":\"echo hi\"}}";
    enqueueCommand(payload);
    WorkerDb.ClaimedTask task = db.claim("conc-worker", "default").orElseThrow();
    TaskExecutor.Result r = executor.run(task);
    assertTrue(
        r.resultJson().contains("escapes the build workspace"),
        "a workDir that escapes the sandbox must fail the task: " + r.resultJson());
  }
}
