package io.adaptiq.titan.trigger.engine;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.trigger.Trigger;
import io.adaptiq.titan.trigger.TriggerContext;
import io.adaptiq.titan.trigger.TriggerOutcome;

/**
 * Strategy for invoking a {@link Trigger#evaluate}, so {@link TriggerDispatch} can be given a
 * <em>bounded</em> evaluation (design/52 D3) without itself depending on a thread pool or on {@code
 * SchedulerSettings} — which keeps the dispatch algorithm engine-free and unit-testable.
 *
 * <p>Production passes a {@link BoundedTriggerEvaluator} (a per-trigger timeout); tests and the
 * no-config path pass {@link #direct()} (a plain, unbounded call).
 */
@FunctionalInterface
public interface TriggerEvaluator {

  /** Evaluate {@code trigger} against {@code ctx}, however this strategy chooses to bound it. */
  @NonNull
  TriggerOutcome evaluate(@NonNull Trigger trigger, @NonNull TriggerContext ctx);

  /** The unbounded strategy — a direct call, no timeout. The dispatch default. */
  @NonNull
  static TriggerEvaluator direct() {
    return Trigger::evaluate;
  }
}
