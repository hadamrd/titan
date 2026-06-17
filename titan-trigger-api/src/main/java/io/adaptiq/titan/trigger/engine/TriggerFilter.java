package io.adaptiq.titan.trigger.engine;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.trigger.Trigger;
import io.adaptiq.titan.trigger.TriggerOutcome;
import java.time.Instant;

/**
 * A pluggable veto on a trigger that is about to fire (design/50, Tier 3).
 *
 * <p>Every registered filter is consulted, in iteration order, after a trigger evaluates and before
 * the engine acts. A filter may pass the outcome through unchanged, or downgrade a {@link
 * TriggerOutcome.Kind#FIRE} to a {@code SKIP} or {@code DEFER} — it must never <em>upgrade</em> an
 * outcome to {@code FIRE}.
 *
 * <p>Discovered by Quarkus CDI ({@code @Inject Instance<TriggerFilter>}). Titan ships none today;
 * ReleaseFlow's CD freeze windows / within-window gates become ordinary {@code TriggerFilter}
 * implementations when CD adopts the module.
 */
public interface TriggerFilter {

  /** Filter a proposed outcome. */
  @NonNull
  TriggerOutcome filter(
      @NonNull TriggerOwner owner,
      @NonNull Trigger trigger,
      @NonNull TriggerOutcome proposed,
      @NonNull Instant now);
}
