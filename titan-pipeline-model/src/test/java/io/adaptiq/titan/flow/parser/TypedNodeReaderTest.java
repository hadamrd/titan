package io.adaptiq.titan.flow.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.adaptiq.titan.flow.parser.grammar.GrammarKey;
import io.adaptiq.titan.flow.parser.grammar.GrammarType;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TypedNodeReader} — the grammar-driven typed reader (design/48 D2/D3/D4).
 * Pure: no DB, no engine. Adversarial — every accessor is exercised on absent, present-and-null,
 * type-mismatched and well-typed input.
 */
class TypedNodeReaderTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String CTX = "stage 'Deploy'";

  /**
   * Parse a JSON object literal into a JsonNode (the parser hands TypedNodeReader Jackson nodes).
   */
  private static JsonNode obj(String json) {
    try {
      return MAPPER.readTree(json);
    } catch (Exception e) {
      throw new IllegalArgumentException("bad test JSON: " + json, e);
    }
  }

  private static GrammarKey stringKey(String name) {
    return GrammarKey.optional(name, "", GrammarKey.string());
  }

  private static GrammarKey boolKey(String name) {
    return GrammarKey.optional(name, "", GrammarKey.bool());
  }

  private static GrammarKey stringOrListKey(String name) {
    return GrammarKey.optional(name, "", GrammarKey.ref("stringOrList"));
  }

  private static String messageOf(org.junit.jupiter.api.function.Executable e) {
    return assertThrows(PipelineParseException.class, e).getMessage();
  }

  // ── requireTyped ─────────────────────────────────────────────────────────

  @Test
  void requireTypedReturnsTheSameNodeWhenTypeMatches() {
    JsonNode node = obj("{\"k\":\"hello\"}").get("k");
    assertSame(node, TypedNodeReader.requireTyped(node, "k", GrammarType.STRING, CTX));
  }

  @Test
  void requireTypedRejectsAnExplicitNullAsPresentButNull() {
    JsonNode node = obj("{\"k\":null}").get("k");
    String msg = messageOf(() -> TypedNodeReader.requireTyped(node, "k", GrammarType.STRING, CTX));
    assertTrue(msg.contains("present but null"), msg);
    assertTrue(msg.contains("'k'"), msg);
    assertTrue(msg.contains(CTX), msg);
  }

  @Test
  void requireTypedRejectsATypeMismatchAndEchoesTheValue() {
    JsonNode node = obj("{\"k\":5}").get("k");
    String msg = messageOf(() -> TypedNodeReader.requireTyped(node, "k", GrammarType.STRING, CTX));
    assertTrue(msg.contains("must be a string"), msg);
    assertTrue(msg.contains("the number 5"), msg);
  }

  @Test
  void requireTypedAcceptsAMatchingBoolean() {
    JsonNode node = obj("{\"k\":true}").get("k");
    assertSame(node, TypedNodeReader.requireTyped(node, "k", GrammarType.BOOLEAN, CTX));
  }

  // ── optionalNode ─────────────────────────────────────────────────────────

  @Test
  void optionalNodeReturnsNullWhenTheKeyIsGenuinelyAbsent() {
    assertNull(TypedNodeReader.optionalNode(obj("{}"), stringKey("agent"), CTX));
  }

  @Test
  void optionalNodeReturnsThePresentTypedNode() {
    JsonNode v =
        TypedNodeReader.optionalNode(obj("{\"agent\":\"linux\"}"), stringKey("agent"), CTX);
    assertEquals("linux", v.textValue());
  }

  @Test
  void optionalNodeThrowsOnPresentNull() {
    assertThrows(
        PipelineParseException.class,
        () -> TypedNodeReader.optionalNode(obj("{\"agent\":null}"), stringKey("agent"), CTX));
  }

  @Test
  void optionalNodeThrowsOnTypeMismatch() {
    assertThrows(
        PipelineParseException.class,
        () -> TypedNodeReader.optionalNode(obj("{\"agent\":true}"), stringKey("agent"), CTX));
  }

  // ── optString ────────────────────────────────────────────────────────────

  @Test
  void optStringIsNullWhenAbsent() {
    assertNull(TypedNodeReader.optString(obj("{}"), stringKey("agent"), CTX));
  }

  @Test
  void optStringReturnsThePresentValue() {
    assertEquals(
        "linux", TypedNodeReader.optString(obj("{\"agent\":\"linux\"}"), stringKey("agent"), CTX));
  }

  @Test
  void optStringThrowsOnANonStringValue() {
    assertThrows(
        PipelineParseException.class,
        () -> TypedNodeReader.optString(obj("{\"agent\":5}"), stringKey("agent"), CTX));
  }

  // ── requireString ────────────────────────────────────────────────────────

  @Test
  void requireStringThrowsWhenTheKeyIsAbsent() {
    String msg = messageOf(() -> TypedNodeReader.requireString(obj("{}"), stringKey("name"), CTX));
    assertTrue(msg.contains("missing required key"), msg);
    assertTrue(msg.contains("'name'"), msg);
  }

  @Test
  void requireStringThrowsOnAPresentNull() {
    String msg =
        messageOf(
            () -> TypedNodeReader.requireString(obj("{\"name\":null}"), stringKey("name"), CTX));
    assertTrue(msg.contains("present but null"), msg);
  }

  @Test
  void requireStringThrowsOnANonStringValue() {
    String msg =
        messageOf(() -> TypedNodeReader.requireString(obj("{\"name\":5}"), stringKey("name"), CTX));
    assertTrue(msg.contains("must be a string"), msg);
  }

  @Test
  void requireStringRejectsABlankString() {
    // Whitespace-only must be rejected — proves the guard is isBlank(), not isEmpty().
    String msg =
        messageOf(
            () -> TypedNodeReader.requireString(obj("{\"name\":\"   \"}"), stringKey("name"), CTX));
    assertTrue(msg.contains("must not be empty"), msg);
  }

  @Test
  void requireStringReturnsTheTextForAValidValue() {
    assertEquals(
        "Build",
        TypedNodeReader.requireString(obj("{\"name\":\"Build\"}"), stringKey("name"), CTX));
  }

  // ── optBoolean ───────────────────────────────────────────────────────────

  @Test
  void optBooleanIsNullWhenAbsent() {
    assertNull(TypedNodeReader.optBoolean(obj("{}"), boolKey("requiresApproval"), CTX));
  }

  @Test
  void optBooleanReturnsTrue() {
    assertEquals(
        Boolean.TRUE,
        TypedNodeReader.optBoolean(
            obj("{\"requiresApproval\":true}"), boolKey("requiresApproval"), CTX));
  }

  @Test
  void optBooleanReturnsFalse() {
    assertEquals(
        Boolean.FALSE,
        TypedNodeReader.optBoolean(
            obj("{\"requiresApproval\":false}"), boolKey("requiresApproval"), CTX));
  }

  @Test
  void optBooleanThrowsOnACoercibleStringInsteadOfCoercingIt() {
    // requiresApproval: "yes" must fail with a type error, not coerce to false (design/48 D4).
    assertThrows(
        PipelineParseException.class,
        () ->
            TypedNodeReader.optBoolean(
                obj("{\"requiresApproval\":\"yes\"}"), boolKey("requiresApproval"), CTX));
  }

  // ── stringList ───────────────────────────────────────────────────────────

  @Test
  void stringListIsEmptyWhenAbsent() {
    assertEquals(
        List.of(), TypedNodeReader.stringList(obj("{}"), stringOrListKey("dependsOn"), CTX));
  }

  @Test
  void stringListFoldsABareStringToAOneElementList() {
    assertEquals(
        List.of("Build"),
        TypedNodeReader.stringList(
            obj("{\"dependsOn\":\"Build\"}"), stringOrListKey("dependsOn"), CTX));
  }

  @Test
  void stringListReadsAnArrayInOrder() {
    assertEquals(
        List.of("Build", "Test"),
        TypedNodeReader.stringList(
            obj("{\"dependsOn\":[\"Build\",\"Test\"]}"), stringOrListKey("dependsOn"), CTX));
  }

  @Test
  void stringListRejectsANonStringElementAndNamesItsIndex() {
    // Element 1 (not 0) is the offender — pins the per-element index in the message.
    String msg =
        messageOf(
            () ->
                TypedNodeReader.stringList(
                    obj("{\"dependsOn\":[\"Build\",5]}"), stringOrListKey("dependsOn"), CTX));
    assertTrue(msg.contains("element 1"), msg);
    assertTrue(msg.contains("must be a string"), msg);
    assertTrue(msg.contains("the number 5"), msg);
  }

  @Test
  void stringListThrowsOnPresentNull() {
    assertThrows(
        PipelineParseException.class,
        () ->
            TypedNodeReader.stringList(
                obj("{\"dependsOn\":null}"), stringOrListKey("dependsOn"), CTX));
  }

  @Test
  void stringListThrowsOnAValueThatIsNeitherStringNorArray() {
    assertThrows(
        PipelineParseException.class,
        () ->
            TypedNodeReader.stringList(
                obj("{\"dependsOn\":5}"), stringOrListKey("dependsOn"), CTX));
  }

  // ── describe ─────────────────────────────────────────────────────────────

  @Test
  void describeNamesEachJsonKind() {
    assertEquals("the string \"x\"", TypedNodeReader.describe(obj("{\"k\":\"x\"}").get("k")));
    assertEquals("the boolean true", TypedNodeReader.describe(obj("{\"k\":true}").get("k")));
    assertEquals("the number 7", TypedNodeReader.describe(obj("{\"k\":7}").get("k")));
    assertEquals("an array", TypedNodeReader.describe(obj("{\"k\":[]}").get("k")));
    assertEquals("an object", TypedNodeReader.describe(obj("{\"k\":{}}").get("k")));
    assertEquals("null", TypedNodeReader.describe(obj("{\"k\":null}").get("k")));
    assertEquals("a missing value", TypedNodeReader.describe(obj("{}").path("nope")));
  }
}
