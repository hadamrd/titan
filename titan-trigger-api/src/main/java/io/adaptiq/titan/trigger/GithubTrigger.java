package io.adaptiq.titan.trigger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;

/**
 * A {@link Trigger} that fires when a verified GitHub webhook delivery arrives (issue #397).
 *
 * <p>Unlike {@link CronTrigger}, this trigger is <em>not</em> polled by the firing engine — it
 * fires only in response to an inbound delivery routed by {@code GithubWebhookApi} (see {@code
 * titan-server/src/main/java/io/adaptiq/titan/api/triggers/GithubWebhookApi.java}). The server
 * resolves the matching job by {@code repository.full_name}, then locates this trigger on that job,
 * recomputes HMAC-SHA256 against the secret resolved via {@link
 * io.adaptiq.titan.credentials.CredentialsService} (by {@link #getCredentialsId() credentialsId})
 * and dispatches a build when the signature matches and the event's branch matches one of the
 * configured {@link #getBranches() branch globs}.
 *
 * <p>Secret storage: the HMAC secret is NEVER stored in plaintext on the trigger; only the {@code
 * credentialsId} addressing tuple is persisted in {@code config_json}, mirroring the constitution
 * rule for credential-aware steps.
 *
 * <p>Defaults: when {@code events} is omitted the trigger accepts {@code push} only — the
 * lowest-surprise default for "CI on every push".
 */
public class GithubTrigger extends Trigger {

  /** The DTO discriminator — see {@link TriggerCodec}. */
  public static final String TYPE = "github";

  /** The {@code X-GitHub-Event} value for a push delivery. */
  public static final String EVENT_PUSH = "push";

  /** The {@code X-GitHub-Event} value for a pull-request delivery. */
  public static final String EVENT_PULL_REQUEST = "pull_request";

  private final List<String> branches;
  private final List<String> events;
  private final String credentialsId;

  public GithubTrigger(
      @CheckForNull String id,
      @CheckForNull List<String> branches,
      @CheckForNull List<String> events,
      @CheckForNull String credentialsId) {
    super(id);
    this.branches = branches == null ? List.of() : List.copyOf(branches);
    this.events = (events == null || events.isEmpty()) ? List.of(EVENT_PUSH) : List.copyOf(events);
    this.credentialsId = credentialsId == null ? "" : credentialsId.trim();
  }

  /**
   * Branch glob patterns this trigger fires on; an empty list means "any branch". Patterns follow
   * the {@code GlobMatcher} convention used elsewhere in Titan (e.g. {@code trunk}, {@code
   * feat/**}).
   */
  @NonNull
  public List<String> getBranches() {
    return branches;
  }

  /**
   * The {@code X-GitHub-Event} types this trigger accepts (e.g. {@code push}, {@code
   * pull_request}). Defaults to {@code [push]}.
   */
  @NonNull
  public List<String> getEvents() {
    return events;
  }

  /**
   * The credentials-store id of the HMAC secret. Required at config time; resolved at receive time
   * via {@code CredentialsService.resolvePlaintext("github-webhook", credentialsId)}.
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
    // A GitHub trigger never fires on a polling tick — only on an HTTP delivery handled by
    // GithubWebhookApi, which short-circuits this class. The polling-firing engine sees the
    // trigger and skips it.
    return TriggerOutcome.skip("github trigger fires only on inbound webhook delivery");
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

  /** Read {@code branches}/{@code events} JSON arrays into a copy-on-write list. */
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

  /** {@link Trigger} descriptor for the GitHub trigger. */
  public static class DescriptorImpl extends TriggerDescriptor {

    @Override
    @NonNull
    public String triggerType() {
      return TYPE;
    }

    @Override
    @NonNull
    public Trigger readState(@CheckForNull String id, @NonNull JsonNode node) {
      return new GithubTrigger(
          id,
          readStringArray(node, "branches"),
          readStringArray(node, "events"),
          node.path("credentialsId").asText(""));
    }
  }
}
