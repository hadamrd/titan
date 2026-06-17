package io.adaptiq.titan.flow.parser;

import com.fasterxml.jackson.databind.JsonNode;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.adaptiq.titan.flow.model.RetryPolicy;
import io.adaptiq.titan.flow.model.StepModel;
import io.adaptiq.titan.flow.parser.grammar.GrammarKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The {@code retry:} grammar scope (design/44 §1, design/42 §4.7) — a declarative per-step retry
 * policy scoped to a step or a stage.
 *
 * <p>Two surface forms parse to one {@link RetryPolicy}:
 *
 * <ul>
 *   <li>a <strong>scalar shorthand</strong> {@code retry: <N>} ⇒ {@code maxAttempts: N} with the
 *       default backoff (design/44 §2);
 *   <li>the <strong>object form</strong> {@code retry: { maxAttempts:, backoff: { initial:,
 *       multiplier:, max: }, retryableExitCodes: [...] }}.
 * </ul>
 *
 * <p>A stage-level {@code retry:} is parser sugar: it is flattened onto every step in the stage at
 * parse time, but a step's own {@code retry:} wins — a step that declares {@code retry:} keeps its
 * own policy, a step that does not inherits the stage's. Same flatten rule as {@code credentials:}
 * / {@code sshAgent:}, only here the unit is a single policy, not a list.
 *
 * <p>Unlike {@code sshAgent:}, {@code retry:} is valid on <em>any</em> executable step node ({@code
 * sh}, {@code script}, {@code git}, …) — it applies via the step scope; gates and preconditions are
 * not steps so they never carry it (design/44 §6).
 */
final class RetryScope implements StepScope {

  static final String KEY = "retry";

  /** The keys allowed inside the {@code retry:} object form. */
  private static final Set<String> RETRY_KEYS =
      Set.of("maxAttempts", "backoff", "retryableExitCodes");

  /** The keys allowed inside the nested {@code backoff:} object. */
  private static final Set<String> BACKOFF_KEYS = Set.of("initial", "multiplier", "max");

  @Override
  public String key() {
    return KEY;
  }

  @Override
  public GrammarKey schema() {
    return GrammarKey.optional(
        KEY,
        "Default retry policy for every step of this stage (design/44). Flattened onto "
            + "each step at parse time; a step's own 'retry' wins.",
        GrammarKey.ref("retryPolicy"));
  }

  @Override
  public GrammarKey stepSchema() {
    return GrammarKey.optional(
        KEY,
        "Declarative per-step retry policy (design/44). Valid on any executable step. "
            + "Absent = no retry; the controller re-enqueues a failed step's task "
            + "with backoff up to maxAttempts.",
        GrammarKey.ref("retryPolicy"));
  }

  @Override
  public void parseStep(JsonNode stepNode, StepModel step, ParseContext ctx) {
    step.setRetry(parse(stepNode.get(KEY), ctx.location() + " retry"));
  }

  @Override
  public void parseStageAndFlatten(JsonNode stageNode, List<StepModel> steps, ParseContext ctx) {
    RetryPolicy stagePolicy = parse(stageNode.get(KEY), ctx.location() + " retry");
    if (stagePolicy == null) {
      return;
    }
    for (StepModel step : steps) {
      // A step's own retry: wins; a step without one inherits the stage's. The stage policy
      // is shared by reference — the baked model is read-only downstream, never mutated.
      if (step.getRetry() == null) {
        step.setRetry(stagePolicy);
      }
    }
  }

  @Override
  public void validate(StepModel step, ParseContext ctx) {
    RetryPolicy policy = step.getRetry();
    if (policy == null) {
      return;
    }
    String where = ctx.location() + " step " + step.getId() + " retry";
    validatePolicy(policy, where);
  }

  /**
   * Parse a {@code retry:} node — on a step or a stage — into a {@link RetryPolicy}, or {@code
   * null} when the key is absent (no retry, today's behaviour). Accepts the scalar shorthand and
   * the object form (design/44 §2). The returned policy is validated by {@link #validatePolicy}.
   */
  @Nullable
  static RetryPolicy parse(@Nullable JsonNode node, @NonNull String context) {
    if (node == null) {
      return null; // genuinely absent — no retry, today's behaviour.
    }
    // design/48 D2/D3: `retry` is declared the retryPolicy oneOf — an integer OR an object,
    // never a string, a null or an array. A present-and-null `retry: null` is now a located
    // error, not silently no-retry; the integer-shorthand-vs-object oneOf stays valid.
    if (node.isNull()) {
      throw TypedNodeReader.presentButNull(KEY, context);
    }
    RetryPolicy policy = new RetryPolicy();
    if (node.isIntegralNumber()) {
      // scalar shorthand — `retry: 3` ⇒ maxAttempts 3, default backoff.
      policy.setMaxAttempts(node.asInt());
    } else if (node.isObject()) {
      ParseSupport.rejectUnknownKeys(node, RETRY_KEYS, context);
      // `maxAttempts` is declared an integer — same type strictness (design/48 §2): a
      // present-and-null or non-integer value is a located error.
      JsonNode maxAttempts = node.get("maxAttempts");
      if (maxAttempts != null) {
        if (maxAttempts.isNull()) {
          throw TypedNodeReader.presentButNull("maxAttempts", context);
        }
        if (!maxAttempts.isIntegralNumber()) {
          throw new PipelineParseException(
              context
                  + ": 'maxAttempts' must be an "
                  + "integer, got "
                  + TypedNodeReader.describe(maxAttempts));
        }
        policy.setMaxAttempts(maxAttempts.asInt());
      }
      policy.setBackoff(parseBackoff(node.get("backoff"), context + ".backoff"));
      policy.setRetryableExitCodes(parseExitCodes(node.get("retryableExitCodes"), context));
    } else {
      // a string, an array, a fractional number — not either oneOf branch.
      throw new PipelineParseException(
          context
              + ": 'retry' must be an integer (the maxAttempts shorthand) or an object"
              + " { maxAttempts, backoff, retryableExitCodes }, got "
              + TypedNodeReader.describe(node));
    }
    validatePolicy(policy, context);
    return policy;
  }

  /** Parse the nested {@code backoff:} object; an absent node yields the default backoff. */
  @NonNull
  private static RetryPolicy.Backoff parseBackoff(
      @Nullable JsonNode node, @NonNull String context) {
    RetryPolicy.Backoff backoff = new RetryPolicy.Backoff();
    if (node == null) {
      return backoff; // genuinely absent — the default backoff.
    }
    // design/48 §2: the nested `backoff` object gets the same type strictness — present-and-
    // null and a non-object value are located errors.
    if (node.isNull()) {
      throw TypedNodeReader.presentButNull("backoff", context);
    }
    if (!node.isObject()) {
      throw new PipelineParseException(
          context
              + ": 'backoff' must be an object { initial, multiplier, max }, got "
              + TypedNodeReader.describe(node));
    }
    ParseSupport.rejectUnknownKeys(node, BACKOFF_KEYS, context);
    // `initial`/`max` are declared strings (a duration suffixed s/m/h) — a non-string or
    // present-and-null value fails before the duration parser runs.
    JsonNode initial = node.get("initial");
    if (initial != null) {
      backoff.setInitialMillis(
          ParseSupport.parseDurationMillis(
              requireDurationString(initial, "initial", context), context + ".initial"));
    }
    JsonNode multiplier = node.get("multiplier");
    if (multiplier != null) {
      if (multiplier.isNull()) {
        throw TypedNodeReader.presentButNull("multiplier", context);
      }
      if (!multiplier.isNumber()) {
        throw new PipelineParseException(
            context
                + ": 'multiplier' must be a number, got "
                + TypedNodeReader.describe(multiplier));
      }
      backoff.setMultiplier(multiplier.asDouble());
    }
    JsonNode max = node.get("max");
    if (max != null) {
      backoff.setMaxMillis(
          ParseSupport.parseDurationMillis(
              requireDurationString(max, "max", context), context + ".max"));
    }
    return backoff;
  }

  /**
   * Read a {@code backoff} duration field — declared a string — rejecting present-and-null and a
   * non-string value with a located error (design/48 §2), before {@code parseDurationMillis} checks
   * the {@code s/m/h} grammar.
   */
  @NonNull
  private static String requireDurationString(
      @NonNull JsonNode node, @NonNull String field, @NonNull String context) {
    if (node.isNull()) {
      throw TypedNodeReader.presentButNull(field, context);
    }
    if (!node.isTextual()) {
      throw new PipelineParseException(
          context
              + ": '"
              + field
              + "' must be a string "
              + "(a duration suffixed s/m/h), got "
              + TypedNodeReader.describe(node));
    }
    return node.textValue();
  }

  /** Parse the {@code retryableExitCodes:} list; an absent node yields an empty list (any exit). */
  @NonNull
  private static List<Integer> parseExitCodes(@Nullable JsonNode node, @NonNull String context) {
    List<Integer> out = new ArrayList<>();
    if (node == null) {
      return out; // genuinely absent — any exit is retryable.
    }
    // design/48 §2: `retryableExitCodes` is declared an array of integers — same strictness.
    if (node.isNull()) {
      throw TypedNodeReader.presentButNull("retryableExitCodes", context);
    }
    if (!node.isArray()) {
      throw new PipelineParseException(
          context
              + ": 'retryableExitCodes' must be an array "
              + "of integers, got "
              + TypedNodeReader.describe(node));
    }
    int index = 0;
    for (JsonNode item : node) {
      if (!item.isIntegralNumber()) {
        throw new PipelineParseException(
            context
                + ": retryableExitCodes["
                + index
                + "] must be an integer, got "
                + TypedNodeReader.describe(item));
      }
      out.add(item.asInt());
      index++;
    }
    return out;
  }

  /**
   * Validate a {@link RetryPolicy}: {@code maxAttempts >= 1}, {@code multiplier >= 1.0},
   * non-negative durations (already guaranteed by the duration parser), and {@code backoff.max >=
   * backoff.initial} (design/44 §2). A located {@link PipelineParseException} names the offending
   * field.
   */
  static void validatePolicy(@NonNull RetryPolicy policy, @NonNull String context) {
    if (policy.getMaxAttempts() < 1) {
      throw new PipelineParseException(
          context
              + ": 'maxAttempts' must be >= 1 (got "
              + policy.getMaxAttempts()
              + ") — 1 means no retry");
    }
    RetryPolicy.Backoff backoff = policy.getBackoff();
    if (backoff.getMultiplier() < 1.0) {
      throw new PipelineParseException(
          context
              + ": 'backoff.multiplier' must be >= 1.0 (got "
              + backoff.getMultiplier()
              + ") — a multiplier below 1 shrinks the delay");
    }
    if (backoff.getInitialMillis() < 0 || backoff.getMaxMillis() < 0) {
      throw new PipelineParseException(context + ": 'backoff' durations must not be negative");
    }
    if (backoff.getMaxMillis() < backoff.getInitialMillis()) {
      throw new PipelineParseException(
          context
              + ": 'backoff.max' must be >= 'backoff.initial'"
              + " — the cap cannot be below the first delay");
    }
  }
}
