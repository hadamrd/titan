package io.adaptiq.titan.trigger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;

/**
 * A {@link Trigger} that fires when a verified Bitbucket Cloud webhook delivery arrives (issue
 * #1079).
 *
 * <p>The shape mirrors {@link GithubTrigger}: a delivery routed by {@code BitbucketWebhookApi}
 * verifies the HMAC-SHA256 signature in {@code X-Hub-Signature} against the secret resolved via
 * {@link io.adaptiq.titan.credentials.CredentialsService} (by {@link #getCredentialsId()
 * credentialsId}) using a constant-time comparison, then dispatches a build when the delivery's
 * {@code X-Event-Key} maps to one of the configured {@link #getEvents() events} and the ref matches
 * one of the configured {@link #getBranches() refs}.
 *
 * <p>Unlike GitLab (which authenticates with a shared-secret token in {@code X-Gitlab-Token}),
 * Bitbucket signs the body — so this trigger reuses the GitHub HMAC verification model. The
 * discriminator {@code type} is {@value #TYPE}; the credentials are resolved under the {@code
 * bitbucket-webhook} scope.
 *
 * <p><strong>Security policy.</strong> The HMAC secret is NEVER stored on the trigger; only the
 * {@code credentialsId} addressing tuple is persisted in {@code config_json}. The expected secret
 * value is NEVER logged — see {@code BitbucketWebhookApi}.
 *
 * <p>Defaults: when {@code events} is omitted the trigger accepts {@code push} only — matching the
 * {@link GithubTrigger} / {@link GitlabTrigger} default for a "CI on every push" pipeline.
 *
 * <p>Discriminated union (per the constitution's typed-config rule): consumers branch on {@link
 * Trigger#getType()}, never on URL / repository shape.
 */
public class BitbucketTrigger extends Trigger {

  /** The DTO discriminator — see {@link TriggerCodec}. */
  public static final String TYPE = "bitbucket";

  /** The {@code X-Event-Key} value for a repository push delivery. */
  public static final String EVENT_REPO_PUSH = "repo:push";

  /** The {@code X-Event-Key} value for a pull-request-created delivery. */
  public static final String EVENT_PR_CREATED = "pullrequest:created";

  /** The {@code X-Event-Key} value for a pull-request-updated delivery. */
  public static final String EVENT_PR_UPDATED = "pullrequest:updated";

  /** Internal event-name alias as accepted on the trigger config for {@code repo:push}. */
  public static final String CFG_PUSH = "push";

  /**
   * Internal event-name alias accepted on the trigger config for {@code pullrequest:created} /
   * {@code pullrequest:updated} — both map to this single config alias.
   */
  public static final String CFG_PULL_REQUEST = "pull_request";

  private final List<String> branches;
  private final List<String> events;
  private final String credentialsId;

  public BitbucketTrigger(
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
   * <p>For {@code push} events the matcher applies to the pushed branch name; for {@code
   * pull_request} events it applies to the PR source (head) branch.
   */
  @NonNull
  public List<String> getBranches() {
    return branches;
  }

  /**
   * The Bitbucket event kinds this trigger accepts. Values are the internal config aliases: {@link
   * #CFG_PUSH}, {@link #CFG_PULL_REQUEST}. Defaults to {@code [push]}.
   */
  @NonNull
  public List<String> getEvents() {
    return events;
  }

  /**
   * The credentials-store id of the HMAC secret. Required at config time; resolved at receive time
   * via {@code CredentialsService.resolvePlaintext("bitbucket-webhook", credentialsId)}.
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
    // A Bitbucket trigger never fires on a polling tick — only on an HTTP delivery handled by
    // BitbucketWebhookApi.
    return TriggerOutcome.skip("bitbucket trigger fires only on inbound webhook delivery");
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

  /** {@link Trigger} descriptor for the Bitbucket trigger. */
  public static class DescriptorImpl extends TriggerDescriptor {

    @Override
    @NonNull
    public String triggerType() {
      return TYPE;
    }

    @Override
    @NonNull
    public Trigger readState(@CheckForNull String id, @NonNull JsonNode node) {
      return new BitbucketTrigger(
          id,
          readStringArray(node, "branches"),
          readStringArray(node, "events"),
          node.path("credentialsId").asText(""));
    }
  }
}
