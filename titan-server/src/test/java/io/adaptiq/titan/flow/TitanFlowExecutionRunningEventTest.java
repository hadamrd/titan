package io.adaptiq.titan.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.adaptiq.titan.api.FakeTitanStores;
import io.adaptiq.titan.build.BuildStateChangedEvent;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.BuildRow;
import io.adaptiq.titan.store.rows.JobRow;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit-level proof (H2-backed {@link FakeTitanStores}) of the issue-#99 fix: {@link
 * TitanFlowExecution#bake()} fires a {@link BuildStateChangedEvent} with {@code newStatus =
 * "RUNNING"} after the bake transaction commits — the QUEUED→RUNNING flip used to be a direct DAO
 * write ({@code BuildDao.activateIfQueued}) that no observer ever saw, so every SCM status
 * reporter's {@code in_progress} signal was dead code on the production path.
 *
 * <p>The contract under test:
 *
 * <ul>
 *   <li><b>Exactly once</b> — the event fires only when the {@code activateIfQueued} CAS actually
 *       flipped the row: a re-delivered BAKE (already-materialised DAG) and a non-{@code QUEUED}
 *       build fire nothing.
 *   <li><b>Provenance</b> — the event carries the build's own {@code triggerType} / {@code
 *       triggerMetaJson} / job identity, read fresh from the row, so reporters can filter without a
 *       DB round-trip (same shape {@code BuildCloser} emits at terminal).
 *   <li><b>Best-effort</b> — a throwing sink, or the default CDI-bus sink outside a container (raw
 *       JUnit), never fails a bake that has already committed.
 * </ul>
 */
class TitanFlowExecutionRunningEventTest {

  private static final String YAML =
      """
      titan:
        stages:
          - stage: Only
            steps:
              - sh: "echo hi"
      """;

  private static final String TRIGGER_META = "{\"commitSha\":\"oid1abc\",\"changeId\":\"42\"}";

  private TitanStores stores;
  private long jobId;

  @BeforeEach
  void setUp() {
    stores = FakeTitanStores.create();
    JobRow row = new JobRow();
    row.fullName = "running-event/" + System.nanoTime();
    row.pipelineScript = YAML;
    row.configJson = "{}";
    row.enabled = true;
    row.createdAt = Instant.now();
    row.updatedAt = row.createdAt;
    jobId = stores.jobs().insert(row);
  }

  @Test
  void bakeFiresRunningEventExactlyOnceWithBuildProvenance() {
    long buildId = insertBuild("QUEUED", 7);
    List<BuildStateChangedEvent> events = new ArrayList<>();

    TitanFlowExecution.BakeResult result =
        new TitanFlowExecution(stores, buildId, events::add).bake(YAML);

    assertEquals(TitanFlowExecution.BakeResult.BAKED, result);
    assertEquals(1, events.size(), "the QUEUED→RUNNING flip fires exactly one event");
    BuildStateChangedEvent evt = events.get(0);
    assertEquals(buildId, evt.buildId());
    assertEquals("RUNNING", evt.newStatus());
    assertEquals("pulsar", evt.triggerType(), "provenance comes from the build row");
    assertEquals(TRIGGER_META, evt.triggerMetaJson());
    assertEquals(jobId, evt.jobId());
    assertEquals(7, evt.buildNumber());

    // The event describes a durable fact: the row really is RUNNING with started_at stamped.
    BuildRow fresh = stores.builds().findById(buildId).orElseThrow();
    assertEquals("RUNNING", fresh.status);
    assertNotNull(fresh.startedAt, "activateIfQueued stamped started_at");
  }

  @Test
  void redeliveredBakeIsAlreadyBakedAndFiresNoSecondEvent() {
    long buildId = insertBuild("QUEUED", 1);
    List<BuildStateChangedEvent> events = new ArrayList<>();
    TitanFlowExecution execution = new TitanFlowExecution(stores, buildId, events::add);

    assertEquals(TitanFlowExecution.BakeResult.BAKED, execution.bake(YAML));
    // Task re-delivery (design/30): the DAG is already materialised — the bake is a no-op and
    // MUST NOT re-fire the RUNNING event (the reporter would double-post in_progress).
    assertEquals(TitanFlowExecution.BakeResult.ALREADY_BAKED, execution.bake(YAML));

    assertEquals(1, events.size(), "a re-delivered bake fires nothing");
  }

  @Test
  void nonQueuedBuildLosesTheActivationCasAndFiresNoEvent() {
    // A build aborted while its BAKE task sat in the queue: the CAS loses, status stays as-is.
    long buildId = insertBuild("ABORTED", 1);
    List<BuildStateChangedEvent> events = new ArrayList<>();

    assertEquals(
        TitanFlowExecution.BakeResult.BAKED,
        new TitanFlowExecution(stores, buildId, events::add).bake(YAML));

    assertTrue(events.isEmpty(), "no CAS flip → no RUNNING event");
    assertEquals("ABORTED", stores.builds().findById(buildId).orElseThrow().status);
  }

  @Test
  void throwingSinkNeverFailsACommittedBake() {
    long buildId = insertBuild("QUEUED", 1);

    TitanFlowExecution.BakeResult result =
        new TitanFlowExecution(
                stores,
                buildId,
                evt -> {
                  throw new IllegalStateException("observer chain blew up");
                })
            .bake(YAML);

    assertEquals(TitanFlowExecution.BakeResult.BAKED, result, "the emit is best-effort");
    assertEquals("RUNNING", stores.builds().findById(buildId).orElseThrow().status);
  }

  @Test
  void defaultCdiSinkOutsideAContainerIsSwallowed() {
    // The production two-arg constructor fires via programmatic Arc lookup; in raw JUnit there is
    // no CDI container and the lookup throws — the bake must still succeed (PR #895 semantics).
    long buildId = insertBuild("QUEUED", 1);

    TitanFlowExecution.BakeResult result = new TitanFlowExecution(stores, buildId).bake(YAML);

    assertEquals(TitanFlowExecution.BakeResult.BAKED, result);
    assertEquals("RUNNING", stores.builds().findById(buildId).orElseThrow().status);
  }

  // ── helpers ──────────────────────────────────────────────────────────────

  private long insertBuild(String status, int buildNumber) {
    BuildRow row = new BuildRow();
    row.jobId = jobId;
    row.buildNumber = buildNumber;
    row.status = status;
    row.triggeredBy = "pulsar";
    row.triggerType = "pulsar";
    row.triggerMetaJson = TRIGGER_META;
    row.queuedAt = Instant.now();
    return stores.builds().insert(row);
  }
}
