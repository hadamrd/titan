package io.adaptiq.titan.flow.expr;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.flow.model.PreviousOutcome;
import io.adaptiq.titan.flow.model.WhenCondition;
import io.adaptiq.titan.flow.model.WhenKind;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Evaluates a <strong>structured</strong> {@code when:} condition (GH #1093) against a {@link
 * WhenContext} at step-dispatch time. The typed counterpart of {@link WhenEvaluator}, which handles
 * the legacy free-form CEL string {@code when:}.
 *
 * <p>The contract mirrors {@link WhenEvaluator#isSkipped}: {@link #isSkipped} returns {@code true}
 * when the step should be <em>skipped</em> (the condition is false). The orchestrator materialises
 * a skipped step {@code SKIPPED} with no worker task.
 *
 * <p>Semantics per discriminator:
 *
 * <ul>
 *   <li>{@code branch: <glob>} — skip unless the build's branch matches the glob. A {@code null}
 *       branch never matches.
 *   <li>{@code previous: success|failure|always} — skip unless the prior outcome satisfies the
 *       guard. {@code always} never skips.
 *   <li>{@code files_changed: [<glob>...]} — skip unless at least one changed file matches one of
 *       the globs. An empty changeset never matches.
 * </ul>
 *
 * <p>Glob grammar (both branch and file globs): {@code *} matches any run of characters except
 * {@code /}; {@code **} matches any run including {@code /}; {@code ?} matches a single non-{@code
 * /} character; everything else is literal. This is the usual GitHub-Actions / GitLab path-filter
 * grammar.
 */
public final class StructuredWhenEvaluator {

  private StructuredWhenEvaluator() {}

  /**
   * Returns {@code true} when a step carrying {@code condition} should be skipped (the condition
   * evaluates false) in {@code ctx}.
   *
   * @param condition the structured guard ({@code null} = no guard, never skipped)
   * @param ctx the build-context facts to evaluate against
   * @throws IllegalStateException if the condition's discriminator is unset or its discriminant
   *     value is missing — a parse-time invariant the parser guarantees, so reaching this is a bug.
   */
  public static boolean isSkipped(WhenCondition condition, @NonNull WhenContext ctx) {
    if (condition == null) {
      return false;
    }
    WhenKind kind = condition.getKind();
    if (kind == null) {
      throw new IllegalStateException("structured when: condition has no discriminator kind");
    }
    switch (kind) {
      case BRANCH:
        return !branchMatches(condition, ctx);
      case PREVIOUS:
        return !previousMatches(condition, ctx);
      case FILES_CHANGED:
        return !filesChangedMatches(condition, ctx);
      default:
        throw new IllegalStateException("unhandled when discriminator kind: " + kind);
    }
  }

  private static boolean branchMatches(@NonNull WhenCondition c, @NonNull WhenContext ctx) {
    String glob = c.getBranch();
    if (glob == null) {
      throw new IllegalStateException("when.branch condition has no branch glob");
    }
    String branch = ctx.branch();
    if (branch == null || branch.isBlank()) {
      return false; // unknown branch — a branch guard cannot match.
    }
    return globToPattern(glob).matcher(branch).matches();
  }

  private static boolean previousMatches(@NonNull WhenCondition c, @NonNull WhenContext ctx) {
    PreviousOutcome guard = c.getPrevious();
    if (guard == null) {
      throw new IllegalStateException("when.previous condition has no outcome");
    }
    switch (guard) {
      case ALWAYS:
        return true;
      case SUCCESS:
        return ctx.previousOutcome() == PreviousOutcome.SUCCESS;
      case FAILURE:
        return ctx.previousOutcome() == PreviousOutcome.FAILURE;
      default:
        throw new IllegalStateException("unhandled previous outcome: " + guard);
    }
  }

  private static boolean filesChangedMatches(@NonNull WhenCondition c, @NonNull WhenContext ctx) {
    List<String> globs = c.getFilesChanged();
    if (globs == null || globs.isEmpty()) {
      throw new IllegalStateException("when.files_changed condition has no globs");
    }
    List<String> changed = ctx.changedFiles();
    if (changed.isEmpty()) {
      return false; // no changeset — a files_changed guard cannot match.
    }
    for (String glob : globs) {
      Pattern p = globToPattern(glob);
      for (String file : changed) {
        if (p.matcher(file).matches()) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * Compile a glob into an anchored regex. {@code **} → {@code .*}; {@code *} → {@code [^/]*};
   * {@code ?} → {@code [^/]}; every other character is matched literally (regex-quoted).
   */
  @NonNull
  static Pattern globToPattern(@NonNull String glob) {
    StringBuilder re = new StringBuilder(glob.length() + 8);
    int i = 0;
    int n = glob.length();
    while (i < n) {
      char ch = glob.charAt(i);
      if (ch == '*') {
        if (i + 1 < n && glob.charAt(i + 1) == '*') {
          re.append(".*");
          i += 2;
        } else {
          re.append("[^/]*");
          i += 1;
        }
      } else if (ch == '?') {
        re.append("[^/]");
        i += 1;
      } else {
        re.append(Pattern.quote(String.valueOf(ch)));
        i += 1;
      }
    }
    return Pattern.compile(re.toString());
  }
}
