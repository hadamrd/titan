package io.adaptiq.titan.flow.orch;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.adaptiq.titan.flow.expr.StructuredWhenEvaluator;
import io.adaptiq.titan.flow.expr.WhenContext;
import io.adaptiq.titan.flow.expr.WhenFacts;
import io.adaptiq.titan.flow.model.PreviousOutcome;
import io.adaptiq.titan.flow.model.StepModel;
import io.adaptiq.titan.flow.model.WhenCondition;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.FlowNodeRow;
import java.util.Map;
import java.util.Set;

/**
 * The dispatch-time decision for a step's <strong>structured</strong> {@code when:} guard (GH
 * #1093), extracted from {@code TitanOrchestrator} (the orchestrator is at its size cap —
 * design/59).
 *
 * <p>Assembles the {@link WhenContext} a structured condition is evaluated against — the build's
 * branch and changed-file set (cached once from trigger metadata, since they are constant across a
 * build's ticks) plus the prior-step outcome derived from the live DAG node statuses — and
 * delegates the boolean verdict to {@link StructuredWhenEvaluator}.
 *
 * <p>One instance per build (mirrors the orchestrator's per-build lifetime); the {@link WhenFacts}
 * lookup is memoised on first use.
 */
public final class StructuredWhenGate {

  private final TitanStores daos;
  private final long buildId;
  @Nullable private WhenFacts cachedFacts;

  public StructuredWhenGate(@NonNull TitanStores daos, long buildId) {
    this.daos = daos;
    this.buildId = buildId;
  }

  /**
   * Whether {@code step}'s structured {@code when:} condition evaluates to "skip" right now. The
   * caller has already established {@code step.getWhenCondition() != null}.
   *
   * @param step the step whose structured guard is being evaluated (its condition must be non-null)
   * @param nodes the live {@code flow_nodes}-by-id map for this build (for the prior-step outcome)
   * @return {@code true} → the step should be materialised {@code SKIPPED}
   */
  public boolean isSkipped(@NonNull StepModel step, @NonNull Map<String, FlowNodeRow> nodes) {
    WhenCondition condition = step.getWhenCondition();
    WhenFacts facts = facts();
    WhenContext ctx =
        new WhenContext(facts.branch(), previousOutcomeOf(step, nodes), facts.changedFiles());
    return StructuredWhenEvaluator.isSkipped(condition, ctx);
  }

  /**
   * The build-global {@link WhenFacts} (branch + changed files), loaded once from the build's
   * trigger metadata and cached. A missing build row or absent metadata degrades to {@link
   * WhenFacts#empty()} — a branch / files_changed guard then evaluates to "skip", never crashes.
   */
  @NonNull
  private WhenFacts facts() {
    WhenFacts facts = cachedFacts;
    if (facts == null) {
      facts =
          daos.builds()
              .findById(buildId)
              .map(b -> WhenFacts.fromTriggerMeta(b.triggerMetaJson))
              .orElseGet(WhenFacts::empty);
      cachedFacts = facts;
    }
    return facts;
  }

  /**
   * The node statuses a {@code when.previous: failure} guard treats as a failure: a parent step
   * that actually <em>ran and did not succeed</em>. {@code FAILED} (hard error) and {@code
   * UNSTABLE} (ran-but-degraded, e.g. test failures that didn't abort) both qualify.
   *
   * <p>Deliberately excluded:
   *
   * <ul>
   *   <li>{@code SKIPPED} — a clean no-op; a skip is not a failure (GH #1093 test matrix).
   *   <li>{@code ABORTED} — the whole build is being torn down (user/system abort); downstream
   *       steps are themselves being abandoned, so treating an aborted parent as a "failure" that
   *       triggers a {@code previous: failure} step would be misleading. An abort is not a
   *       step-level failure.
   * </ul>
   */
  private static final Set<String> FAILURE_STATUSES = Set.of("FAILED", "UNSTABLE");

  /**
   * The observed outcome of a step's immediate predecessor(s) for a {@code when.previous:} guard:
   * {@link PreviousOutcome#FAILURE} if any parent node is in {@link #FAILURE_STATUSES} ({@code
   * FAILED} or {@code UNSTABLE}), else {@link PreviousOutcome#SUCCESS}.
   *
   * <p>Only a parent that <em>ran and did not succeed</em> counts as a failure. A {@code SKIPPED}
   * parent is deliberately <strong>not</strong> a failure — a skipped step is a clean no-op, so a
   * downstream {@code when.previous: failure} must not treat it as a failure (GH #1093 test
   * matrix). An {@code ABORTED} parent is likewise not a failure: an abort tears the whole build
   * down, so the guarded step is itself being abandoned rather than reacting to a peer's error.
   */
  @NonNull
  static PreviousOutcome previousOutcomeOf(
      @NonNull StepModel step, @NonNull Map<String, FlowNodeRow> nodes) {
    for (String parentId : step.getParentIds()) {
      FlowNodeRow parent = nodes.get(parentId);
      if (parent != null && parent.status != null && FAILURE_STATUSES.contains(parent.status)) {
        return PreviousOutcome.FAILURE;
      }
    }
    return PreviousOutcome.SUCCESS;
  }
}
