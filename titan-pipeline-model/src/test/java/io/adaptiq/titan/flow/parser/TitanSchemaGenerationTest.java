package io.adaptiq.titan.flow.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
