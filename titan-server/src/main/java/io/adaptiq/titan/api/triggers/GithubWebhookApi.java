package io.adaptiq.titan.api.triggers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.adaptiq.titan.api.WebhookPayloadParams;
import io.adaptiq.titan.api.WebhookTriggerMatcher;
import io.adaptiq.titan.build.BuildEnqueuer;
import io.adaptiq.titan.credentials.CredentialsService;
import io.adaptiq.titan.job.Job;
import io.adaptiq.titan.job.JobService;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.BuildRow;
import io.adaptiq.titan.store.rows.JobRow;
import io.adaptiq.titan.trigger.GithubTrigger;
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
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Jakarta REST resource: {@code POST /api/v1/triggers/github} — receives a GitHub webhook delivery,
 * verifies its HMAC-SHA256 signature against the secret stored on the matching job's {@link
 * GithubTrigger}, and enqueues a build when the event/branch match (issue #397).
 *
 * <p><strong>Auth model.</strong> {@code @PermitAll} — GitHub does not speak OIDC; the only
 * authentication is the HMAC signature in {@code X-Hub-Signature-256}. Constant-time comparison
 * ({@link MessageDigest#isEqual}) defends against signature-oracle timing attacks.
 *
 * <p><strong>Lookup model.</strong> Unlike the older {@code /api/v1/webhooks/github} endpoint
 * (which authenticates with a single global secret + matches on {@code repository.full_name}), this
 * endpoint per-trigger: it scans jobs whose {@code config_json} carries a {@code github} trigger
 * whose {@code branches} match the delivery's {@code ref}, then resolves the per-trigger {@code
 * credentialsId} to recompute the HMAC. This is the discriminated-union shape locked in by the PDL
 * parser — never sniff headers/URLs to dispatch, branch by {@link Trigger#getType() trigger.type}.
 *
 * <p><strong>Forward-compat.</strong> Unknown {@code X-GitHub-Event} types (anything that isn't
 * {@code push}) and non-matching branches both return {@code 200} no-op: a future event type, or a
 * delivery targeted at a branch nobody configured, is not an error.
 */
@Path("/api/v1/triggers/github")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
@PermitAll
public class GithubWebhookApi {

  private static final Logger LOGGER = Logger.getLogger(GithubWebhookApi.class.getName());

  /** The credentials scope used to look up GitHub webhook HMAC secrets. */
  static final String CREDENTIALS_SCOPE = "github-webhook";

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final TitanStores stores;
  private final JobService jobs;
  private final CredentialsService credentials;
  private final WebhookTriggerMatcher triggerMatcher;

  GithubWebhookApi(TitanStores stores, JobService jobs, CredentialsService credentials) {
    this.stores = stores;
    this.jobs = jobs;
    this.credentials = credentials;
    this.triggerMatcher = new WebhookTriggerMatcher(stores);
  }

  // ── POST /api/v1/triggers/github ────────────────────────────────────────────

  @POST
  @Consumes(MediaType.APPLICATION_JSON)
  public Response receive(@jakarta.ws.rs.core.Context HttpHeaders headers, byte[] body) {
    String signatureHeader = firstHeader(headers, "X-Hub-Signature-256");
    String eventType = firstHeader(headers, "X-GitHub-Event");
    byte[] rawBody = body == null ? new byte[0] : body;

    if (signatureHeader == null || signatureHeader.isBlank()) {
      return unauthorized("X-Hub-Signature-256 header missing");
    }

    // Forward-compat: anything that isn't a push is acknowledged but does nothing. (No
    // pull_request / ping / etc. yet — these are tracked as follow-ups; the SPI already accepts
    // `events: [push, pull_request]` so we'll widen the dispatch when we add the wiring.)
    if (eventType == null || !GithubTrigger.EVENT_PUSH.equalsIgnoreCase(eventType)) {
      return ok(false, "unsupported event type", eventType);
    }

    // Parse the JSON body for ref + repository.full_name. A malformed body is 200 no-op too —
    // GitHub retries on 5xx, and we don't want to bounce a junk delivery into a retry storm.
    JsonNode payload;
    try {
      payload = MAPPER.readTree(rawBody);
    } catch (IOException e) {
      LOGGER.log(Level.FINE, "github webhook: body is not JSON", e);
      return ok(false, "malformed JSON", null);
    }
    String ref = payload.path("ref").asText("");
    String branch = refToBranch(ref);
    if (branch == null || branch.isEmpty()) {
      return ok(false, "no ref in payload", null);
    }
    String triggerMetaJson = buildTriggerMetaJson(payload, branch);

    // Find every (job, trigger) whose branches match this delivery. We need the candidate set
    // *before* signature verification — the secret is per-trigger, so any match whose secret
    // signs the body is treated as authentic for that trigger.
    List<JobTriggerMatch> candidates = matchingTriggers(branch);
    if (candidates.isEmpty()) {
      return ok(false, "no job matches branch '" + branch + "'", null);
    }

    // Verify the signature against each candidate's secret. The first match wins for THAT job;
    // signature mismatch for ANY candidate doesn't fail the request — a candidate that doesn't
    // sign isn't a recipient. But if NONE verify, we return 401: that means the body wasn't
    // signed by any configured secret, which is the canonical "signature invalid" case.
    int enqueued = 0;
    int enqueueFailures = 0;
    boolean anyVerified = false;
    for (JobTriggerMatch m : candidates) {
      Optional<String> secret =
          credentials.resolvePlaintext(CREDENTIALS_SCOPE, m.trigger.getCredentialsId());
      if (secret.isEmpty() || secret.get().isEmpty()) {
        LOGGER.log(
            Level.WARNING,
            "[github-webhook] job {0}: credentialsId ''{1}'' did not resolve — skipping",
            new Object[] {m.job.fullName(), m.trigger.getCredentialsId()});
        continue;
      }
      if (!verifyHmac(secret.get(), rawBody, signatureHeader)) {
        continue;
      }
      anyVerified = true;
      if (!m.job.enabled()) {
        continue;
      }
      // Mirror #920: skip pipelines with required-no-default params the webhook can't supply,
      // rather than enqueueing a build that's guaranteed to fail at parameter bake (#919, #937).
      // For legacy jobs without a discovered-pipeline row the matcher returns an empty list, so
      // dispatch behaviour is preserved for the pre-design-66 manual-onboarding path.
      Optional<JobRow> rowOpt = stores.jobs().findById(m.job.id());
      if (rowOpt.isPresent()) {
        // #938 — push payloads carry no inputs, so the helper returns an empty set. Routed
        // through the shared extractor anyway to keep all three webhook handlers uniform.
        Set<String> supplied = WebhookPayloadParams.suppliedKeys(payload, "push");
        List<String> missing =
            triggerMatcher.unsatisfiedRequiredParams(rowOpt.get(), branch, supplied);
        if (!missing.isEmpty()) {
          LOGGER.log(
              Level.INFO,
              "pipeline_skipped reason=missing_required_params pipeline={0} missing={1} trigger={2}",
              new Object[] {m.job.fullName(), missing, "push"});
          continue;
        }
      }
      // NEVER swallow an enqueue failure into a 2xx (issue #69): GitHub treats any 2xx as
      // delivered and never redelivers, so a swallowed insert failure is a silently lost push.
      // Log at SEVERE with the cause chain, keep trying the remaining candidates (one job's
      // failure must not starve its siblings), and answer 500 below so the sender retries.
      try {
        enqueueBuild(m.job.id(), triggerMetaJson);
        enqueued++;
      } catch (RuntimeException e) {
        enqueueFailures++;
        LOGGER.log(
            Level.SEVERE,
            "[github-webhook] failed to enqueue build for job "
                + m.job.fullName()
                + " — delivery"
                + " will be answered 500 so the sender retries (issue #69)",
            e);
      }
    }

    if (!anyVerified) {
      return unauthorized("X-Hub-Signature-256 did not match any configured secret");
    }
    if (enqueueFailures > 0) {
      return enqueueFailed(enqueueFailures, enqueued);
    }
    return ok(enqueued > 0, "enqueued " + enqueued + " build(s)", eventType);
  }

  // ── matching ────────────────────────────────────────────────────────────────

  /**
   * Scan all jobs; return the (job, trigger) tuples whose github trigger matches {@code branch}.
   */
  @NonNull
  private List<JobTriggerMatch> matchingTriggers(@NonNull String branch) {
    List<JobTriggerMatch> out = new ArrayList<>();
    for (Job job : jobs.listAll()) {
      for (Trigger t : parseTriggers(job)) {
        if (!GithubTrigger.TYPE.equals(t.getType())) {
          continue;
        }
        GithubTrigger gh = (GithubTrigger) t;
        if (branchMatches(gh.getBranches(), branch)) {
          out.add(new JobTriggerMatch(job, gh));
        }
      }
    }
    return out;
  }

  /** Parse the {@code triggers} array out of a job's {@code config_json}; empty on any error. */
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
          Level.FINE, "[github-webhook] job " + job.fullName() + ": triggers parse failed", e);
      return List.of();
    }
  }

  /**
   * Branch matcher — empty pattern list means "any branch"; otherwise a pattern matches if it
   * equals the branch literally, or — when it contains {@code **} — the branch starts with the
   * prefix before {@code **}. Mirrors the {@code GlobMatcher} convention sketched in the
   * GithubTrigger javadoc.
   */
  static boolean branchMatches(@NonNull List<String> patterns, @NonNull String branch) {
    if (patterns.isEmpty()) {
      return true;
    }
    for (String pat : patterns) {
      if (pat == null || pat.isEmpty()) {
        continue;
      }
      if (pat.equals(branch)) {
        return true;
      }
      int star = pat.indexOf("**");
      if (star >= 0) {
        String prefix = pat.substring(0, star);
        if (branch.startsWith(prefix)) {
          return true;
        }
      }
    }
    return false;
  }

  /** Strip the {@code refs/heads/} prefix to get the branch name, or null when not a branch ref. */
  @Nullable
  private static String refToBranch(@Nullable String ref) {
    if (ref == null) {
      return null;
    }
    String prefix = "refs/heads/";
    if (!ref.startsWith(prefix)) {
      return null;
    }
    return ref.substring(prefix.length());
  }

  // ── HMAC ────────────────────────────────────────────────────────────────────

  /**
   * Verify a GitHub-style {@code X-Hub-Signature-256: sha256=…} header against the raw request
   * body, using constant-time {@link MessageDigest#isEqual}.
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
    // MessageDigest.isEqual is the JDK's constant-time comparator — it does NOT short-circuit
    // on the first differing byte. NEVER replace this with Arrays.equals or String.equals.
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

  // ── trigger metadata (issue #589) ───────────────────────────────────────────

  /**
   * Strip a GitHub push payload to the structured facts the build header renders: branch (already
   * resolved from {@code ref}), full 40-char commit SHA (from {@code head_commit.id} or fallback
   * {@code after}), and actor (from {@code pusher.name} or {@code sender.login}). Returns a JSON
   * string ready to persist, or {@code null} when no fact could be extracted.
   *
   * <p>The persisted SHA is the FULL value (#971 sibling of #967). GitHub's {@code POST
   * /repos/.../statuses/{sha}} endpoint requires the full ref-resolvable SHA; truncating at write
   * time forced consumers (status reporter, head-sha filter, PR comment reporter) to either guess
   * or refuse to act. The UI is responsible for truncating to 7-char short form at display time.
   *
   * <p><strong>Secrets policy.</strong> Only these three string fields are copied out — never the
   * raw payload, never headers, never any nested object. GitHub push payloads do not contain
   * tokens, but the policy is enforced by what this method writes, not by the wire format.
   */
  @Nullable
  static String buildTriggerMetaJson(@NonNull JsonNode payload, @NonNull String branch) {
    String sha = commitSha(payload);
    String actor = actor(payload);
    if (branch.isEmpty() && sha == null && actor == null) {
      return null;
    }
    StringBuilder sb = new StringBuilder(96).append('{');
    boolean first = true;
    if (!branch.isEmpty()) {
      sb.append("\"branch\":").append(jsonString(branch));
      first = false;
    }
    if (sha != null) {
      if (!first) {
        sb.append(',');
      }
      sb.append("\"commitSha\":").append(jsonString(sha));
      first = false;
    }
    if (actor != null) {
      if (!first) {
        sb.append(',');
      }
      sb.append("\"actor\":").append(jsonString(actor));
    }
    return sb.append('}').toString();
  }

  /**
   * Extract the full 40-char commit SHA from a GitHub push payload. Prefer {@code head_commit.id};
   * fall back to {@code after} (force-pushes / branch-creation events where {@code head_commit} is
   * absent). Returns {@code null} when neither is set. The value is NOT truncated — see {@link
   * #buildTriggerMetaJson} for why (closes #971).
   */
  @Nullable
  private static String commitSha(@NonNull JsonNode payload) {
    String full = payload.path("head_commit").path("id").asText("");
    if (full.isEmpty()) {
      full = payload.path("after").asText("");
    }
    return full.isEmpty() ? null : full;
  }

  @Nullable
  private static String actor(@NonNull JsonNode payload) {
    String name = payload.path("pusher").path("name").asText("");
    if (name.isEmpty()) {
      name = payload.path("sender").path("login").asText("");
    }
    return name.isEmpty() ? null : name;
  }

  /** Minimal JSON-string escaper for the small, ASCII-leaning fields we copy out. */
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

  /**
   * Insert a {@code QUEUED} build row plus an {@code ORCHESTRATE/SYNTHESIZE} task — mirrors {@link
   * io.adaptiq.titan.api.JobBuildsApi#triggerBuild}.
   *
   * <p>Closes #821: must enqueue {@code SYNTHESIZE} (not {@code BAKE}). Webhook-discovered builds
   * arrive without a {@code pipeline_model_json} — the controller must drive the design/38 §3 Stage
   * 1b worker synthesis before bake. Enqueuing BAKE directly fails in {@code
   * QueueProcessor.handleBake} with "build N has no synthesized model — synthesize must run first"
   * and the build never runs.
   */
  private long enqueueBuild(long jobId, @Nullable String triggerMetaJson) {
    return stores.withTransaction(
        conn -> {
          int buildNumber = stores.builds().nextBuildNumber(conn, jobId);
          BuildRow build = new BuildRow();
          build.jobId = jobId;
          build.buildNumber = buildNumber;
          build.status = "QUEUED";
          build.triggeredBy = "github:webhook";
          build.triggerType = "github";
          build.queuedAt = Instant.now();
          build.triggerMetaJson = triggerMetaJson;
          long buildId = stores.builds().insert(conn, build);
          BuildEnqueuer.enqueueSynthesizeEntryTask(conn, buildId);
          return buildId;
        });
  }

  // ── helpers ─────────────────────────────────────────────────────────────────

  @Nullable
  private static String firstHeader(@Nullable HttpHeaders headers, @NonNull String name) {
    if (headers == null) {
      return null;
    }
    // Header names are case-insensitive per RFC 7230 §3.2; JAX-RS preserves case but matches
    // case-insensitively on lookup.
    String v = headers.getHeaderString(name);
    if (v != null) {
      return v;
    }
    // Defensive: some containers normalise to lower-case.
    return headers.getHeaderString(name.toLowerCase(Locale.ROOT));
  }

  @NonNull
  private static Response unauthorized(@NonNull String detail) {
    return Response.status(401)
        .type("application/problem+json")
        .entity(
            java.util.Map.of(
                "type",
                "https://titan.adaptiq.io/problems/webhook-signature-invalid",
                "title",
                "Webhook signature invalid",
                "status",
                401,
                "detail",
                detail))
        .build();
  }

  /**
   * 500 problem+json for a delivery where at least one matched job's build could not be enqueued
   * (issue #69). Non-2xx is load-bearing: GitHub only redelivers on non-2xx, so this is the line
   * between "retried" and "silently dropped". The cause chain is already logged at SEVERE by the
   * caller; the body carries counts only — never exception internals.
   */
  @NonNull
  private static Response enqueueFailed(int failures, int enqueued) {
    return Response.status(500)
        .type("application/problem+json")
        .entity(
            java.util.Map.of(
                "type",
                "https://titan.adaptiq.io/problems/webhook-enqueue-failed",
                "title",
                "Webhook build enqueue failed",
                "status",
                500,
                "detail",
                "failed to enqueue "
                    + failures
                    + " build(s) ("
                    + enqueued
                    + " enqueued) — retry the delivery"))
        .build();
  }

  @NonNull
  private static Response ok(boolean dispatched, @NonNull String detail, @Nullable String event) {
    java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
    body.put("accepted", true);
    body.put("dispatched", dispatched);
    body.put("detail", detail);
    if (event != null) {
      body.put("eventType", event);
    }
    return Response.ok(body).build();
  }

  /** Internal tuple — a job and the GithubTrigger on it that matched the delivery's branch. */
  private static final class JobTriggerMatch {
    final Job job;
    final GithubTrigger trigger;

    JobTriggerMatch(@NonNull Job job, @NonNull GithubTrigger trigger) {
      this.job = job;
      this.trigger = trigger;
    }
  }
}
