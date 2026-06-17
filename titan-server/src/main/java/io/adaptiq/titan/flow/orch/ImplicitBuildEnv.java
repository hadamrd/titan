package io.adaptiq.titan.flow.orch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.adaptiq.titan.store.rows.BuildRow;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Computes the <strong>implicit build environment</strong> Titan injects into every step's env at
 * dispatch (closes #847).
 *
 * <p>Implicit env vars are facts <em>about the build itself</em> — the commit SHA that kicked it
 * off, the branch, the build number, the build id. They are <strong>always present</strong>,
 * regardless of whether the user's pipeline YAML declared anything, and they are
 * <strong>not</strong> routed through {@code parameters:}. The original bug: discovery-triggered
 * builds wrote {@code GIT_COMMIT} into {@code parameters_json}, but {@link
 * io.adaptiq.titan.flow.ParameterResolver} fails the bake with <em>"parameter 'GIT_COMMIT' was
 * supplied but is not declared by the pipeline"</em> whenever the YAML didn't pre-declare it —
 * which is most V1-golden-path pipelines.
 *
 * <p>The fix is to route the SHA / branch through {@code trigger_meta_json} (the column the GitHub
 * App webhook already writes for the same purpose) and then surface it back as implicit env right
 * before the worker dispatch. {@code parameters:} stays exclusively user-owned; implicit env is
 * engine-owned and overrides any user attempt to set the same key.
 *
 * <p><strong>Engine-reserved names</strong> (subject to the same rule as {@code TITAN_*}):
 *
 * <ul>
 *   <li>{@code GIT_COMMIT} — full commit SHA (or short SHA for webhook builds — whatever the
 *       trigger source captured)
 *   <li>{@code GIT_BRANCH} — branch name (no {@code refs/heads/} prefix)
 *   <li>{@code BUILD_NUMBER} — the per-job build number
 *   <li>{@code BUILD_ID} — the global build id (alias for BUILD_NUMBER kept for
 *       backward-compatibility, but is the global id, not the per-job number)
 * </ul>
 *
 * <p>Any field whose value is absent / blank is simply omitted from the map — the worker only
 * exports the keys we actually set, so a missing GIT_BRANCH never surfaces as the empty string in a
 * shell step.
 */
public final class ImplicitBuildEnv {

  private static final ObjectMapper JSON = new ObjectMapper();

  private ImplicitBuildEnv() {}

  /**
   * Build the implicit env map for one build. Reads {@code trigger_meta_json} for commit SHA +
   * branch; stamps {@code BUILD_NUMBER} and {@code BUILD_ID} from the row's own columns.
   *
   * @return a fresh ordered map; never null, possibly empty (if the build has no trigger metadata
   *     and somehow no build number — defensive only)
   */
  @NonNull
  public static Map<String, String> forBuild(@NonNull BuildRow build) {
    Map<String, String> env = new LinkedHashMap<>();
    putIfPresent(env, "GIT_COMMIT", extract(build.triggerMetaJson, "commitSha"));
    putIfPresent(env, "GIT_BRANCH", extract(build.triggerMetaJson, "branch"));
    // Build identifiers are always available (the row is, by definition, persisted).
    env.put("BUILD_NUMBER", Integer.toString(build.buildNumber));
    env.put("BUILD_ID", Long.toString(build.id));
    return env;
  }

  /**
   * Extract a single text field from a {@code trigger_meta_json} blob. Returns null if the JSON is
   * absent / blank / malformed / does not contain the field — the caller must handle null.
   */
  @Nullable
  private static String extract(@Nullable String triggerMetaJson, @NonNull String field) {
    if (triggerMetaJson == null || triggerMetaJson.isBlank()) {
      return null;
    }
    try {
      JsonNode root = JSON.readTree(triggerMetaJson);
      JsonNode node = root.path(field);
      if (node.isMissingNode() || node.isNull()) {
        return null;
      }
      String value = node.asText("");
      return value.isBlank() ? null : value;
    } catch (Exception e) {
      // Malformed trigger_meta_json must not break dispatch. Treat as absent.
      return null;
    }
  }

  private static void putIfPresent(
      @NonNull Map<String, String> out, @NonNull String key, @Nullable String value) {
    if (value != null) {
      out.put(key, value);
    }
  }
}
