package io.adaptiq.titan.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link WorkerConfig#queueNames()} — the queue-routing fix for issue #824.
 *
 * <p>Before the fix the worker polled only {@code TITAN_QUEUE} (default {@code "default"}) and
 * therefore silently dropped any step pinned to an {@code agent: <label>} queue. These tests pin
 * down the new behaviour: queue list is derived from labels + explicit override + the implicit
 * {@code "default"} fallback, dedup'd, with order preserved.
 */
final class WorkerConfigQueueRoutingTest {

  /** A {@link WorkerConfig} that varies only the fields the routing test cares about. */
  private static WorkerConfig cfg(String queue, String labels, String agentId) {
    return new WorkerConfig(
        "jdbc:postgresql://x/y",
        "u",
        "p",
        agentId,
        "display",
        labels,
        1,
        "/titan",
        "NORMAL",
        queue,
        WorkerConfig.DEFAULT_SYNTHESIS_QUEUE,
        Paths.get("/tmp/ws"),
        "",
        Paths.get("/tmp/libs"),
        1000L,
        10000L,
        "",
        Map.of());
  }

  @Test
  void linuxLabelWorker_subscribesToLinuxQueue_andDefault() {
    // The bug from #824: a worker started with TITAN_LABELS=linux and TITAN_QUEUE=default
    // MUST poll the `linux` queue too — that is where StepDispatcher routes a stage with
    // `agent: linux`.
    List<String> queues = cfg("default", "linux", "worker-1").queueNames();
    assertTrue(queues.contains("linux"), "must poll the 'linux' queue: " + queues);
    assertTrue(queues.contains("default"), "must still poll 'default': " + queues);
  }

  @Test
  void multipleLabels_eachBecomeAQueue() {
    List<String> queues = cfg("default", "linux, docker , gpu", "worker-1").queueNames();
    assertTrue(queues.contains("linux"));
    assertTrue(queues.contains("docker"));
    assertTrue(queues.contains("gpu"));
    assertTrue(queues.contains("default"));
  }

  @Test
  void deduplicates_preservingFirstOccurrenceOrder() {
    // Explicit TITAN_QUEUE=linux + TITAN_LABELS=linux must not yield two "linux" entries.
    List<String> queues = cfg("linux", "linux,docker", "worker-x").queueNames();
    assertEquals("linux", queues.get(0), "explicit TITAN_QUEUE keeps priority: " + queues);
    assertEquals(1, queues.stream().filter("linux"::equals).count(), "dedup: " + queues);
  }

  @Test
  void blankLabels_stillPollsDefault() {
    // A worker with TITAN_LABELS unset must still drain "default" — the universal fallback.
    List<String> queues = cfg("default", "", "worker-1").queueNames();
    assertTrue(queues.contains("default"), "default fallback: " + queues);
  }

  @Test
  void agentIdIsAlwaysSubscribed() {
    // For future per-agent-pinned tasks (e.g. cancel-step delivery) — the worker MUST drain its
    // own agent-id queue without the operator having to add it to TITAN_LABELS.
    List<String> queues = cfg("default", "linux", "worker-42").queueNames();
    assertTrue(queues.contains("worker-42"), "agent-id queue: " + queues);
  }

  @Test
  void synthesisQueue_isNotInStepQueues() {
    // Synthesis is drained on a SEPARATE last-resort pass in TitanWorker.dispatchOne so
    // synthesis bursts cannot starve step work. queueNames() must NOT include it.
    List<String> queues = cfg("default", "linux", "worker-1").queueNames();
    assertTrue(
        !queues.contains(WorkerConfig.DEFAULT_SYNTHESIS_QUEUE),
        "synthesis is drained separately, never as a step queue: " + queues);
  }

  @Test
  void blankTokens_areDropped() {
    // A trailing comma must not yield an empty-string queue name (which would silently match
    // QUEUED rows with NULL/empty queue_name — a footgun).
    List<String> queues = cfg("default", "linux,,docker,", "worker-1").queueNames();
    assertTrue(queues.stream().noneMatch(String::isEmpty), "no empty queues: " + queues);
    assertTrue(queues.contains("linux"));
    assertTrue(queues.contains("docker"));
  }
}
