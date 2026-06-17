package io.adaptiq.titan.flow.parser;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves and inlines top-level {@code include:} directives on a Titan pipeline YAML before the
 * structural parse runs (issue #1120).
 *
 * <p>Two reference shapes are accepted at the grammar level:
 *
 * <ul>
 *   <li>a bare string — a repo-relative path to a sibling YAML fragment;
 *   <li>an object {@code { repo, ref, path, credential? }} — a fragment from another repo at a
 *       given git ref. Cross-repo I/O is delegated to {@link IncludeResolver#resolveRepo}.
 * </ul>
 *
 * <p>Customer pain (verbatim from #1120): "Every repo has the same lint+test prelude. In GitLab I
 * use include, in GHA I use reusable workflows. If I have to copy-paste 40 lines into every
 * titan-pipeline.yml I will not migrate."
 *
 * <h2>Semantics</h2>
 *
 * <p>Includes are resolved depth-first. Each included document may itself declare {@code include:};
 * that nested list is processed first so deep fragments are fully inlined before being merged into
 * the parent. The processor caps recursion at {@link #MAX_INCLUDE_DEPTH} and tracks visited source
 * ids to detect cycles ({@code A.yml} → {@code B.yml} → {@code A.yml} is a parse error, not a stack
 * overflow).
 *
 * <p>Merge: the <strong>main file wins</strong> for any scalar / object key it sets explicitly;
 * list-valued keys ({@code stages}, {@code parameters}, {@code triggers}, {@code notify}, {@code
 * credentials}) are <em>concatenated</em> — included entries first, then the main file's. This
 * gives the prelude-then-main reading order customers expect, mirroring GitLab CI's {@code
 * include:} ordering. Map-valued keys ({@code libraries}, {@code env}) are shallow-merged with the
 * main file winning on key conflict.
 *
 * <p>The processor operates on the document <em>body</em> — both the canonical root form and the
 * legacy {@code titan:} wrapper — and returns a new YAML string the existing parser can consume
 * unchanged. No grammar surface beyond the {@code include:} key itself moves.
 */
final class IncludeProcessor {

  private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

  /**
   * Hard cap on include nesting depth (#1128) — high enough for real factoring (a shared prologue
   * file that itself pulls in one or two common fragments), low enough to fail loud on accidentally
   * recursive include graphs before the JVM stack does.
   */
  static final int MAX_INCLUDE_DEPTH = 5;

  /** Keys whose values are lists at root and are concatenated on merge. */
  private static final Set<String> LIST_MERGE_KEYS =
      Set.of("stages", "parameters", "triggers", "notify", "credentials");

  /** Keys whose values are maps at root and are shallow-merged (main wins) on merge. */
  private static final Set<String> MAP_MERGE_KEYS = Set.of("libraries", "env");

  private IncludeProcessor() {}

  /**
   * Walk and inline all {@code include:} references inside {@code body}, returning a new ObjectNode
   * with the {@code include:} key removed. {@code body} is the pipeline body node — either the
   * document root or the value under a {@code titan:} wrapper.
   */
  @NonNull
  static ObjectNode inline(
      @NonNull ObjectNode body, @NonNull IncludeResolver resolver, @NonNull String mainSourceId) {
    Set<String> visited = new LinkedHashSet<>();
    visited.add(mainSourceId);
    return inlineInto(body, resolver, visited, 0, "pipeline");
  }

  @NonNull
  private static ObjectNode inlineInto(
      @NonNull ObjectNode body,
      @NonNull IncludeResolver resolver,
      @NonNull Set<String> visited,
      int depth,
      @NonNull String context) {
    if (depth > MAX_INCLUDE_DEPTH) {
      throw new PipelineParseException(
          context
              + ": include nesting exceeds the cap of "
              + MAX_INCLUDE_DEPTH
              + " (likely a deeply-chained or accidentally-recursive include graph).");
    }

    JsonNode includeNode = body.get("include");
    if (includeNode == null || includeNode.isNull()) {
      // Nothing to do — return as-is (with any present-but-null `include:` stripped so the
      // downstream parser does not see it).
      ObjectNode copy = body.deepCopy();
      copy.remove("include");
      return copy;
    }
    // #1128: accept three shapes — string, object, or list. A single string/object is treated as
    // a 1-element list so the merge precedence rule ("local file > last include > … > first
    // include") collapses to the obvious thing for the common single-include case.
    List<JsonNode> entries = new ArrayList<>();
    if (includeNode.isArray()) {
      for (JsonNode e : includeNode) {
        entries.add(e);
      }
    } else if (includeNode.isTextual() || includeNode.isObject()) {
      entries.add(includeNode);
    } else {
      throw new PipelineParseException(
          context
              + ": 'include' must be a path string, a { repo, ref, path } object, or a list "
              + "thereof — got "
              + includeNode.getNodeType().name().toLowerCase());
    }

    // Resolve every entry into a (sourceId, body) pair, recursing so nested includes are fully
    // inlined before we merge them upward.
    List<ObjectNode> includedBodies = new ArrayList<>();
    int idx = 0;
    for (JsonNode entry : entries) {
      String where = context + " include[" + idx + "]";
      IncludeResolver.ResolvedInclude resolved = resolveEntry(entry, resolver, where);
      if (visited.contains(resolved.sourceId())) {
        throw new PipelineParseException(
            where
                + ": include cycle detected — '"
                + resolved.sourceId()
                + "' has already been included on this chain: "
                + String.join(" -> ", visited)
                + " -> "
                + resolved.sourceId());
      }
      JsonNode parsed;
      try {
        parsed = YAML.readTree(resolved.text());
      } catch (JacksonException e) {
        throw new PipelineParseException(
            where
                + ": included file '"
                + resolved.sourceId()
                + "' is not valid YAML: "
                + e.getOriginalMessage(),
            e);
      }
      if (parsed == null || !parsed.isObject()) {
        throw new PipelineParseException(
            where
                + ": included file '"
                + resolved.sourceId()
                + "' must be a YAML object (got "
                + (parsed == null ? "null" : parsed.getNodeType().name().toLowerCase())
                + ")");
      }
      ObjectNode includedRoot = (ObjectNode) parsed;
      // An included file may itself use either form (root or `titan:` wrapper).
      ObjectNode includedBody;
      if (includedRoot.has("titan") && includedRoot.get("titan").isObject()) {
        includedBody = (ObjectNode) includedRoot.get("titan");
      } else {
        includedBody = includedRoot;
      }
      Set<String> nestedVisited = new LinkedHashSet<>(visited);
      nestedVisited.add(resolved.sourceId());
      ObjectNode flattened = inlineInto(includedBody, resolver, nestedVisited, depth + 1, where);
      includedBodies.add(flattened);
      idx++;
    }

    // Merge: included bodies first, in order; the main body (without its `include:`) merged last
    // so its scalar/object keys win.
    ObjectNode merged = YAML.createObjectNode();
    for (ObjectNode inc : includedBodies) {
      mergeInto(merged, inc);
    }
    ObjectNode mainCopy = body.deepCopy();
    mainCopy.remove("include");
    mergeInto(merged, mainCopy);

    // #1128: post-merge dedupe of `stages` by stage id — a same-id stage declared LATER (i.e. in
    // a later include or in the local file) fully replaces the earlier one. There is no per-step
    // merge inside a stage — replace whole stage. This gives the locked precedence:
    //   local file > last include > … > first include
    // while preserving the prologue-first reading order for non-conflicting ids.
    JsonNode stages = merged.get("stages");
    if (stages != null && stages.isArray()) {
      merged.set(
          "stages", dedupeStagesLastWins((com.fasterxml.jackson.databind.node.ArrayNode) stages));
    }
    return merged;
  }

  /**
   * Dedupe a stage list by stage id, keeping the LAST occurrence and preserving its position in the
   * original order. A stage's id is the {@code stage:} key (with {@code id:} as an explicit
   * override). Non-object entries pass through untouched — structural validation handles them
   * downstream.
   */
  @NonNull
  private static com.fasterxml.jackson.databind.node.ArrayNode dedupeStagesLastWins(
      @NonNull com.fasterxml.jackson.databind.node.ArrayNode stages) {
    // First pass: find the index of the LAST occurrence per id.
    Map<String, Integer> lastIdx = new LinkedHashMap<>();
    for (int i = 0; i < stages.size(); i++) {
      JsonNode s = stages.get(i);
      String id = stageIdOf(s);
      if (id != null) {
        lastIdx.put(id, i);
      }
    }
    // Second pass: emit entries whose index is the last-seen for their id; anonymous/non-id
    // entries always pass through.
    com.fasterxml.jackson.databind.node.ArrayNode out = YAML.createArrayNode();
    for (int i = 0; i < stages.size(); i++) {
      JsonNode s = stages.get(i);
      String id = stageIdOf(s);
      if (id == null || lastIdx.get(id) == i) {
        out.add(s.deepCopy());
      }
    }
    return out;
  }

  @edu.umd.cs.findbugs.annotations.Nullable
  private static String stageIdOf(@NonNull JsonNode stage) {
    if (!stage.isObject()) {
      return null;
    }
    JsonNode id = stage.get("id");
    if (id != null && id.isTextual()) {
      return id.asText();
    }
    JsonNode name = stage.get("stage");
    if (name != null && name.isTextual()) {
      return name.asText();
    }
    return null;
  }

  @NonNull
  private static IncludeResolver.ResolvedInclude resolveEntry(
      @NonNull JsonNode entry, @NonNull IncludeResolver resolver, @NonNull String where) {
    if (entry.isTextual()) {
      return resolver.resolveLocal(entry.asText(), where);
    }
    if (entry.isObject()) {
      Set<String> allowed = Set.of("repo", "ref", "path", "credential");
      Iterator<String> it = entry.fieldNames();
      while (it.hasNext()) {
        String f = it.next();
        if (!allowed.contains(f)) {
          throw new PipelineParseException(
              where
                  + ": unknown key '"
                  + f
                  + "' on cross-repo include entry "
                  + "(allowed: repo, ref, path, credential)");
        }
      }
      String repo = requireStringField(entry, "repo", where);
      String ref = requireStringField(entry, "ref", where);
      String pathInRepo = requireStringField(entry, "path", where);
      String credential = optionalStringField(entry, "credential", where);
      return resolver.resolveRepo(repo, ref, pathInRepo, credential, where);
    }
    throw new PipelineParseException(
        where
            + ": each include entry must be a string (local path) or an object "
            + "{ repo, ref, path[, credential] }; got "
            + entry.getNodeType().name().toLowerCase());
  }

  @NonNull
  private static String requireStringField(
      @NonNull JsonNode obj, @NonNull String name, @NonNull String where) {
    JsonNode v = obj.get(name);
    if (v == null || v.isNull()) {
      throw new PipelineParseException(
          where + ": cross-repo include is missing required key '" + name + "'");
    }
    if (!v.isTextual() || v.asText().isBlank()) {
      throw new PipelineParseException(
          where + ": cross-repo include '" + name + "' must be a non-blank string");
    }
    return v.asText();
  }

  private static String optionalStringField(
      @NonNull JsonNode obj, @NonNull String name, @NonNull String where) {
    JsonNode v = obj.get(name);
    if (v == null || v.isNull()) {
      return null;
    }
    if (!v.isTextual()) {
      throw new PipelineParseException(
          where + ": cross-repo include '" + name + "' must be a string");
    }
    return v.asText();
  }

  /**
   * Merge {@code src} into {@code dest}, applying Titan's merge rules: list-keys concatenate, map-
   * keys shallow-merge, everything else is an override (the later-merged source wins).
   */
  private static void mergeInto(@NonNull ObjectNode dest, @NonNull ObjectNode src) {
    Iterator<Map.Entry<String, JsonNode>> it = src.fields();
    while (it.hasNext()) {
      Map.Entry<String, JsonNode> e = it.next();
      String key = e.getKey();
      JsonNode value = e.getValue();
      if (LIST_MERGE_KEYS.contains(key) && value.isArray()) {
        JsonNode existing = dest.get(key);
        if (existing == null || !existing.isArray()) {
          dest.set(key, value.deepCopy());
        } else {
          for (JsonNode v : value) {
            ((com.fasterxml.jackson.databind.node.ArrayNode) existing).add(v.deepCopy());
          }
        }
      } else if (MAP_MERGE_KEYS.contains(key) && value.isObject()) {
        JsonNode existing = dest.get(key);
        if (existing == null || !existing.isObject()) {
          dest.set(key, value.deepCopy());
        } else {
          // Shallow merge: src's keys override existing's.
          Iterator<Map.Entry<String, JsonNode>> sub = value.fields();
          Map<String, JsonNode> tmp = new LinkedHashMap<>();
          Iterator<Map.Entry<String, JsonNode>> ex = existing.fields();
          while (ex.hasNext()) {
            Map.Entry<String, JsonNode> x = ex.next();
            tmp.put(x.getKey(), x.getValue());
          }
          while (sub.hasNext()) {
            Map.Entry<String, JsonNode> x = sub.next();
            tmp.put(x.getKey(), x.getValue());
          }
          ObjectNode mergedMap = YAML.createObjectNode();
          for (Map.Entry<String, JsonNode> x : tmp.entrySet()) {
            mergedMap.set(x.getKey(), x.getValue().deepCopy());
          }
          dest.set(key, mergedMap);
        }
      } else {
        dest.set(key, value.deepCopy());
      }
    }
  }
}
