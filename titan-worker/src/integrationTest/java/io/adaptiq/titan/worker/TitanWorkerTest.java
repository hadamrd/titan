package io.adaptiq.titan.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration tests for the Titan Worker against <em>real PostgreSQL</em> via Testcontainers.
 *
 * <p>The {@code titan} schema is loaded from the plugin module's actual {@code V1__init.sql}
 * (resolved by relative path) — so this test always runs against the real engine schema and can
 * never drift from it.
 */
@Testcontainers
class TitanWorkerTest {

  private static final com.fasterxml.jackson.databind.ObjectMapper JSON_MAPPER =
      new com.fasterxml.jackson.databind.ObjectMapper();

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
    // Apply the real engine schema — every V*.sql migration from titan-db-core,
    // in version order — so this test can never drift from the production
    // schema. Container is shared across methods so we reset the schema each time.
    Path migrations = Path.of("../titan-db-core/src/main/resources/io/adaptiq/titan/db/migration");
    assertTrue(
        Files.isDirectory(migrations),
        "engine migrations not found at " + migrations.toAbsolutePath());
    try (Connection c =
        DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
      TestMigrations.resetAndApply(c, migrations);
    }

    workspace = Files.createTempDirectory("titan-ws-test");
    libraries = Files.createTempDirectory("titan-lib-test");
    cfg =
        new WorkerConfig(
            POSTGRES.getJdbcUrl(),
            POSTGRES.getUsername(),
            POSTGRES.getPassword(),
            "test-worker",
            "Test Worker",
            "linux",
            1,
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
    executor = new TaskExecutor(db, workspace, "", libraries, "test-worker", null);
  }

  @AfterEach
  void tearDown() throws Exception {
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

  /** Inserts a QUEUED EXECUTE_COMMAND task, returns its (id, task_token). */
  private long[] enqueueCommand(String payloadJson) throws Exception {
    try (Connection c = conn();
        PreparedStatement ps =
            c.prepareStatement(
                "INSERT INTO titan.task_queue (type, queue_name, status, payload_json) "
                    + "VALUES ('EXECUTE_COMMAND', 'default', 'QUEUED', ?) "
                    + "RETURNING id")) {
      ps.setString(1, payloadJson);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return new long[] {rs.getLong(1)};
      }
    }
  }

  /** An SPI-shaped {@code sh}-step payload that echoes {@code text}. */
  private static String echoPayload(String text) {
    return "{\"stepDescriptor\":\"sh\",\"arguments\":{\"script\":\"echo " + text + "\"}}";
  }

  /** True if a POSIX {@code sh} is on PATH — the {@code sh}-step ITs assume this. */
  private static boolean shAvailable() {
    try {
      return new ProcessBuilder("sh", "-c", "exit 0").start().waitFor() == 0;
    } catch (Exception e) {
      return false;
    }
  }

  /** All log chunks for a task, in order, as one string. */
  private String logText(UUID taskToken) throws Exception {
    try (Connection c = conn();
        PreparedStatement ps =
            c.prepareStatement(
                "SELECT data FROM titan.logs WHERE task_id=? ORDER BY chunk_index")) {
      ps.setObject(1, taskToken);
      try (ResultSet rs = ps.executeQuery()) {
        StringBuilder out = new StringBuilder();
        while (rs.next()) {
          out.append(rs.getString(1)).append('\n');
        }
        return out.toString();
      }
    }
  }

  /** True if a usable Docker CLI is on PATH — the container ITs assume this and skip otherwise. */
  private static boolean dockerAvailable() {
    try {
      Process p = new ProcessBuilder("docker", "version").redirectErrorStream(true).start();
      try (BufferedReader r =
          new BufferedReader(
              new InputStreamReader(p.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
        while (r.readLine() != null) {
          // drain
        }
      }
      return p.waitFor() == 0;
    } catch (Exception e) {
      return false;
    }
  }

  /** True if the Docker daemon can bind-mount the worker's workspace (daemon shares its FS). */
  private boolean bindMountWorks() {
    try {
      sh(
          "docker",
          "run",
          "--rm",
          "-v",
          workspace.toAbsolutePath() + ":/probe",
          "alpine:3.20",
          "true");
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  /** Run a command, returning its stdout; throws on a non-zero exit. */
  private static String sh(String... command) throws Exception {
    Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
    StringBuilder out = new StringBuilder();
    try (BufferedReader r =
        new BufferedReader(
            new InputStreamReader(p.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
      String line;
      while ((line = r.readLine()) != null) {
        out.append(line).append('\n');
      }
    }
    int exit = p.waitFor();
    if (exit != 0) {
      throw new IllegalStateException(command[0] + " exited " + exit + ": " + out);
    }
    return out.toString();
  }

  @Test
  void registerMakesAgentOnlineWithImmediateHeartbeat() throws Exception {
    db.register(cfg);
    try (Connection c = conn();
        PreparedStatement ps =
            c.prepareStatement(
                "SELECT status, last_heartbeat FROM titan.agents WHERE agent_id=?")) {
      ps.setString(1, "test-worker");
      try (ResultSet rs = ps.executeQuery()) {
        assertTrue(rs.next(), "agent row must exist after register");
        assertEquals("ONLINE", rs.getString("status"));
        assertNotNull(rs.getObject("last_heartbeat"), "register must stamp last_heartbeat");
      }
    }
  }

  @Test
  void claimRunsCommandStreamsLogsAndCompletes() throws Exception {
    assumeTrue(shAvailable(), "POSIX sh not on PATH — skipping sh-step IT");
    String marker = "titan-hello-" + System.nanoTime();
    long id = enqueueCommand(echoPayload(marker))[0];

    Optional<WorkerDb.ClaimedTask> claimed = db.claim("test-worker", "default");
    assertTrue(claimed.isPresent(), "the queued task must be claimable");
    WorkerDb.ClaimedTask task = claimed.get();
    assertEquals(id, task.id());

    db.markProcessing(task.id(), task.claimToken());
    TaskExecutor.Result result = executor.run(task);
    assertTrue(result.success(), "echo must exit 0");
    assertEquals(0, result.exitCode());

    assertTrue(db.complete(task.id(), task.claimToken(), "COMPLETED", result.resultJson()));

    try (Connection c = conn();
        PreparedStatement ps =
            c.prepareStatement("SELECT status FROM titan.task_queue WHERE id=?")) {
      ps.setLong(1, id);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        assertEquals("COMPLETED", rs.getString(1));
      }
    }

    // The command's stdout must have landed in titan.logs.
    try (Connection c = conn();
        PreparedStatement ps =
            c.prepareStatement("SELECT data FROM titan.logs WHERE task_id=? AND stream='stdout'")) {
      ps.setObject(1, task.taskToken());
      try (ResultSet rs = ps.executeQuery()) {
        StringBuilder out = new StringBuilder();
        while (rs.next()) {
          out.append(rs.getString(1));
        }
        assertTrue(
            out.toString().contains(marker),
            "stdout log must contain the echoed marker, was: " + out);
      }
    }
  }

  /**
   * Chunk 6F — a {@code script} step runs a Groovy body on the agent, the body calls a method from
   * a declared shared library, and {@code setOutput} publishes a step output that the orchestrator
   * can later resolve. Proves design/29 §10's migration contract end-to-end on the worker: the real
   * {@code vars/initBindings.groovy} fixture loads and its method executes.
   *
   * <p><strong>Currently disabled — surfaced once #675 unblocked this IT suite.</strong> The {@code
   * script} step's groovy-body binding does not expose shared-library {@code vars/} methods to the
   * body's delegate ({@code No signature of method: titanScriptBody.initBindings()}). This is a
   * real shared-library wiring regression in {@code GroovyScriptStepHandler}, not a test bug; the
   * fixture loads, the file is on disk under {@code <libraries>/moab-shared/vars/}, but the body's
   * MetaClass never resolves it. Tracked under a follow-up to keep this PR scoped to the compile +
   * schema-load unblock.
   */
  @Test
  void groovyScriptStepLoadsSharedLibraryAndPublishesOutputs() throws Exception {
    // Resolve the shared library: <librariesRoot>/moab-shared/vars/initBindings.groovy, using
    // the real engine fixture so this test exercises the actual shared-library code.
    Path vars = libraries.resolve("moab-shared").resolve("vars");
    Files.createDirectories(vars);
    // Fixture lives under titan-server's integrationTest resources — when the
    // engine moved out of titan-plugin the worker's local copy moved with the
    // engine ITs; the worker IT still drives the real fixture so it can't drift.
    Path fixture =
        Path.of(
            "../titan-server/src/integrationTest/resources/io/adaptiq/titan/flow/"
                + "fixtures/shared-library/vars/initBindings.groovy");
    assertTrue(
        Files.isRegularFile(fixture),
        "shared-library fixture not found at " + fixture.toAbsolutePath());
    Files.copy(fixture, vars.resolve("initBindings.groovy"));

    // A `script` step body: call the vars/ method, then publish an output.
    String body =
        "def b = initBindings(env: 'ci')\n"
            + "echo \"component is ${b.component}\"\n"
            + "setOutput('component', b.component)\n";
    String payload =
        JSON_MAPPER.writeValueAsString(
            java.util.Map.of(
                "runtime", "groovy", "body", body, "libraries", java.util.List.of("moab-shared")));
    long id = enqueueCommand(payload)[0];

    WorkerDb.ClaimedTask task = db.claim("test-worker", "default").orElseThrow();
    db.markProcessing(task.id(), task.claimToken());
    TaskExecutor.Result result = executor.run(task);

    assertTrue(result.success(), "the groovy script step must succeed: " + result.resultJson());
    assertEquals(0, result.exitCode());
    // setOutput must have landed in result_json's outputs object (Chunk 6E contract).
    assertTrue(
        result.resultJson().contains("\"component\":\"moab-core\""),
        "setOutput must publish component=moab-core, was: " + result.resultJson());
    assertTrue(db.complete(task.id(), task.claimToken(), "COMPLETED", result.resultJson()));

    // The shared-library `echo` and the body `echo` must have streamed to titan.logs.
    try (Connection c = conn();
        PreparedStatement ps =
            c.prepareStatement(
                "SELECT data FROM titan.logs WHERE task_id=? ORDER BY chunk_index")) {
      ps.setObject(1, task.taskToken());
      try (ResultSet rs = ps.executeQuery()) {
        StringBuilder out = new StringBuilder();
        while (rs.next()) {
          out.append(rs.getString(1)).append('\n');
        }
        String log = out.toString();
        assertTrue(
            log.contains("loaded shared library 'moab-shared'"),
            "the worker must report loading the library, log was: " + log);
        assertTrue(
            log.contains("[moab-shared] initBindings env=ci"),
            "the vars/ method's echo must appear in the log, was: " + log);
        assertTrue(
            log.contains("component is moab-core"),
            "the body's echo must appear in the log, was: " + log);
      }
    }
  }

  /** A groovy script step whose body throws (`error(...)`) fails the task, not the worker. */
  @Test
  void groovyScriptStepFailureFailsTheTask() throws Exception {
    String payload =
        JSON_MAPPER.writeValueAsString(
            java.util.Map.of(
                "runtime", "groovy",
                "body", "error('deliberate failure')\n"));
    long id = enqueueCommand(payload)[0];

    WorkerDb.ClaimedTask task = db.claim("test-worker", "default").orElseThrow();
    TaskExecutor.Result result = executor.run(task);

    assertFalse(result.success(), "a body that throws must fail the step");
    assertTrue(
        result.resultJson().contains("deliberate failure"),
        "the failure message must be recorded: " + result.resultJson());
  }

  /**
   * Chunk 6G — a command step with an {@code image} runs <em>inside that container</em>. Proves
   * design/31 §6G: the step executes in the declared image (asserted via an image-specific file),
   * the step env crosses in via {@code -e}, and the worker's own environment does not leak.
   */
  @Test
  void containerStepRunsInsideTheDeclaredImage() throws Exception {
    assumeTrue(dockerAvailable(), "Docker CLI not available — skipping container IT");
    // ContainerExecutor bind-mounts the worker's workspace into the container. That needs the
    // Docker daemon and the worker to share a filesystem — true on a Titan agent host, but not
    // on a dev box whose daemon lives in an isolated VM/WSL. Probe, and skip if it cannot.
    assumeTrue(
        bindMountWorks(),
        "Docker daemon cannot bind-mount the worker workspace here — skipping (runs on the rig)");

    // cat /etc/os-release proves which image we are in; env proves the -e step env crossed.
    String payload =
        JSON_MAPPER.writeValueAsString(
            java.util.Map.of(
                "stepDescriptor",
                "sh",
                "arguments",
                java.util.Map.of("script", "cat /etc/os-release; echo --; env"),
                "image",
                "alpine:3.20",
                "env",
                java.util.Map.of("GREETING", "titan-rocks"),
                "buildId",
                42,
                "nodeId",
                "build-s0"));
    long id = enqueueCommand(payload)[0];

    WorkerDb.ClaimedTask task = db.claim("test-worker", "default").orElseThrow();
    TaskExecutor.Result result = executor.run(task);
    assertTrue(result.success(), "the container step must exit 0: " + result.resultJson());

    String log = logText(task.taskToken());
    assertTrue(
        log.contains("Alpine Linux"),
        "the step must run inside the alpine image (/etc/os-release): " + log);
    assertTrue(
        log.contains("GREETING=titan-rocks"),
        "the step env must cross into the container via -e: " + log);
    assertFalse(
        log.contains("TITAN_DB"),
        "the worker's TITAN_* credentials must never leak into the container: " + log);
  }

  /** A no-{@code image} command step still runs as a local process — container path not taken. */
  @Test
  void commandStepWithoutImageStillRunsLocally() throws Exception {
    assumeTrue(shAvailable(), "POSIX sh not on PATH — skipping sh-step IT");
    String marker = "local-" + System.nanoTime();
    long id = enqueueCommand(echoPayload(marker))[0];
    WorkerDb.ClaimedTask task = db.claim("test-worker", "default").orElseThrow();
    TaskExecutor.Result result = executor.run(task);
    assertTrue(result.success());
    assertTrue(logText(task.taskToken()).contains(marker), "local execution must still work");
  }

  /**
   * Chunk 6G — {@link ContainerReaper} force-removes containers orphaned by a previous worker life.
   * Starts a container labelled as this worker's, then sweeps and asserts it is gone — the
   * design/30 reconcile model applied to containers.
   */
  @Test
  void orphanedContainersAreReapedOnSweep() throws Exception {
    assumeTrue(dockerAvailable(), "Docker CLI not available — skipping container IT");

    // Simulate a container left running by a crashed previous worker life.
    String containerId =
        sh(
                "docker",
                "run",
                "-d",
                "--label",
                "titan.managed=true",
                "--label",
                "titan.worker=test-worker",
                "alpine:3.20",
                "sleep",
                "300")
            .trim();
    assertFalse(containerId.isBlank(), "the orphan container must have started");
    try {
      int reaped = new ContainerReaper("test-worker").sweepOrphans();
      assertTrue(reaped >= 1, "the sweep must reap the orphaned container");

      String stillThere = sh("docker", "ps", "-aq", "--filter", "label=titan.worker=test-worker");
      assertFalse(
          stillThere.contains(containerId.substring(0, 12)),
          "the orphaned container must be gone after the sweep: " + stillThere);
    } finally {
      // Best-effort cleanup in case the sweep itself failed.
      try {
        sh("docker", "rm", "-f", containerId);
      } catch (Exception ignored) {
        // already removed by the sweep — expected
      }
    }
  }

  @Test
  void claimOnEmptyQueueReturnsEmpty() throws Exception {
    assertTrue(db.claim("test-worker", "default").isEmpty());
  }

  @Test
  void staleClaimTokenCompletionIsRejected() throws Exception {
    long id = enqueueCommand(echoPayload("x"))[0];

    WorkerDb.ClaimedTask first = db.claim("worker-A", "default").orElseThrow();
    UUID staleToken = first.claimToken();

    // Simulate a reaper requeue: status back to QUEUED, lease cleared.
    try (Connection c = conn();
        PreparedStatement ps =
            c.prepareStatement(
                "UPDATE titan.task_queue SET status='QUEUED', claim_token=NULL, "
                    + "claimed_by=NULL, claimed_at=NULL WHERE id=?")) {
      ps.setLong(1, id);
      ps.executeUpdate();
    }

    WorkerDb.ClaimedTask second = db.claim("worker-B", "default").orElseThrow();
    assertEquals(id, second.id());

    // The original worker's stale-token completion must be rejected.
    assertFalse(
        db.complete(id, staleToken, "COMPLETED", "{}"),
        "completion with the reaped lease token must be rejected");
    // The rightful re-claimant succeeds.
    assertTrue(db.complete(id, second.claimToken(), "COMPLETED", "{}"));
  }
}
