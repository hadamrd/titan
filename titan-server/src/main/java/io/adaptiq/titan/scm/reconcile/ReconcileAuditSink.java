package io.adaptiq.titan.scm.reconcile;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Audit emission seam — production wires this to {@code AuditService.recordAs("reconcile",
 * SCM_WEBHOOK_RECOVERED, …)} (issue #1118). Behind an interface so tests can assert on emit count +
 * payload without standing up a Quarkus SecurityIdentity.
 */
public interface ReconcileAuditSink {

  /**
   * Emit an {@code SCM_WEBHOOK_RECOVERED} audit row. {@code gapSeconds} is the wall-clock distance
   * between {@code event.occurredAt} and the reconcile pickup; operators sort the audit log by this
   * to spot persistent webhook flakiness.
   */
  void recordRecovered(@NonNull ScmEvent event, long gapSeconds);
}
