package io.adaptiq.titan.flow.parser.grammar;

import com.fasterxml.jackson.databind.JsonNode;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * The grammar-declared JSON value type of a {@link GrammarKey} (design/48 D2).
 *
 * <p>design/47 made the parser project key <em>names</em> from {@link TitanGrammar}; design/48
 * completes it by making the parser project key <em>types</em> from the same source. A {@code
 * GrammarKey} already carries a draft-2020-12 {@code valueSchema} fragment; {@link
 * GrammarKey#declaredType()} classifies that fragment into one of these cases, and the parser's
 * typed reader ({@code TypedNodeReader}) consults that classification instead of calling Jackson's
 * permissive {@code asText()}/{@code asBoolean()} coercions.
 *
 * <p>The classification is computed <em>from the schema fragment</em>, never hand-duplicated — so
 * there is no second place a key's type can be declared, and no drift surface. The {@code $ref}
 * shapes the grammar uses ({@code stringOrList}, {@code retryPolicy}) are resolved against the
 * {@code $def} bodies declared in {@link TitanGrammar}; an unrecognised fragment is a programming
 * error in the grammar, not a parse-time condition.
 */
public enum GrammarType {

  /** A JSON string — schema fragment {@code {"type":"string"}}. */
  STRING("a string"),

  /** A JSON boolean — schema fragment {@code {"type":"boolean"}}. */
  BOOLEAN("a boolean (true or false)"),

  /** A JSON array — schema fragment {@code {"type":"array", ...}}. */
  ARRAY("an array"),

  /** A JSON object — schema fragment {@code {"type":"object", ...}}. */
  OBJECT("an object"),

  /**
   * A single string or a list of strings — the {@code stringOrList} {@code $def}'s {@code oneOf}.
   * Used by {@code libraries}, {@code approvers}, {@code dependsOn}, {@code choices}, {@code
   * sshAgent}.
   */
  STRING_OR_LIST("a string or a list of strings"),

  /**
   * An integer shorthand or the retry-policy object — the {@code retryPolicy} {@code $def}'s {@code
   * oneOf} (design/44 §2). Used by {@code retry}.
   */
  INTEGER_OR_OBJECT("an integer (the maxAttempts shorthand) or an object"),

  /**
   * Any JSON value — an empty schema fragment {@code {}}. Used by {@code parameters[].default},
   * which is genuinely any-typed (design/48 D2). The typed reader applies no type check to it.
   */
  ANY("any value");

  private final String humanName;

  GrammarType(@NonNull String humanName) {
    this.humanName = humanName;
  }

  /** The human phrasing used in a type-mismatch error message — e.g. {@code "a boolean ..."}. */
  @NonNull
  public String humanName() {
    return humanName;
  }

  /**
   * Whether a present, non-null {@code node} matches this declared type. {@link #ANY} matches
   * everything; the {@code oneOf} cases match either branch. A {@code null} node is <em>not</em> a
   * type match — present-and-null is rejected separately by the reader (design/48 D3), so this
   * method is only ever called on a present, concrete node.
   */
  public boolean matches(@NonNull JsonNode node) {
    switch (this) {
      case STRING:
        return node.isTextual();
      case BOOLEAN:
        return node.isBoolean();
      case ARRAY:
        return node.isArray();
      case OBJECT:
        return node.isObject();
      case STRING_OR_LIST:
        return node.isTextual() || node.isArray();
      case INTEGER_OR_OBJECT:
        return node.isIntegralNumber() || node.isObject();
      case ANY:
        return true;
      default:
        throw new IllegalStateException("unhandled grammar type " + this);
    }
  }
}
