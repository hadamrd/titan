package io.adaptiq.titan.flow.expr;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.adaptiq.titan.flow.model.PreviousOutcome;
import java.util.List;

/**
 * The build-context facts a structured {@code when:} condition is evaluated against at
 * step-dispatch time (GH #1093). Immutable; assembled by {@code TitanOrchestrator} from the build's
 * trigger metadata and the DAG node statuses, then handed to {@link StructuredWhenEvaluator}.
 *
 * @param branch the branch this build is for ({@code null} when unknown — e.g. a manual build with
 *     no SCM trigger metadata); a {@code when.branch} guard against a {@code null} branch never
 *     matches, so the step is skipped.
 * @param previousOutcome the observed outcome of this step's immediate predecessor(s) in the DAG —
 *     {@link PreviousOutcome#SUCCESS} when no parent failed, {@link PreviousOutcome#FAILURE} when a
 *     parent failed. A {@code SKIPPED} parent is <strong>not</strong> a failure (GH #1093). Never
 *     {@link PreviousOutcome#ALWAYS} — that is a guard value, not an observed outcome.
 * @param changedFiles the set of file paths changed in this build (from SCM trigger metadata);
 *     empty when the trigger carried no changeset, in which case a {@code when.files_changed} guard
 *     never matches.
 */
public record WhenContext(
    @Nullable String branch,
    @NonNull PreviousOutcome previousOutcome,
    @NonNull List<String> changedFiles) {}
