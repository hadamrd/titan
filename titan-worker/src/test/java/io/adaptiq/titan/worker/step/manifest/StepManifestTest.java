package io.adaptiq.titan.worker.step.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.adaptiq.titan.worker.step.StepDescriptor;
import io.adaptiq.titan.worker.step.StepRequest;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for the Tier-1 container-step manifest (design/42 §4.8, §8): parsing the manifest
 * format, the derived {@link StepDescriptor}, and the {@code ${{ args.<name> }}} placeholder
 * substitution rule. The full 42-T discovery/integration suite is separate.
 */
class StepManifestTest {

  private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

  private static StepManifest parse(String yaml) throws Exception {
    JsonNode root = YAML.readTree(yaml);
    return StepManifestLoader.parse(root, "<test>");
  }

  @Test
  void parsesTheDesignSection8Example() throws Exception {
    // The literal manifest from design/42 §8.
    StepManifest m =
        parse(
            """
                step: acmeNotify
                image: acme/notify:1.4
                command: ["/notify", "--channel", "${{ args.channel }}", "--text", "${{ args.message }}"]
                params:
                  - { name: channel, type: string, required: true }
                  - { name: message, type: string, required: true }
                """);
    assertEquals("acmeNotify", m.step());
    assertEquals("acme/notify:1.4", m.image());
    assertEquals(
        List.of("/notify", "--channel", "${{ args.channel }}", "--text", "${{ args.message }}"),
        m.command());
    assertEquals(2, m.params().size());
    assertEquals("channel", m.params().get(0).name());
    assertTrue(m.params().get(0).required());
    assertEquals("string", m.params().get(0).type());
  }

  @Test
  void paramTypeDefaultsToString() throws Exception {
    StepManifest m =
        parse(
            """
                step: s
                image: img
                command: ["x"]
                params:
                  - { name: a }
                """);
    assertEquals("string", m.params().get(0).type());
  }

  @Test
  void rejectsMissingStep() {
    ManifestThrows(
        """
                image: img
                command: ["x"]
                """,
        "'step' is required");
  }

  @Test
  void rejectsMissingImage() {
    ManifestThrows(
        """
                step: s
                command: ["x"]
                """,
        "'image' is required");
  }

  @Test
  void rejectsMissingOrEmptyCommand() {
    ManifestThrows(
        """
                step: s
                image: img
                """,
        "'command' is required");
    ManifestThrows(
        """
                step: s
                image: img
                command: []
                """,
        "'command' is required");
  }

  @Test
  void rejectsNonScalarCommandEntry() {
    ManifestThrows(
        """
                step: s
                image: img
                command: [["nested"]]
                """,
        "'command' entry must be a scalar");
  }

  private static void ManifestThrows(String yaml, String expectedFragment) {
    StepManifestLoader.ManifestException ex =
        assertThrows(
            StepManifestLoader.ManifestException.class,
            () -> {
              JsonNode root = YAML.readTree(yaml);
              StepManifestLoader.parse(root, "<test>");
            });
    assertTrue(
        ex.getMessage().contains(expectedFragment),
        "expected '" + expectedFragment + "' in: " + ex.getMessage());
  }

  @Test
  void descriptorIsDerivedFromTheManifest() throws Exception {
    StepManifest m =
        parse(
            """
                step: acmeNotify
                image: acme/notify:1.4
                displayName: Acme Notify
                command: ["/notify"]
                params:
                  - { name: message, type: string, required: true }
                """);
    StepDescriptor d = new ContainerManifestStepHandler(m).descriptor();
    assertEquals("acmeNotify", d.descriptorId());
    assertEquals("Acme Notify", d.displayName());
    assertEquals(1, d.parameters().size());
    assertEquals("message", d.parameters().get(0).name());
    assertTrue(d.parameters().get(0).required());
    assertNull(d.scalarShorthandKey());
  }

  @Test
  void substitutesArgsPlaceholders() {
    List<String> command =
        List.of("/notify", "--channel", "${{ args.channel }}", "--text", "${{args.message}}");
    StepRequest req = request(Map.of("channel", "ops", "message", "build green"));
    List<String> out = ContainerManifestStepHandler.substitute(command, req);
    assertEquals(List.of("/notify", "--channel", "ops", "--text", "build green"), out);
  }

  @Test
  void substitutesMultiplePlaceholdersInOneEntry() {
    List<String> command = List.of("${{ args.a }}-${{ args.b }}");
    StepRequest req = request(Map.of("a", "x", "b", "y"));
    assertEquals(List.of("x-y"), ContainerManifestStepHandler.substitute(command, req));
  }

  @Test
  void entriesWithoutPlaceholdersPassThroughVerbatim() {
    List<String> command = List.of("plain", "--flag");
    StepRequest req = request(Map.of());
    assertEquals(command, ContainerManifestStepHandler.substitute(command, req));
  }

  @Test
  void aNullValuedArgumentSubstitutesEmptyString() {
    List<String> command = List.of("v=${{ args.x }}");
    java.util.HashMap<String, Object> args = new java.util.HashMap<>();
    args.put("x", null);
    StepRequest req = request(args);
    assertEquals(List.of("v="), ContainerManifestStepHandler.substitute(command, req));
  }

  @Test
  void aMissingReferencedArgumentIsACleanFailure() {
    List<String> command = List.of("${{ args.missing }}");
    StepRequest req = request(Map.of("present", "1"));
    ContainerManifestStepHandler.MissingArgument ex =
        assertThrows(
            ContainerManifestStepHandler.MissingArgument.class,
            () -> ContainerManifestStepHandler.substitute(command, req));
    assertTrue(ex.getMessage().contains("missing"));
  }

  private static StepRequest request(Map<String, Object> args) {
    return new StepRequest(
        "acmeNotify", args, Path.of("."), Map.of(), 1L, "n1", null, null, null, null);
  }
}
