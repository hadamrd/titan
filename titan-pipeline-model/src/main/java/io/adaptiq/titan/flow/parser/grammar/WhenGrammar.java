package io.adaptiq.titan.flow.parser.grammar;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * The {@code whenCondition} reusable {@code $def} (GH #1093) — extracted from {@link TitanGrammar}
 * so that monolith stays under the 1000-line module cap (design/59). Declares the value shape of
 * the structured step-level {@code when:} block: a discriminated union of exactly one of {@code
 * branch} (glob), {@code previous} ({@code success|failure|always}) or {@code files_changed} (glob
 * list).
 *
 * <p>The {@code oneOf}-of-{@code required} shape is what makes a malformed block (zero or two
 * discriminators) a <em>schema</em> validation failure — the editor and any JSON-Schema validator
 * reject it before the parser runs, matching the GH #1093 acceptance criterion ("malformed when
 * block → schema validation failure at parse, NOT at runtime").
 */
public final class WhenGrammar {

  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  private WhenGrammar() {}

  /** The {@code whenCondition} {@code $def} (GH #1093). */
  @NonNull
  public static ObjectNode whenConditionDef() {
    ObjectNode def = NODES.objectNode();
    def.put("type", "object");
    def.put(
        "description",
        "A structured step condition (GH #1093): a discriminated union — declare exactly one of "
            + "branch, previous or files_changed. A false condition skips the step (SKIPPED).");
    def.put("additionalProperties", false);

    ArrayNode oneOf = def.putArray("oneOf");
    oneOf.add(NODES.objectNode().set("required", NODES.arrayNode().add("branch")));
    oneOf.add(NODES.objectNode().set("required", NODES.arrayNode().add("previous")));
    oneOf.add(NODES.objectNode().set("required", NODES.arrayNode().add("files_changed")));

    ObjectNode props = def.putObject("properties");
    ObjectNode branch = props.putObject("branch");
    branch.put("type", "string");
    branch.put(
        "description",
        "Run only when the build's branch matches this glob (e.g. 'main', 'release/*').");

    ObjectNode previous = props.putObject("previous");
    previous.put("type", "string");
    ArrayNode prevEnum = previous.putArray("enum");
    prevEnum.add("success");
    prevEnum.add("failure");
    prevEnum.add("always");
    previous.put(
        "description",
        "Run based on the prior step's outcome: success (prior succeeded). NOTE: 'failure' and "
            + "'always' are [experimental] — under the engine's default fail-fast mode an upstream "
            + "failure halts the build before a downstream step is dispatched, so a 'failure'/"
            + "'always' guard does not yet fire in the upstream-failed scenario (the AncestorClosure "
            + "fail-fast exemption is a tracked follow-up). "
            + "'success' and all parse/evaluation semantics are fully supported today.");

    ObjectNode filesChanged = props.putObject("files_changed");
    filesChanged.put("type", "array");
    filesChanged.set("items", NODES.objectNode().put("type", "string"));
    filesChanged.put("minItems", 1);
    filesChanged.put(
        "description",
        "Run only when at least one changed file in the build matches one of these globs.");

    return def;
  }
}
