package io.adaptiq.titan.build;

import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Map;

/**
 * Options for {@link BuildService#replay(long, String, ReplayOptions)} — issue #307.
 *
 * <p>{@code paramOverrides} is merged into the parent build's effective parameters (parent first,
 * overrides win on key collision). {@code null} or empty means "replay with the parent's parameters
 * verbatim" — the common case.
 */
public record ReplayOptions(@Nullable Map<String, String> paramOverrides) {

  /** No overrides — replay with the parent's parameters unchanged. */
  public static ReplayOptions none() {
    return new ReplayOptions(null);
  }
}
