package io.adaptiq.titan.trigger.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.adaptiq.titan.api.PostgresItProfile;
import io.adaptiq.titan.api.PostgresTestResource;
import io.adaptiq.titan.flow.TitanFlowExecution;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.BuildRow;
import io.adaptiq.titan.store.rows.JobRow;
import io.adaptiq.titan.store.rows.TaskQueueRow;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Adversarial full-lifecycle IT for issue #106 — a <em>cron-fired</em> build must reach the worker
 * path and a terminal {@code SUCCESS}, driven end to end by production components:
 *
 * <ol>
 *   <li>{@link TriggerEngine#tick()} (the production {@code @Scheduled} entry point, NOT a direct
 *       {@code DbTriggerScope.fire()} call) evaluates the job's {@code config_json} cron trigger
 *       and fires — inserting the build + its entry ORCHESTRATE task;
 *   <li>the live {@code QueueProcessorScheduler} (ticking in this Quarkus test context) claims and
 *       dispatches that task — this is exactly where the pre-fix build died with "unknown
 *       orchestration action 'null'" because the cron path enqueued {@code {"buildId":N}} with no
 *       {@code "action"} key;
 *   <li>a stubbed worker (this test) claims the dispatched {@code EXECUTE_COMMAND/SYNTHESIZE} task
 *       off the shared {@code synthesis} queue — proof the build reached the WORKER path — writes
 *       the model, and completes each step task green;
 *   <li>the controller bakes, advances, and closes the build {@code SUCCESS}.
 * </ol>
 *
 * <p>Complements {@code CronTriggerSmokeIT} (which only asserts a build row appears) and the
 * payload-shape unit pin in {@code SynthesizeEntryTaskTest}: on the pre-#106 code this test fails
 * in seconds with the build FAILED and {@code error_message} carrying "unknown orchestration action
 * 'null'" — surfaced verbatim in the assertion message below.
 */
@QuarkusTest
@TestProfile(PostgresItProfile.class)
@QuarkusTestResource(value = PostgresTestResource.class, restrictToAnnotatedClass = true)
class CronTriggerBuildLifecycleIT {

  private static final ObjectMapper JSON = new ObjectMapper();

  /** Generous wall-clock budget: ADVANCE re-arm cadence is 5s, synthesis poll 2s. */
  private static final Duration DEADLINE = Duration.ofSeconds(180);

  private static final Set<String> TERMINAL = Set.of("SUCCESS", "FAILED", "ABORTED");

  /** Trivial one-step pipeline; no {@code agent:} label → the step runs off the default queue. */
  private static final String PIPELINE_YAML =
      """
      stages:
        - stage: hello
          steps:
            - sh: echo cron-lifecycle
      """;

  @Inject TitanStores stores;

  @Inject TriggerEngine engine;

  @Test
  void cronFiredBuildReachesWorkerSynthesisAndTerminalSuccess() throws Exception {
    // ── Seed: a cron-triggered job whose trigger baseline is already 2 minutes old ─
    JobRow row = new JobRow();
    row.fullName = "cron-lifecycle-it/" + System.nanoTime();
    row.displayName = "cron-lifecycle-it";
    row.pipelineScript = PIPELINE_YAML;
    // DbTriggerSubsystem parses triggers out of config_json.triggers[] via TriggerCodec; a
    // stable id is required for the last-fired bookkeeping.
    row.configJson =
        "{\"triggers\":[{\"type\":\"cron\",\"id\":\"cron-106\",\"spec\":\"* * * * *\"}]}";
    row.createdBy = "cron-lifecycle-it";
    row.enabled = true;
    long jobId = stores.jobs().insert(row);

    // Past first-sight: without a stored last_fired_at, design/50's first-sight rule would only
    // stamp a baseline on the first tick and never fire retroactively.
    Instant twoMinutesAgo = Instant.now().minus(Duration.ofMinutes(2));
    stores.withTransaction(
        conn -> {
          stores.jobTriggers().recordState(conn, jobId, "cron-106", twoMinutesAgo, null);
          return null;
        });

    // ── Fire via the production tick — the full DbTriggerSubsystem → TriggerDispatch →
    // DbTriggerScope.fire() chain, not a direct fire() call ─
    engine.tick();

    BuildRow build =
        await(
            "cron tick fired a build", () -> stores.builds().listByJob(jobId).stream().findFirst());

    // ── Drive to terminal with a stubbed green worker; the CONTROLLER side is entirely the
    // production QueueProcessorScheduler ticking in the background ─
    boolean sawWorkerSynthesis = false;
    long deadlineNanos = System.nanoTime() + DEADLINE.toNanos();
    while (!TERMINAL.contains(build.status)) {
      if (System.nanoTime() > deadlineNanos) {
        fail(
            "build "
                + build.id
                + " did not reach a terminal state within "
                + DEADLINE.getSeconds()
                + "s — status="
                + build.status
                + " errorMessage="
                + build.errorMessage);
      }
      sawWorkerSynthesis |= stubWorkerDrainSynthesisQueue();
      stubWorkerDrainStepQueue();
      Thread.sleep(200);
      build = stores.builds().findById(build.id).orElseThrow();
    }

    // Adversarial pin on the exact #106 failure mode, surfaced with its message if it regresses.
    assertFalse(
        build.errorMessage != null && build.errorMessage.contains("unknown orchestration action"),
        "the #106 regression is back — cron path enqueued an ORCHESTRATE task without an"
            + " 'action' key: "
            + build.errorMessage);
    assertTrue(
        sawWorkerSynthesis,
        "the cron-fired build never dispatched a worker EXECUTE_COMMAND/SYNTHESIZE task — it"
            + " terminated inside the controller (status="
            + build.status
            + ", errorMessage="
            + build.errorMessage
            + ")");
    assertEquals(
        "SUCCESS",
        build.status,
        "a cron-fired one-step build must close SUCCESS via the worker path; errorMessage="
            + build.errorMessage);
  }

  /**
   * Stub the worker's synthesis loop: claim from the shared {@code synthesis} queue, write the
   * model exactly the way a worker's YAML-identity synthesis does, complete the task green. Returns
   * true if a synthesis task was claimed (the "reached the worker path" proof).
   */
  private boolean stubWorkerDrainSynthesisQueue() throws Exception {
    boolean claimedAny = false;
    Optional<TaskQueueRow> claimed;
    while ((claimed =
            stores.taskQueue().claimExecuteCommand("stub-worker", "synthesis", UUID.randomUUID()))
        .isPresent()) {
      TaskQueueRow t = claimed.get();
      claimedAny = true;
      JsonNode payload = JSON.readTree(t.payloadJson);
      String script = payload.get("pipelineScript").asText();
      new TitanFlowExecution(stores, t.buildId).synthesize(script);
      stores.taskQueue().complete(t.id, t.claimToken, "COMPLETED", "{\"status\":\"OK\"}");
    }
    return claimedAny;
  }

  /** Stub the worker's step loop: complete every dispatched step task green (exit 0). */
  private void stubWorkerDrainStepQueue() {
    Optional<TaskQueueRow> claimed;
    while ((claimed =
            stores.taskQueue().claimExecuteCommand("stub-worker", "default", UUID.randomUUID()))
        .isPresent()) {
      TaskQueueRow t = claimed.get();
      stores.taskQueue().complete(t.id, t.claimToken, "COMPLETED", "{\"exitCode\":0}");
    }
  }

  /** Poll {@code supplier} until it yields, or fail after {@link #DEADLINE}. */
  private static <T> T await(String what, java.util.function.Supplier<Optional<T>> supplier)
      throws InterruptedException {
    long deadlineNanos = System.nanoTime() + DEADLINE.toNanos();
    while (System.nanoTime() <= deadlineNanos) {
      Optional<T> v = supplier.get();
      if (v.isPresent()) {
        return v.get();
      }
      Thread.sleep(200);
    }
    throw new AssertionError("timed out waiting for: " + what);
  }
}
