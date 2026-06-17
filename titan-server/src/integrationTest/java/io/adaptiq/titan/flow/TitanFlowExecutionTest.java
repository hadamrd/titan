package io.adaptiq.titan.flow;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.adaptiq.titan.store.BuildDao;
import io.adaptiq.titan.store.JobDao;
import io.adaptiq.titan.store.TaskQueueDao;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.BuildRow;
import io.adaptiq.titan.store.rows.JobRow;
import io.adaptiq.titan.store.rows.TaskQueueRow;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * DB-level integration tests for the Phase 1 execution engine flow: job creation → build insertion
 * → START_PIPELINE task enqueue → QueueProcessor claim → build status RUNNING.
 *
 * <p>These tests exercise the DAO layer end-to-end with H2 + Flyway (same pattern as {@code
 * JobDaoTest}). No container — we test the DB logic, not the UI wiring.
 */
class TitanFlowExecutionTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private HikariDataSource ds;
  private JobDao jobDao;
  private BuildDao buildDao;
  private TaskQueueDao taskDao;

  @BeforeEach
  void setUp() {
    HikariConfig cfg = new HikariConfig();
    cfg.setJdbcUrl(
        "jdbc:h2:mem:rfexec-"
            + System.nanoTime()
            + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE");
    cfg.setDriverClassName("org.h2.Driver");
    cfg.setMaximumPoolSize(4);
    ds = new HikariDataSource(cfg);

    Flyway.configure(getClass().getClassLoader())
        .dataSource(ds)
        .schemas("titan")
        .defaultSchema("titan")
        .createSchemas(true)
        .locations("classpath:io/adaptiq/titan/db/migration")
        .load()
        .migrate();

    TitanStores daos = TitanStores.forDataSource(ds);
    jobDao = daos.jobs();
    buildDao = daos.builds();
    taskDao = daos.taskQueue();
  }

  @AfterEach
  void tearDown() {
    TitanStores.reset();
    if (ds != null) ds.close();
  }

  // ── Job → Build → Task lifecycle ────────────────────────────────────────

  @Test
  void fullLifecycle_jobToBuildToTask() throws Exception {
    // 1. Insert a job
    JobRow job = makeJob("org/my-pipeline", "echo hello");
    long jobId = jobDao.insert(job);
    assertTrue(jobId > 0, "job should get a generated ID");

    // 2. Get next build number (should be 1 for a new job)
    assertEquals(1, buildDao.nextBuildNumber(jobId));

    // 3. Insert a build in QUEUED status
    BuildRow build = makeBuild(jobId, 1);
    long buildId = buildDao.insert(build);
    assertTrue(buildId > 0, "build should get a generated ID");

    // 4. Verify build is QUEUED
    BuildRow found = buildDao.findById(buildId).orElseThrow();
    assertEquals("QUEUED", found.status);
    assertEquals(1, found.buildNumber);

    // 5. Push START_PIPELINE task
    TaskQueueRow task = makeStartPipelineTask(buildId);
    long taskId = taskDao.insert(task);
    assertTrue(taskId > 0, "task should get a generated ID");

    // 6. Verify task is in the queue
    List<TaskQueueRow> buildTasks = taskDao.listByBuild(buildId);
    assertEquals(1, buildTasks.size());
    assertEquals("ORCHESTRATE", buildTasks.get(0).type);
    assertEquals("QUEUED", buildTasks.get(0).status);
    assertEquals("default", buildTasks.get(0).queueName);

    // 7. Verify the payload contains the right buildId and action
    JsonNode payload = JSON.readTree(buildTasks.get(0).payloadJson);
    assertEquals(buildId, payload.get("buildId").asLong());
    assertEquals("START_PIPELINE", payload.get("action").asText());
  }

  @Test
  void claimTask_thenUpdateBuildToRunning() {
    long jobId = jobDao.insert(makeJob("org/claim-test", "echo test"));
    long buildId = buildDao.insert(makeBuild(jobId, 1));
    taskDao.insert(makeStartPipelineTask(buildId));

    // Claim from the "default" queue (simulating QueueProcessor)
    Optional<TaskQueueRow> claimed = taskDao.claimTask("default", "controller:test");
    assertTrue(claimed.isPresent(), "should claim the QUEUED task");
    assertEquals("CLAIMED", claimed.get().status);
    assertEquals("controller:test", claimed.get().claimedBy);

    // After claim, update build status to RUNNING
    buildDao.updateStatus(buildId, "RUNNING", Instant.now(), null, null, null);
    BuildRow running = buildDao.findById(buildId).orElseThrow();
    assertEquals("RUNNING", running.status);
    assertNotNull(running.startedAt);

    // Complete the task
    assertTrue(taskDao.completeTask(claimed.get().id, "{\"ok\":true}"));

    // Verify no more tasks to claim
    assertTrue(taskDao.claimTask("default", "controller:test").isEmpty());
  }

  @Test
  void nextBuildNumber_incrementsCorrectly() {
    long jobId = jobDao.insert(makeJob("org/incr", "echo incr"));
    assertEquals(1, buildDao.nextBuildNumber(jobId));

    buildDao.insert(makeBuild(jobId, 1));
    assertEquals(2, buildDao.nextBuildNumber(jobId));

    buildDao.insert(makeBuild(jobId, 2));
    assertEquals(3, buildDao.nextBuildNumber(jobId));
  }

  @Test
  void multipleTasks_claimedInPriorityOrder() {
    long jobId = jobDao.insert(makeJob("org/prio", "echo prio"));
    long buildId = buildDao.insert(makeBuild(jobId, 1));

    // Insert low-priority then high-priority
    TaskQueueRow low = makeStartPipelineTask(buildId);
    low.priority = 0;
    taskDao.insert(low);

    TaskQueueRow high = makeStartPipelineTask(buildId);
    high.priority = 10;
    taskDao.insert(high);

    // First claim should get the high-priority task
    TaskQueueRow first = taskDao.claimTask("default", "c:1").orElseThrow();
    assertEquals(10, first.priority);

    // Second claim gets the low-priority task
    TaskQueueRow second = taskDao.claimTask("default", "c:1").orElseThrow();
    assertEquals(0, second.priority);

    // No more tasks
    assertTrue(taskDao.claimTask("default", "c:1").isEmpty());
  }

  @Test
  void buildStatusTransition_queuedToRunningToSuccess() {
    long jobId = jobDao.insert(makeJob("org/trans", "echo trans"));
    long buildId = buildDao.insert(makeBuild(jobId, 1));

    // QUEUED → RUNNING
    Instant started = Instant.now();
    buildDao.updateStatus(buildId, "RUNNING", started, null, null, null);
    assertEquals("RUNNING", buildDao.findById(buildId).orElseThrow().status);

    // RUNNING → SUCCESS
    Instant finished = Instant.now();
    long durationMs = finished.toEpochMilli() - started.toEpochMilli();
    buildDao.updateStatus(buildId, "SUCCESS", started, finished, durationMs, null);

    BuildRow done = buildDao.findById(buildId).orElseThrow();
    assertEquals("SUCCESS", done.status);
    assertNotNull(done.finishedAt);
    assertNotNull(done.durationMs);
  }

  @Test
  void buildStatusTransition_queuedToFailed() {
    long jobId = jobDao.insert(makeJob("org/fail", "echo fail"));
    long buildId = buildDao.insert(makeBuild(jobId, 1));

    buildDao.updateStatus(buildId, "FAILED", null, Instant.now(), null, "something broke");

    BuildRow failed = buildDao.findById(buildId).orElseThrow();
    assertEquals("FAILED", failed.status);
    assertEquals("something broke", failed.errorMessage);
  }

  @Test
  void listByJob_returnsBuildsNewestFirst() {
    long jobId = jobDao.insert(makeJob("org/list", "echo list"));
    buildDao.insert(makeBuild(jobId, 1));
    buildDao.insert(makeBuild(jobId, 2));
    buildDao.insert(makeBuild(jobId, 3));

    List<BuildRow> builds = buildDao.listByJob(jobId);
    assertEquals(3, builds.size());
    // Ordered queued_at DESC. Three inserts can share a timestamp, so assert the contract —
    // non-increasing queued_at — rather than a strict build-number order (which ties break
    // arbitrarily).
    for (int i = 1; i < builds.size(); i++) {
      assertFalse(
          builds.get(i).queuedAt.isAfter(builds.get(i - 1).queuedAt),
          "builds must be ordered newest-first by queued_at");
    }
    assertEquals(
        java.util.Set.of(1, 2, 3),
        builds.stream().map(b -> b.buildNumber).collect(java.util.stream.Collectors.toSet()));
  }

  @Test
  void jobEnabled_controlsBuildability() {
    JobRow job = makeJob("org/toggle", "echo toggle");
    long jobId = jobDao.insert(job);

    JobRow found = jobDao.findById(jobId).orElseThrow();
    assertTrue(found.enabled, "new job should be enabled");

    found.enabled = false;
    jobDao.update(found);
    assertFalse(jobDao.findById(jobId).orElseThrow().enabled);
  }

  // ── Helpers ──────────────────────────────────────────────────────────────

  private static JobRow makeJob(String fullName, String script) {
    JobRow r = new JobRow();
    r.fullName = fullName;
    r.pipelineScript = script;
    r.configJson = "{}";
    r.enabled = true;
    return r;
  }

  private static BuildRow makeBuild(long jobId, int number) {
    BuildRow r = new BuildRow();
    r.jobId = jobId;
    r.buildNumber = number;
    r.status = "QUEUED";
    return r;
  }

  private static TaskQueueRow makeStartPipelineTask(long buildId) {
    TaskQueueRow t = new TaskQueueRow();
    t.type = "ORCHESTRATE";
    t.queueName = "default";
    t.status = "QUEUED";
    t.priority = 0;
    t.payloadJson = String.format("{\"buildId\":%d,\"action\":\"START_PIPELINE\"}", buildId);
    t.attempts = 0;
    t.maxAttempts = 3;
    t.visibilityTimeoutSeconds = 3600;
    t.buildId = buildId;
    return t;
  }
}
