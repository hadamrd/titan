package io.adaptiq.titan.flow.parser.grammar;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * One key of the Titan YAML grammar, in one grammar context (design/47 §2.1).
 *
 * <p>A {@code GrammarKey} is the single declaration of a YAML key: its {@code name}, whether it is
 * {@code required} in its context, its human {@code description}, and the draft-2020-12 JSON Schema
 * fragment for the key's <em>value</em>. {@link TitanGrammar} composes ordered lists of these into
 * the grammar contexts; {@code TitanYamlParser} projects {@link #name()} sets out of them and
 * {@code TitanSchemaGenerator} projects the schema's {@code properties} blocks.
 *
 * <p>The {@code valueSchema} is a Jackson {@link JsonNode} so it composes freely — a {@code $ref},
 * a typed scalar, a {@code oneOf}. The {@code description} is carried <em>separately</em> from the
 * value schema (not baked into it) so the generator can place it on the {@code properties} entry
 * exactly as the hand-authored schema does — a {@code $ref} cannot carry a sibling {@code type} but
 * can carry a sibling {@code description}, which is the shape the current schema uses.
 *
 * <p>This is a record — immutable by construction. The {@code valueSchema} node is shared by
 * reference; callers (the fragment helpers, the generator) treat grammar nodes as read-only.
 *
 * @param name the YAML key, e.g. {@code "stages"}
 * @param required whether the key is required in its grammar context
 * @param description the human description, ported verbatim from the editor schema; may be empty
 * @param valueSchema the draft-2020-12 fragment for the key's value
 */
public record GrammarKey(
    @NonNull String name,
    boolean required,
    @NonNull String description,
    @NonNull JsonNode valueSchema) {

  /** A node factory that does not intern small numbers — every fragment gets a fresh node. */
  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  public GrammarKey {
    if (name.isBlank()) {
      throw new IllegalArgumentException("a grammar key must have a non-blank name");
    }
  }

  /** A required key. */
  @NonNull
  public static GrammarKey required(
      @NonNull String name, @NonNull String description, @NonNull JsonNode valueSchema) {
    return new GrammarKey(name, true, description, valueSchema);
  }

  /** An optional key. */
  @NonNull
  public static GrammarKey optional(
      @NonNull String name, @NonNull String description, @NonNull JsonNode valueSchema) {
    return new GrammarKey(name, false, description, valueSchema);
  }

  // ── fragment helpers — the recurring value-schema shapes (design/47 §2.1) ──

  /** An empty object schema — a schema that accepts any value. */
  @NonNull
  public static ObjectNode any() {
    return NODES.objectNode();
  }

  /** A plain {@code {"type": "string"}} value schema. */
  @NonNull
  public static ObjectNode string() {
    return NODES.objectNode().put("type", "string");
  }

  /** A {@code {"type": "string", "examples": [...]}} value schema. */
  @NonNull
  public static ObjectNode stringWithExamples(@NonNull String... examples) {
    ObjectNode node = string();
    ArrayNode arr = node.putArray("examples");
    for (String e : examples) {
      arr.add(e);
    }
    return node;
  }

  /**
   * A {@code {"type": "string", "enum": [...]}} value schema — used for a closed set of accepted
   * camelCase YAML literals (e.g. {@code failurePolicy}). Drives editor autocomplete + schema-level
   * rejection of unknown values.
   */
  @NonNull
  public static ObjectNode stringEnum(@NonNull String... values) {
    ObjectNode node = string();
    ArrayNode arr = node.putArray("enum");
    for (String v : values) {
      arr.add(v);
    }
    return node;
  }

  /** A plain {@code {"type": "boolean"}} value schema. */
  @NonNull
  public static ObjectNode bool() {
    return NODES.objectNode().put("type", "boolean");
  }

  /** A {@code {"type": "boolean", "default": <d>}} value schema. */
  @NonNull
  public static ObjectNode boolWithDefault(boolean def) {
    return NODES.objectNode().put("type", "boolean").put("default", def);
  }

  /** A {@code {"type": "integer", "minimum": <m>}} value schema. */
  @NonNull
  public static ObjectNode integerMin(int minimum) {
    return NODES.objectNode().put("type", "integer").put("minimum", minimum);
  }

  /** A {@code {"$ref": "#/$defs/<def>"}} value schema. */
  @NonNull
  public static ObjectNode ref(@NonNull String def) {
    return NODES.objectNode().put("$ref", "#/$defs/" + def);
  }

  /** A typed array whose items are the given schema — {@code {"type":"array","items":<items>}}. */
  @NonNull
  public static ObjectNode arrayOf(@NonNull JsonNode items) {
    ObjectNode node = NODES.objectNode().put("type", "array");
    node.set("items", items);
    return node;
  }

  /** A typed array of {@code {"$ref": "#/$defs/<def>"}} items. */
  @NonNull
  public static ObjectNode arrayOfRef(@NonNull String def) {
    return arrayOf(ref(def));
  }

  /**
   * Classify this key's {@code valueSchema} fragment into its grammar-declared {@link GrammarType}
   * (design/48 D2) — the type the parser's typed reader enforces.
   *
   * <p>This is the single projection of a grammar key's <em>type</em>, exactly as {@link #name()}
   * is the projection of its name. It reads the {@code valueSchema} the grammar already declares;
   * it does not hand-duplicate a type list, so there is no drift surface. The recognised fragment
   * shapes are exactly the ones {@link GrammarKey}'s fragment helpers and {@link TitanGrammar}'s
   * {@code $defs} produce:
   *
   * <ul>
   *   <li>{@code {"type":"string"}} → {@link GrammarType#STRING};
   *   <li>{@code {"type":"boolean"}} → {@link GrammarType#BOOLEAN};
   *   <li>{@code {"type":"array", ...}} → {@link GrammarType#ARRAY};
   *   <li>{@code {"type":"object", ...}} → {@link GrammarType#OBJECT};
   *   <li>an empty fragment {@code {}} → {@link GrammarType#ANY};
   *   <li>{@code {"$ref":"#/$defs/stringOrList"}} → {@link GrammarType#STRING_OR_LIST};
   *   <li>{@code {"$ref":"#/$defs/retryPolicy"}} → {@link GrammarType#INTEGER_OR_OBJECT};
   *   <li>any other {@code $ref} to an object-shaped {@code $def} ({@code parameter}, {@code node},
   *       {@code step}, {@code credentialBinding}) → {@link GrammarType#OBJECT}.
   * </ul>
   *
   * <p>An unrecognised fragment is a programming error in the grammar declaration — it throws an
   * {@link IllegalStateException}, never a {@link JsonNode}-level parse condition.
   */
  @NonNull
  public GrammarType declaredType() {
    JsonNode v = valueSchema;
    if (v.has("$ref")) {
      String ref = v.get("$ref").asText();
      // The two $defs the grammar uses as a key value are oneOf-shaped — their declared type
      // is the union of the oneOf branches, recognised by the $def name. Every other $ref a
      // grammar key points at is an object-shaped $def.
      if (ref.endsWith("/stringOrList")) {
        return GrammarType.STRING_OR_LIST;
      }
      if (ref.endsWith("/retryPolicy")) {
        return GrammarType.INTEGER_OR_OBJECT;
      }
      return GrammarType.OBJECT;
    }
    JsonNode type = v.get("type");
    if (type == null) {
      // an empty fragment — `any()` — accepts any value (e.g. parameters[].default).
      return GrammarType.ANY;
    }
    switch (type.asText()) {
      case "string":
        return GrammarType.STRING;
      case "boolean":
        return GrammarType.BOOLEAN;
      case "array":
        return GrammarType.ARRAY;
      case "object":
        return GrammarType.OBJECT;
      default:
        throw new IllegalStateException(
            "grammar key '"
                + name
                + "' has an unrecognised value-schema type '"
                + type.asText()
                + "' — design/48 D2 expects string/boolean/array/"
                + "object or a known $ref");
    }
  }

  /**
   * Render this key as a JSON Schema {@code properties} entry — the value schema with the
   * description spliced in as a sibling field, mirroring the hand-authored schema's shape. A blank
   * description is omitted. When the value schema already carries a {@code description} (some
   * {@code $defs} shapes do) the key's own description takes precedence at the call site.
   */
  @NonNull
  public JsonNode toPropertyNode() {
    JsonNode value = valueSchema.deepCopy();
    if (!description.isBlank() && value instanceof ObjectNode obj) {
      ObjectNode withDesc = NODES.objectNode();
      // Place `description` first when the fragment is a bare $ref (matches the editor
      // schema's `{ "$ref": ..., "description": ... }` ordering); otherwise append it.
      if (obj.size() == 1 && obj.has("$ref")) {
        withDesc.set("$ref", obj.get("$ref"));
        withDesc.put("description", description);
      } else {
        // type-first, then description, then the rest — the hand-authored ordering.
        if (obj.has("type")) {
          withDesc.set("type", obj.get("type"));
        }
        withDesc.put("description", description);
        obj.fields()
            .forEachRemaining(
                e -> {
                  if (!"type".equals(e.getKey()) && !"description".equals(e.getKey())) {
                    withDesc.set(e.getKey(), e.getValue());
                  }
                });
      }
      return withDesc;
    }
    return value;
  }
}
