package io.adaptiq.titan.api.triggers.bitbucket;

import com.fasterxml.jackson.databind.JsonNode;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Objects;

/**
 * The structured projection of a Bitbucket Cloud webhook body — the small set of scalar fields the
 * webhook handler routes on (issue #1079).
 *
 * <p>This is the "extract once, branch on type" half of the discriminated-union shape. The raw
 * payload is read by {@link #parse(BitbucketEvent, JsonNode)} into a typed value; the handler then
 * branches on {@link #event()}, never on the raw JSON shape — per the constitution's typed-config
 * rule.
 *
 * <p>Four fields are surfaced:
 *
 * <ul>
 *   <li>{@link #ref()} — the branch name that drives the matcher. For {@code repo:push} this is the
 *       pushed branch ({@code push.changes[].new.name} where {@code new.type == "branch"}); for a
 *       pull-request event it is the PR source (head) branch.
 *   <li>{@link #commitSha()} — the full commit hash, persisted into {@code trigger_meta_json}.
 *   <li>{@link #actor()} — the human-meaningful "who triggered this", for UI display.
 *   <li>{@link #prId()} — the Bitbucket PR id ({@code 0} for push events).
 * </ul>
 */
public final class BitbucketPayload {

  private final BitbucketEvent event;
  private final String ref;
  @Nullable private final String commitSha;
  @Nullable private final String actor;
  private final long prId;

  private BitbucketPayload(
      @NonNull BitbucketEvent event,
      @NonNull String ref,
      @Nullable String commitSha,
      @Nullable String actor,
      long prId) {
    this.event = Objects.requireNonNull(event);
    this.ref = Objects.requireNonNull(ref);
    this.commitSha = commitSha;
    this.actor = actor;
    this.prId = prId;
  }

  @NonNull
  public BitbucketEvent event() {
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
   * The Bitbucket pull-request id ({@code pullrequest.id}). {@code 0} for {@code repo:push} events
   * — downstream PR-comment / build-status reporters treat {@code 0} as "not a PR build, no-op".
   */
  public long prId() {
    return prId;
  }

  /**
   * Project a parsed JSON body into {@link BitbucketPayload}, or {@code null} when the payload is
   * not actionable (e.g. a {@code repo:push} delivery whose change is a tag rather than a branch,
   * or a pull-request body with no source branch).
   */
  @Nullable
  public static BitbucketPayload parse(@NonNull BitbucketEvent event, @NonNull JsonNode payload) {
    String actor = actor(payload);
    switch (event) {
      case REPO_PUSH:
        {
          JsonNode change = firstBranchChange(payload);
          if (change == null) {
            return null;
          }
          String branch = textOrEmpty(change, "new", "name");
          if (branch.isEmpty()) {
            return null;
          }
          String sha = nonEmpty(textOrEmpty(change, "new", "target", "hash"));
          return new BitbucketPayload(event, branch, sha, actor, 0L);
        }
      case PR_CREATED:
      case PR_UPDATED:
        {
          JsonNode pr = payload.path("pullrequest");
          String sourceBranch = textOrEmpty(pr, "source", "branch", "name");
          if (sourceBranch.isEmpty()) {
            return null;
          }
          String sha = nonEmpty(textOrEmpty(pr, "source", "commit", "hash"));
          long prId = pr.path("id").asLong(0L);
          return new BitbucketPayload(event, sourceBranch, sha, actor, prId);
        }
      case UNKNOWN:
      default:
        return null;
    }
  }

  /**
   * Find the first {@code push.changes[]} entry whose {@code new} ref is a branch. Bitbucket
   * batches multiple ref updates into one delivery; tag pushes and branch deletions ({@code new ==
   * null}) are skipped here — only branch updates drive a build.
   */
  @Nullable
  private static JsonNode firstBranchChange(@NonNull JsonNode payload) {
    JsonNode changes = payload.path("push").path("changes");
    if (!changes.isArray()) {
      return null;
    }
    for (JsonNode change : changes) {
      JsonNode newRef = change.path("new");
      if (newRef.isObject() && "branch".equals(textOrEmpty(newRef, "type"))) {
        return change;
      }
    }
    return null;
  }

  @Nullable
  private static String actor(@NonNull JsonNode payload) {
    String name = nonEmpty(textOrEmpty(payload, "actor", "display_name"));
    if (name == null) {
      name = nonEmpty(textOrEmpty(payload, "actor", "nickname"));
    }
    return name;
  }

  // ── helpers ────────────────────────────────────────────────────────────────

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
