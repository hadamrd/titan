package io.adaptiq.titan.flow.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.adaptiq.titan.flow.parser.PipelineParseException;

/**
 * Build-level failure policy (design/29 §3, issue #392): what the engine does when any node in the
 * DAG terminates as {@code FAILED}.
 *
 * <p>Promoted from a free-form {@code String} on {@link PipelineModel} to a typed enum so unknown
 * values are rejected at parse time instead of silently disabling the failure sweep at runtime (the
 * bug surfaced during PR #390). The YAML wire form is the camelCase literal — {@link
 * #BLOCK_ON_FAILURE} ⇔ {@code blockOnFailure}, {@link #CONTINUE_ON_FAILURE} ⇔ {@code
 * continueOnFailure} — kept stable across the parse/JSON-serialization boundary by {@link
 * #yamlValue()} / {@link #fromYaml(String, String)}.
 */
public enum FailurePolicy {
  /**
   * The default: a {@code FAILED} node skips its <strong>own descendant subgraph</strong> — the
   * transitive {@code dependsOn} / {@code parentIds} closure rooted at the failed node. Independent
   * sibling chains continue to run (issue #945; matches GitHub Actions / GitLab CI / Buildkite).
   * The build still finishes as {@code FAILED} if any node failed.
   *
   * <p>Pre-#945 this policy blanket-skipped every non-terminal node on the first failure — a
   * DAG-contract bug that tainted independent siblings sharing only a common ancestor.
   */
  BLOCK_ON_FAILURE("blockOnFailure"),

  /**
   * Let the DAG continue past a {@code FAILED} node — its descendants run anyway (no skip). The
   * build still finishes as {@code FAILED} if any node failed. Use for "report all failures in one
   * run" pipelines (e.g. lint + test + build all reported in one shot).
   */
  CONTINUE_ON_FAILURE("continueOnFailure");

  private final String yamlValue;

  FailurePolicy(@NonNull String yamlValue) {
    this.yamlValue = yamlValue;
  }

  /** The camelCase YAML literal — the wire form on the {@code failurePolicy:} key. */
  @JsonValue
  @NonNull
  public String yamlValue() {
    return yamlValue;
  }

  /**
   * Parse a YAML literal into the enum, throwing a located {@link PipelineParseException} for
   * anything that is not {@code blockOnFailure} or {@code continueOnFailure}.
   *
   * @param raw the camelCase literal read from YAML; never {@code null} here — the caller's typed
   *     reader already gates absent/null.
   * @param context human-readable parse context (e.g. {@code "pipeline failurePolicy"}) so the
   *     error message points at the offending key.
   */
  @NonNull
  public static FailurePolicy fromYaml(@NonNull String raw, @NonNull String context) {
    for (FailurePolicy p : values()) {
      if (p.yamlValue.equals(raw)) {
        return p;
      }
    }
    throw new PipelineParseException(
        context
            + ": unknown failurePolicy '"
            + raw
            + "' — accepted values are 'blockOnFailure', 'continueOnFailure'");
  }

  /**
   * Jackson-side deserialization (for round-tripping a persisted {@link PipelineModel}). Bare
   * camelCase YAML literal only — uses {@link #fromYaml(String, String)} with a synthetic context
   * so a corrupted persisted value still produces a located error.
   */
  @JsonCreator
  @NonNull
  static FailurePolicy fromJson(@Nullable String raw) {
    if (raw == null) {
      return BLOCK_ON_FAILURE;
    }
    return fromYaml(raw, "PipelineModel.failurePolicy");
  }
}
