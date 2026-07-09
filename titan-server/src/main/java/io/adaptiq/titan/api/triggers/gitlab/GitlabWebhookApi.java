package io.adaptiq.titan.api.triggers.gitlab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.adaptiq.titan.api.WebhookPayloadParams;
import io.adaptiq.titan.api.WebhookTriggerMatcher;
import io.adaptiq.titan.credentials.CredentialsService;
import io.adaptiq.titan.job.Job;
import io.adaptiq.titan.job.JobService;
import io.adaptiq.titan.store.TaskQueueDao;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.BuildRow;
import io.adaptiq.titan.store.rows.JobRow;
import io.adaptiq.titan.store.rows.TaskQueueRow;
import io.adaptiq.titan.trigger.GitlabTrigger;
import io.adaptiq.titan.trigger.Trigger;
import io.adaptiq.titan.trigger.TriggerCodec;
import jakarta.annotation.security.PermitAll;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Jakarta REST resource: {@code POST /api/v1/triggers/gitlab} — receives a GitLab webhook delivery,
 * verifies its {@code X-Gitlab-Token} shared-secret token against the secret stored on the matching
 * job's {@link GitlabTrigger}, and enqueues a build when the event kind / ref match (issue #1078).
 *
 * <p><strong>Auth model.</strong> {@code @PermitAll} — GitLab does not speak OIDC. The only
 * authentication is the shared-secret in {@code X-Gitlab-Token}, compared in constant time ({@link
 * MessageDigest#isEqual}) against the credential resolved from the per-trigger {@link
 * GitlabTrigger#getCredentialsId() credentialsId}. The expected token is NEVER logged.
 *
 * <p><strong>Event dispatch.</strong> The {@code X-Gitlab-Event} header drives a discriminated
 * union: {@code Push Hook} → build the pushed branch / SHA; {@code Merge Request Hook} with action
 * {@code open}/{@code reopen}/{@code update} → build the MR head; {@code Tag Push Hook} → build the
 * tag. Anything else returns {@code 200 {ok:true, ignored:"<kind>"}} — a forward-compat no-op.
 * Missing / unparseable {@code object_kind} returns {@code 400} with a structured problem-detail
 * body and never NPEs.
 *
 * <p><strong>Lookup model.</strong> Mirrors {@code GithubWebhookApi}: scan every job whose {@code
 * config_json} carries a GitLab trigger whose ref-pattern matches the delivery, then for each
 * candidate resolve its per-trigger token and compare. Discriminated by {@link Trigger#getType()} —
 * never by URL / repo sniffing.
 */
@Path("/api/v1/triggers/gitlab")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
@PermitAll
public class GitlabWebhookApi {

  private static final Logger LOGGER = Logger.getLogger(GitlabWebhookApi.class.getName());

  /** The credentials scope used to look up GitLab webhook shared-secret tokens. */
  static final String CREDENTIALS_SCOPE = "gitlab-webhook";

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final TitanStores stores;
  private final JobService jobs;
  private final CredentialsService credentials;
  private final WebhookTriggerMatcher triggerMatcher;

  GitlabWebhookApi(TitanStores stores, JobService jobs, CredentialsService credentials) {
    this.stores = stores;
    this.jobs = jobs;
    this.credentials = credentials;
    this.triggerMatcher = new WebhookTriggerMatcher(stores);
  }

  // ── POST /api/v1/triggers/gitlab ────────────────────────────────────────────

  @POST
  @Consumes(MediaType.APPLICATION_JSON)
  public Response receive(@jakarta.ws.rs.core.Context HttpHeaders headers, byte[] body) {
    String token = firstHeader(headers, "X-Gitlab-Token");
    String eventHeader = firstHeader(headers, "X-Gitlab-Event");
    byte[] rawBody = body == null ? new byte[0] : body;

    if (token == null || token.isBlank()) {
      return unauthorized("X-Gitlab-Token header missing", "missing_token");
    }
    if (eventHeader == null || eventHeader.isBlank()) {
      return badRequest("X-Gitlab-Event header missing", "missing_event_header");
    }

    // Parse the body. GitLab guarantees JSON on every event, so a malformed body is a 400 —
    // unlike GitHub, GitLab does NOT retry on 5xx by default, so we can return the structured
    // error without fearing a retry storm.
    JsonNode payload;
    try {
      payload = MAPPER.readTree(rawBody);
    } catch (IOException e) {
      LOGGER.log(Level.FINE, "[gitlab-webhook] body is not JSON", e);
      return badRequest("request body is not valid JSON", "malformed_json");
    }
    if (payload == null || !payload.isObject()) {
      return badRequest("request body must be a JSON object", "malformed_json");
    }

    // GitLab payloads carry an `object_kind` field that mirrors X-Gitlab-Event. We accept
    // either, but a missing object_kind on a Push / Merge Request / Tag Push delivery is a
    // contract violation by the sender — 400 with structured detail, never an NPE.
    JsonNode kindNode = payload.get("object_kind");
    String objectKind = (kindNode == null || !kindNode.isTextual()) ? "" : kindNode.asText();
    GitlabEvent event = GitlabEvent.fromHeaderOrKind(eventHeader, objectKind);
    if (event == GitlabEvent.UNKNOWN) {
      return ok(false, "unsupported event kind", eventHeader);
    }
    if (event.requiresObjectKind() && objectKind.isEmpty()) {
      return badRequest(
          "payload missing required 'object_kind' field for " + eventHeader, "missing_object_kind");
    }

    // Discriminated extraction — never sniff URL / repository shape; branch on event type.
    GitlabPayload parsed = GitlabPayload.parse(event, payload);
    if (parsed == null) {
      return ok(false, "event payload not actionable (e.g. MR action != open/sync)", eventHeader);
    }

    // Find every (job, trigger) whose GitLab trigger accepts this event + ref.
    List<JobTriggerMatch> candidates = matchingTriggers(event, parsed.ref());
    if (candidates.isEmpty()) {
      return ok(false, "no job matches ref '" + parsed.ref() + "'", eventHeader);
    }

    // The status reporter resolves the post-back token via the SAME credentialsId the inbound
    // webhook trigger uses (issue #1080). Multiple matched triggers MUST share a webhook secret
    // to all have verified the same X-Gitlab-Token, so picking the first match's credentialsId is
    // safe — they're equivalent for this delivery.
    @Nullable
    String matchedCredentialsId =
        candidates.isEmpty() ? null : candidates.get(0).trigger.getCredentialsId();
    String triggerMetaJson = buildTriggerMetaJson(parsed, matchedCredentialsId);

    int enqueued = 0;
    int enqueueFailures = 0;
    boolean anyVerified = false;
    for (JobTriggerMatch m : candidates) {
      Optional<String> secret =
          credentials.resolvePlaintext(CREDENTIALS_SCOPE, m.trigger.getCredentialsId());
      if (secret.isEmpty() || secret.get().isEmpty()) {
        LOGGER.log(
            Level.WARNING,
            "[gitlab-webhook] job {0}: credentialsId ''{1}'' did not resolve — skipping",
            new Object[] {m.job.fullName(), m.trigger.getCredentialsId()});
        continue;
      }
      // SECURITY: constant-time compare. NEVER log expected or actual token bytes.
      if (!constantTimeEquals(secret.get(), token)) {
        continue;
      }
      anyVerified = true;
      if (!m.job.enabled()) {
        continue;
      }
      Optional<JobRow> rowOpt = stores.jobs().findById(m.job.id());
      if (rowOpt.isPresent()) {
        Set<String> supplied = WebhookPayloadParams.suppliedKeys(payload, event.shortName());
        List<String> missing =
            triggerMatcher.unsatisfiedRequiredParams(rowOpt.get(), parsed.ref(), supplied);
        if (!missing.isEmpty()) {
          LOGGER.log(
              Level.INFO,
              "pipeline_skipped reason=missing_required_params pipeline={0} missing={1} trigger={2}",
              new Object[] {m.job.fullName(), missing, event.shortName()});
          continue;
        }
      }
      // NEVER swallow an enqueue failure into a 2xx (issue #69): GitLab only retries on non-2xx,
      // so a swallowed insert failure is a silently lost event. Log at SEVERE with the cause
      // chain, keep trying the remaining candidates, and answer 500 below.
      try {
        enqueueBuild(m.job.id(), triggerMetaJson);
        enqueued++;
      } catch (RuntimeException e) {
        enqueueFailures++;
        LOGGER.log(
            Level.SEVERE,
            "[gitlab-webhook] failed to enqueue build for job "
                + m.job.fullName()
                + " — delivery"
                + " will be answered 500 so the sender retries (issue #69)",
            e);
      }
    }

    if (!anyVerified) {
      return unauthorized("X-Gitlab-Token did not match any configured secret", "invalid_token");
    }
    if (enqueueFailures > 0) {
      return problem(
          500,
          "Webhook build enqueue failed",
          "failed to enqueue "
              + enqueueFailures
              + " build(s) ("
              + enqueued
              + " enqueued) — retry the delivery",
          "enqueue_failed");
    }
    return ok(enqueued > 0, "enqueued " + enqueued + " build(s)", eventHeader);
  }

  // ── matching ────────────────────────────────────────────────────────────────

  @NonNull
  private List<JobTriggerMatch> matchingTriggers(@NonNull GitlabEvent event, @NonNull String ref) {
    List<JobTriggerMatch> out = new ArrayList<>();
    for (Job job : jobs.listAll()) {
      for (Trigger t : parseTriggers(job)) {
        if (!GitlabTrigger.TYPE.equals(t.getType())) {
          continue;
        }
        GitlabTrigger gl = (GitlabTrigger) t;
        if (!eventAccepted(gl, event)) {
          continue;
        }
        if (refMatches(gl.getBranches(), ref)) {
          out.add(new JobTriggerMatch(job, gl));
        }
      }
    }
    return out;
  }

  static boolean eventAccepted(@NonNull GitlabTrigger gl, @NonNull GitlabEvent event) {
    List<String> events = gl.getEvents();
    if (events.isEmpty()) {
      // Defensive — constructor injects [push] when null/empty, but tolerate.
      return event == GitlabEvent.PUSH;
    }
    for (String configured : events) {
      if (configured == null) {
        continue;
      }
      if (configured.equalsIgnoreCase(event.configName())) {
        return true;
      }
    }
    return false;
  }

  @NonNull
  private static List<Trigger> parseTriggers(@NonNull Job job) {
    String configJson = job.configJson();
    if (configJson == null || configJson.isBlank()) {
      return List.of();
    }
    try {
      JsonNode root = MAPPER.readTree(configJson);
      return TriggerCodec.read(root.path("triggers"));
    } catch (IOException e) {
      LOGGER.log(
          Level.FINE, "[gitlab-webhook] job " + job.fullName() + ": triggers parse failed", e);
      return List.of();
    }
  }

  static boolean refMatches(@NonNull List<String> patterns, @NonNull String ref) {
    if (patterns.isEmpty()) {
      return true;
    }
    for (String pat : patterns) {
      if (pat == null || pat.isEmpty()) {
        continue;
      }
      if (pat.equals(ref)) {
        return true;
      }
      int star = pat.indexOf("**");
      if (star >= 0) {
        String prefix = pat.substring(0, star);
        if (ref.startsWith(prefix)) {
          return true;
        }
      }
    }
    return false;
  }

  // ── token compare ───────────────────────────────────────────────────────────

  /**
   * Constant-time string equality. Both inputs are converted to bytes under UTF-8 before {@link
   * MessageDigest#isEqual} is applied. Lengths that differ short-circuit only the
   * <em>byte-array</em> compare, not the body — which is the standard JDK behaviour and
   * cryptographically acceptable for shared-secret comparison.
   *
   * <p>NEVER replace with {@code String.equals} or {@code Arrays.equals} — both are early-exit and
   * leak timing.
   */
  static boolean constantTimeEquals(@NonNull String expected, @NonNull String actual) {
    byte[] a = expected.getBytes(StandardCharsets.UTF_8);
    byte[] b = actual.getBytes(StandardCharsets.UTF_8);
    return MessageDigest.isEqual(a, b);
  }

  // ── trigger metadata ────────────────────────────────────────────────────────

  /**
   * Serialize the structured-facts header the UI renders AND the GitLab status reporter consumes:
   * ref + full commit SHA + actor + event kind + project id/path + the credentialsId of the trigger
   * the secret matched on (issue #1080). The secrets policy is the same as the GitHub handler —
   * only these scalar fields are copied out; never the raw payload, never the unsealed token.
   *
   * <p>{@code projectId} is emitted as a numeric JSON value; {@code gitlabCredentialsId} is the
   * unique key (NOT the plaintext) the status reporter will pass back through {@code
   * CredentialsService.resolvePlaintext} to mint its {@code PRIVATE-TOKEN} header.
   *
   * <p>{@code mrIid} is emitted as a numeric JSON value <em>only</em> for Merge Request Hook events
   * (issue #1168). It is the project-scoped MR id that {@code GitlabMrCommentReporter} / {@code
   * GitlabMrReviewReporter} address their note + discussion API calls at. Push / tag-push / manual
   * builds omit it entirely, so those reporters no-op.
   */
  @Nullable
  static String buildTriggerMetaJson(
      @NonNull GitlabPayload parsed, @Nullable String matchedCredentialsId) {
    if (parsed.ref().isEmpty() && parsed.commitSha() == null && parsed.actor() == null) {
      return null;
    }
    StringBuilder sb = new StringBuilder(192).append('{');
    boolean first = true;
    if (!parsed.ref().isEmpty()) {
      sb.append("\"branch\":").append(jsonString(parsed.ref()));
      first = false;
    }
    if (parsed.commitSha() != null) {
      if (!first) {
        sb.append(',');
      }
      sb.append("\"commitSha\":").append(jsonString(parsed.commitSha()));
      first = false;
    }
    if (parsed.actor() != null) {
      if (!first) {
        sb.append(',');
      }
      sb.append("\"actor\":").append(jsonString(parsed.actor()));
      first = false;
    }
    if (parsed.projectId() > 0L) {
      if (!first) {
        sb.append(',');
      }
      sb.append("\"projectId\":").append(parsed.projectId());
      first = false;
    }
    if (parsed.projectPath() != null) {
      if (!first) {
        sb.append(',');
      }
      sb.append("\"projectPath\":").append(jsonString(parsed.projectPath()));
      first = false;
    }
    if (parsed.mrIid() > 0L) {
      if (!first) {
        sb.append(',');
      }
      sb.append("\"mrIid\":").append(parsed.mrIid());
      first = false;
    }
    if (matchedCredentialsId != null && !matchedCredentialsId.isEmpty()) {
      if (!first) {
        sb.append(',');
      }
      sb.append("\"gitlabCredentialsId\":").append(jsonString(matchedCredentialsId));
      first = false;
    }
    if (!first) {
      sb.append(',');
    }
    sb.append("\"eventKind\":").append(jsonString(parsed.event().shortName()));
    return sb.append('}').toString();
  }

  @NonNull
  private static String jsonString(@NonNull String v) {
    StringBuilder sb = new StringBuilder(v.length() + 2).append('"');
    for (int i = 0; i < v.length(); i++) {
      char c = v.charAt(i);
      switch (c) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        case '\b' -> sb.append("\\b");
        case '\f' -> sb.append("\\f");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        default -> {
          if (c < 0x20) {
            sb.append(String.format("\\u%04x", (int) c));
          } else {
            sb.append(c);
          }
        }
      }
    }
    return sb.append('"').toString();
  }

  // ── enqueue ─────────────────────────────────────────────────────────────────

  private long enqueueBuild(long jobId, @Nullable String triggerMetaJson) {
    return stores.withTransaction(
        conn -> {
          int buildNumber = stores.builds().nextBuildNumber(conn, jobId);
          BuildRow build = new BuildRow();
          build.jobId = jobId;
          build.buildNumber = buildNumber;
          build.status = "QUEUED";
          build.triggeredBy = "gitlab:webhook";
          build.triggerType = "gitlab";
          build.queuedAt = Instant.now();
          build.triggerMetaJson = triggerMetaJson;
          long buildId = stores.builds().insert(conn, build);

          TaskQueueRow task = new TaskQueueRow();
          task.type = "ORCHESTRATE";
          task.queueName = "default";
          task.status = "QUEUED";
          task.priority = 0;
          task.payloadJson = "{\"action\":\"SYNTHESIZE\",\"buildId\":" + buildId + "}";
          task.attempts = 0;
          task.maxAttempts = 3;
          task.visibilityTimeoutSeconds = 3600;
          task.buildId = buildId;
          task.availableAt = Instant.now();
          TitanStores.onConnection(conn, TaskQueueDao.class, dao -> dao.insert(task));
          return buildId;
        });
  }

  // ── helpers ─────────────────────────────────────────────────────────────────

  @Nullable
  private static String firstHeader(@Nullable HttpHeaders headers, @NonNull String name) {
    if (headers == null) {
      return null;
    }
    String v = headers.getHeaderString(name);
    if (v != null) {
      return v;
    }
    return headers.getHeaderString(name.toLowerCase(Locale.ROOT));
  }

  @NonNull
  private static Response unauthorized(@NonNull String detail, @NonNull String reason) {
    return problem(401, "Webhook token invalid", detail, reason);
  }

  @NonNull
  private static Response badRequest(@NonNull String detail, @NonNull String reason) {
    return problem(400, "Webhook payload invalid", detail, reason);
  }

  @NonNull
  private static Response problem(
      int status, @NonNull String title, @NonNull String detail, @NonNull String reason) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("ok", false);
    body.put("type", "https://titan.adaptiq.io/problems/gitlab-webhook-" + reason);
    body.put("title", title);
    body.put("status", status);
    body.put("detail", detail);
    body.put("reason", reason);
    return Response.status(status).type("application/problem+json").entity(body).build();
  }

  @NonNull
  private static Response ok(boolean dispatched, @NonNull String detail, @Nullable String event) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("ok", true);
    body.put("dispatched", dispatched);
    body.put("detail", detail);
    if (!dispatched) {
      body.put("ignored", event == null ? "" : event);
    }
    if (event != null) {
      body.put("eventType", event);
    }
    return Response.ok(body).build();
  }

  /** Internal tuple — a job and the GitlabTrigger on it that matched the delivery. */
  private static final class JobTriggerMatch {
    final Job job;
    final GitlabTrigger trigger;

    JobTriggerMatch(@NonNull Job job, @NonNull GitlabTrigger trigger) {
      this.job = job;
      this.trigger = trigger;
    }
  }
}
