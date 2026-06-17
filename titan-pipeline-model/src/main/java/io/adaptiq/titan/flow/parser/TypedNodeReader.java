package io.adaptiq.titan.flow.parser;

import com.fasterxml.jackson.databind.JsonNode;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.adaptiq.titan.flow.parser.grammar.GrammarKey;
import io.adaptiq.titan.flow.parser.grammar.GrammarType;
import java.util.ArrayList;
import java.util.List;

/**
 * The {@link io.adaptiq.titan.flow.parser.grammar.TitanGrammar}-driven typed reader (design/48 D2).
 *
 * <p>design/47 made the parser project grammar key <em>names</em> from the single grammar source;
 * design/48 completes it by making the parser project grammar key <em>types</em>. Before this
 * class, {@code TitanYamlParser} and the six {@link StepScope}s read grammar-key values through
 * Jackson's permissive accessors — {@code JsonNode.asText()}, {@code asBoolean()}, {@code
 * ParseSupport.optText}/{@code stringList} — which silently <em>coerce</em> a wrong-typed value and
 * silently treat an explicit {@code null} as key-absent. The adversarial {@code
 * GrammarSchemaDivergenceTest} documented this as a class of parser leniency: the schema rejects
 * {@code requiresApproval: "yes"}, {@code agent: null}, {@code dependsOn: 5}; the parser accepted
 * them all.
 *
 * <p>This reader closes that gap. Every accessor takes the parent {@link JsonNode}, the key, and
 * that key's {@link GrammarKey} (the single grammar declaration). It:
 *
 * <ul>
 *   <li>returns the value <em>only</em> if the present node matches the grammar-declared {@link
 *       GrammarType} ({@link GrammarKey#declaredType()});
 *   <li>throws a located, value-echoing {@link PipelineParseException} on a type mismatch —
 *       <em>"stage 'Deploy': 'requiresApproval' must be a boolean (true or false), got the string
 *       \"yes\""</em> (design/48 D4) — never a coercion;
 *   <li>throws on a present-and-null value — <em>"stage 'Build': 'agent' is present but null — omit
 *       the key, or give it a value"</em> (design/48 D3);
 *   <li>treats a genuinely-absent key (not in the mapping) as absent, unchanged.
 * </ul>
 *
 * <p>The reader never consults a hand-written type list — {@link GrammarKey#declaredType()} is the
 * one projection of a key's type. Adding a grammar key with a new type is one {@code GrammarKey}
 * declaration; this reader needs no change.
 *
 * <p>Pure parser machinery — generic-model only, no step-descriptor knowledge (design/42 §4.6).
 * design/48 D5 keeps this reader off step <em>descriptor</em> argument maps; it governs only
 * grammar keys.
 */
final class TypedNodeReader {

  private TypedNodeReader() {}

  /**
   * Validate that a present grammar-key {@code node} matches {@code key}'s declared type, or throw
   * a located {@link PipelineParseException}. A {@code null}/missing/{@link JsonNode#isNull()
   * null-valued} node is rejected as present-and-null (design/48 D3) — callers that accept an
   * absent key must guard for absence before calling this. The validated node is returned for
   * fluent use.
   *
   * <p>This is the entry point for scopes that already hold the value node (the scope methods take
   * {@code stageNode.get(KEY)} directly). {@link #optionalNode} layers absence-tolerance on top of
   * it.
   */
  @NonNull
  static JsonNode requireTyped(
      @NonNull JsonNode node,
      @NonNull String keyName,
      @NonNull GrammarType type,
      @NonNull String context) {
    if (node.isNull()) {
      throw presentButNull(keyName, context);
    }
    if (!type.matches(node)) {
      throw typeMismatch(keyName, type, node, context);
    }
    return node;
  }

  /**
   * Fetch a grammar key off {@code parent} and type-check it. Returns {@code null} when the key is
   * genuinely absent (not in the mapping); throws on present-and-null (design/48 D3) and on a type
   * mismatch (design/48 D4). The returned node is guaranteed present, non-null and of the declared
   * type.
   */
  @Nullable
  static JsonNode optionalNode(
      @NonNull JsonNode parent, @NonNull GrammarKey key, @NonNull String context) {
    JsonNode v = parent.get(key.name());
    if (v == null) {
      return null; // genuinely absent — the key is not in the mapping.
    }
    return requireTyped(v, key.name(), key.declaredType(), context);
  }

  /**
   * Read an optional grammar key declared {@link GrammarType#STRING string}. Returns {@code null}
   * when absent; throws on present-and-null or a non-string value. Replaces the coercing {@code
   * ParseSupport.optText} for grammar string keys.
   */
  @Nullable
  static String optString(
      @NonNull JsonNode parent, @NonNull GrammarKey key, @NonNull String context) {
    JsonNode v = optionalNode(parent, key, context);
    return v == null ? null : v.textValue();
  }

  /**
   * Read a required grammar key declared {@link GrammarType#STRING string}. Throws when absent,
   * present-and-null, a non-string value, or a blank string. Replaces {@code ParseSupport
   * .requireText} for grammar string keys — same blank-rejection, but a wrong-typed value now fails
   * with a type error rather than being coerced.
   */
  @NonNull
  static String requireString(
      @NonNull JsonNode parent, @NonNull GrammarKey key, @NonNull String context) {
    JsonNode v = parent.get(key.name());
    if (v == null) {
      throw new PipelineParseException(context + ": missing required key '" + key.name() + "'");
    }
    requireTyped(v, key.name(), key.declaredType(), context);
    String text = v.textValue();
    if (text.isBlank()) {
      throw new PipelineParseException(
          context + ": required key '" + key.name() + "' must not be empty");
    }
    return text;
  }

  /**
   * Read an optional grammar key declared {@link GrammarType#BOOLEAN boolean}. Returns {@code null}
   * when absent; throws on present-and-null or a non-boolean value. Replaces the coercing {@code
   * JsonNode.asBoolean()} for grammar boolean keys — {@code requiresApproval: "yes"} now fails with
   * a type error rather than being coerced to {@code false}.
   */
  @Nullable
  static Boolean optBoolean(
      @NonNull JsonNode parent, @NonNull GrammarKey key, @NonNull String context) {
    JsonNode v = optionalNode(parent, key, context);
    return v == null ? null : v.booleanValue();
  }

  /**
   * Read an optional grammar key declared {@link GrammarType#STRING_OR_LIST string-or-list} into a
   * {@code List<String>} — a bare string folds to a one-element list, an array yields its elements.
   * Returns an empty list when absent; throws on present-and-null, a non-string/list value, or a
   * non-string array element. Replaces the coercing {@code ParseSupport.stringList} for grammar
   * string-or-list keys — {@code dependsOn: 5} now fails with a type error.
   */
  @NonNull
  static List<String> stringList(
      @NonNull JsonNode parent, @NonNull GrammarKey key, @NonNull String context) {
    List<String> out = new ArrayList<>();
    JsonNode v = optionalNode(parent, key, context);
    if (v == null) {
      return out;
    }
    if (v.isArray()) {
      int index = 0;
      for (JsonNode item : v) {
        if (!item.isTextual()) {
          throw new PipelineParseException(
              context
                  + ": '"
                  + key.name()
                  + "' element "
                  + index
                  + " must be a "
                  + "string, got "
                  + describe(item));
        }
        out.add(item.textValue());
        index++;
      }
    } else {
      out.add(v.textValue()); // a bare string is a one-element list.
    }
    return out;
  }

  // ── error helpers — located, value-echoing (design/48 D3/D4) ──

  /** A present-and-null error (design/48 D3). */
  @NonNull
  static PipelineParseException presentButNull(@NonNull String keyName, @NonNull String context) {
    return new PipelineParseException(
        context
            + ": '"
            + keyName
            + "' is present but null — omit the key, or give it a "
            + "value");
  }

  /** A type-mismatch error that echoes the offending value and names the expected type (D4). */
  @NonNull
  static PipelineParseException typeMismatch(
      @NonNull String keyName,
      @NonNull GrammarType expected,
      @NonNull JsonNode actual,
      @NonNull String context) {
    return new PipelineParseException(
        context
            + ": '"
            + keyName
            + "' must be "
            + expected.humanName()
            + ", got "
            + describe(actual));
  }

  /**
   * A short human description of an offending node — its JSON kind and, for a scalar, its value, so
   * the error echoes what the author actually wrote (design/48 D4).
   */
  @NonNull
  static String describe(@NonNull JsonNode node) {
    if (node.isTextual()) {
      return "the string \"" + node.textValue() + "\"";
    }
    if (node.isBoolean()) {
      return "the boolean " + node.booleanValue();
    }
    if (node.isNumber()) {
      return "the number " + node.numberValue();
    }
    if (node.isArray()) {
      return "an array";
    }
    if (node.isObject()) {
      return "an object";
    }
    if (node.isNull()) {
      return "null";
    }
    return "a " + node.getNodeType().name().toLowerCase(java.util.Locale.ROOT) + " value";
  }
}
