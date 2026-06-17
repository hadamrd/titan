package io.adaptiq.titan.flow;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Signals that an orchestrator reconciler lost a compare-and-set transition to a concurrent
 * reconciler — a <em>benign</em> outcome, not an internal error.
 *
 * <h2>Why this is a typed exception, not a generic {@link RuntimeException}</h2>
 *
 * <p>{@link io.adaptiq.titan.store.FlowNodeDao#compareAndSetStatus} returns {@code int} (0 / 1) and
 * is the primary signal — most call sites already inspect the return value and no-op on 0. This
 * type exists for the cases where the CAS loss must escape a deeper call stack and be recognised by
 * a higher layer without resorting to message-string sniffing.
 *
 * <p>{@link io.adaptiq.titan.queue.QueueProcessor#handleAdvance} treats this exception as a benign
 * race signal: log {@code INFO}, re-enqueue an {@code ADVANCE} with a 1-second delay, complete the
 * task safely. It MUST NOT fail-close the build — the orchestrator's contract (see {@link
 * TitanOrchestrator} class javadoc) is that CAS losing is by design.
 *
 * <p>Issue #911 (builds 14, 29, 30 on titan.test) — two concurrent ADVANCE workers race on the
 * first park-on-approval transition. The loser used to bubble a JDBI-named "FlowNodeDao
 * .compareAndSetStatus failed" exception which {@code QueueProcessor.handleAdvance} fail-closed the
 * build on with a cryptic "internal error" message. With this type (and the message-substring
 * fallback for the JDBI shape) the loser simply re-enqueues.
 */
public class CasLostException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public CasLostException(@NonNull String message) {
    super(message);
  }

  public CasLostException(@NonNull String message, @NonNull Throwable cause) {
    super(message, cause);
  }
}
