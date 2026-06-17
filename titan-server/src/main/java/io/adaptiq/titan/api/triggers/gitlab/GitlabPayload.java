package io.adaptiq.titan.api.triggers.gitlab;

import com.fasterxml.jackson.databind.JsonNode;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Objects;
import java.util.Set;

/**
 * The structured projection of a GitLab webhook body — the small set of scalar fields the webhook
 * handler routes on (issue #1078).
 *
 * <p>This is the "extract once, branch on type" half of the discriminated-union shape. The raw
 * payload is read by {@link #parse(GitlabEvent, JsonNode)} into a typed value; the handler then
 * branches on {@link #event()}, never on the raw JSON shape — per the constitution's typed- config
 * rule.
 *
 * <p>Three fields are surfaced:
 *
 * <ul>
 *   <li>{@link #ref()} — the branch / tag / source-branch name that drives the matcher.
 *   <li>{@link #commitSha()} — the full 40-char SHA, persisted into {@code trigger_meta_json}.
 *   <li>{@link #actor()} — the human-meaningful "who triggered this", for UI display.
 * </ul>
 */
public final class GitlabPayload {

  /** MR actions that should enqueue a build. Closed / merged / etc. are intentionally ignored. */
  private static final Set<String> MR_BUILD_ACTIONS = Set.of("open", "reopen", "update");

  private final GitlabEvent event;
  private final String ref;
  @Nullable private final String commitSha;
  @Nullable private final String actor;
  private final long projectId;
  @Nullable private final String projectPath;
  private final long mrIid;

  private GitlabPayload(
      @NonNull GitlabEvent event,
      @NonNull String ref,
      @Nullable String commitSha,
      @Nullable String actor,
      long projectId,
      @Nullable String projectPath,
      long mrIid) {
    this.event = Objects.requireNonNull(event);
    this.ref = Objects.requireNonNull(ref);
    this.commitSha = commitSha;
    this.actor = actor;
    this.projectId = projectId;
    this.projectPath = projectPath;
    this.mrIid = mrIid;
  }

  /**
   * The numeric {@code project.id} from the webhook payload, used by the GitLab Commit-Status API
   * path {@code /projects/{id}/statuses/{sha}} (issue #1080). {@code 0} when the payload omitted it
   * — the status reporter then silently skips, matching GitHub's no-linkage behaviour.
   */
  public long projectId() {
    return projectId;
  }

  /**
   * The {@code project.path_with_namespace} (e.g. {@code group/sub/repo}), kept for diagnostics and
   * UI display. The status reporter prefers {@link #projectId()} because numeric ids never collide
   * across rename/move events.
   */
  @Nullable
  public String projectPath() {
    return projectPath;
  }

  @NonNull
  public GitlabEvent event() {
    return event;
  }

  @NonNull
  public String ref() {
    return ref;
  }

  @Nullable
  public String commitSha() {
    return commitSha;
  }

  @Nullable
  public String actor() {
    return actor;
  }

  /**
   * The merge-request {@code iid} (project-scoped internal id) from a Merge Request Hook's {@code
   * object_attributes.iid} (issue #1168). {@code 0} for push / tag-push / non-MR events — the MR
   * comment + review reporters treat {@code 0} as "not an MR build, no-op".
   */
  public long mrIid() {
    return mrIid;
  }

  /**
   * Project a parsed JSON body into {@link GitlabPayload}, or {@code null} when the payload is not
   * actionable (e.g. a Merge Request Hook with {@code action: close}).
   */
  @Nullable
  public static GitlabPayload parse(@NonNull GitlabEvent event, @NonNull JsonNode payload) {
    long projectId = extractProjectId(payload);
    String projectPath = nonEmpty(textOrEmpty(payload, "project", "path_with_namespace"));
    switch (event) {
      case PUSH:
        {
          String ref = stripRef(textOrEmpty(payload, "ref"), "refs/heads/");
          if (ref.isEmpty()) {
            return null;
          }
          String sha = nonEmpty(textOrEmpty(payload, "checkout_sha"));
          if (sha == null) {
            sha = nonEmpty(textOrEmpty(payload, "after"));
          }
          String actor = nonEmpty(textOrEmpty(payload, "user_username"));
          if (actor == null) {
            actor = nonEmpty(textOrEmpty(payload, "user_name"));
          }
          return new GitlabPayload(event, ref, sha, actor, projectId, projectPath, 0L);
        }
      case TAG_PUSH:
        {
          String tag = stripRef(textOrEmpty(payload, "ref"), "refs/tags/");
          if (tag.isEmpty()) {
            return null;
          }
          String sha = nonEmpty(textOrEmpty(payload, "checkout_sha"));
          if (sha == null) {
            sha = nonEmpty(textOrEmpty(payload, "after"));
          }
          String actor = nonEmpty(textOrEmpty(payload, "user_username"));
          if (actor == null) {
            actor = nonEmpty(textOrEmpty(payload, "user_name"));
          }
          return new GitlabPayload(event, tag, sha, actor, projectId, projectPath, 0L);
        }
      case MERGE_REQUEST:
        {
          JsonNode attrs = payload.path("object_attributes");
          String action = textOrEmpty(attrs, "action");
          if (!MR_BUILD_ACTIONS.contains(action.toLowerCase(java.util.Locale.ROOT))) {
            return null;
          }
          String sourceBranch = textOrEmpty(attrs, "source_branch");
          if (sourceBranch.isEmpty()) {
            return null;
          }
          String sha = nonEmpty(textOrEmpty(attrs, "last_commit", "id"));
          String actor = nonEmpty(textOrEmpty(payload, "user", "username"));
          if (actor == null) {
            actor = nonEmpty(textOrEmpty(payload, "user", "name"));
          }
          long mrIid = attrs.path("iid").asLong(0L);
          return new GitlabPayload(event, sourceBranch, sha, actor, projectId, projectPath, mrIid);
        }
      case UNKNOWN:
      default:
        return null;
    }
  }

  /**
   * Read {@code project.id} as a {@code long}. GitLab emits this as an integer; we tolerate textual
   * "12345" too for resilience against non-stock proxies. Returns {@code 0} when absent /
   * unparsable — the reporter treats {@code 0} as "no linkage, silently skip".
   */
  private static long extractProjectId(@NonNull JsonNode payload) {
    JsonNode project = payload.get("project");
    if (project == null || !project.isObject()) {
      return 0L;
    }
    JsonNode id = project.get("id");
    if (id == null) {
      return 0L;
    }
    if (id.isIntegralNumber()) {
      return id.asLong();
    }
    if (id.isTextual()) {
      try {
        return Long.parseLong(id.asText());
      } catch (NumberFormatException ignored) {
        return 0L;
      }
    }
    return 0L;
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  @NonNull
  private static String stripRef(@NonNull String ref, @NonNull String prefix) {
    if (!ref.startsWith(prefix)) {
      return "";
    }
    return ref.substring(prefix.length());
  }

  @NonNull
  private static String textOrEmpty(@NonNull JsonNode parent, @NonNull String field) {
    JsonNode v = parent.get(field);
    return (v == null || !v.isTextual()) ? "" : v.asText();
  }

  /** Nested path read — each segment is descended via {@code path}, returning "" on absence. */
  @NonNull
  private static String textOrEmpty(@NonNull JsonNode parent, @NonNull String... path) {
    JsonNode cur = parent;
    for (String seg : path) {
      cur = cur.path(seg);
    }
    return cur.isTextual() ? cur.asText() : "";
  }

  @Nullable
  private static String nonEmpty(@Nullable String v) {
    return (v == null || v.isEmpty()) ? null : v;
  }
}
