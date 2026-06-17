package io.adaptiq.titan.trigger.engine;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.trigger.Trigger;
import io.adaptiq.titan.trigger.TriggerContext;
import io.adaptiq.titan.trigger.TriggerEvent;
import io.adaptiq.titan.trigger.TriggerOutcome;
import java.time.Instant;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The firing algorithm of the trigger engine (design/50, Tier 3; design/51; design/52).
 *
 * <p>Split out from {@link TriggerEngine} (the Quarkus {@code @Scheduled} shell) deliberately: this
 * class touches only the Tier-3 SPI and {@code java.time}, so it is exercised in tests against
 * in-memory fakes — no engine, no database, no clock games. {@code TriggerEngine} supplies the real
 * subsystems, the wall clock and the {@link TriggerEvaluator}; the logic lives here.
 *
 * <p>One evaluation pass — driven by the periodic poll ({@code event == null}) or by an event
 * delivery (design/51). Per {@code (owner, trigger)}, within one locked scope: evaluate the trigger
 * (through the supplied {@link TriggerEvaluator}, so a hung {@code evaluate()} cannot freeze the
 * pass — design/52 D3), run it past every {@link TriggerFilter}, then act on the verdict — {@code
 * FIRE} starts a build unless one is already in flight (coalescing, design/50 D5) and stamps the
 * occurrence; {@code SKIP}/{@code DEFER} persist nothing.
 */
public final class TriggerDispatch {

  private static final Logger LOGGER = Logger.getLogger(TriggerDispatch.class.getName());

  private TriggerDispatch() {}

  /** A periodic-poll evaluation pass — no event, unbounded (direct) evaluation. */
  public static void evaluate(
      @NonNull TriggerSubsystem subsystem,
      @NonNull List<TriggerFilter> filters,
      @NonNull Instant now) {
    evaluate(subsystem, filters, now, null, TriggerEvaluator.direct());
  }

  /** An event-delivery evaluation pass — unbounded (direct) evaluation. */
  public static void evaluate(
      @NonNull TriggerSubsystem subsystem,
      @NonNull List<TriggerFilter> filters,
      @NonNull Instant now,
      @CheckForNull TriggerEvent event) {
    evaluate(subsystem, filters, now, event, TriggerEvaluator.direct());
  }

  /**
   * Evaluate every owner of one subsystem, optionally with an event in scope (design/51) and
   * through a given {@link TriggerEvaluator} (design/52). A failure for one owner is logged and
   * isolated — the remaining owners are still evaluated.
   */
  public static void evaluate(
      @NonNull TriggerSubsystem subsystem,
      @NonNull List<TriggerFilter> filters,
      @NonNull Instant now,
      @CheckForNull TriggerEvent event,
      @NonNull TriggerEvaluator evaluator) {
    TriggerStore store = subsystem.store();
    for (TriggerOwner owner : subsystem.owners()) {
      if (!owner.schedulingEnabled() || owner.triggers().isEmpty()) {
        continue;
      }
      try {
        store.inLockedScope(
            owner,
            scope -> {
              for (Trigger trigger : owner.triggers()) {
                evaluateOne(owner, trigger, scope, filters, now, event, evaluator);
              }
            });
      } catch (RuntimeException e) {
        LOGGER.log(Level.WARNING, "[trigger] evaluating owner " + owner.ownerId() + " failed", e);
      }
    }
  }

  /** Evaluate a single trigger inside an already-locked scope. */
  static void evaluateOne(
      @NonNull TriggerOwner owner,
      @NonNull Trigger trigger,
      @NonNull TriggerScope scope,
      @NonNull List<TriggerFilter> filters,
      @NonNull Instant now,
      @CheckForNull TriggerEvent event,
      @NonNull TriggerEvaluator evaluator) {
    Instant lastFiredAt = scope.lastFiredAt(trigger.getId());
    if (event == null && lastFiredAt == null) {
      // First poll-tick sight of this trigger: stamp `now` as the catch-up baseline and
      // stop — a trigger never fires retroactively for slots before it existed. This is
      // poll-only (design/51 D2): on an event
      // delivery the event *is* the signal, so a never-fired event trigger fires at once.
      scope.recordFired(trigger.getId(), now);
      return;
    }
    TriggerOutcome outcome;
    try {
      outcome =
          evaluator.evaluate(
              trigger, new TriggerContext(now, lastFiredAt, owner.hashSeed(), event));
    } catch (RuntimeException e) {
      LOGGER.log(Level.WARNING, "[trigger] trigger " + trigger.getId() + " evaluation failed", e);
      scope.recordError(trigger.getId(), String.valueOf(e));
      return;
    }

    for (TriggerFilter filter : filters) {
      try {
        outcome = filter.filter(owner, trigger, outcome, now);
      } catch (RuntimeException e) {
        LOGGER.log(Level.WARNING, "[trigger] trigger filter failed — ignoring it", e);
      }
    }

    switch (outcome.kind()) {
      case FIRE -> {
        if (!scope.buildInFlight()) {
          scope.fire();
        }
        // Stamp the satisfied occurrence even when coalesced: the in-flight build absorbs
        // it. Stamp `now`, not the matched instant — a backlog collapses to one build.
        scope.recordFired(trigger.getId(), now);
      }
      case SKIP, DEFER -> {
        // Nothing persisted — the trigger is reconsidered on the next tick.
      }
    }
  }
}
