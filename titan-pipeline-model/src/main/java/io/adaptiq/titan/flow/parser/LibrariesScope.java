package io.adaptiq.titan.flow.parser;

import com.fasterxml.jackson.databind.JsonNode;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.flow.model.PipelineModel;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Parses the pipeline-root {@code libraries:} block (design/53) — a map of {@code alias → <git
 * -url>@<ref>} that drives the {@code <alias>.<method>} dotted-step dispatch.
 *
 * <p>Bare-coordinate values are public repos; the object form {@code {url, credential}} names a
 * credential resolved on the worker via {@code SecretProvider} (design/40).
 *
 * <p>Extracted from {@link TitanYamlParser} (#1101 — keeps the parser under the file-size cap
 * without touching behaviour). Errors are identical to the previous in-parser implementation, so
 * {@code LibrariesScopeTest} fixtures continue to assert the same messages.
 */
final class LibrariesScope {

  private LibrariesScope() {}

  /**
   * Apply the libraries block on {@code titan} to {@code model}. No-op when absent. Throws {@link
   * PipelineParseException} on any structural error.
   */
  static void applyTo(
      @NonNull JsonNode titan, @NonNull PipelineModel model, @NonNull String context) {
    JsonNode node = titan.get("libraries");
    if (node == null || node.isNull()) {
      return;
    }
    if (!node.isObject()) {
      throw new PipelineParseException(
          context + ".libraries must be a map of <alias>: <coordinate-or-{url,credential}>");
    }
    Map<String, String> aliases = new LinkedHashMap<>();
    Map<String, String> credentials = new LinkedHashMap<>();
    Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
    while (fields.hasNext()) {
      Map.Entry<String, JsonNode> e = fields.next();
      String alias = e.getKey();
      JsonNode v = e.getValue();
      if (alias.isBlank()) {
        throw new PipelineParseException(context + ".libraries: alias must be non-blank");
      }
      if (alias.contains(".")) {
        throw new PipelineParseException(
            context
                + ".libraries['"
                + alias
                + "']: alias must not contain '.' — the dot is reserved for "
                + "the <alias>.<method> dispatch (design/53)");
      }
      String coordinate;
      String credentialName = null;
      if (v != null && v.isTextual() && !v.asText().isBlank()) {
        coordinate = v.asText();
      } else if (v != null && v.isObject()) {
        JsonNode urlNode = v.get("url");
        if (urlNode == null || !urlNode.isTextual() || urlNode.asText().isBlank()) {
          throw new PipelineParseException(
              context
                  + ".libraries['"
                  + alias
                  + "'].url must be a string "
                  + "coordinate (<git-url>@<ref>)");
        }
        coordinate = urlNode.asText();
        JsonNode credNode = v.get("credential");
        if (credNode != null && !credNode.isNull()) {
          if (!credNode.isTextual() || credNode.asText().isBlank()) {
            throw new PipelineParseException(
                context
                    + ".libraries['"
                    + alias
                    + "'].credential must be a "
                    + "secret name (string)");
          }
          credentialName = credNode.asText();
        }
      } else {
        throw new PipelineParseException(
            context
                + ".libraries['"
                + alias
                + "'] must be either a string "
                + "coordinate or an object { url, credential }");
      }
      aliases.put(alias, coordinate);
      if (credentialName != null) {
        credentials.put(alias, credentialName);
      }
    }
    model.setLibraryAliases(aliases);
    model.setLibraryAliasCredentials(credentials);
  }
}
