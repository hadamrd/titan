package io.adaptiq.titan.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.BuildRow;
import io.adaptiq.titan.store.rows.JobRow;
import io.adaptiq.titan.store.rows.LogRow;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.RestAssured;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Adversarial regression tests for {@link BuildLogsSse} (issue #88): the poll cursor must key on
 * the per-task {@code chunk_index} (unique + gapless via {@code uq_logs_task_chunk}), NOT on the
 * global row {@code id}.
 *
 * <p>Log row ids come from a sequence shared by every concurrently-writing task, so id order is not
 * monotonic in chunk_index order. The old implementation iterated rows in chunk_index order but
 * high-watermarked a single {@code id} cursor — any chunk whose id was lower than an
 * already-emitted row's id was skipped <em>forever</em> (build 681: chunks 9/11/13/19/21 dropped;
 * chunk 9 was the vitest FAIL line).
 *
 * <p>These tests seed {@code titan.logs} with ids deliberately inverted relative to chunk_index
 * (rows are inserted out of chunk order; the H2 identity column hands out ids in insert order) and
 * assert the SSE stream carries EVERY chunk exactly once, in chunk_index order per task. Verified
 * red on the pre-fix cursor logic: {@code streamsIdInvertedChunks_singleTask} dropped chunk 1 (3/4
 * frames) when run against the old max-id watermark.
 *
 * <p>H2-backed {@link TitanStores} via {@link H2StoresProducer} — no Docker. The build is seeded in
 * a terminal status, so the stream self-terminates with a {@code done} event and a plain blocking
 * GET returns the full SSE body.
 */
@QuarkusTest
@TestSecurity(user = "testuser", roles = "ADMIN")
class BuildLogsSseTest {

  @Inject TitanStores stores;

  /**
   * Single task, ids inverted within the task: chunk_index 1 is inserted BEFORE chunk_index 0, so
   * chunk 1 gets the lower id. The old global max-id cursor emitted chunk 0 (higher id), advanced
   * past chunk 1's id, and dropped chunk 1 forever.
   */
  @Test
  void streamsIdInvertedChunks_singleTask() {
    long buildId = insertTerminalBuild("org/sse-inverted-" + System.nanoTime());
    UUID token = enqueueTask(buildId, "node-a");

    // Insert order (= id order): chunk 1, chunk 0, chunk 3, chunk 2 — two id inversions.
    insertChunk(token, 1, "line-1 FAIL src/thing.test.ts");
    insertChunk(token, 0, "line-0");
    insertChunk(token, 3, "line-3");
    insertChunk(token, 2, "line-2");

    List<String> logs = streamLogEvents(buildId, token);

    assertEquals(
        List.of("line-0", "line-1 FAIL src/thing.test.ts", "line-2", "line-3"),
        logs,
        "every chunk exactly once, in chunk_index order");
  }

  /**
   * Two tasks of the same build, ids inverted ACROSS tasks: the later task's chunk carries a lower
   * id than the earlier task's chunk. With the old single cursor shared across tasks, whichever
   * task drained first pushed the watermark above the other task's ids.
   */
  @Test
  void streamsIdInvertedChunks_acrossTasks() {
    long buildId = insertTerminalBuild("org/sse-crosstask-" + System.nanoTime());
    UUID tokenA = enqueueTask(buildId, "node-a");
    UUID tokenB = enqueueTask(buildId, "node-b");

    // Task B's chunks get the LOWER ids (inserted first); task A's the higher ones.
    insertChunk(tokenB, 0, "B-0");
    insertChunk(tokenB, 1, "B-1");
    insertChunk(tokenA, 0, "A-0");
    insertChunk(tokenA, 1, "A-1");

    List<String> logs = streamLogEvents(buildId, null);

    assertEquals(4, logs.size(), "no chunk dropped: " + logs);
    assertTrue(indexOf(logs, "A-0") < indexOf(logs, "A-1"), "task A in chunk_index order: " + logs);
    assertTrue(indexOf(logs, "B-0") < indexOf(logs, "B-1"), "task B in chunk_index order: " + logs);
  }

  /**
   * The retry-announcement line is written at {@code chunk_index = -1} (StepRetryPolicy) so it
   * sorts before the worker's first chunk — the per-task mark must start below -1, not at 0/-1.
   */
  @Test
  void streamsRetryAnnouncementChunkAtMinusOne() {
    long buildId = insertTerminalBuild("org/sse-retry-" + System.nanoTime());
    UUID token = enqueueTask(buildId, "node-a");

    insertChunk(token, 0, "first-worker-line");
    insertChunk(token, -1, "retry 2/3 of node-a");

    List<String> logs = streamLogEvents(buildId, token);

    assertEquals(List.of("retry 2/3 of node-a", "first-worker-line"), logs);
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  private long insertTerminalBuild(String jobFullName) {
    JobRow job = new JobRow();
    job.fullName = jobFullName;
    job.enabled = true;
    job.pipelineScript = "";
    job.configJson = "{}";
    long jobId = stores.jobs().insert(job);

    BuildRow build = new BuildRow();
    build.jobId = jobId;
    build.buildNumber = stores.builds().nextBuildNumber(jobId);
    build.status = "FAILED"; // terminal → the SSE stream self-terminates with `done`
    build.queuedAt = Instant.now();
    build.triggeredBy = "test";
    build.triggerType = "manual";
    return stores.withTransaction(conn -> stores.builds().insert(conn, build));
  }

  private UUID enqueueTask(long buildId, String nodeId) {
    long taskId =
        stores.taskQueue().enqueue("EXECUTE_COMMAND", "default", 0, "{}", 3, 60, buildId, nodeId);
    return stores.taskQueue().findById(taskId).orElseThrow().taskToken;
  }

  private void insertChunk(UUID token, int chunkIndex, String data) {
    LogRow row = new LogRow();
    row.taskId = token;
    row.chunkIndex = chunkIndex;
    row.stream = "stdout";
    row.data = data;
    row.isFinal = false;
    stores.logs().insert(row);
  }

  /**
   * Blocking GET on the SSE endpoint (the terminal build closes the stream after the drain), then
   * extracts the {@code data:} payload of every {@code event: log} frame, in wire order.
   */
  private List<String> streamLogEvents(long buildId, UUID taskToken) {
    String path =
        "/api/v1/builds/" + buildId + "/logs" + (taskToken == null ? "" : "?taskId=" + taskToken);
    String body =
        RestAssured.given().when().get(path).then().statusCode(200).extract().body().asString();

    List<String> logs = new ArrayList<>();
    String currentEvent = null;
    for (String line : body.split("\n", -1)) {
      line = line.stripTrailing();
      if (line.startsWith("event:")) {
        currentEvent = line.substring("event:".length()).trim();
      } else if (line.startsWith("data:") && "log".equals(currentEvent)) {
        logs.add(line.substring("data:".length()).stripLeading());
      }
    }
    assertTrue(body.contains("done"), "stream must terminate with a done event; body:\n" + body);
    return logs;
  }

  private static int indexOf(List<String> list, String value) {
    int i = list.indexOf(value);
    assertTrue(i >= 0, "missing chunk " + value + " in " + list);
    return i;
  }
}
