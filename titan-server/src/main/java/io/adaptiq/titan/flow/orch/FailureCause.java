package io.adaptiq.titan.flow.orch;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Closed set of diagnosed build-failure root causes (issue #1105). The {@link
 * BuildFailureClassifier} maps a failing step's log against externally-configured signatures to one
 * of these constants and persists the {@link #wire()} form on {@code titan.builds.failure_cause}.
 *
 * <p>This is the single source of truth for the cross-boundary discriminator (manifesto: "no
 * stringly-typed cross-module discriminators"). The DB column, the {@code failure-signatures.yaml}
 * {@code cause:} keys, and the UI badge all speak the lowercase {@link #wire()} name — never a bare
 * string literal compared ad-hoc.
 */
public enum FailureCause {
  TEST_FAILURE("test_failure"),
  COMPILE_ERROR("compile_error"),
  OOM("oom"),
  TIMEOUT("timeout"),
  NETWORK("network"),
  RATE_LIMIT("rate_limit"),
  /** No signature matched — the operator still has to read the log. */
  UNKNOWN("unknown");

  private final String wire;

  FailureCause(String wire) {
    this.wire = wire;
  }

  /** Lowercase persisted form — the value written to {@code failure_cause} and sent to the UI. */
  @NonNull
  public String wire() {
    return wire;
  }

  /**
   * Resolve a wire name (e.g. a {@code cause:} key from {@code failure-signatures.yaml}) to its
   * constant. Unknown / null inputs are a configuration error and throw — a typo in the signatures
   * file must fail loud at load time, not silently classify everything as UNKNOWN.
   *
   * @throws IllegalArgumentException if {@code wire} matches no constant.
   */
  @NonNull
  public static FailureCause fromWire(@Nullable String wire) {
    if (wire != null) {
      String trimmed = wire.trim();
      for (FailureCause c : values()) {
        if (c.wire.equals(trimmed)) {
          return c;
        }
      }
    }
    throw new IllegalArgumentException("Unknown FailureCause wire name: " + wire);
  }
}
