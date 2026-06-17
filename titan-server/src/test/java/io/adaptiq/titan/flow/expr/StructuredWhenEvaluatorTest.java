package io.adaptiq.titan.flow.expr;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.adaptiq.titan.flow.model.PreviousOutcome;
import io.adaptiq.titan.flow.model.WhenCondition;
import io.adaptiq.titan.flow.model.WhenKind;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link StructuredWhenEvaluator} (GH #1093) — one happy + one adversarial per
 * discriminator. {@code isSkipped(...) == true} means the step is skipped (the guard is false).
 */
class StructuredWhenEvaluatorTest {

  private static WhenContext ctx(String branch, PreviousOutcome prev, List<String> changed) {
    return new WhenContext(branch, prev, changed);
  }

  private static final WhenContext PLAIN =
      ctx("trunk", PreviousOutcome.SUCCESS, List.of("src/Main.java"));

  // ── branch ───────────────────────────────────────────────────────────────

  @Test
  void branchExactMatchRuns() {
    assertFalse(StructuredWhenEvaluator.isSkipped(WhenCondition.ofBranch("trunk"), PLAIN));
  }

  @Test
  void branchGlobMatchRuns() {
    WhenContext c = ctx("release/1.2", PreviousOutcome.SUCCESS, List.of());
    assertFalse(StructuredWhenEvaluator.isSkipped(WhenCondition.ofBranch("release/*"), c));
  }

  @Test
  void branchMismatchSkips() {
    // when.branch != trunk on a feature branch fires; on trunk it skips (the issue's scenario).
    assertTrue(
        StructuredWhenEvaluator.isSkipped(WhenCondition.ofBranch("feature/*"), PLAIN),
        "trunk does not match feature/* → skip");
  }

  @Test
  void branchStarDoesNotCrossSlash() {
    WhenContext c = ctx("release/1/2", PreviousOutcome.SUCCESS, List.of());
    assertTrue(
        StructuredWhenEvaluator.isSkipped(WhenCondition.ofBranch("release/*"), c),
        "single * must not cross a / — release/1/2 should not match release/*");
  }

  @Test
  void branchDoubleStarCrossesSlash() {
    WhenContext c = ctx("release/1/2", PreviousOutcome.SUCCESS, List.of());
    assertFalse(StructuredWhenEvaluator.isSkipped(WhenCondition.ofBranch("release/**"), c));
  }

  @Test
  void branchNullBranchSkips() {
    // Adversarial: a build with no SCM branch metadata — a branch guard cannot match.
    WhenContext c = ctx(null, PreviousOutcome.SUCCESS, List.of());
    assertTrue(StructuredWhenEvaluator.isSkipped(WhenCondition.ofBranch("main"), c));
  }

  // ── previous ───────────────────────────────────────────────────────────────

  @Test
  void previousSuccessRunsWhenPriorSucceeded() {
    assertFalse(
        StructuredWhenEvaluator.isSkipped(
            WhenCondition.ofPrevious(PreviousOutcome.SUCCESS), PLAIN));
  }

  @Test
  void previousSuccessSkipsWhenPriorFailed() {
    WhenContext c = ctx("trunk", PreviousOutcome.FAILURE, List.of());
    assertTrue(
        StructuredWhenEvaluator.isSkipped(WhenCondition.ofPrevious(PreviousOutcome.SUCCESS), c));
  }

  @Test
  void previousFailureRunsOnlyOnFailure() {
    WhenContext failed = ctx("trunk", PreviousOutcome.FAILURE, List.of());
    assertFalse(
        StructuredWhenEvaluator.isSkipped(
            WhenCondition.ofPrevious(PreviousOutcome.FAILURE), failed));
    // Adversarial: prior succeeded → a failure-guarded step must be skipped.
    assertTrue(
        StructuredWhenEvaluator.isSkipped(
            WhenCondition.ofPrevious(PreviousOutcome.FAILURE), PLAIN));
  }

  @Test
  void previousAlwaysNeverSkips() {
    WhenContext failed = ctx("trunk", PreviousOutcome.FAILURE, List.of());
    assertFalse(
        StructuredWhenEvaluator.isSkipped(WhenCondition.ofPrevious(PreviousOutcome.ALWAYS), PLAIN));
    assertFalse(
        StructuredWhenEvaluator.isSkipped(
            WhenCondition.ofPrevious(PreviousOutcome.ALWAYS), failed));
  }

  // ── files_changed ──────────────────────────────────────────────────────────

  @Test
  void filesChangedMatchRuns() {
    WhenContext c = ctx("trunk", PreviousOutcome.SUCCESS, List.of("docs/readme.md", "src/A.java"));
    assertFalse(
        StructuredWhenEvaluator.isSkipped(WhenCondition.ofFilesChanged(List.of("docs/**")), c));
  }

  @Test
  void filesChangedNoMatchSkips() {
    WhenContext c = ctx("trunk", PreviousOutcome.SUCCESS, List.of("src/A.java"));
    assertTrue(
        StructuredWhenEvaluator.isSkipped(WhenCondition.ofFilesChanged(List.of("docs/**")), c));
  }

  @Test
  void filesChangedEmptyChangesetSkips() {
    // Adversarial: empty changeset — a files_changed guard cannot match.
    WhenContext c = ctx("trunk", PreviousOutcome.SUCCESS, List.of());
    assertTrue(StructuredWhenEvaluator.isSkipped(WhenCondition.ofFilesChanged(List.of("**")), c));
  }

  // ── contract / adversarial ───────────────────────────────────────────────

  @Test
  void nullConditionNeverSkips() {
    assertFalse(StructuredWhenEvaluator.isSkipped(null, PLAIN));
  }

  @Test
  void conditionWithNoKindThrows() {
    WhenCondition broken = new WhenCondition(); // kind left null — a programming-error shape.
    assertThrows(
        IllegalStateException.class, () -> StructuredWhenEvaluator.isSkipped(broken, PLAIN));
  }

  @Test
  void branchKindWithoutGlobThrows() {
    WhenCondition broken = new WhenCondition();
    broken.setKind(WhenKind.BRANCH); // discriminator set but value missing
    assertThrows(
        IllegalStateException.class, () -> StructuredWhenEvaluator.isSkipped(broken, PLAIN));
  }

  @Test
  void questionMarkGlobMatchesSingleChar() {
    WhenContext c = ctx("v1", PreviousOutcome.SUCCESS, List.of());
    assertFalse(StructuredWhenEvaluator.isSkipped(WhenCondition.ofBranch("v?"), c));
    WhenContext c2 = ctx("v12", PreviousOutcome.SUCCESS, List.of());
    assertTrue(StructuredWhenEvaluator.isSkipped(WhenCondition.ofBranch("v?"), c2));
  }

  @Test
  void globTreatsRegexMetacharsAsLiterals() {
    // A dot in the branch glob must match a literal dot, not "any char".
    WhenContext dot = ctx("release.1", PreviousOutcome.SUCCESS, List.of());
    assertFalse(StructuredWhenEvaluator.isSkipped(WhenCondition.ofBranch("release.1"), dot));
    WhenContext other = ctx("releaseX1", PreviousOutcome.SUCCESS, List.of());
    assertTrue(
        StructuredWhenEvaluator.isSkipped(WhenCondition.ofBranch("release.1"), other),
        "the '.' must be literal, so releaseX1 must NOT match release.1");
  }
}
