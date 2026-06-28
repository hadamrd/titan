package io.adaptiq.titan.scm.pulsar;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.api.FakeTitanStores;
import io.adaptiq.titan.build.BuildEnqueuer;
import io.adaptiq.titan.build.BuildStateChangedEvent;
import io.adaptiq.titan.scm.reconcile.JdbiEventDedupeStore;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.BuildRow;
import io.adaptiq.titan.store.rows.JobRow;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Closed-loop integration test for the Titan↔Pulsar CI cycle (issue #2). Where {@code
 * PulsarWebhookEnqueueIT} tests only the <em>left half</em> (signed change webhook → {@code QUEUED}
 * build) and {@code PulsarCheckReporterTest} tests only the <em>right half</em> (a hand-built {@link
 * BuildStateChangedEvent} → ledger POST), this IT joins them into ONE loop and proves the {@code
 * changeId} that arrives on the webhook is the SAME {@code changeId} the reporter posts the verdict
 * back to.
 *
 * <p><strong>No fabricated event.</strong> The {@link BuildStateChangedEvent} driven into the
 * reporter is constructed entirely from the build row the real {@link
 * io.adaptiq.titan.api.PulsarWebhookApi#receive} enqueued — its {@code triggerType}, {@code
 * triggerMetaJson}, {@code jobId} and {@code buildId} are read straight back out of {@code
 * stores.builds().listByJob(jobId)}. No {@code changeId} literal is ever typed into the right leg.
 * If the webhook's {@code triggerMetaJson} {@code changeId} key were renamed/dropped, or {@code
 * PulsarCheckReporter.extractChangeId} broke, the reporter would resolve no change and post nothing
 * — so the happy-path {@code verify(1, ...)} below turns RED. That is the regression class this
 * ticket exists to guard.
 *
 * <p><strong>Left leg is real:</strong> an on-disk git repo with {@code .titan/pipelines/ci.yml}
 * published under {@code refs/pulsar/changes/<id>} into a bare mirror, reached over {@code file://}
 * by a real {@link PulsarEventSource} (same fixture style as {@code PulsarWebhookEnqueueIT}). <br>
 * <strong>Right leg is real:</strong> a WireMock server stands in for the Pulsar ledger node; the
 * real {@link PulsarCheckReporter} + {@link PulsarClient} point at it (same style as {@code
 * PulsarCheckReporterTest}). No real Pulsar node, no worker container.
 */
class PulsarFullLoopIT {

  private static final String REPO = "acme/web";
  private static final String SECRET = "it-pulsar-secret";
  /** The repo as it appears URL-encoded on the ledger path — {@code acme/web} → {@code acme%2Fweb}. */
  private static final String ENC_REPO = URLEncoder.encode(REPO, StandardCharsets.UTF_8);

  private static final String CHANGE_GREEN = "CA";
  private static final String CHANGE_RED = "CF";
  /** A change id that NOTHING in this loop touches — used to prove the verdict is not mis-routed. */
  private static final String CHANGE_DECOY = "CZ";

  @TempDir Path root;

  private Path nodes;
  private String revision;
  private WireMockServer wiremock;
  private TitanStores stores;
  private io.adaptiq.titan.api.PulsarWebhookApi api;
  private PulsarCheckReporter reporter;
  private long jobId;

  @BeforeEach
  void setUp() throws Exception {
    buildGitFixture();

    wiremock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    wiremock.start();

    stores = FakeTitanStores.create();
    JobRow row = new JobRow();
    row.fullName = REPO;
    row.enabled = true;
    row.pipelineScript = "";
    row.configJson = "{}";
    jobId = stores.jobs().insert(row);

    // Left leg — real PulsarWebhookApi over a real PulsarEventSource pointed at the file:// mirror.
    api =
        new io.adaptiq.titan.api.PulsarWebhookApi(
            stores,
            new JdbiEventDedupeStore(stores),
            Optional.of(SECRET),
            nodes.toUri().toString());

    // Right leg — real reporter + client pointed at the WireMock ledger node.
    PulsarClient client = new PulsarClient("http://localhost:" + wiremock.port());
    reporter = new PulsarCheckReporter(stores, client, "https://titan.example.com", true);
  }

  @AfterEach
  void tearDown() {
    if (wiremock != null) {
      wiremock.stop();
    }
  }

  // ── happy path: green build → success verdict on the SAME change ─────────────

  @Test
  void signedChange_buildsGreen_reportsSuccessToSameChange() {
    wiremock.stubFor(post(urlMatching("/_pulsar/.*")).willReturn(aResponse().withStatus(201)));

    // Drive the full loop: webhook enqueue → read the build's OWN trigger meta back → report green.
    BuildStateChangedEvent terminal = enqueueViaWebhookThenTerminal(CHANGE_GREEN, "SUCCESS");
    reporter.report(terminal);

    // Exactly one CI verdict, on the change the webhook triggered, carrying the gate-clearing shape.
    wiremock.verify(
        1,
        postRequestedFor(urlEqualTo(eventsUrl(CHANGE_GREEN)))
            .withRequestBody(containing("\"kind\":\"ci\""))
            .withRequestBody(containing("\"check\":\"build\""))
            .withRequestBody(containing("\"conclusion\":\"success\"")));

    // Same-change, not a decoy: the verdict must NOT land on any other change url.
    wiremock.verify(0, postRequestedFor(urlEqualTo(eventsUrl(CHANGE_DECOY))));
    wiremock.verify(
        0,
        postRequestedFor(
            urlMatching("/_pulsar/ledger/[^/]+/changes/(?!" + CHANGE_GREEN + "/)[^/]+/events")));
  }

  // ── failure verdict: red build → failure on the SAME change ──────────────────

  @Test
  void signedChange_buildFails_reportsFailureToSameChange() {
    wiremock.stubFor(post(urlMatching("/_pulsar/.*")).willReturn(aResponse().withStatus(201)));

    BuildStateChangedEvent terminal = enqueueViaWebhookThenTerminal(CHANGE_RED, "FAILED");
    reporter.report(terminal);

    wiremock.verify(
        1,
        postRequestedFor(urlEqualTo(eventsUrl(CHANGE_RED)))
            .withRequestBody(containing("\"kind\":\"ci\""))
            .withRequestBody(containing("\"check\":\"build\""))
            .withRequestBody(containing("\"conclusion\":\"failure\"")));

    wiremock.verify(0, postRequestedFor(urlEqualTo(eventsUrl(CHANGE_DECOY))));
    wiremock.verify(
        0,
        postRequestedFor(
            urlMatching("/_pulsar/ledger/[^/]+/changes/(?!" + CHANGE_RED + "/)[^/]+/events")));
  }

  // ── adversarial: a manual build never reaches the ledger ─────────────────────

  @Test
  void manualBuild_neverReportsToLedger() {
    wiremock.stubFor(post(urlMatching("/_pulsar/.*")).willReturn(aResponse().withStatus(201)));

    // A real manually-triggered build through the SAME enqueue seam — trigger type "manual", no
    // changeId in meta. Built (not fabricated) so the reporter sees real, non-Pulsar provenance.
    BuildEnqueuer.enqueue(stores, jobId, "alice", "manual", "{\"actor\":\"alice\"}", null);
    BuildRow manual = onlyBuild();
    assertEquals("manual", manual.triggerType, "fixture sanity: build is manually triggered");

    reporter.report(
        new BuildStateChangedEvent(
            manual.id,
            "SUCCESS",
            manual.triggerType,
            manual.triggerMetaJson,
            manual.jobId,
            manual.buildNumber));

    wiremock.verify(0, postRequestedFor(urlMatching("/_pulsar/.*")));
  }

  // ── loop driver ──────────────────────────────────────────────────────────────

  /**
   * Drive the LEFT leg for {@code changeId} (signed webhook → real clone/discovery → {@code QUEUED}
   * build), then read the enqueued build row back and synthesise the terminal {@link
   * BuildStateChangedEvent} EXCLUSIVELY from that row — never a typed {@code changeId} literal. This
   * is the join point that makes the loop closed: the right leg can only post to the change the left
   * leg actually threaded through {@code triggerMetaJson}.
   */
  @NonNull
  private BuildStateChangedEvent enqueueViaWebhookThenTerminal(
      @NonNull String changeId, @NonNull String terminalStatus) {
    byte[] body = changeBody(REPO, changeId, revision);
    Response resp = api.receive(headers(sig(SECRET, body), "delivery-" + changeId), body);
    assertEquals(202, resp.getStatus(), "webhook must enqueue a build for change " + changeId);

    BuildRow row = onlyBuild();
    // Provenance sanity — these are the values the reporter will thread, not author-typed literals.
    assertTrue(
        row.triggerType != null && row.triggerType.startsWith("pulsar"),
        "trigger type must originate from the real webhook enqueue");
    assertNotNull(row.triggerMetaJson, "trigger meta must originate from the real webhook enqueue");
    assertTrue(
        row.triggerMetaJson.contains(changeId),
        "the enqueued build must carry the webhook's own changeId in its trigger meta");

    return new BuildStateChangedEvent(
        row.id, terminalStatus, row.triggerType, row.triggerMetaJson, row.jobId, row.buildNumber);
  }

  @NonNull
  private BuildRow onlyBuild() {
    List<BuildRow> builds = stores.builds().listByJob(jobId);
    assertEquals(1, builds.size(), "expected exactly one build for the job in this test");
    return builds.get(0);
  }

  @NonNull
  private static String eventsUrl(@NonNull String changeId) {
    return "/_pulsar/ledger/" + ENC_REPO + "/changes/" + changeId + "/events";
  }

  // ── git fixture (mirrors PulsarWebhookEnqueueIT) ─────────────────────────────

  private void buildGitFixture() throws Exception {
    nodes = root.resolve("nodes");
    Path work = root.resolve("work");
    Files.createDirectories(work);

    git(work, "init", "-q", "-b", "main", ".");
    write(work, ".titan/pipelines/ci.yml", "stages:\n  - name: build\n");
    git(work, "add", "-A");
    git(work, "-c", "user.email=t@titan", "-c", "user.name=titan", "commit", "-q", "-m", "ci");
    revision = git(work, "rev-parse", "HEAD").trim();

    // Publish every change id this IT references under its own refs/pulsar/changes/<id>.
    git(work, "update-ref", "refs/pulsar/changes/" + CHANGE_GREEN, revision);
    git(work, "update-ref", "refs/pulsar/changes/" + CHANGE_RED, revision);

    Path mirror = nodes.resolve(REPO + ".git");
    Files.createDirectories(mirror.getParent());
    git(root, "clone", "-q", "--mirror", work.toUri().toString(), mirror.toString());
  }

  private static String git(Path cwd, String... args) throws Exception {
    String[] cmd = new String[args.length + 1];
    cmd[0] = "git";
    System.arraycopy(args, 0, cmd, 1, args.length);
    ProcessBuilder pb = new ProcessBuilder(cmd).directory(cwd.toFile());
    pb.redirectErrorStream(true);
    Process p = pb.start();
    String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    if (!p.waitFor(60, TimeUnit.SECONDS)) {
      p.destroyForcibly();
      throw new IllegalStateException("git " + args[0] + " timed out");
    }
    if (p.exitValue() != 0) {
      throw new IllegalStateException("git " + args[0] + " exit=" + p.exitValue() + ": " + out);
    }
    return out;
  }

  private static void write(Path repo, String relPath, String content) {
    try {
      Path target = repo.resolve(relPath);
      Files.createDirectories(target.getParent());
      Files.writeString(target, content);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  // ── webhook http helpers (mirror PulsarWebhookEnqueueIT) ─────────────────────

  @NonNull
  private static byte[] changeBody(
      @NonNull String repo, @NonNull String changeId, @NonNull String rev) {
    return ("{\"repo\":\""
            + repo
            + "\",\"changeId\":\""
            + changeId
            + "\",\"revision\":\""
            + rev
            + "\"}")
        .getBytes(StandardCharsets.UTF_8);
  }

  @NonNull
  private static String sig(@NonNull String secret, @NonNull byte[] body) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      byte[] digest = mac.doFinal(body);
      StringBuilder sb = new StringBuilder(digest.length * 2);
      for (byte b : digest) {
        sb.append(String.format("%02x", b));
      }
      return "sha256=" + sb;
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
