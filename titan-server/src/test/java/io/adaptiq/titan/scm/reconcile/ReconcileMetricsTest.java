package io.adaptiq.titan.scm.reconcile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.adaptiq.titan.audit.AuditAction;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Targeted coverage of the small surface pieces of issue #1118.
 *
 * <ul>
 *   <li>{@link ReconcileMetrics} survives concurrent writes and reports the totals.
 *   <li>{@link AuditAction#SCM_WEBHOOK_RECOVERED} is present (UI mirror is keyed on the name).
 *   <li>{@link ScmProvider#wire()} returns lowercase for the persisted form.
 * </ul>
 */
class ReconcileMetricsTest {

  @Test
  void recovered_counter_isConcurrencySafe() throws Exception {
    ReconcileMetrics m = new ReconcileMetrics();
    ExecutorService pool = Executors.newFixedThreadPool(8);
    for (int i = 0; i < 1_000; i++) {
      pool.submit(() -> m.incrementRecovered(ScmProvider.GITHUB));
    }
    pool.shutdown();
    assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
    assertEquals(1_000L, m.recovered(ScmProvider.GITHUB));
    assertEquals(0L, m.recovered(ScmProvider.GITLAB));
  }

  @Test
  void lagSeconds_lastWriteWins() {
    ReconcileMetrics m = new ReconcileMetrics();
    m.setLagSeconds(ScmProvider.GITHUB, "owner/r", 30);
    m.setLagSeconds(ScmProvider.GITHUB, "owner/r", 120);
    assertEquals(120L, m.lagSeconds(ScmProvider.GITHUB, "owner/r"));
    assertEquals(0L, m.lagSeconds(ScmProvider.GITHUB, "unknown"));
  }

  @Test
  void auditAction_scmWebhookRecovered_isDeclared() {
    // Sentinel test: the UI mirrors AuditAction string names. Removing or renaming this
    // constant silently breaks the audit-log Details cell. Adversarial — keep it.
    assertEquals(AuditAction.SCM_WEBHOOK_RECOVERED, AuditAction.valueOf("SCM_WEBHOOK_RECOVERED"));
  }

  @Test
  void scmProvider_wire_isLowercased() {
    // The dedupe row primary key uses the wire form. Uppercase would break round-trip with the
    // webhook hot-path which already writes lowercase.
    assertEquals("github", ScmProvider.GITHUB.wire());
    assertEquals("bitbucket", ScmProvider.BITBUCKET.wire());
    assertEquals("gitlab", ScmProvider.GITLAB.wire());
  }

  @Test
  void scmEvent_rejectsEmptyEventId() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ScmEvent(
                ScmProvider.GITHUB, "owner/r", "", "push", java.time.Instant.now(), new byte[0]));
  }
}
