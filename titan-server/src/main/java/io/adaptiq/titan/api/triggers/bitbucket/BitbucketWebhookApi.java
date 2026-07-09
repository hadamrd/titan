package io.adaptiq.titan.api.triggers.bitbucket;

import com.fasterxml.jackson.core.JsonProcessingException;
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
import io.adaptiq.titan.trigger.BitbucketTrigger;
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
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Jakarta REST resource: {@code POST /api/v1/triggers/bitbucket} — receives a Bitbucket Cloud
 * webhook delivery, verifies its HMAC-SHA256 signature ({@code X-Hub-Signature: sha256=…}) against
 * the secret stored on the matching job's {@link BitbucketTrigger}, and enqueues a build when the
 * event kind / ref match (issue #1079).
 *
 * <p><strong>Auth model.</strong> {@code @PermitAll} — Bitbucket does not speak OIDC. The only
 * authentication is the HMAC signature in {@code X-Hub-Signature}, recomputed over the raw body and
 * compared in constant time ({@link MessageDigest#isEqual}) against the credential resolved from
 * the per-trigger {@link BitbucketTrigger#getCredentialsId() credentialsId}. The expected secret is
 * NEVER logged.
 *
 * <p><strong>Event dispatch.</strong> The {@code X-Event-Key} header drives a discriminated union:
 * {@code repo:push} → build the pushed branch / hash; {@code pullrequest:created} / {@code
 * pullrequest:updated} → build the PR head branch. Anything else ({@code pullrequest:approved},
 * {@code repo:fork}, …) returns {@code 200 {ok:true, ignored:"<key>"}} — a forward-compat no-op.
 * Missing {@code X-Event-Key} returns {@code 400} with a structured problem-detail body.
 *
 * <p><strong>Lookup model.</strong> Mirrors {@code GithubWebhookApi}: scan every job whose {@code
 * config_json} carries a Bitbucket trigger whose ref-pattern matches the delivery, then for each
 * candidate recompute the HMAC against its resolved secret. Discriminated by {@link
 * Trigger#getType()} — never by URL / repo sniffing.
 */
@Path("/api/v1/triggers/bitbucket")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
@PermitAll
public class BitbucketWebhookApi {

  private static final Logger LOGGER = Logger.getLogger(BitbucketWebhookApi.class.getName());

  /** The credentials scope used to look up Bitbucket webhook HMAC secrets. */
  static final String CREDENTIALS_SCOPE = "bitbucket-webhook";

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final TitanStores stores;
  private final JobService jobs;
  private final CredentialsService credentials;
  private final WebhookTriggerMatcher triggerMatcher;

  BitbucketWebhookApi(TitanStores stores, JobService jobs, CredentialsService credentials) {
    this.stores = stores;
    this.jobs = jobs;
    this.credentials = credentials;
    this.triggerMatcher = new WebhookTriggerMatcher(stores);
  }

  // ── POST /api/v1/triggers/bitbucket ─────────────────────────────────────────

  @POST
  @Consumes(MediaType.APPLICATION_JSON)
  public Response receive(@jakarta.ws.rs.core.Context HttpHeaders headers, byte[] body) {
    String signatureHeader = firstHeader(headers, "X-Hub-Signature");
    String eventKey = firstHeader(headers, "X-Event-Key");
    byte[] rawBody = body == null ? new byte[0] : body;

    if (signatureHeader == null || signatureHeader.isBlank()) {
      return unauthorized("X-Hub-Signature header missing", "missing_signature");
    }
    if (eventKey == null || eventKey.isBlank()) {
      return badRequest("X-Event-Key header missing", "missing_event_header");
    }

    // Forward-compat: an event-key we don't act on (pullrequest:approved, repo:fork, …) is a 200
    // no-op — acknowledged but ignored — BEFORE any signature work, so an unconfigured event never
    // triggers a 401.
    BitbucketEvent event = BitbucketEvent.fromEventKey(eventKey);
    if (event == BitbucketEvent.UNKNOWN) {
      return ok(false, "unsupported event key", eventKey);
    }

    // Parse the body. Bitbucket guarantees JSON on every event, so a malformed body is a 400.
    JsonNode payload;
    try {
      payload = MAPPER.readTree(rawBody);
    } catch (IOException e) {
      LOGGER.log(Level.FINE, "[bitbucket-webhook] body is not JSON", e);
      return badRequest("request body is not valid JSON", "malformed_json");
    }
    if (payload == null || !payload.isObject()) {
      return badRequest("request body must be a JSON object", "malformed_json");
    }

    // Discriminated extraction — never sniff URL / repository shape; branch on event type.
    BitbucketPayload parsed = BitbucketPayload.parse(event, payload);
    if (parsed == null) {
      return ok(
          false, "event payload not actionable (e.g. tag push or no source branch)", eventKey);
    }

    // Find every (job, trigger) whose Bitbucket trigger accepts this event + ref.
    List<JobTriggerMatch> candidates = matchingTriggers(event, parsed.ref());
    if (candidates.isEmpty()) {
      return ok(false, "no job matches ref '" + parsed.ref() + "'", eventKey);
    }

    String triggerMetaJson = buildTriggerMetaJson(parsed);

    int enqueued = 0;
    int enqueueFailures = 0;
    boolean anyVerified = false;
    for (JobTriggerMatch m : candidates) {
      Optional<String> secret =
          credentials.resolvePlaintext(CREDENTIALS_SCOPE, m.trigger.getCredentialsId());
      if (secret.isEmpty() || secret.get().isEmpty()) {
        LOGGER.log(
            Level.WARNING,
            "[bitbucket-webhook] job {0}: credentialsId ''{1}'' did not resolve — skipping",
            new Object[] {m.job.fullName(), m.trigger.getCredentialsId()});
        continue;
      }
      // SECURITY: constant-time HMAC compare. NEVER log expected or actual signature bytes.
      if (!verifyHmac(secret.get(), rawBody, signatureHeader)) {
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
      // NEVER swallow an enqueue failure into a 2xx (issue #69): Bitbucket treats 2xx as
      // delivered, so a swallowed insert failure is a silently lost event. Log at SEVERE with
      // the cause chain, keep trying the remaining candidates, and answer 500 below.
      try {
        enqueueBuild(m.job.id(), triggerMetaJson);
        enqueued++;
      } catch (RuntimeException e) {
        enqueueFailures++;
        LOGGER.log(
            Level.SEVERE,
            "[bitbucket-webhook] failed to enqueue build for job "
                + m.job.fullName()
                + " —"
                + " delivery will be answered 500 so the sender retries (issue #69)",
            e);
      }
    }

    if (!anyVerified) {
      return unauthorized(
          "X-Hub-Signature did not match any configured secret", "invalid_signature");
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
    return ok(enqueued > 0, "enqueued " + enqueued + " build(s)", eventKey);
  }

  // ── matching ────────────────────────────────────────────────────────────────

  @NonNull
  private List<JobTriggerMatch> matchingTriggers(
      @NonNull BitbucketEvent event, @NonNull String ref) {
    List<JobTriggerMatch> out = new ArrayList<>();
    for (Job job : jobs.listAll()) {
      for (Trigger t : parseTriggers(job)) {
        if (!BitbucketTrigger.TYPE.equals(t.getType())) {
          continue;
        }
        BitbucketTrigger bb = (BitbucketTrigger) t;
        if (!eventAccepted(bb, event)) {
          continue;
        }
        if (refMatches(bb.getBranches(), ref)) {
          out.add(new JobTriggerMatch(job, bb));
        }
      }
    }
    return out;
  }

  static boolean eventAccepted(@NonNull BitbucketTrigger bb, @NonNull BitbucketEvent event) {
    List<String> events = bb.getEvents();
    if (events.isEmpty()) {
      // Defensive — constructor injects [push] when null/empty, but tolerate.
      return event == BitbucketEvent.REPO_PUSH;
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
          Level.FINE, "[bitbucket-webhook] job " + job.fullName() + ": triggers parse failed", e);
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
      // Reuse the shared matcher's glob semantics (handles `*`/`**`, anchored both
      // ends) rather than a private copy — keeps bitbucket from diverging from the
      // github/gitlab sibling handlers on the same documented glob convention.
      if (WebhookTriggerMatcher.globMatches(pat, ref)) {
        return true;
      }
    }
    return false;
  }

  // ── HMAC ────────────────────────────────────────────────────────────────────

  /**
   * Verify a Bitbucket-style {@code X-Hub-Signature: sha256=…} header against the raw request body,
   * using constant-time {@link MessageDigest#isEqual}.
   */
  static boolean verifyHmac(
      @NonNull String secret, @NonNull byte[] body, @NonNull String signatureHeader) {
    String prefix = "sha256=";
    if (!signatureHeader.startsWith(prefix)) {
      return false;
    }
    String hex = signatureHeader.substring(prefix.length());
    byte[] expected;
    try {
      expected = hexToBytes(hex);
    } catch (IllegalArgumentException e) {
      return false;
    }
    byte[] actual = hmacSha256(secret, body);
    // MessageDigest.isEqual is the JDK's constant-time comparator — it does NOT short-circuit on
    // the first differing byte. NEVER replace this with Arrays.equals or String.equals.
    return MessageDigest.isEqual(expected, actual);
  }

  @NonNull
  private static byte[] hmacSha256(@NonNull String secret, @NonNull byte[] body) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return mac.doFinal(body);
    } catch (Exception e) {
      throw new IllegalStateException("HMAC-SHA256 unavailable", e);
    }
  }

  @NonNull
  private static byte[] hexToBytes(@NonNull String hex) {
    int len = hex.length();
    if ((len & 1) != 0) {
      throw new IllegalArgumentException("odd-length hex");
    }
    byte[] out = new byte[len / 2];
    for (int i = 0; i < len; i += 2) {
      int hi = Character.digit(hex.charAt(i), 16);
      int lo = Character.digit(hex.charAt(i + 1), 16);
      if (hi < 0 || lo < 0) {
        throw new IllegalArgumentException("non-hex character at offset " + i);
      }
      out[i / 2] = (byte) ((hi << 4) | lo);
    }
    return out;
  }

  // ── trigger metadata ────────────────────────────────────────────────────────

  /**
   * Serialize the structured facts the build header renders: ref (branch) + full commit hash +
   * actor + PR id (PR events only) + event kind. The secrets policy mirrors the GitHub handler —
   * only these scalar fields are copied out; never the raw payload, never the signature.
   */
  @Nullable
  static String buildTriggerMetaJson(@NonNull BitbucketPayload parsed) {
    if (parsed.ref().isEmpty() && parsed.commitSha() == null && parsed.actor() == null) {
      return null;
    }
    Map<String, Object> meta = new LinkedHashMap<>();
    if (!parsed.ref().isEmpty()) {
      meta.put("branch", parsed.ref());
    }
    if (parsed.commitSha() != null) {
      meta.put("commitSha", parsed.commitSha());
    }
    if (parsed.actor() != null) {
      meta.put("actor", parsed.actor());
    }
    if (parsed.prId() > 0L) {
      meta.put("prId", parsed.prId());
    }
    meta.put("eventKind", parsed.event().shortName());
    try {
      return MAPPER.writeValueAsString(meta);
    } catch (JsonProcessingException e) {
      // Scalar values only (strings/longs) — serialization cannot realistically fail.
      throw new IllegalStateException("failed to serialize bitbucket trigger meta", e);
    }
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
          build.triggeredBy = "bitbucket:webhook";
          build.triggerType = "bitbucket";
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
    return problem(401, "Webhook signature invalid", detail, reason);
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
    body.put("type", "https://titan.adaptiq.io/problems/bitbucket-webhook-" + reason);
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

  /** Internal tuple — a job and the BitbucketTrigger on it that matched the delivery. */
  private static final class JobTriggerMatch {
    final Job job;
    final BitbucketTrigger trigger;

    JobTriggerMatch(@NonNull Job job, @NonNull BitbucketTrigger trigger) {
      this.job = job;
      this.trigger = trigger;
    }
  }
}
