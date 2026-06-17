package io.adaptiq.titan.flow.model;

/**
 * The discriminator of a structured {@code when:} block (GH #1093). A step's typed {@code when:}
 * condition is a <strong>discriminated union</strong> — exactly one of these kinds is present on
 * any given {@link WhenCondition}.
 *
 * <p>This enum is the single cross-module discriminator for the structured {@code when:} surface
 * (Titan manifesto — "no stringly-typed cross-module discriminators"): the parser ({@code
 * titan-pipeline-model}) sets it, the orchestrator's evaluator ({@code titan-server}) reads it.
 * Neither side compares string literals.
 */
public enum WhenKind {
  /** {@code when.branch: <glob>} — run only when the build's branch matches the glob. */
  BRANCH,
  /** {@code when.previous: success|failure|always} — run based on the prior step's outcome. */
  PREVIOUS,
  /** {@code when.files_changed: [<glob>...]} — run only when a changed file matches a glob. */
  FILES_CHANGED
}
