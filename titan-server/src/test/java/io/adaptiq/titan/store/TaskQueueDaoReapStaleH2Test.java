package io.adaptiq.titan.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.adaptiq.titan.store.rows.TaskQueueRow;
import java.lang.reflect.Method;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Adversarial H2 regression for issue #518.
 *
 * <p>{@code TaskQueueDao.reapStale} previously embedded {@code CURRENT_TIMESTAMP - (:n * INTERVAL
 * '1' SECOND)} in its UPDATEs. PostgreSQL accepts that arithmetic; H2 — which every
 * {@code @QuarkusTest} bootstraps against — rejects it with {@code "Feature not supported: UNKNOWN
 * * INTERVAL SECOND"}, which crashed the SCHEDULED {@code QueueProcessor.reapStaleTasks} sweep on
 * every test-context startup and took every {@code @QuarkusTest} in {@code titan-server} down with
 * it (JobsApiTest #511, AuditApiTest #519).
 *
 * <p>This test runs the reaper directly against an in-memory H2 with the production schema. It MUST
 * hard-fail if anyone re-introduces dialect-specific interval math.
 *
 * <p>Also pins the issue #49 liveness guard: a stale-by-{@code claimed_at} claim whose {@code
 * claimed_by} worker still heartbeats within the liveness window must NOT be reaped — the lease
 * lives as long as the executing worker does. A claimant with a lapsed heartbeat (or none at all —
 * e.g. a controller) keeps the original reap behavior.
 */
class TaskQueueDaoReapStaleH2Test {

  private static final int WORKER_LIVENESS_SECONDS = 90;

  private TitanStores stores;

  @BeforeEach
  void setUp() throws Exception {
    Class<?> fake = Class.forName("io.adaptiq.titan.api.FakeTitanStores");
    Method create = fake.getDeclaredMethod("create");
    create.setAccessible(true);
    stores = (TitanStores) create.invoke(null);
  }

  // ── exhausted-retry path → terminally FAILED ──────────────────────────────

  @Test
  void reapStale_failsExhaustedClaimedTaskOlderThanCutoff() {
    long id =
        stores.taskQueue().enqueue("EXECUTE_COMMAND", "default", 0, "{}", 3, 60, null, null, null);
    // Simulate a zombie claim: status=CLAIMED, attempts maxed, claimed 2h ago.
    forceClaim(id, "CLAIMED", 3, 3, Instant.now().minusSeconds(7200));

    TaskQueueDao.ReapResult r = stores.taskQueue().reapStale(3600, WORKER_LIVENESS_SECONDS);

    assertEquals(1, r.failed(), "exhausted stale claim must be reaped to FAILED");
    assertEquals(0, r.requeued(), "exhausted claim must NOT be requeued");

    TaskQueueRow row = stores.taskQueue().findById(id).orElseThrow();
    assertEquals("FAILED", row.status);
    assertNotNull(row.completedAt, "completed_at must be stamped on terminal failure");
    assertTrue(
        row.resultJson != null && row.resultJson.contains("visibility timeout exceeded"),
        "result_json must carry the visibility-timeout reason, was: " + row.resultJson);
  }

  // ── retryable path → reset to QUEUED, lease cleared ───────────────────────

  @Test
  void reapStale_requeuesRetryableClaimedTaskAndClearsLease() {
    long id =
        stores.taskQueue().enqueue("EXECUTE_COMMAND", "default", 0, "{}", 3, 60, null, null, null);
    // Retryable: attempts (1) < max_attempts (3); claim is 2h stale.
    forceClaim(id, "PROCESSING", 1, 3, Instant.now().minusSeconds(7200));

    TaskQueueDao.ReapResult r = stores.taskQueue().reapStale(3600, WORKER_LIVENESS_SECONDS);

    assertEquals(1, r.requeued(), "retryable stale claim must be reset to QUEUED");
    assertEquals(0, r.failed(), "retryable claim must NOT be terminally failed");

    TaskQueueRow row = stores.taskQueue().findById(id).orElseThrow();
    assertEquals("QUEUED", row.status);
    assertNull(row.claimToken, "lease token must be cleared on requeue (doc-27 G3)");
    assertNull(row.claimedBy);
    assertNull(row.claimedAt);
  }

  // ── fresh claim within visibility window → untouched ──────────────────────

  @Test
  void reapStale_leavesFreshClaimAlone() {
    long id =
        stores.taskQueue().enqueue("EXECUTE_COMMAND", "default", 0, "{}", 3, 60, null, null, null);
    // Claimed 10s ago, visibility timeout 3600s → well inside the lease.
    forceClaim(id, "CLAIMED", 0, 3, Instant.now().minusSeconds(10));

    TaskQueueDao.ReapResult r = stores.taskQueue().reapStale(3600, WORKER_LIVENESS_SECONDS);

    assertEquals(0, r.failed());
    assertEquals(0, r.requeued());
    assertEquals("CLAIMED", stores.taskQueue().findById(id).orElseThrow().status);
  }

  // ── issue #49: live-worker liveness guard ─────────────────────────────────

  @Test
  void reapStale_sparesStaleClaimWhoseWorkerStillHeartbeats() {
    long id =
        stores.taskQueue().enqueue("EXECUTE_COMMAND", "default", 0, "{}", 3, 60, null, null, null);
    // Execution outlives the reap window: claimed 2h ago, still PROCESSING…
    UUID token = forceClaim(id, "PROCESSING", 1, 3, Instant.now().minusSeconds(7200));
    // …but the claimant is demonstrably alive (heartbeat 5s ago).
    stampAgentHeartbeat("test-worker", Instant.now().minusSeconds(5));

    TaskQueueDao.ReapResult r = stores.taskQueue().reapStale(3600, WORKER_LIVENESS_SECONDS);

    assertEquals(0, r.requeued(), "a live worker's in-flight task must NOT be requeued (#49)");
    assertEquals(0, r.failed(), "a live worker's in-flight task must NOT be failed (#49)");
    TaskQueueRow row = stores.taskQueue().findById(id).orElseThrow();
    assertEquals("PROCESSING", row.status);
    assertEquals(token, row.claimToken, "lease token must survive the sweep");
    // The still-valid lease completes exactly once — accepted, no stale rejection.
    assertEquals(1, stores.taskQueue().complete(id, token, "COMPLETED", "{}"));
  }

  @Test
  void reapStale_sparesExhaustedStaleClaimWhoseWorkerStillHeartbeats() {
    long id =
        stores.taskQueue().enqueue("EXECUTE_COMMAND", "default", 0, "{}", 3, 60, null, null, null);
    // attempts == max_attempts would be terminally FAILED on the old predicate — but the
    // worker is alive and still executing, so the reaper must not fail it under it.
    UUID token = forceClaim(id, "PROCESSING", 3, 3, Instant.now().minusSeconds(7200));
    stampAgentHeartbeat("test-worker", Instant.now().minusSeconds(5));

    TaskQueueDao.ReapResult r = stores.taskQueue().reapStale(3600, WORKER_LIVENESS_SECONDS);

    assertEquals(0, r.failed(), "a live worker's exhausted-attempts task must NOT be failed (#49)");
    assertEquals(0, r.requeued());
    assertEquals("PROCESSING", stores.taskQueue().findById(id).orElseThrow().status);
    assertEquals(1, stores.taskQueue().complete(id, token, "COMPLETED", "{}"));
  }

  @Test
  void reapStale_reapsStaleClaimWhoseWorkerHeartbeatLapsed() {
    long id =
        stores.taskQueue().enqueue("EXECUTE_COMMAND", "default", 0, "{}", 3, 60, null, null, null);
    UUID token = forceClaim(id, "PROCESSING", 1, 3, Instant.now().minusSeconds(7200));
    // The claimant exists but stopped heartbeating 10min ago → genuinely dead → reap.
    stampAgentHeartbeat("test-worker", Instant.now().minusSeconds(600));

    TaskQueueDao.ReapResult r = stores.taskQueue().reapStale(3600, WORKER_LIVENESS_SECONDS);

    assertEquals(1, r.requeued(), "a dead worker's task MUST still be reaped");
    TaskQueueRow row = stores.taskQueue().findById(id).orElseThrow();
    assertEquals("QUEUED", row.status);
    assertNull(row.claimToken);
    // The zombie's late completion is rejected — the lease died with the reap (doc-27 G3).
    assertEquals(0, stores.taskQueue().complete(id, token, "COMPLETED", "{}"));
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  /** Upsert a {@code titan.agents} row for {@code agentId} with the given heartbeat instant. */
  private void stampAgentHeartbeat(String agentId, Instant lastHeartbeat) {
    stores.agents().register(agentId, agentId, "", 1);
    stores.withTransaction(
        conn -> {
          try (PreparedStatement ps =
              conn.prepareStatement(
                  "UPDATE titan.agents SET last_heartbeat = ? WHERE agent_id = ?")) {
            ps.setTimestamp(1, Timestamp.from(lastHeartbeat));
            ps.setString(2, agentId);
            ps.executeUpdate();
            return null;
          } catch (java.sql.SQLException e) {
            throw new RuntimeException(e);
          }
        });
  }

  private UUID forceClaim(
      long id, String status, int attempts, int maxAttempts, Instant claimedAt) {
    UUID token = UUID.randomUUID();
    stores.withTransaction(
        conn -> {
          try (PreparedStatement ps =
              conn.prepareStatement(
                  "UPDATE titan.task_queue SET status = ?, attempts = ?, max_attempts = ?, "
                      + "claim_token = ?, claimed_by = ?, claimed_at = ? WHERE id = ?")) {
            ps.setString(1, status);
            ps.setInt(2, attempts);
            ps.setInt(3, maxAttempts);
            ps.setObject(4, token);
            ps.setString(5, "test-worker");
            ps.setTimestamp(6, Timestamp.from(claimedAt));
            ps.setLong(7, id);
            ps.executeUpdate();
            return null;
          } catch (java.sql.SQLException e) {
            throw new RuntimeException(e);
          }
        });
    return token;
  }
}
