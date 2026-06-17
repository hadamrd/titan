package io.adaptiq.titan.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.GithubPipelineDiscoveredRow;
import io.adaptiq.titan.store.rows.JobRow;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Decides whether a webhook event ({@code push} / {@code pull_request}) should dispatch a build for
 * a given {@link JobRow}, by parsing the {@code triggers[]} array stored in the matching {@code
 * titan.github_pipelines_discovered} row's {@code parsed_metadata}.
 *
 * <p><strong>Why it exists.</strong> Before this class, {@code GithubAppWebhookApi#handlePush}
 * enqueued every job linked to the repo, irrespective of the pipeline's declared {@code triggers:}
 * block. A repo with N pipelines therefore triggered N builds on every push, including pipelines
 * that the YAML explicitly said are {@code manual}-only or restricted to a different branch. This
 * was observed live on titan.test.example.com (8 builds on 1 push, 2 failures from
 * push-incompatible pipelines).
 *
 * <p><strong>Trigger-match semantics (V1, narrow — mirrors GitHub Actions).</strong>
 *
 * <ul>
 *   <li>A trigger is a JSON object like {@code {"type":"push","branch":"main"}}, {@code
 *       {"type":"push","branches":["main","release/*"]}}, {@code {"type":"pull_request"}}, {@code
 *       {"type":"manual"}}, or {@code {"type":"schedule","cron":"..."}}.
 *   <li>For a push event with branch {@code B}: dispatches iff a trigger has {@code type==push} AND
 *       (no branch filter OR the filter matches {@code B}).
 *   <li>For a pull_request event: dispatches iff a trigger has {@code type==pull_request}. No PR
 *       action filter for V1.
 *   <li>{@code type==manual} NEVER matches a webhook event.
 *   <li>{@code type==schedule} NEVER matches a webhook event (the cron scheduler dispatches those).
 *   <li>Pipeline has NO {@code triggers:} block (empty list) → fires on every push (matches the
 *       GitLab / CircleCI default; deliberately not GitHub Actions's "must declare {@code on:}"
 *       rule because real Titan pipelines often omit the stanza meaning "just run on push").
 *   <li>Job has NO discovered pipeline row (legacy / manually-onboarded jobs that pre-date design
 *       66) → fall through and dispatch, preserving back-compat with the original behaviour.
 * </ul>
 *
 * <p><strong>Branch glob.</strong> Filters support a single wildcard character {@code *} which
 * matches any sequence of characters (including {@code /}). E.g. {@code "release/*"} matches {@code
 * "release/1.2"} AND {@code "release/foo/bar"}. No regex, no {@code !negate}, no path filters —
 * that's the V1 stopping line.
 */
public final class WebhookTriggerMatcher {

  private static final Logger LOGGER = Logger.getLogger(WebhookTriggerMatcher.class.getName());
  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** Event types this matcher understands. */
  enum EventType {
    PUSH,
    PULL_REQUEST
  }

  private final TitanStores stores;

  public WebhookTriggerMatcher(@NonNull TitanStores stores) {
    this.stores = stores;
  }

  /**
   * Decide whether {@code job} should dispatch on the given event.
   *
   * @param job the job linked to the repo by {@code (github_installation_id, github_repo_id)}.
   * @param event the webhook event class.
   * @param branch the branch of the event (push branch, or PR head ref).
   * @return {@code true} iff at least one trigger in the matching discovered-pipeline row matches
   *     the event. If no discovered row exists for this job, returns {@code true} for back-compat
   *     with pre-design-66 manually-onboarded jobs.
   */
  boolean shouldDispatch(@NonNull JobRow job, @NonNull EventType event, @NonNull String branch) {
    Optional<GithubPipelineDiscoveredRow> rowOpt = findDiscoveredRow(job, branch);
    if (rowOpt.isEmpty()) {
      // No discovered row — likely a legacy manually-onboarded job. Preserve the old behaviour:
      // dispatch. (Design-66 jobs always have a discovered row, so this branch only catches
      // pre-66 jobs.)
      LOGGER.log(
          Level.FINE,
          "[github-app] no discovered row for job {0} — dispatch (legacy)",
          new Object[] {job.fullName});
      return true;
    }
    GithubPipelineDiscoveredRow row = rowOpt.get();
    List<JsonNode> triggers = extractTriggers(row);
    if (triggers.isEmpty()) {
      // No declared triggers → default to "fires on every push" (matches GitLab CI / CircleCI
      // defaults; opposite of GitHub Actions which requires an explicit `on:` block). We picked
      // the permissive default because real-world Titan pipelines often omit the triggers stanza
      // when they want "build on every push" — making that the implicit shape avoids a sea of
      // dead pipelines on first install. PR events do NOT fall through (avoid double-firing on
      // PRs with same-branch pushes); schedule / manual obviously never reach this code path.
      return event == EventType.PUSH;
    }
    for (JsonNode t : triggers) {
      if (triggerMatches(t, event, branch)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Pick the discovered-pipeline row that backs this job. The job's {@code config_json} carries the
   * filename the scanner persisted (see {@code GithubRepoScanner#syncJobsForRepo}); the row is
   * keyed by {@code (repoId, branch, filename)}. Try the event branch first; fall back to any row
   * matching the filename for this repo (handles PRs whose head branch was never scanned).
   */
  @NonNull
  Optional<GithubPipelineDiscoveredRow> findDiscoveredRow(
      @NonNull JobRow job, @NonNull String eventBranch) {
    if (job.githubRepoId == null) {
      return Optional.empty();
    }
    String filename = extractFilename(job.configJson);
    if (filename == null || filename.isEmpty()) {
      return Optional.empty();
    }
    long repoId = job.githubRepoId;
    // 1) Prefer the row scanned against this exact branch (#887 branch-aware semantics).
    if (!eventBranch.isEmpty()) {
      for (GithubPipelineDiscoveredRow r :
          stores.githubPipelinesDiscovered().listByRepoAndBranch(repoId, eventBranch)) {
        if (filename.equals(r.filename)) {
          return Optional.of(r);
        }
      }
    }
    // 2) Fall back to any row with this filename for this repo (PR head ref not yet scanned, or
    // mismatched branch column for legacy rows).
    for (GithubPipelineDiscoveredRow r : stores.githubPipelinesDiscovered().listByRepo(repoId)) {
      if (filename.equals(r.filename)) {
        return Optional.of(r);
      }
    }
    return Optional.empty();
  }

  /**
   * Compute the names of declared {@code required=true} parameters that have no default and were
   * not supplied by the trigger source (issue #919). Webhook fan-in MUST skip enqueueing a build if
   * this list is non-empty — those builds are guaranteed to fail at parameter-bake time, and
   * spamming the build history with doomed rows obscures the real cause (a misconfigured pipeline
   * or a webhook flow that can't supply the parameter).
   *
   * <p>If no discovered row exists (legacy pre-design-66 manually onboarded job) we return an empty
   * list — there is no parameter schema to enforce, so dispatch behaviour is preserved.
   *
   * @param job the candidate job.
   * @param eventBranch the branch carried by the event (used to pick the right discovered row).
   * @param suppliedParamNames param names supplied by the trigger source (webhook fan-in supplies
   *     none; manual / API trigger supplies the explicit set). Note: implicit env from #847 ({@code
   *     GIT_COMMIT}, {@code GIT_BRANCH}, {@code BUILD_NUMBER}, {@code BUILD_ID}) is NOT a parameter
   *     — it flows through {@code trigger_meta_json} → implicit env, never through {@code
   *     parameters:} — so it is intentionally absent from this set.
   * @return ordered list of missing param names (empty if all required-no-default params are
   *     satisfied or if no schema is available).
   */
  @NonNull
  public List<String> unsatisfiedRequiredParams(
      @NonNull JobRow job, @NonNull String eventBranch, @NonNull Set<String> suppliedParamNames) {
    Optional<GithubPipelineDiscoveredRow> rowOpt = findDiscoveredRow(job, eventBranch);
    if (rowOpt.isEmpty()) {
      return List.of();
    }
    return unsatisfiedRequiredParams(rowOpt.get(), suppliedParamNames);
  }

  /** Variant that takes the already-resolved discovered row — exposed for unit testing. */
  @NonNull
  static List<String> unsatisfiedRequiredParams(
      @NonNull GithubPipelineDiscoveredRow row, @NonNull Set<String> suppliedParamNames) {
    if (row.parsedMetadata == null || row.parsedMetadata.isBlank()) {
      return List.of();
    }
    try {
      JsonNode meta = MAPPER.readTree(row.parsedMetadata);
      JsonNode params = meta.get("parameters");
      if (params == null || !params.isArray()) {
        return List.of();
      }
      List<String> missing = new ArrayList<>();
      for (JsonNode p : params) {
        if (p == null || !p.isObject()) {
          continue;
        }
        boolean required = p.path("required").asBoolean(false);
        if (!required) {
          continue;
        }
        boolean hasDefault = p.path("hasDefault").asBoolean(false);
        if (hasDefault) {
          continue;
        }
        JsonNode nameNode = p.get("name");
        if (nameNode == null || !nameNode.isTextual()) {
          continue;
        }
        String name = nameNode.asText();
        if (name.isEmpty() || suppliedParamNames.contains(name)) {
          continue;
        }
        missing.add(name);
      }
      return missing;
    } catch (Exception e) {
      LOGGER.log(
          Level.FINE,
          "[github-app] could not parse parameters for {0}: {1}",
          new Object[] {row.filename, e.getMessage()});
      return List.of();
    }
  }

  // ── trigger parsing & matching ─────────────────────────────────────────────

  @NonNull
  private static List<JsonNode> extractTriggers(@NonNull GithubPipelineDiscoveredRow row) {
    if (row.parsedMetadata == null || row.parsedMetadata.isBlank()) {
      return List.of();
    }
    try {
      JsonNode meta = MAPPER.readTree(row.parsedMetadata);
      JsonNode trig = meta.get("triggers");
      if (trig == null || !trig.isArray()) {
        return List.of();
      }
      List<JsonNode> out = new java.util.ArrayList<>(trig.size());
      for (JsonNode t : trig) {
        out.add(t);
      }
      return out;
    } catch (Exception e) {
      LOGGER.log(
          Level.FINE,
          "[github-app] could not parse triggers for {0}: {1}",
          new Object[] {row.filename, e.getMessage()});
      return List.of();
    }
  }

  static boolean triggerMatches(
      @NonNull JsonNode trigger, @NonNull EventType event, @NonNull String branch) {
    String type = triggerType(trigger);
    if (type == null) {
      return false;
    }
    return switch (type) {
      case "push" -> event == EventType.PUSH && branchFilterMatches(trigger, branch);
      case "pull_request" -> event == EventType.PULL_REQUEST;
        // manual / schedule / anything else — never fires on a webhook event.
      default -> false;
    };
  }

  @Nullable
  private static String triggerType(@NonNull JsonNode trigger) {
    if (trigger.isTextual()) {
      // Allow shorthand "push" / "pull_request" — though the grammar generally emits objects.
      return trigger.asText().toLowerCase(java.util.Locale.ROOT);
    }
    JsonNode t = trigger.get("type");
    if (t == null || !t.isTextual()) {
      return null;
    }
    return t.asText().toLowerCase(java.util.Locale.ROOT);
  }

  /**
   * @return {@code true} if the trigger's branch filter (if any) matches {@code branch}. No filter
   *     means "all branches match".
   */
  static boolean branchFilterMatches(@NonNull JsonNode trigger, @NonNull String branch) {
    JsonNode branchNode = trigger.get("branch");
    JsonNode branchesNode = trigger.get("branches");
    if (branchNode == null && branchesNode == null) {
      return true; // unfiltered
    }
    if (branchNode != null && branchNode.isTextual()) {
      if (globMatches(branchNode.asText(), branch)) {
        return true;
      }
    }
    if (branchesNode != null && branchesNode.isArray()) {
      for (JsonNode b : branchesNode) {
        if (b.isTextual() && globMatches(b.asText(), branch)) {
          return true;
        }
      }
    }
    if (branchNode != null && branchNode.isArray()) {
      // Defensive: a YAML author wrote `branch: [a, b]` instead of `branches:`.
      for (JsonNode b : branchNode) {
        if (b.isTextual() && globMatches(b.asText(), branch)) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * Glob match: {@code *} matches any sequence (greedy, including {@code /}). All other characters
   * are literal. Translated to a regex {@code .*} so we stay within {@link Pattern} and don't
   * import an Ant/Maven globber.
   */
  public static boolean globMatches(@NonNull String pattern, @NonNull String value) {
    if (pattern.equals(value)) {
      return true;
    }
    if (pattern.indexOf('*') < 0) {
      return false;
    }
    StringBuilder regex = new StringBuilder(pattern.length() + 4);
    for (int i = 0; i < pattern.length(); i++) {
      char c = pattern.charAt(i);
      if (c == '*') {
        regex.append(".*");
      } else {
        regex.append(Pattern.quote(String.valueOf(c)));
      }
    }
    return value.matches(regex.toString());
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  /** Extract {@code "filename"} from a job's {@code config_json} blob. Null-safe. */
  @Nullable
  static String extractFilename(@Nullable String configJson) {
    if (configJson == null || configJson.isBlank()) {
      return null;
    }
    try {
      JsonNode root = MAPPER.readTree(configJson);
      JsonNode f = root.get("filename");
      if (f != null && f.isTextual()) {
        return f.asText();
      }
    } catch (Exception ignored) {
      // Legacy configJson may not be JSON at all — that's fine, we just have no filename to match.
    }
    return null;
  }
}
