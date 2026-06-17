package io.adaptiq.titan.trigger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;

/**
 * A {@link Trigger} that fires when a verified GitLab webhook delivery arrives (issue #1078).
 *
 * <p>The shape mirrors {@link GithubTrigger}: a delivery routed by {@code GitlabWebhookApi}
 * verifies the shared-secret token in {@code X-Gitlab-Token} against the secret resolved via {@link
 * io.adaptiq.titan.credentials.CredentialsService} (by {@link #getCredentialsId() credentialsId})
 * using a constant-time comparison, then dispatches a build when the event's kind matches one of
 * the configured {@link #getEvents() events} and (for push / tag-push) the ref matches one of the
 * configured {@link #getBranches() refs}.
 *
 * <p><strong>Security policy.</strong> The shared secret is NEVER stored on the trigger; only the
 * {@code credentialsId} addressing tuple is persisted in {@code config_json}. The expected token
 * value is NEVER logged — see {@code GitlabWebhookApi}.
 *
 * <p>Defaults: when {@code events} is omitted the trigger accepts {@code push} only — matching the
 * {@link GithubTrigger} default for a "CI on every push" pipeline.
 *
 * <p>Discriminated union (per the constitution's typed-config rule): consumers branch on {@link
 * Trigger#getType()}, never on URL / repository shape.
 */
public class GitlabTrigger extends Trigger {

  /** The DTO discriminator — see {@link TriggerCodec}. */
  public static final String TYPE = "gitlab";

  /** The {@code X-Gitlab-Event} value for a push delivery. */
  public static final String EVENT_PUSH = "Push Hook";

  /** The {@code X-Gitlab-Event} value for a merge-request delivery. */
  public static final String EVENT_MERGE_REQUEST = "Merge Request Hook";

  /** The {@code X-Gitlab-Event} value for a tag-push delivery. */
  public static final String EVENT_TAG_PUSH = "Tag Push Hook";

  /** Internal event-name aliases as accepted on the trigger config. */
  public static final String CFG_PUSH = "push";

  public static final String CFG_MERGE_REQUEST = "merge_request";
  public static final String CFG_TAG_PUSH = "tag_push";

  private final List<String> branches;
  private final List<String> events;
  private final String credentialsId;

  public GitlabTrigger(
      @CheckForNull String id,
      @CheckForNull List<String> branches,
      @CheckForNull List<String> events,
      @CheckForNull String credentialsId) {
    super(id);
    this.branches = branches == null ? List.of() : List.copyOf(branches);
    this.events = (events == null || events.isEmpty()) ? List.of(CFG_PUSH) : List.copyOf(events);
    this.credentialsId = credentialsId == null ? "" : credentialsId.trim();
  }

  /**
   * Ref / branch glob patterns this trigger fires on; an empty list means "any ref". Patterns
   * follow Titan's standard glob convention (e.g. {@code trunk}, {@code feat/**}).
   *
   * <p>For {@code tag_push} events the matcher applies to the tag name (after stripping {@code
   * refs/tags/}); for {@code push} events it applies to the branch name (after stripping {@code
   * refs/heads/}); for {@code merge_request} events it applies to the source branch.
   */
  @NonNull
  public List<String> getBranches() {
    return branches;
  }

  /**
   * The GitLab event kinds this trigger accepts. Values are the internal config aliases: {@link
   * #CFG_PUSH}, {@link #CFG_MERGE_REQUEST}, {@link #CFG_TAG_PUSH}. Defaults to {@code [push]}.
   */
  @NonNull
  public List<String> getEvents() {
    return events;
  }

  /**
   * The credentials-store id of the shared-secret token. Required at config time; resolved at
   * receive time via {@code CredentialsService.resolvePlaintext("gitlab-webhook", credentialsId)}.
   */
  @NonNull
  public String getCredentialsId() {
    return credentialsId;
  }

  @Override
  @NonNull
  public String getType() {
    return TYPE;
  }

  @Override
  @NonNull
  public TriggerOutcome evaluate(@NonNull TriggerContext ctx) {
    // A GitLab trigger never fires on a polling tick — only on an HTTP delivery handled by
    // GitlabWebhookApi.
    return TriggerOutcome.skip("gitlab trigger fires only on inbound webhook delivery");
  }

  @Override
  public void writeState(@NonNull ObjectNode node) {
    ArrayNode b = node.putArray("branches");
    for (String pat : branches) {
      b.add(pat);
    }
    ArrayNode e = node.putArray("events");
    for (String ev : events) {
      e.add(ev);
    }
    node.put("credentialsId", credentialsId);
  }

  /**
   * Read a JSON array of strings out of {@code field}, returning an empty list on absence/error.
   */
  @NonNull
  private static List<String> readStringArray(@NonNull JsonNode parent, @NonNull String field) {
    JsonNode arr = parent.get(field);
    if (arr == null || !arr.isArray()) {
      return List.of();
    }
    List<String> out = new ArrayList<>(arr.size());
    for (JsonNode v : arr) {
      if (v != null && v.isTextual()) {
        out.add(v.asText());
      }
    }
    return out;
  }

  /** {@link Trigger} descriptor for the GitLab trigger. */
  public static class DescriptorImpl extends TriggerDescriptor {

    @Override
    @NonNull
    public String triggerType() {
      return TYPE;
    }

    @Override
    @NonNull
    public Trigger readState(@CheckForNull String id, @NonNull JsonNode node) {
      return new GitlabTrigger(
          id,
          readStringArray(node, "branches"),
          readStringArray(node, "events"),
          node.path("credentialsId").asText(""));
    }
  }
}
