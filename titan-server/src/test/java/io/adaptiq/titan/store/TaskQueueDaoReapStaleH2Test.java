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
 */
class TaskQueueDaoReapStaleH2Test {

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

    TaskQueueDao.ReapResult r = stores.taskQueue().reapStale(3600);

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

    TaskQueueDao.ReapResult r = stores.taskQueue().reapStale(3600);

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

    TaskQueueDao.ReapResult r = stores.taskQueue().reapStale(3600);

    assertEquals(0, r.failed());
    assertEquals(0, r.requeued());
    assertEquals("CLAIMED", stores.taskQueue().findById(id).orElseThrow().status);
  }

  // ── helper ────────────────────────────────────────────────────────────────

  private void forceClaim(
      long id, String status, int attempts, int maxAttempts, Instant claimedAt) {
    stores.withTransaction(
        conn -> {
          try (PreparedStatement ps =
              conn.prepareStatement(
                  "UPDATE titan.task_queue SET status = ?, attempts = ?, max_attempts = ?, "
                      + "claim_token = ?, claimed_by = ?, claimed_at = ? WHERE id = ?")) {
            ps.setString(1, status);
            ps.setInt(2, attempts);
            ps.setInt(3, maxAttempts);
            ps.setObject(4, UUID.randomUUID());
            ps.setString(5, "test-worker");
            ps.setTimestamp(6, Timestamp.from(claimedAt));
            ps.setLong(7, id);
            ps.executeUpdate();
            return null;
          } catch (java.sql.SQLException e) {
            throw new RuntimeException(e);
          }
        });
  }
}
