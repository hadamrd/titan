package io.adaptiq.titan.flow.expr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * The build-global facts a structured {@code when:} guard needs (GH #1093) — the build's branch and
 * its changed-file set — extracted from a build's SCM trigger metadata JSON ({@code
 * builds.trigger_meta_json}).
 *
 * <p>Trigger metadata is written by the SCM trigger layer, e.g. {@code {"branch":"trunk",
 * "commitSha":"a3f9c12","actor":"kira.rai"}}. The changed-file set, when present, is carried as a
 * {@code changedFiles} (or {@code changed_files}) string array. Both fields degrade gracefully: a
 * {@code null}/blank/malformed payload yields a {@code null} branch and an empty changeset, which
 * makes a {@code when.branch} / {@code when.files_changed} guard evaluate to "skip" rather than
 * crash.
 *
 * @param branch the build's branch, or {@code null} when the trigger metadata carries none
 * @param changedFiles the changed file paths; never {@code null}, possibly empty
 */
public record WhenFacts(@Nullable String branch, @NonNull List<String> changedFiles) {

  private static final ObjectMapper JSON = new ObjectMapper();

  /** The empty facts — unknown branch, no changeset. */
  @NonNull
  public static WhenFacts empty() {
    return new WhenFacts(null, List.of());
  }

  /**
   * Parse build-when facts from a build's {@code trigger_meta_json}. Tolerant: any null, blank or
   * unparseable input yields {@link #empty()}.
   */
  @NonNull
  public static WhenFacts fromTriggerMeta(@Nullable String triggerMetaJson) {
    if (triggerMetaJson == null || triggerMetaJson.isBlank()) {
      return empty();
    }
    try {
      JsonNode root = JSON.readTree(triggerMetaJson);
      if (root == null || !root.isObject()) {
        return empty();
      }
      String branch = textOrNull(root.get("branch"));
      List<String> changed = new ArrayList<>();
      JsonNode files =
          root.has("changedFiles") ? root.get("changedFiles") : root.get("changed_files");
      if (files != null && files.isArray()) {
        for (JsonNode f : files) {
          if (f.isTextual() && !f.textValue().isBlank()) {
            changed.add(f.textValue());
          }
        }
      }
      return new WhenFacts(branch, List.copyOf(changed));
    } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
      return empty();
    }
  }

  @Nullable
  private static String textOrNull(@Nullable JsonNode node) {
    if (node == null || node.isNull() || !node.isTextual() || node.textValue().isBlank()) {
      return null;
    }
    return node.textValue();
  }
}
