package io.adaptiq.titan.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.adaptiq.titan.build.BuildEnqueuedEvent;
import io.adaptiq.titan.build.BuildEnqueuer;
import io.adaptiq.titan.scm.github.GithubAppWebhookSecretComparator;
import io.adaptiq.titan.scm.pulsar.PulsarChangeDiscovery;
import io.adaptiq.titan.scm.pulsar.PulsarClientFactory;
import io.adaptiq.titan.scm.pulsar.PulsarEventSource;
import io.adaptiq.titan.scm.pulsar.PulsarEventSource.PulsarTrigger;
import io.adaptiq.titan.scm.reconcile.EventDedupeStore;
import io.adaptiq.titan.scm.reconcile.ScmProvider;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.JobRow;
import jakarta.annotation.security.PermitAll;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Jakarta REST resource: {@code POST /api/v1/pulsar/events} — the Pulsar App-pattern webhook sink
 * (issue #1287, convergence axis 2 / scm-depth). The real trigger path: a Pulsar node delivers a
 * change-event (change opened / revision pushed), and this endpoint HMAC-verifies it, normalizes it
 * through {@link PulsarEventSource#triggerFor} (clone change revision from Pulsar git smart-HTTP +
 * discover {@code .titan/pipelines/*.yml}), and dispatches a real build via the SAME seam GitHub
 * uses — a {@code QUEUED} build + an {@code ORCHESTRATE/SYNTHESIZE} task in one transaction
 * (mirrors {@link GithubAppWebhookApi#receive}).
 *
 * <p><strong>Auth.</strong> {@code @PermitAll} — a Pulsar node does not speak OIDC. Signature
 * verification is the entire auth check; constant-time HMAC compare via {@link
 * GithubAppWebhookSecretComparator#constantTimeEquals} (mirrors GitHub's {@code
 * X-Hub-Signature-256} handling). The secret is sourced from the {@code pulsar.webhook-secret}
 * config; no secret configured → 401 rather than 500.
 *
 * <p><strong>Idempotency.</strong> A Pulsar node re-delivers on any non-2xx. The {@code
 * X-Pulsar-Delivery} header carries a unique delivery id; absent that, the deterministic event id
 * {@code <repo>:<changeId>:<revision>} is used. Recent ids are kept in a bounded in-memory LRU and
 * duplicates within {@link #DELIVERY_TTL_MIN} are fast-skipped, so a redelivery never
 * double-builds.
 *
 * <p><strong>Cross-path dedupe (issue #4).</strong> The LRU only suppresses redeliveries to
 * <em>this process</em>. The authoritative cross-path gate is the shared, DB-backed {@link
 * EventDedupeStore} keyed by {@code <repo>:<changeId>:<revision>} — the SAME key the poll scanner
 * ({@code PulsarRepoScanner}) claims. Whichever path sees a change tip first wins the {@code
 * markSeen} claim; the other no-ops. So a change whose webhook arrives AND is later re-discovered
 * by the scanner (or vice-versa) builds EXACTLY ONCE. The claim is taken before the fallible clone
 * and released on failure, mirroring {@code PulsarScanScheduler.dispatchDiscovery}.
 *
 * <p><strong>Job resolution.</strong> A change's {@code repo} maps to the Titan job whose {@code
 * full_name} equals that repo (the App-pattern linkage; UI install + a richer mapping land in
 * #1283). No matching job → authenticated no-op (2xx, no build), mirroring GitHub's "no matching
 * job" path.
 */
@Path("/api/v1/pulsar/events")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
@PermitAll
public class PulsarWebhookApi {

  private static final Logger LOGGER = Logger.getLogger(PulsarWebhookApi.class.getName());

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** Max delivery-ids retained in the in-memory replay-suppression LRU. */
  static final int DELIVERY_LRU_CAP = 1024;

  /** TTL after which a delivery id can replay. */
  static final Duration DELIVERY_TTL_MIN = Duration.ofMinutes(10);

  private final TitanStores stores;
  private final Supplier<Optional<String>> webhookSecretSource;
  private final Function<PulsarChangeDiscovery, Optional<PulsarTrigger>> triggerResolver;
  private final EventDedupeStore dedupe;

  /**
   * Enqueue-time check sink (issue #1). In production this is {@code Event<BuildEnqueuedEvent>::fire}
   * — CDI fans it out to {@code PulsarCheckReporter#onBuildEnqueued}, which posts the immediate
   * PENDING check. A no-op in legacy test wiring.
   */
  private final Consumer<BuildEnqueuedEvent> enqueuedSink;

  /** Bounded insertion-ordered map → simple LRU for delivery-id replay suppression. */
  private final Map<String, Instant> deliveryLru =
      Collections.synchronizedMap(
          new LinkedHashMap<>(DELIVERY_LRU_CAP * 4 / 3, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Instant> eldest) {
              return size() > DELIVERY_LRU_CAP;
            }
          });

  /** Production constructor — wires the real {@link PulsarEventSource} for the node base URL. */
  @Inject
  public PulsarWebhookApi(
      TitanStores stores,
      EventDedupeStore dedupe,
      Event<BuildEnqueuedEvent> enqueuedEvent,
      @ConfigProperty(name = "pulsar.webhook-secret") Optional<String> webhookSecret,
      @ConfigProperty(
              name = "pulsar.node-base-url",
              defaultValue = PulsarClientFactory.DEFAULT_ENDPOINT)
          String nodeBaseUrl) {
    this(
        stores,
        () -> webhookSecret.filter(s -> !s.isBlank()),
        dedupe,
        new PulsarEventSource(nodeBaseUrl)::triggerFor,
        enqueuedEvent::fire);
  }

  /** Test-only constructor — no enqueue-time check sink (legacy enqueue-path tests). */
  PulsarWebhookApi(
      @NonNull TitanStores stores,
      @NonNull Supplier<Optional<String>> webhookSecretSource,
      @NonNull EventDedupeStore dedupe,
      @NonNull Function<PulsarChangeDiscovery, Optional<PulsarTrigger>> triggerResolver) {
    this(stores, webhookSecretSource, dedupe, triggerResolver, e -> {});
  }

  /** Test-only constructor — explicit enqueue-time check sink (issue #1 wiring), no network. */
  PulsarWebhookApi(
      @NonNull TitanStores stores,
      @NonNull Supplier<Optional<String>> webhookSecretSource,
      @NonNull EventDedupeStore dedupe,
      @NonNull Function<PulsarChangeDiscovery, Optional<PulsarTrigger>> triggerResolver,
      @NonNull Consumer<BuildEnqueuedEvent> enqueuedSink) {
    this.stores = stores;
    this.webhookSecretSource = webhookSecretSource;
    this.dedupe = dedupe;
    this.triggerResolver = triggerResolver;
    this.enqueuedSink = enqueuedSink;
  }

  // ── POST /api/v1/pulsar/events ───────────────────────────────────────────────

  @POST
  @Consumes(MediaType.APPLICATION_JSON)
  public Response receive(@jakarta.ws.rs.core.Context HttpHeaders headers, byte[] body) {
    String signatureHeader = firstHeader(headers, "X-Pulsar-Signature-256");
    String deliveryId = firstHeader(headers, "X-Pulsar-Delivery");
    byte[] rawBody = body == null ? new byte[0] : body;

    if (signatureHeader == null || signatureHeader.isBlank()) {
      return problem(401, "webhook-signature-invalid", "X-Pulsar-Signature-256 header missing");
    }

    // Step 1: verify signature BEFORE parsing — never let an unauthenticated body reach Jackson.
    Optional<String> secret = webhookSecretSource.get();
    if (secret.isEmpty()) {
      return problem(
          401,
          "pulsar-webhook-not-configured",
          "no pulsar.webhook-secret configured for this node");
    }
    if (!verifyHmac(secret.get(), rawBody, signatureHeader)) {
      return problem(401, "webhook-signature-invalid", "X-Pulsar-Signature-256 did not match");
    }

    // Step 2: parse (signature already proven, so the body is trusted).
    JsonNode payload;
    try {
      payload = MAPPER.readTree(rawBody);
    } catch (IOException e) {
      LOGGER.log(Level.FINE, "[pulsar] body is not JSON", e);
      return problem(400, "webhook-malformed", "request body is not valid JSON");
    }

    String repo = payload.path("repo").asText("");
    String changeId = payload.path("changeId").asText("");
    String revision = payload.path("revision").asText("");
    if (repo.isBlank() || changeId.isBlank() || revision.isBlank()) {
      return problem(
          400, "pulsar-event-malformed", "payload must carry repo, changeId and revision");
    }

    // Step 3: idempotency (after signature so an attacker can't poison the LRU). Prefer the
    // explicit delivery id; fall back to the deterministic (repo,change,revision) identity.
    String eventId =
        deliveryId != null && !deliveryId.isBlank()
            ? deliveryId
            : eventId(repo, changeId, revision);
    if (isDuplicateDelivery(eventId)) {
      LOGGER.log(Level.FINE, "[pulsar] duplicate delivery {0} — skipping", new Object[] {eventId});
      return Response.noContent().build();
    }

    // Step 4: resolve the target job BEFORE the (expensive) clone — no job, no work.
    // A disabled job is the operator's hard stop, exactly like an unlinked repo: the GitHub
    // mirror gates every match through shouldDispatch (enabled jobs only), so honor `enabled`
    // here too — otherwise a disabled job would still build on a Pulsar change-event.
    Optional<JobRow> job = stores.jobs().findByFullName(repo);
    if (job.isEmpty() || !job.get().enabled) {
      LOGGER.log(
          Level.FINE,
          "[pulsar] change {0}@{1}: no enabled job linked to repo — authenticated no-op",
          new Object[] {changeId, repo});
      return Response.noContent().build();
    }

    // Step 5: cross-path dedupe claim (issue #4). Claim the SHARED key <repo>:<changeId>:<revision>
    // in the DB-backed EventDedupeStore — the same key the poll scanner claims — so a change tip
    // seen
    // by BOTH the webhook and the scanner builds EXACTLY ONCE. If the scanner already claimed it,
    // markSeen returns false and we no-op. Claimed BEFORE the fallible clone below; a failure
    // releases it (mirrors PulsarScanScheduler.dispatchDiscovery) so a later delivery/scan retries.
    PulsarChangeDiscovery change = PulsarChangeDiscovery.of(repo, changeId, revision);
    if (!dedupe.markSeen(
        ScmProvider.PULSAR, change.dispatchEventId(), EventDedupeStore.Source.WEBHOOK)) {
      LOGGER.log(
          Level.FINE,
          "[pulsar] change {0}@{1} rev {2} already claimed by another path — no-op",
          new Object[] {changeId, repo, revision});
      return Response.noContent().build();
    }

    try {
      // Step 6: normalize via the merged event source (clone tip + discover
      // .titan/pipelines/*.yml).
      Optional<PulsarTrigger> trigger = triggerResolver.apply(change);
      if (trigger.isEmpty()) {
        // Honest no-op: a change with no pipeline file dispatches nothing, never an error. The
        // claim
        // is KEPT (mirrors the scan path) so a redelivery of the same empty change is not
        // re-cloned.
        return Response.noContent().build();
      }

      String triggerMeta =
          "{\"commitSha\":\"" + revision + "\",\"changeId\":\"" + changeId + "\"}";
      long buildId =
          BuildEnqueuer.enqueue(
              stores, job.get().id, "pulsar:change:" + changeId, "pulsar", triggerMeta, null);
      // Issue #1 (GitHub-Actions parity): announce the build the instant it is enqueued, BEFORE any
      // worker pickup, so PulsarCheckReporter posts the immediate PENDING check. Best-effort — a
      // dispatch failure must never fail the build insert nor flip the 202 (which would orphan the
      // committed build behind a released dedupe claim → double build on redelivery).
      fireEnqueued(buildId, job.get().id, triggerMeta);
      LOGGER.log(
          Level.INFO,
          "[pulsar] enqueued build {0} for job {1} (change {2}@{3} rev {4})",
          new Object[] {buildId, job.get().fullName, changeId, repo, revision});
      return Response.status(202)
          .entity(Map.of("accepted", true, "repo", repo, "changeId", changeId, "buildId", buildId))
          .build();
    } catch (RuntimeException e) {
      // The shared claim was taken BEFORE this fallible clone/enqueue; release it so a later
      // delivery or scan can retry (mirrors PulsarScanScheduler.dispatchDiscovery). Re-throw so the
      // node sees a non-2xx and re-delivers.
      dedupe.release(ScmProvider.PULSAR, change.dispatchEventId());
      throw e;
    }
  }

  /**
   * Fire {@link BuildEnqueuedEvent} for a freshly-enqueued Pulsar build (issue #1). The build number
   * is resolved from the just-inserted row (best-effort, {@code 0} if unreadable — the reporter does
   * not key on it). Wrapped so a sink/CDI failure is logged and swallowed: the build is already
   * committed and the webhook has already decided on a {@code 202}; an enqueue-time check is a
   * convenience, never a correctness gate.
   */
  private void fireEnqueued(long buildId, long jobId, @NonNull String triggerMeta) {
    try {
      int buildNumber = stores.builds().findById(buildId).map(b -> b.buildNumber).orElse(0);
      enqueuedSink.accept(
          new BuildEnqueuedEvent(buildId, jobId, "pulsar", triggerMeta, buildNumber));
    } catch (RuntimeException e) {
      LOGGER.log(
          Level.WARNING,
          "[pulsar] enqueue-time check report failed for build {0} (build already enqueued): {1}",
          new Object[] {buildId, e.getMessage()});
    }
  }

  // ── HMAC (mirrors GithubAppWebhookApi.verifyHmac) ────────────────────────────

  /**
   * Verify a {@code sha256=<hex>} header against the node's webhook secret using a constant-time
   * compare ({@link GithubAppWebhookSecretComparator#constantTimeEquals}).
   */
  static boolean verifyHmac(
      @NonNull String secret, @NonNull byte[] body, @NonNull String signatureHeader) {
    String prefix = "sha256=";
    if (!signatureHeader.startsWith(prefix)) {
      return false;
    }
    String hex = signatureHeader.substring(prefix.length());
    if (hex.isEmpty() || (hex.length() & 1) != 0) {
      return false;
    }
    String expectedHex;
    try {
      expectedHex = HexFormat.of().formatHex(hmacSha256(secret, body));
    } catch (RuntimeException e) {
      return false;
    }
    return GithubAppWebhookSecretComparator.constantTimeEquals(
        expectedHex, hex.toLowerCase(Locale.ROOT));
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

  // ── idempotency LRU (mirrors GithubAppWebhookApi.isDuplicateDelivery) ────────

  boolean isDuplicateDelivery(@NonNull String eventId) {
    Instant now = Instant.now();
    synchronized (deliveryLru) {
      Instant prev = deliveryLru.get(eventId);
      if (prev != null && Duration.between(prev, now).compareTo(DELIVERY_TTL_MIN) <= 0) {
        return true;
      }
      deliveryLru.put(eventId, now);
      return false;
    }
  }

  // ── helpers ──────────────────────────────────────────────────────────────────

  @NonNull
  private static String eventId(
      @NonNull String repo, @NonNull String changeId, @NonNull String revision) {
    return repo + ":" + changeId + ":" + revision;
  }

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
  private static Response problem(int status, @NonNull String slug, @NonNull String detail) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("type", "https://titan.adaptiq.io/problems/" + slug);
    body.put("title", slug);
    body.put("status", status);
    body.put("detail", detail);
    return Response.status(status).type("application/problem+json").entity(body).build();
  }
}
