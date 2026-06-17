package io.adaptiq.titan.trigger.engine;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Collection;

/**
 * A consumer's registration with the trigger engine (design/50, Tier 3 — the one extension point a
 * consumer must implement to gain scheduled triggers).
 *
 * <p>Titan registers exactly one ({@code DbTriggerSubsystem}); when ReleaseFlow's CD engine adopts
 * the module it registers its own — no engine change, no reimplementation (design/50 D2). {@code
 * TriggerEngine} discovers every {@code TriggerSubsystem} via Quarkus CDI {@code Instance<>} each
 * tick and evaluates them independently.
 */
public interface TriggerSubsystem {

  /** The owners to evaluate this tick — typically rebuilt fresh per call. */
  @NonNull
  Collection<TriggerOwner> owners();

  /** The persistence + locking backend for these owners. */
  @NonNull
  TriggerStore store();
}
