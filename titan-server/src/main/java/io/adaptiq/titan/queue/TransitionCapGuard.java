package io.adaptiq.titan.queue;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.micrometer.core.instrument.Metrics;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Per-build state-transition spam guard (issue #1050 / V1-shippable-bar §3).
 *
 * <p>ORCHESTRATE / BAKE / SYNTHESIZE / ADVANCE are driven by a 500ms {@link QueueProcessor#tick()}
 * loop and have no per-build debounce — a wedged or pathological build can rack up thousands of
 * transitions, flooding the controller log and the events stream. This guard counts transitions
 * per-(build, kind), warns at a soft cap, and halts (fail-close) at a hard cap.
 *
 * <p>Thresholds default to <em>200</em> (soft) and <em>1000</em> (hard) and are overridable via the
 * {@code LOOP_TRANSITION_SOFT_CAP} and {@code LOOP_TRANSITION_HARD_CAP} environment variables.
 * Healthy pipelines in the @golden suite stay well below 200 transitions in any single kind.
 *
 * <p>A Prometheus counter {@code titan_engine_transitions_total{kind}} is incremented on every
 * recorded transition. The {@code build_id} dimension called out in the issue is INTENTIONALLY
 * dropped from the metric labels (CONSTITUTION §6: bounded-cardinality labels only); the build id
 * is preserved in the structured WARN/HALT log lines and the build's failure reason.
 *
 * <p>Structured log markers (grep-friendly):
 *
 * <ul>
 *   <li>{@code [titan-cap] transition_cap_warn build=<id> kind=<KIND> count=<n> softCap=<n>}
 *   <li>{@code [titan-cap] transition_cap_halt build=<id> kind=<KIND> count=<n> hardCap=<n>}
 * </ul>
 *
 * <p>This class is thread-safe — counters live in a {@link ConcurrentHashMap} of {@link AtomicLong}
 * cells. Per-build entries are evicted on terminal transitions via {@link #onBuildTerminal(long)}.
 */
public final class TransitionCapGuard {

  private static final Logger LOGGER = Logger.getLogger(TransitionCapGuard.class.getName());

  /** Default soft cap — log WARN. */
  public static final int DEFAULT_SOFT_CAP = 200;

  /** Default hard cap — fail-close the build. */
  public static final int DEFAULT_HARD_CAP = 1000;

  /** Outcome of a {@link #recordTransition(long, String)} call. */
  public enum Outcome {
    /** Within budget — proceed. */
    OK,
    /** Crossed the soft cap on THIS call — caller may log/observe; proceed. */
    SOFT_WARN,
    /** Crossed the hard cap on THIS call — caller MUST fail-close the build. */
    HARD_HALT
  }

  private final int softCap;
  private final int hardCap;

  /** Per-build, per-kind counter. Key: build id; value: kind -> count. */
  private final ConcurrentMap<Long, ConcurrentMap<String, AtomicLong>> counters =
      new ConcurrentHashMap<>();

  /**
   * Once a build trips the hard cap we stop emitting WARN/HALT (the build is already fail-closed by
   * the caller). This set is also evicted on {@link #onBuildTerminal(long)}.
   */
  private final java.util.Set<Long> halted = java.util.concurrent.ConcurrentHashMap.newKeySet();

  public TransitionCapGuard() {
    this(
        readCap("LOOP_TRANSITION_SOFT_CAP", DEFAULT_SOFT_CAP),
        readCap("LOOP_TRANSITION_HARD_CAP", DEFAULT_HARD_CAP));
  }

  /** Test seam — inject specific cap values. */
  TransitionCapGuard(int softCap, int hardCap) {
    if (softCap <= 0 || hardCap <= 0 || hardCap < softCap) {
      throw new IllegalArgumentException(
          "invalid caps: soft=" + softCap + " hard=" + hardCap + " (require 0 < soft <= hard)");
    }
    this.softCap = softCap;
    this.hardCap = hardCap;
  }

  /**
   * Record one transition of {@code kind} for {@code buildId}. Increments the Micrometer counter
   * and returns the {@link Outcome} the caller should react to.
   *
   * <p>Once a build has been halted, subsequent calls return {@link Outcome#HARD_HALT} idempotently
   * (the caller's fail-close path is itself idempotent) until {@link #onBuildTerminal(long)} is
   * invoked.
   */
  @NonNull
  public Outcome recordTransition(long buildId, @NonNull String kind) {
    Metrics.counter("titan.engine.transitions.total", "kind", kind).increment();

    long count =
        counters
            .computeIfAbsent(buildId, b -> new ConcurrentHashMap<>())
            .computeIfAbsent(kind, k -> new AtomicLong())
            .incrementAndGet();

    if (halted.contains(buildId)) {
      return Outcome.HARD_HALT;
    }

    if (count > hardCap) {
      if (halted.add(buildId)) {
        LOGGER.log(
            Level.WARNING,
            "[titan-cap] transition_cap_halt build={0} kind={1} count={2} hardCap={3}",
            new Object[] {buildId, kind, count, hardCap});
      }
      return Outcome.HARD_HALT;
    }

    if (count == softCap + 1L) {
      LOGGER.log(
          Level.WARNING,
          "[titan-cap] transition_cap_warn build={0} kind={1} count={2} softCap={3}",
          new Object[] {buildId, kind, count, softCap});
      return Outcome.SOFT_WARN;
    }

    return Outcome.OK;
  }

  /**
   * Evict all per-build counters and the halted bit. Called when a build reaches a terminal status
   * (SUCCESS, FAILED, CANCELLED, ABORTED). Re-running a build (a fresh build id) gets a fresh
   * budget by virtue of a fresh key.
   */
  public void onBuildTerminal(long buildId) {
    counters.remove(buildId);
    halted.remove(buildId);
  }

  /** Test/observability — current count for a (build, kind), or 0 if none. */
  public long countFor(long buildId, @NonNull String kind) {
    ConcurrentMap<String, AtomicLong> perKind = counters.get(buildId);
    if (perKind == null) {
      return 0L;
    }
    AtomicLong cell = perKind.get(kind);
    return cell == null ? 0L : cell.get();
  }

  /** Test/observability — whether this build has been halted. */
  public boolean isHalted(long buildId) {
    return halted.contains(buildId);
  }

  public int softCap() {
    return softCap;
  }

  public int hardCap() {
    return hardCap;
  }

  /** Build the customer-facing failure reason for a hard-cap halt. */
  @NonNull
  public String haltReason(@NonNull String kind, long count) {
    return "transition spam guard hit — likely tight loop, see logs (kind="
        + kind
        + ", count="
        + count
        + ", hardCap="
        + hardCap
        + ")";
  }

  private static int readCap(@NonNull String envKey, int defaultValue) {
    String raw = System.getenv(envKey);
    if (raw == null || raw.isBlank()) {
      return defaultValue;
    }
    try {
      int parsed = Integer.parseInt(raw.trim());
      return parsed > 0 ? parsed : defaultValue;
    } catch (NumberFormatException e) {
      LOGGER.log(
          Level.WARNING,
          "[titan-cap] could not parse {0}={1} — falling back to default {2}",
          new Object[] {envKey, raw, defaultValue});
      return defaultValue;
    }
  }
}
