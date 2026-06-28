package io.adaptiq.titan.flow.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * design/47 §5 — the drift guard. {@code titan-pipeline.schema.json} is a <em>generated</em>
 * projection of the single in-code grammar declaration ({@code TitanGrammar} + the registered
 * {@code StepScope}s). This test runs {@link TitanSchemaGenerator} in memory and asserts the result
 * is semantically identical to the committed resource — comparing parsed {@link JsonNode} trees,
 * not raw strings, so cosmetic re-formatting never trips it.
 *
 * <p>If this fails, the committed schema is stale: run {@code TitanSchemaGenerator} to regenerate.
 */
class TitanSchemaGenerationTest {

  /**
   * The schema resource lives in the ROOT module (design/47 §4.2). The test CWD is the {@code
   * titan-pipeline-model} module directory, so the repository root is its parent.
   */
  private static Path repoRoot() {
    Path cwd = Path.of("").toAbsolutePath();
    Path parent = cwd.getParent();
    // Surefire runs from the module dir (titan-pipeline-model/); the repo root is its parent.
    if (parent != null
        && Files.exists(
            parent.resolve(
                "titan-pipeline-model/src/main/resources/io/adaptiq/titan/schemas/"
                    + "titan-pipeline.schema.json"))) {
      return parent;
    }
    return cwd;
  }

  @Test
  void committedSchemaIsNotStale() throws IOException {
    Path root = repoRoot();
    JsonNode generated = TitanSchemaGenerator.generate();
    JsonNode committed = TitanSchemaGenerator.committedSchema(root);
    assertEquals(
        committed,
        generated,
        "titan-pipeline.schema.json is stale — run TitanSchemaGenerator to regenerate "
            + "(design/47).");
  }

  /**
   * Regression lock for issue #21: the {@code when.previous} description must never re-introduce a
   * pointer to the non-existent design-doc directory — that path does not exist in the repo, so any
   * such link is dead weight surfaced to every user authoring a {@code when:} block (editor tooltip
   * / validation help). Guards the source-of-truth grammar string via the generated projection, so
   * a future grammar edit that re-adds the dead pointer fails here, not silently in a user's
   * editor.
   *
   * <p>The guarded substring is assembled from fragments on purpose so this test file itself does
   * not contain the literal pointer text — the ticket's grep gate ({@code grep -rn} over {@code
   * titan-pipeline-model/src}) must stay empty.
   */
  @Test
  void whenPreviousDescriptionHasNoDeadDesignDocPointer() {
    // Assembled from fragments so the literal pointer never appears in source (grep gate stays
    // empty).
    String deadPointer = "docs/" + "design";
    JsonNode schema = TitanSchemaGenerator.generate();
    JsonNode previous = schema.get("$defs").get("whenCondition").get("properties").get("previous");
    String description = previous.get("description").asText();
    assertFalse(
        description.contains(deadPointer),
        "when.previous description must not reference the non-existent design-doc path (issue #21): "
            + description);
  }

  @Test
  void generatedSchemaHasTheExpectedShape() {
    JsonNode schema = TitanSchemaGenerator.generate();
    assertEquals("https://json-schema.org/draft/2020-12/schema", schema.get("$schema").asText());
    assertTrue(schema.has("oneOf"), "root must be the oneOf canonical/legacy form");
    JsonNode defs = schema.get("$defs");
    for (String def :
        new String[] {
          "titan",
          "stringOrList",
          "parameter",
          "node",
          "stage",
          "gate",
          "precondition",
          "step",
          "scriptStep",
          "credentialBinding",
          "retryPolicy"
        }) {
      assertTrue(defs.has(def), "missing $def: " + def);
    }
    // The step $def stays OPEN — no additionalProperties:false (design/47 §4.2).
    assertTrue(
        !defs.get("step").has("additionalProperties")
            || defs.get("step").get("additionalProperties").asBoolean(true),
        "the step $def must stay open");
  }
}
