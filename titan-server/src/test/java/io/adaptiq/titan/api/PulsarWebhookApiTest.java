package io.adaptiq.titan.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.scm.pulsar.PulsarChangeDiscovery;
import io.adaptiq.titan.scm.pulsar.PulsarEventSource.PipelineFile;
import io.adaptiq.titan.scm.pulsar.PulsarEventSource.PulsarTrigger;
import io.adaptiq.titan.scm.pulsar.PulsarTriggerRequest;
import io.adaptiq.titan.scm.reconcile.EventDedupeStore;
import io.adaptiq.titan.scm.reconcile.JdbiEventDedupeStore;
import io.adaptiq.titan.scm.reconcile.ScmProvider;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.JobRow;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PulsarWebhookApi} — covers acceptance criteria from issue #1287:
 *
 * <ul>
 *   <li>signed change-event + linked job + pipeline present → 202 + build enqueued (triggerType
 *       {@code pulsar}).
 *   <li>bad / missing HMAC → 401, no build, body never parsed.
 *   <li>change with no {@code .titan/pipelines/*.yml} → 2xx, no build (honest no-op).
 *   <li>duplicate delivery (same event id) → deduped, single build.
 * </ul>
 *
 * <p>Strategy mirrors {@link GithubAppWebhookApiTest}: a real H2-backed {@link TitanStores} via
 * {@link FakeTitanStores} (real DAOs / real enqueue transaction), the SUT constructed with a {@code
 * Supplier<Optional<String>>} secret seam and a {@code Function} trigger seam so the unit test
 * never shells out to git — the real clone+discover path is covered by {@code
 * PulsarCloneDiscoveryIT}.
 */
class PulsarWebhookApiTest {

  private static final String SECRET = "test-pulsar-webhook-secret";
  private static final String REPO = "acme/web";
  private static final String CHANGE_ID = "42";
  private static final String REVISION = "abcdef1234567890";

  private TitanStores stores;
  private EventDedupeStore dedupe;

  /** A resolver that always returns a trigger carrying one pipeline (change HAS a pipeline). */
  private final Function<PulsarChangeDiscovery, Optional<PulsarTrigger>> withPipeline =
      change ->
          Optional.of(
              new PulsarTrigger(
                  PulsarTriggerRequest.fromChange(change),
                  List.of(
                      new PipelineFile(
                          ".titan/pipelines/ci.yml",
                          "stages: []".getBytes(StandardCharsets.UTF_8)))));

  /** A resolver that always returns empty (change has NO pipeline file). */
  private final Function<PulsarChangeDiscovery, Optional<PulsarTrigger>> noPipeline =
      change -> Optional.empty();

  @BeforeEach
  void setUp() {
    stores = FakeTitanStores.create();
    dedupe = new JdbiEventDedupeStore(stores);
  }

  private PulsarWebhookApi api(Function<PulsarChangeDiscovery, Optional<PulsarTrigger>> resolver) {
    return new PulsarWebhookApi(stores, () -> Optional.of(SECRET), dedupe, resolver);
  }

  // ── 1. signed event + linked job + pipeline → 202 + enqueue ──────────────────

  @Test
  void signedEvent_jobLinked_pipelinePresent_enqueuesBuild() {
    long jobId = seedJob(REPO);
    byte[] body = changeBody(REPO, CHANGE_ID, REVISION);

    Response resp = api(withPipeline).receive(headers(sig(SECRET, body), "delivery-1"), body);

    assertEquals(202, resp.getStatus());
    assertEquals(1, stores.builds().listByJob(jobId).size());
    assertEquals("pulsar", stores.builds().listByJob(jobId).get(0).triggerType);
  }

  // ── 2. bad HMAC → 401, no build, no parse ────────────────────────────────────

  @Test
  void invalidSignature_returns401_noEnqueue() {
    long jobId = seedJob(REPO);
    byte[] body = changeBody(REPO, CHANGE_ID, REVISION);

    Response resp =
        api(withPipeline).receive(headers(sig("wrong-secret", body), "delivery-2"), body);

    assertEquals(401, resp.getStatus());
    assertTrue(stores.builds().listByJob(jobId).isEmpty());
  }

  // ── 3. missing signature → 401 ───────────────────────────────────────────────

  @Test
  void missingSignature_returns401() {
    byte[] body = changeBody(REPO, CHANGE_ID, REVISION);
    Response resp = api(withPipeline).receive(headers(null, "delivery-3"), body);
    assertEquals(401, resp.getStatus());
  }

  // ── 4. no secret configured → 401, not 500 ───────────────────────────────────

  @Test
  void noSecretConfigured_returns401() {
    PulsarWebhookApi api = new PulsarWebhookApi(stores, Optional::empty, dedupe, withPipeline);
    byte[] body = changeBody(REPO, CHANGE_ID, REVISION);
    Response resp = api.receive(headers("sha256=deadbeef", "delivery-4"), body);
    assertEquals(401, resp.getStatus());
  }

  // ── 5. change with no pipeline → 2xx, no build (honest no-op) ─────────────────

  @Test
  void noPipelineFile_returns204_noBuild() {
    long jobId = seedJob(REPO);
    byte[] body = changeBody(REPO, CHANGE_ID, REVISION);

    Response resp = api(noPipeline).receive(headers(sig(SECRET, body), "delivery-5"), body);

    assertEquals(204, resp.getStatus());
    assertTrue(stores.builds().listByJob(jobId).isEmpty());
  }

  // ── 6. duplicate delivery (same id) → deduped, single build ──────────────────

  @Test
  void duplicateDelivery_sameId_dedupesToSingleBuild() {
    long jobId = seedJob(REPO);
    byte[] body = changeBody(REPO, CHANGE_ID, REVISION);
    String signature = sig(SECRET, body);
    PulsarWebhookApi api = api(withPipeline);

    Response first = api.receive(headers(signature, "delivery-6"), body);
    Response second = api.receive(headers(signature, "delivery-6"), body);

    assertEquals(202, first.getStatus());
    assertEquals(204, second.getStatus());
    assertEquals(1, stores.builds().listByJob(jobId).size(), "redelivery must not double-build");
  }

  // ── 6b. duplicate by derived event id (no delivery header) → deduped ─────────

  @Test
  void duplicateDelivery_noHeader_dedupesByDerivedEventId() {
    long jobId = seedJob(REPO);
    byte[] body = changeBody(REPO, CHANGE_ID, REVISION);
    String signature = sig(SECRET, body);
    PulsarWebhookApi api = api(withPipeline);

    Response first = api.receive(headers(signature, null), body);
    Response second = api.receive(headers(signature, null), body);

    assertEquals(202, first.getStatus());
    assertEquals(204, second.getStatus());
    assertEquals(1, stores.builds().listByJob(jobId).size());
  }

  // ── 7. no job linked to the repo → authenticated no-op ───────────────────────

  @Test
  void noJobForRepo_returns204_noBuild() {
    // No job seeded for REPO.
    byte[] body = changeBody(REPO, CHANGE_ID, REVISION);
    Response resp = api(withPipeline).receive(headers(sig(SECRET, body), "delivery-7"), body);
    assertEquals(204, resp.getStatus());
  }

  // ── 7b. disabled job → authenticated no-op (operator's hard stop) ────────────

  @Test
  void disabledJob_returns204_noBuild() {
    JobRow row = new JobRow();
    row.fullName = REPO;
    row.enabled = false;
    row.pipelineScript = "";
    row.configJson = "{}";
    long jobId = stores.jobs().insert(row);
    byte[] body = changeBody(REPO, CHANGE_ID, REVISION);

    Response resp = api(withPipeline).receive(headers(sig(SECRET, body), "delivery-7b"), body);

    assertEquals(204, resp.getStatus());
    assertTrue(
        stores.builds().listByJob(jobId).isEmpty(), "a disabled job must not build on a change");
  }

  // ── 8. adversarial: malformed payload (missing revision) → 400 ───────────────

  @Test
  void malformedPayload_missingRevision_returns400() {
    seedJob(REPO);
    byte[] body =
        ("{\"repo\":\"" + REPO + "\",\"changeId\":\"" + CHANGE_ID + "\"}")
            .getBytes(StandardCharsets.UTF_8);
    Response resp = api(withPipeline).receive(headers(sig(SECRET, body), "delivery-8"), body);
    assertEquals(400, resp.getStatus());
  }

  // ── 9. adversarial: a different revision is NOT deduped (new build owed) ──────

  @Test
  void advancedRevision_isNotDeduped_secondBuildEnqueued() {
    long jobId = seedJob(REPO);
    PulsarWebhookApi api = api(withPipeline);

    byte[] body1 = changeBody(REPO, CHANGE_ID, "rev-one");
    byte[] body2 = changeBody(REPO, CHANGE_ID, "rev-two");

    api.receive(headers(sig(SECRET, body1), null), body1);
    api.receive(headers(sig(SECRET, body2), null), body2);

    assertEquals(
        2,
        stores.builds().listByJob(jobId).size(),
        "a change whose tip advanced owes a fresh build");
  }

  // ── 10. CROSS-PATH DEDUPE (issue #4): webhook claims the SHARED key ──────────
  // After the webhook builds a tip, the poll scanner (which dedupes on the SAME shared
  // <repo>:<changeId>:<revision> key via EventDedupeStore) must find it already claimed and skip —
  // so webhook-then-scan at the same tip builds EXACTLY ONCE. Removing the webhook's markSeen claim
  // makes `scanWouldDispatch` flip to true → this test goes red (the double-build regression).

  @Test
  void webhookThenScan_sameTip_buildsOnce_viaSharedDedupe() {
    long jobId = seedJob(REPO);
    byte[] body = changeBody(REPO, CHANGE_ID, REVISION);

    Response resp = api(withPipeline).receive(headers(sig(SECRET, body), "delivery-x"), body);
    assertEquals(202, resp.getStatus());
    assertEquals(1, stores.builds().listByJob(jobId).size());

    // Simulate the poll scanner re-seeing the SAME change tip: it claims the shared key as RECONCILE
    // exactly as PulsarRepoScanner does. The webhook already claimed it → markSeen returns false.
    String sharedKey = PulsarChangeDiscovery.dispatchEventId(REPO, CHANGE_ID, REVISION);
    boolean scanWouldDispatch =
        dedupe.markSeen(ScmProvider.PULSAR, sharedKey, EventDedupeStore.Source.RECONCILE);

    assertFalse(scanWouldDispatch, "the scanner must NOT re-dispatch a tip the webhook already built");
    assertEquals(
        1,
        stores.builds().listByJob(jobId).size(),
        "webhook-then-scan at the same tip builds exactly once");
  }

  // ── 11. CROSS-PATH (issue #4): scan-first then webhook no-ops ────────────────
  // Inverse direction: the scanner claimed the tip first (it recovered a dropped webhook). A late
  // webhook for the SAME tip must no-op (204), not double-build.

  @Test
  void scanFirstThenWebhook_sameTip_webhookNoOps() {
    long jobId = seedJob(REPO);
    // The poll scanner already claimed + built this exact tip (Source.RECONCILE).
    String sharedKey = PulsarChangeDiscovery.dispatchEventId(REPO, CHANGE_ID, REVISION);
    dedupe.markSeen(ScmProvider.PULSAR, sharedKey, EventDedupeStore.Source.RECONCILE);

    byte[] body = changeBody(REPO, CHANGE_ID, REVISION);
    Response resp = api(withPipeline).receive(headers(sig(SECRET, body), "delivery-y"), body);

    assertEquals(204, resp.getStatus(), "a late webhook for an already-scanned tip is a no-op");
    assertTrue(
        stores.builds().listByJob(jobId).isEmpty(),
        "scan-then-webhook at the same tip must not produce a second build");
  }

  // ── 12. CROSS-PATH adversarial: dispatch failure RELEASES the shared claim ───
  // The claim is taken BEFORE the fallible clone; if the clone throws, the claim must be released so
  // a later scan/delivery can retry — otherwise the change is permanently dropped.

  @Test
  void webhookDispatchFailure_releasesSharedClaim_soScanCanRetry() {
    seedJob(REPO);
    Function<PulsarChangeDiscovery, Optional<PulsarTrigger>> boom =
        change -> {
          throw new RuntimeException("clone failed: git missing");
        };
    byte[] body = changeBody(REPO, CHANGE_ID, REVISION);

    try {
      api(boom).receive(headers(sig(SECRET, body), "delivery-z"), body);
    } catch (RuntimeException expected) {
      // re-thrown so the node re-delivers — expected
    }

    String sharedKey = PulsarChangeDiscovery.dispatchEventId(REPO, CHANGE_ID, REVISION);
    boolean scanCanClaim =
        dedupe.markSeen(ScmProvider.PULSAR, sharedKey, EventDedupeStore.Source.RECONCILE);
    assertTrue(scanCanClaim, "a failed webhook dispatch must release the shared claim for retry");
  }

  // ── HMAC primitive coverage ──────────────────────────────────────────────────

  @Test
  void verifyHmac_rejectsMissingPrefix() {
    byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
    assertFalse(PulsarWebhookApi.verifyHmac(SECRET, body, hex(SECRET, body)));
  }

  @Test
  void verifyHmac_acceptsCorrectSignature() {
    byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
    assertTrue(PulsarWebhookApi.verifyHmac(SECRET, body, "sha256=" + hex(SECRET, body)));
  }

  @Test
  void verifyHmac_rejectsTamperedBody() {
    byte[] body = "{\"x\":1}".getBytes(StandardCharsets.UTF_8);
    byte[] tampered = "{\"x\":2}".getBytes(StandardCharsets.UTF_8);
    assertFalse(PulsarWebhookApi.verifyHmac(SECRET, tampered, "sha256=" + hex(SECRET, body)));
  }

  // ── helpers ──────────────────────────────────────────────────────────────────

  private long seedJob(@NonNull String fullName) {
    JobRow row = new JobRow();
    row.fullName = fullName;
    row.enabled = true;
    row.pipelineScript = "";
    row.configJson = "{}";
    return stores.jobs().insert(row);
  }

  @NonNull
  private static byte[] changeBody(
      @NonNull String repo, @NonNull String changeId, @NonNull String revision) {
    return ("{\"repo\":\""
            + repo
            + "\",\"changeId\":\""
            + changeId
            + "\",\"revision\":\""
            + revision
            + "\"}")
        .getBytes(StandardCharsets.UTF_8);
  }

  @NonNull
  private static String sig(@NonNull String secret, @NonNull byte[] body) {
    return "sha256=" + hex(secret, body);
  }

  @NonNull
  private static String hex(@NonNull String secret, @NonNull byte[] body) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      byte[] digest = mac.doFinal(body);
      StringBuilder sb = new StringBuilder(digest.length * 2);
      for (byte b : digest) {
        sb.append(String.format("%02x", b));
      }
      return sb.toString();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @NonNull
  private static HttpHeaders headers(String signature, String deliveryId) {
    Map<String, String> map = new HashMap<>();
    if (signature != null) {
      map.put("X-Pulsar-Signature-256", signature);
    }
    if (deliveryId != null) {
      map.put("X-Pulsar-Delivery", deliveryId);
    }
    return new StubHeaders(map);
  }

  static final class StubHeaders implements HttpHeaders {
    private final Map<String, String> headers;

    StubHeaders(@NonNull Map<String, String> headers) {
      this.headers = headers;
    }

    @Override
    public String getHeaderString(String name) {
      for (var e : headers.entrySet()) {
        if (e.getKey().equalsIgnoreCase(name)) {
          return e.getValue();
        }
      }
      return null;
    }

    @Override
    public List<String> getRequestHeader(String name) {
      String v = getHeaderString(name);
      return v == null ? List.of() : List.of(v);
    }

    @Override
    public MultivaluedMap<String, String> getRequestHeaders() {
      MultivaluedMap<String, String> out = new MultivaluedHashMap<>();
      headers.forEach(out::add);
      return out;
    }

    @Override
    public List<jakarta.ws.rs.core.MediaType> getAcceptableMediaTypes() {
      return List.of();
    }

    @Override
    public List<java.util.Locale> getAcceptableLanguages() {
      return List.of();
    }

    @Override
    public jakarta.ws.rs.core.MediaType getMediaType() {
      return null;
    }

    @Override
    public java.util.Locale getLanguage() {
      return null;
    }

    @Override
    public Map<String, jakarta.ws.rs.core.Cookie> getCookies() {
      return Map.of();
    }

    @Override
    public java.util.Date getDate() {
      return null;
    }

    @Override
    public int getLength() {
      return -1;
    }
  }
}
