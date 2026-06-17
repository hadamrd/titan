package io.adaptiq.titan.trigger;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;

/**
 * The verdict of evaluating a {@link Trigger}, or of a {@link
 * io.adaptiq.titan.trigger.engine.TriggerFilter} filtering one (design/50, Tier 2).
 *
 * <p>Three kinds:
 *
 * <ul>
 *   <li>{@link Kind#FIRE} — the owner should act now (subject to coalescing).
 *   <li>{@link Kind#SKIP} — nothing to do; the {@code reason} is for diagnostics.
 *   <li>{@link Kind#DEFER} — not now, re-evaluate no earlier than {@code deferUntil}. This arm is
 *       what lifts the model beyond cron: an event- or gate-driven trigger (a CD freeze window, a
 *       within-window gate) defers rather than skips, and the engine leaves its state untouched so
 *       the next tick reconsiders it.
 * </ul>
 *
 * <p>Cron triggers only ever return {@code FIRE} or {@code SKIP}; {@code DEFER} exists so the same
 * engine can serve CD's gated triggers unchanged (design/50 D4).
 */
public final class TriggerOutcome {

  /** The three verdict kinds. */
  public enum Kind {
    FIRE,
    SKIP,
    DEFER
  }

  private static final TriggerOutcome FIRE = new TriggerOutcome(Kind.FIRE, null, null);

  private final Kind kind;

  @CheckForNull private final String reason;

  @CheckForNull private final Instant deferUntil;

  private TriggerOutcome(
      @NonNull Kind kind, @CheckForNull String reason, @CheckForNull Instant deferUntil) {
    this.kind = kind;
    this.reason = reason;
    this.deferUntil = deferUntil;
  }

  /** The owner should act now. */
  @NonNull
  public static TriggerOutcome fire() {
    return FIRE;
  }

  /** Nothing to do; {@code reason} is recorded for diagnostics only. */
  @NonNull
  public static TriggerOutcome skip(@NonNull String reason) {
    return new TriggerOutcome(Kind.SKIP, reason, null);
  }

  /** Not now — reconsider no earlier than {@code until}. */
  @NonNull
  public static TriggerOutcome defer(@NonNull Instant until) {
    return new TriggerOutcome(Kind.DEFER, null, until);
  }

  @NonNull
  public Kind kind() {
    return kind;
  }

  @CheckForNull
  public String reason() {
    return reason;
  }

  @CheckForNull
  public Instant deferUntil() {
    return deferUntil;
  }

  @Override
  public String toString() {
    return switch (kind) {
      case FIRE -> "FIRE";
      case SKIP -> "SKIP(" + reason + ")";
      case DEFER -> "DEFER(until=" + deferUntil + ")";
    };
  }
}
