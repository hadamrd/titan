package io.adaptiq.titan.worker.step.manifest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses one {@code *.titanstep.yaml} file into a {@link StepManifest} (design/42 §4.8, §8).
 *
 * <p>The manifest format, per design/42 §8:
 *
 * <pre>{@code
 * step: acmeNotify                      # required — the descriptorId contributed
 * image: acme/notify:1.4                # required — the container image
 * command: ["/notify", "--text", "${{ args.message }}"]   # required — argv
 * params:                               # the step's declared arguments
 *   - { name: message, type: string, required: true }
 * displayName: Acme notify              # optional
 * help: Sends a notification.           # optional
 * }</pre>
 *
 * <p>A malformed manifest is rejected with a clear, located {@link ManifestException}. Per the 42-P
 * bad-jar posture (design/42 §4.3 rule 4), {@code StepHandlerDiscovery} catches this, logs a
 * WARNING and skips that one file — a single bad manifest never crashes discovery.
 *
 * <p>Reuses the YAML capability already on the worker classpath (jackson-dataformat-yaml, pulled in
 * transitively via {@code titan-pipeline-model}'s {@code TitanYamlParser}).
 */
public final class StepManifestLoader {

  /** The filename suffix that marks a Tier-1 manifest (design/42 §4.8 — {@code *.jar} = Tier-2). */
  public static final String MANIFEST_SUFFIX = ".titanstep.yaml";

  private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

  private StepManifestLoader() {
    // static-only
  }

  /** Whether {@code file}'s name marks it as a Tier-1 step manifest. */
  public static boolean isManifest(Path file) {
    return file.getFileName().toString().toLowerCase().endsWith(MANIFEST_SUFFIX);
  }

  /**
   * Parse one manifest file.
   *
   * @param file the {@code *.titanstep.yaml} file
   * @return the parsed, validated manifest
   * @throws ManifestException if the file is unreadable or the manifest is malformed
   */
  public static StepManifest load(Path file) {
    String origin = file.toAbsolutePath().toString();
    JsonNode root;
    try {
      root = YAML.readTree(file.toFile());
    } catch (IOException e) {
      throw new ManifestException(origin, "cannot read/parse YAML: " + e.getMessage());
    }
    if (root == null || !root.isObject()) {
      throw new ManifestException(origin, "the manifest must be a YAML object");
    }
    return parse(root, origin);
  }

  /** Parse a manifest from an already-loaded YAML tree — the seam unit tests exercise. */
  public static StepManifest parse(JsonNode root, String origin) {
    String step = requiredText(root, "step", origin);
    String image = requiredText(root, "image", origin);

    JsonNode commandNode = root.get("command");
    if (commandNode == null || !commandNode.isArray() || commandNode.isEmpty()) {
      throw new ManifestException(
          origin, "'command' is required and must be a non-empty list of argv strings");
    }
    List<String> command = new ArrayList<>(commandNode.size());
    for (JsonNode arg : commandNode) {
      if (!arg.isValueNode()) {
        throw new ManifestException(origin, "every 'command' entry must be a scalar string");
      }
      command.add(arg.asText());
    }

    List<StepManifest.ManifestParam> params = parseParams(root.get("params"), origin);

    String displayName = optionalText(root, "displayName");
    String help = optionalText(root, "help");

    return new StepManifest(step, image, command, params, displayName, help, origin);
  }

  private static List<StepManifest.ManifestParam> parseParams(JsonNode paramsNode, String origin) {
    if (paramsNode == null || paramsNode.isNull()) {
      return List.of();
    }
    if (!paramsNode.isArray()) {
      throw new ManifestException(origin, "'params' must be a list");
    }
    List<StepManifest.ManifestParam> params = new ArrayList<>(paramsNode.size());
    for (JsonNode p : paramsNode) {
      if (!p.isObject()) {
        throw new ManifestException(origin, "every 'params' entry must be an object");
      }
      JsonNode nameNode = p.get("name");
      if (nameNode == null || !nameNode.isValueNode() || nameNode.asText().isBlank()) {
        throw new ManifestException(origin, "a 'params' entry is missing its 'name'");
      }
      String name = nameNode.asText();
      JsonNode typeNode = p.get("type");
      String type = typeNode == null || !typeNode.isValueNode() ? null : typeNode.asText();
      JsonNode reqNode = p.get("required");
      boolean required = reqNode != null && reqNode.asBoolean(false);
      params.add(new StepManifest.ManifestParam(name, type, required));
    }
    return params;
  }

  private static String requiredText(JsonNode root, String field, String origin) {
    JsonNode node = root.get(field);
    if (node == null || !node.isValueNode() || node.asText().isBlank()) {
      throw new ManifestException(
          origin, "'" + field + "' is required and must be a non-empty string");
    }
    return node.asText().trim();
  }

  private static String optionalText(JsonNode root, String field) {
    JsonNode node = root.get(field);
    return node == null || !node.isValueNode() ? null : node.asText();
  }

  /** A malformed or unreadable manifest — carries the file origin so the WARNING is located. */
  public static final class ManifestException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ManifestException(String origin, String detail) {
      super("invalid step manifest " + origin + ": " + detail);
    }
  }
}
