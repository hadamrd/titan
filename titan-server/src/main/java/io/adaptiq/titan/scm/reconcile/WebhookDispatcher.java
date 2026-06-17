package io.adaptiq.titan.scm.reconcile;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Provider-agnostic seam that hands a recovered {@link ScmEvent} back to the SAME dispatch path
 * used by the live webhook hot-path (issue #1118).
 *
 * <p>The acceptance criterion is strict: "feeds new events through the SAME dispatch path as {@code
 * WebhooksApi} (no parallel handler)". So this is an interface, and the production wiring holds a
 * reference to the existing per-provider webhook handler (e.g. {@code GithubWebhookApi} exposes a
 * package-private {@code dispatchVerified(ScmEvent)} method called by both the JAX-RS receiver and
 * the reconcile loop).
 *
 * <p>For unit tests {@code FakeWebhookDispatcher} records the calls so we can assert exactly-once.
 */
public interface WebhookDispatcher {

  /** Dispatch outcome — narrow enum so the scheduler does not branch on raw counts. */
  enum Outcome {
    /** A build was enqueued for at least one matching job. */
    DISPATCHED,
    /** Event valid but no job matched — recorded for dedupe, no audit / metric bump. */
    NO_MATCH,
    /** Provider rejected the event shape (e.g. unsupported eventType). */
    SKIPPED,
    /** Transient failure on the dispatch side — retry on the next tick. */
    FAILED
  }

  /**
   * Hand a recovered event back to the dispatch tail. Implementations MUST be idempotent in
   * conjunction with {@link EventDedupeStore}: the scheduler calls {@code markSeen} BEFORE {@code
   * dispatch}, so this method can assume the event has not been dispatched before.
   */
  @NonNull
  Outcome dispatch(@NonNull ScmEvent event);
}
