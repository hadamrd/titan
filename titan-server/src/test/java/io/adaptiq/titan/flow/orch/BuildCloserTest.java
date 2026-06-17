package io.adaptiq.titan.flow.orch;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.adaptiq.titan.api.FakeTitanStores;
import io.adaptiq.titan.flow.NotificationContext;
import io.adaptiq.titan.flow.NotificationDispatcher;
import io.adaptiq.titan.flow.model.PipelineModel;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.BuildRow;
import io.adaptiq.titan.store.rows.JobRow;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link BuildCloser} — the extracted terminal-write collaborator (design 67 step
 * 5). No Quarkus, no Testcontainers. The {@link FakeTitanStores} factory gives us a real H2-backed
 * {@link TitanStores} so the {@code BuildDao.updateStatus} chokepoint is exercised against actual
 * SQL, not a hand-rolled mock.
 *
 * <p>The CDI {@link io.adaptiq.titan.build.BuildStateChangedEvent} fan-out is best-effort and runs
 * outside a Quarkus container here — the production guard ({@code try/catch} around {@code
 * Arc.container()}) swallows the {@code IllegalStateException} and never fails the close. We do not
 * assert "event was fired"; that belongs to a Quarkus IT.
 */
class BuildCloserTest {

  @Test
  void closeOnSuccessPersistsTerminalStatusAndDuration() {
    TitanStores stores = FakeTitanStores.create();
    long buildId = insertRunningBuild(stores);

    BuildCloser closer = new BuildCloser(stores, new RecordingNotifications());
    closer.close(buildId, "SUCCESS", emptyModel());

    BuildRow row = stores.builds().findById(buildId).orElseThrow();
    assertEquals("SUCCESS", row.status);
    assertNotNull(row.finishedAt);
    assertNotNull(row.durationMs);
    assertTrue(row.durationMs >= 0, "duration must be non-negative");
  }

  @Test
  void closeOnFailedReflectsDurationFromStartedAt() {
    TitanStores stores = FakeTitanStores.create();
    long buildId = insertRunningBuild(stores);
    // Plant a started_at 250ms in the past so duration is a real positive number.
    Instant started = Instant.now().minusMillis(250);
    stores.builds().updateStatus(buildId, "RUNNING", started, null, null, null);

    BuildCloser closer = new BuildCloser(stores, new RecordingNotifications());
    closer.close(buildId, "FAILED", emptyModel());

    BuildRow row = stores.builds().findById(buildId).orElseThrow();
    assertEquals("FAILED", row.status);
    assertNotNull(row.durationMs);
    assertTrue(
        row.durationMs >= 200, "duration ~= finishedAt - startedAt ≥ 200ms, was " + row.durationMs);
  }

  @Test
  void closeWithNullStartedAtBackfillsToZeroDurationAndDoesNotNpe() {
    TitanStores stores = FakeTitanStores.create();
    long buildId = insertRunningBuild(stores);
    // started_at is null on the inserted row (insertRunningBuild doesn't set it).
    BuildRow before = stores.builds().findById(buildId).orElseThrow();
    assertNull(before.startedAt);

    BuildCloser closer = new BuildCloser(stores, new RecordingNotifications());
    assertDoesNotThrow(() -> closer.close(buildId, "SUCCESS", emptyModel()));

    BuildRow row = stores.builds().findById(buildId).orElseThrow();
    assertEquals("SUCCESS", row.status);
    // Back-filled started_at = finished_at → duration ~= 0 (a few ms slop tolerated).
    assertNotNull(row.durationMs);
    assertTrue(
        row.durationMs < 100,
        "duration should be ~0 when started_at was back-filled, was " + row.durationMs);
  }

  @Test
  void notificationHookThrowsExceptionIsSwallowedAndCloseReturnsNormally() {
    TitanStores stores = FakeTitanStores.create();
    long buildId = insertRunningBuild(stores);

    ThrowingNotifications throwing = new ThrowingNotifications();
    BuildCloser closer = new BuildCloser(stores, throwing);
    assertDoesNotThrow(() -> closer.close(buildId, "SUCCESS", emptyModel()));

    // The DB still got the terminal write — the swallowed notify error must not abort the close.
    BuildRow row = stores.builds().findById(buildId).orElseThrow();
    assertEquals("SUCCESS", row.status);
    assertTrue(throwing.called, "notify hook should have been invoked once");
  }

  @Test
  void closeIsIdempotentSecondCallDoesNotRefireNotify() {
    TitanStores stores = FakeTitanStores.create();
    long buildId = insertRunningBuild(stores);

    RecordingNotifications notif = new RecordingNotifications();
    BuildCloser closer = new BuildCloser(stores, notif);

    closer.close(buildId, "SUCCESS", emptyModel());
    assertEquals(1, notif.callCount, "first close fires the hook");

    // Second call — row is already terminal, must be a full no-op (no second notify, no second
    // updateStatus write — the status remains exactly what the first call set).
    closer.close(buildId, "FAILED", emptyModel());
    assertEquals(1, notif.callCount, "idempotent — second close must NOT re-fire notify");

    BuildRow row = stores.builds().findById(buildId).orElseThrow();
    assertEquals("SUCCESS", row.status, "idempotent — second close must NOT overwrite status");
  }

  @Test
  void nullNotificationDispatcherDoesNotNpe() {
    TitanStores stores = FakeTitanStores.create();
    long buildId = insertRunningBuild(stores);

    BuildCloser closer = new BuildCloser(stores, null);
    assertDoesNotThrow(() -> closer.close(buildId, "SUCCESS", emptyModel()));

    BuildRow row = stores.builds().findById(buildId).orElseThrow();
    assertEquals("SUCCESS", row.status);
  }

  // ── fixtures ──────────────────────────────────────────────────────────────

  private static long insertJob(TitanStores stores) {
    JobRow row = new JobRow();
    row.fullName = "build-closer-test/" + System.nanoTime();
    row.pipelineScript = "titan: {}";
    row.configJson = "{}";
    row.enabled = true;
    row.createdAt = Instant.now();
    row.updatedAt = row.createdAt;
    return stores.jobs().insert(row);
  }

  private static long insertRunningBuild(TitanStores stores) {
    long jobId = insertJob(stores);
    BuildRow row = new BuildRow();
    row.jobId = jobId;
    row.buildNumber = 1;
    row.status = "RUNNING";
    row.queuedAt = Instant.now();
    return stores.builds().insert(row);
  }

  private static PipelineModel emptyModel() {
    PipelineModel m = new PipelineModel();
    m.setStages(List.of());
    return m;
  }

  /** A NotificationDispatcher subclass that records hook invocations. */
  private static final class RecordingNotifications extends NotificationDispatcher {
    int callCount = 0;
    NotificationContext lastCtx;

    @Override
    public void fireBuildHooks(long buildId, String result, PipelineModel model) {
      callCount++;
    }

    @Override
    public void fireBuildHooks(NotificationContext ctx, PipelineModel model) {
      callCount++;
      lastCtx = ctx;
    }
  }

  /** A NotificationDispatcher subclass that throws to prove the swallow contract. */
  private static final class ThrowingNotifications extends NotificationDispatcher {
    boolean called = false;

    @Override
    public void fireBuildHooks(long buildId, String result, PipelineModel model) {
      called = true;
      throw new RuntimeException("simulated sink failure");
    }

    @Override
    public void fireBuildHooks(NotificationContext ctx, PipelineModel model) {
      called = true;
      throw new RuntimeException("simulated sink failure");
    }
  }
}
