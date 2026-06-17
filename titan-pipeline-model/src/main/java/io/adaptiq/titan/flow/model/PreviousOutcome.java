package io.adaptiq.titan.flow.model;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * The accepted values of a {@code when.previous:} guard (GH #1093) — the outcome of the prior
 * step(s) a step's run is conditioned on. Mirrors the GitHub Actions {@code success()} / {@code
 * failure()} / {@code always()} job-condition vocabulary.
 *
 * <p>A shared-module enum, not a string literal, so the parser and the orchestrator's evaluator
 * agree on the discriminator (Titan manifesto — "no stringly-typed cross-module discriminators").
 */
public enum PreviousOutcome {
  /** Run only if the prior step(s) all succeeded (or were skipped). The default-ish, GHA-style. */
  SUCCESS("success"),
  /** Run only if a prior step failed — the GHA {@code if: failure()} case. */
  FAILURE("failure"),
  /** Always run, regardless of the prior outcome — the GHA {@code if: always()} case. */
  ALWAYS("always");

  private final String yaml;

  PreviousOutcome(@NonNull String yaml) {
    this.yaml = yaml;
  }

  /** The lower-case YAML literal a pipeline author writes (e.g. {@code "success"}). */
  @NonNull
  public String yaml() {
    return yaml;
  }

  /**
   * Parse a YAML literal into a {@link PreviousOutcome}, case-insensitively.
   *
   * @param value the YAML literal ({@code "success"} / {@code "failure"} / {@code "always"})
   * @return the matching constant, or {@code null} if {@code value} is not a legal literal
   */
  @Nullable
  public static PreviousOutcome fromYaml(@Nullable String value) {
    if (value == null) {
      return null;
    }
    for (PreviousOutcome o : values()) {
      if (o.yaml.equalsIgnoreCase(value)) {
        return o;
      }
    }
    return null;
  }
}
