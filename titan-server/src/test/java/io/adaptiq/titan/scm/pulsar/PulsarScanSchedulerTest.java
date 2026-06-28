package io.adaptiq.titan.scm.pulsar;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.api.FakeTitanStores;
import io.adaptiq.titan.scm.pulsar.PulsarEventSource.PipelineFile;
import io.adaptiq.titan.scm.pulsar.PulsarEventSource.PulsarTrigger;
import io.adaptiq.titan.scm.reconcile.EventDedupeStore;
import io.adaptiq.titan.scm.reconcile.JdbiEventDedupeStore;
import io.adaptiq.titan.scm.reconcile.ScmProvider;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.JobRow;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PulsarScanScheduler} — the boot-wired poll → build-enqueue driver (#T2).
 *
 * <p>Drives the REAL {@link PulsarRepoScanner} over WireMock-faked nodes and the REAL DB-backed
 * {@link JdbiEventDedupeStore} over an H2 {@link FakeTitanStores}, so the dedupe regression (a
 * change must not re-enqueue on the next tick) is exercised against the actual persistent store,
 * not a per-tick fake. The {@code dispatch} seam counts enqueues so a build is never written and we
 * assert on the (jobId) the tail resolved.
 */
class PulsarScanSchedulerTest {

  private static final String REPO = "r";
  private static final String CHANGE = "c1";
  private static final String REV1 = "o1";

  private TitanStores stores;
  private final List<WireMockServer> nodes = new ArrayList<>();

  /** Resolver that always returns a trigger carrying one pipeline (change HAS a pipeline). */
  private final Function<String, Function<PulsarChangeDiscovery, Optional<PulsarTrigger>>>
      withPipeline =
          nodeUrl ->
              change ->
                  Optional.of(
                      new PulsarTrigger(
                          PulsarTriggerRequest.fromChange(change),
                          List.of(
                              new PipelineFile(
                                  ".titan/pipelines/ci.yml",
                                  "stages: []".getBytes(StandardCharsets.UTF_8)))));

  @BeforeEach
  void setUp() {
    stores = FakeTitanStores.create();
  }

  @AfterEach
  void tearDown() {
    nodes.forEach(WireMockServer::stop);
  }

  // ── empty source list → no-op ────────────────────────────────────────────────

  @Test
  void emptySources_isNoOp() {
    AtomicInteger enqueued = new AtomicInteger();
    PulsarScanScheduler scheduler = scheduler(true, enqueued, withPipeline);

    int count = scheduler.tick();

    assertEquals(0, count);
    assertEquals(0, enqueued.get());
  }

  // ── disabled → fast no-op even with a registered source ──────────────────────

  @Test
  void disabled_doesNotScan() {
    seedJob(REPO);
    String node = node(oneChange(REPO, CHANGE, REV1));
    stores.pulsarSources().insert(node, "n", 1);
    AtomicInteger enqueued = new AtomicInteger();
    PulsarScanScheduler scheduler = scheduler(false, enqueued, withPipeline);

    int count = scheduler.tick();

    assertEquals(0, count);
    assertEquals(0, enqueued.get());
  }

  // ── two sources → one enqueue per fresh discovery ────────────────────────────

  @Test
  void twoSources_enqueuesOncePerFreshDiscovery() {
    seedJob(REPO);
    String nodeA = node(oneChange(REPO, "ca", "oa"));
    String nodeB = node(oneChange(REPO, "cb", "ob"));
    stores.pulsarSources().insert(nodeA, "a", 1);
    stores.pulsarSources().insert(nodeB, "b", 1);
    AtomicInteger enqueued = new AtomicInteger();
    PulsarScanScheduler scheduler = scheduler(true, enqueued, withPipeline);

    int count = scheduler.tick();

    assertEquals(2, count, "one fresh discovery per node");
    assertEquals(2, enqueued.get(), "one enqueue per fresh discovery");
  }

  // ── a source whose scan throws does NOT abort the others ─────────────────────

  @Test
  void oneNodeDown_doesNotStarveSiblings() {
    seedJob(REPO);
    String down = nodeReturning502();
    String up = node(oneChange(REPO, CHANGE, REV1));
    stores.pulsarSources().insert(down, "down", 0);
    stores.pulsarSources().insert(up, "up", 1);
    AtomicInteger enqueued = new AtomicInteger();
    PulsarScanScheduler scheduler = scheduler(true, enqueued, withPipeline);

    int count = scheduler.tick();

    assertEquals(1, count, "the healthy node's change still dispatches");
    assertEquals(1, enqueued.get());
  }

  // ── DEDUPE REGRESSION: same change across two ticks → single enqueue ─────────

  @Test
  void sameChange_acrossTwoTicks_dedupesToSingleEnqueue() {
    seedJob(REPO);
    String node = node(oneChange(REPO, CHANGE, REV1));
    stores.pulsarSources().insert(node, "n", 1);
    AtomicInteger enqueued = new AtomicInteger();
    PulsarScanScheduler scheduler = scheduler(true, enqueued, withPipeline);

    int first = scheduler.tick();
    int second = scheduler.tick();

    assertEquals(1, first, "first tick discovers the change");
    assertEquals(0, second, "second tick re-sees the SAME (repo:change:rev) — deduped");
    assertEquals(1, enqueued.get(), "the persistent dedupe store prevents a re-enqueue");
  }

  // ── REGRESSION: dispatch FAILURE releases the claim → next tick retries ──────

  @Test
  void dispatchFailure_releasesClaim_soNextTickRetries() {
    seedJob(REPO);
    String node = node(oneChange(REPO, CHANGE, REV1));
    stores.pulsarSources().insert(node, "n", 1);
    AtomicInteger enqueued = new AtomicInteger();
    AtomicInteger triggerCalls = new AtomicInteger();
    // triggerFor throws on the first tick (transient clone failure), succeeds on the second.
    Function<String, Function<PulsarChangeDiscovery, Optional<PulsarTrigger>>> flakyResolver =
        nodeUrl ->
            change -> {
              if (triggerCalls.incrementAndGet() == 1) {
                throw new PulsarApiException("clone failed: git missing", -1);
              }
              return withPipeline.apply(nodeUrl).apply(change);
            };
    PulsarScanScheduler scheduler = scheduler(true, enqueued, flakyResolver);

    scheduler.tick(); // tick 1: discovers, dispatch THROWS → claim released, nothing enqueued
    assertEquals(0, enqueued.get(), "first tick's transient failure enqueues nothing");

    int second = scheduler.tick(); // tick 2: claim was released → re-emits + re-dispatches
    assertEquals(1, second, "the released change is re-discovered on the next tick");
    assertEquals(1, enqueued.get(), "the retry now succeeds and enqueues exactly one build");
  }

  // ── empty trigger (no pipeline) KEEPS the claim → NOT re-dispatched ──────────

  @Test
  void emptyTrigger_keepsClaim_soNotReDispatched() {
    seedJob(REPO);
    String node = node(oneChange(REPO, CHANGE, REV1));
    stores.pulsarSources().insert(node, "n", 1);
    AtomicInteger enqueued = new AtomicInteger();
    AtomicInteger triggerCalls = new AtomicInteger();
    Function<String, Function<PulsarChangeDiscovery, Optional<PulsarTrigger>>> emptyResolver =
        nodeUrl ->
            change -> {
              triggerCalls.incrementAndGet();
              return Optional.empty(); // honest no-op: no pipeline file
            };
    PulsarScanScheduler scheduler = scheduler(true, enqueued, emptyResolver);

    scheduler.tick();
    scheduler.tick();

    assertEquals(0, enqueued.get(), "no pipeline ⇒ never enqueues");
    assertEquals(
        1, triggerCalls.get(), "empty is NOT a failure: claim is KEPT, so no wasted re-clone");
  }

  // ── successful enqueue stays claimed → NOT re-dispatched next tick ───────────

  @Test
  void successfulEnqueue_staysClaimed_notReDispatched() {
    seedJob(REPO);
    String node = node(oneChange(REPO, CHANGE, REV1));
    stores.pulsarSources().insert(node, "n", 1);
    AtomicInteger enqueued = new AtomicInteger();
    PulsarScanScheduler scheduler = scheduler(true, enqueued, withPipeline);

    scheduler.tick();
    scheduler.tick();

    assertEquals(1, enqueued.get(), "a successful enqueue keeps the claim — not re-dispatched");
  }

  // ── one failing discovery does not starve a sibling discovery ───────────────

  @Test
  void oneFailingDiscovery_doesNotStarveSibling() {
    seedJob(REPO);
    // One repo with two open changes ⇒ a single scan yields two discoveries; the resolver fails
    // one. The surviving sibling must still enqueue (per-discovery isolation).
    String node = twoChangeNode(REPO, "bad", "ob", "good", "og");
    stores.pulsarSources().insert(node, "n", 1);
    AtomicInteger enqueued = new AtomicInteger();
    Function<String, Function<PulsarChangeDiscovery, Optional<PulsarTrigger>>> mixedResolver =
        nodeUrl ->
            change -> {
              if (change.changeId().equals("bad")) {
                throw new PulsarApiException("clone failed for the bad change", -1);
              }
              return withPipeline.apply(nodeUrl).apply(change);
            };
    PulsarScanScheduler scheduler = scheduler(true, enqueued, mixedResolver);

    scheduler.tick();

    assertEquals(
        1, enqueued.get(), "the bad discovery's failure must not stop the sibling enqueue");
  }

  // ── no enabled job for the repo → no-op (mirrors the webhook tail) ───────────

  @Test
  void noEnabledJob_isNoOp() {
    // No job seeded for REPO.
    String node = node(oneChange(REPO, CHANGE, REV1));
    stores.pulsarSources().insert(node, "n", 1);
    AtomicInteger enqueued = new AtomicInteger();
    PulsarScanScheduler scheduler = scheduler(true, enqueued, withPipeline);

    int count = scheduler.tick();

    // The change is discovered (count reflects scanner output) but dispatch is a no-op.
    assertEquals(1, count);
    assertEquals(0, enqueued.get(), "an unlinked repo must not build");
  }

  // ── CROSS-PATH DEDUPE (issue #4): scan after a missed webhook → exactly one ──
  // The webhook for this tip was DROPPED (no claim exists), so the poll scanner must recover it:
  // discover + enqueue exactly one build.

  @Test
  void scanAfterMissedWebhook_enqueuesExactlyOne() {
    seedJob(REPO);
    String node = node(oneChange(REPO, CHANGE, REV1));
    stores.pulsarSources().insert(node, "n", 1);
    AtomicInteger enqueued = new AtomicInteger();
    PulsarScanScheduler scheduler = scheduler(true, enqueued, withPipeline);

    int count = scheduler.tick();

    assertEquals(1, count, "the dropped-webhook change is recovered by the scan");
    assertEquals(1, enqueued.get(), "scan-after-missed-webhook enqueues exactly one build");
  }

  // ── CROSS-PATH DEDUPE (issue #4): webhook-then-scan at same tip → scan no-ops ─
  // The webhook already claimed this exact tip in the SHARED EventDedupeStore (Source.WEBHOOK).
  // The scan re-sees the same <repo>:<changeId>:<revision> key and must skip — zero second build.
  // Drop the webhook's claim (the pre-seed below) and the scan would enqueue → double build.

  @Test
  void scanAfterWebhookClaim_sameTip_doesNotDoubleBuild() {
    seedJob(REPO);
    String node = node(oneChange(REPO, CHANGE, REV1));
    stores.pulsarSources().insert(node, "n", 1);
    AtomicInteger enqueued = new AtomicInteger();
    EventDedupeStore shared = new JdbiEventDedupeStore(stores);
    // The webhook hot-path already dispatched this tip → it claimed the shared key as WEBHOOK.
    String sharedKey = PulsarChangeDiscovery.dispatchEventId(REPO, CHANGE, REV1);
    shared.markSeen(ScmProvider.PULSAR, sharedKey, EventDedupeStore.Source.WEBHOOK);

    PulsarScanScheduler scheduler = scheduler(true, enqueued, withPipeline, shared, c -> {});
    int count = scheduler.tick();

    assertEquals(0, count, "the scan must not re-discover a tip the webhook already claimed");
    assertEquals(
        0, enqueued.get(), "webhook-then-scan at the same tip enqueues exactly one (zero here)");
  }

  // ── AUDIT (issue #4): a scan-recovered build emits SCM_WEBHOOK_RECOVERED ─────

  @Test
  void recoveredBuild_emitsOneReconcileAudit() {
    seedJob(REPO);
    String node = node(oneChange(REPO, CHANGE, REV1));
    stores.pulsarSources().insert(node, "n", 1);
    AtomicInteger enqueued = new AtomicInteger();
    List<PulsarChangeDiscovery> recovered = new ArrayList<>();
    PulsarScanScheduler scheduler =
        scheduler(true, enqueued, withPipeline, new JdbiEventDedupeStore(stores), recovered::add);

    scheduler.tick();

    assertEquals(1, enqueued.get());
    assertEquals(1, recovered.size(), "a scan-recovered build emits exactly one audit event");
    assertEquals(REV1, recovered.get(0).revision(), "audit carries the recovered change tip");
  }

  // ── AUDIT adversarial: an honest no-op (no pipeline) emits NO audit ──────────

  @Test
  void noBuild_emitsNoReconcileAudit() {
    seedJob(REPO);
    String node = node(oneChange(REPO, CHANGE, REV1));
    stores.pulsarSources().insert(node, "n", 1);
    AtomicInteger enqueued = new AtomicInteger();
    List<PulsarChangeDiscovery> recovered = new ArrayList<>();
    Function<String, Function<PulsarChangeDiscovery, Optional<PulsarTrigger>>> emptyResolver =
        nodeUrl -> change -> Optional.empty();
    PulsarScanScheduler scheduler =
        scheduler(true, enqueued, emptyResolver, new JdbiEventDedupeStore(stores), recovered::add);

    scheduler.tick();

    assertEquals(0, enqueued.get());
    assertTrue(recovered.isEmpty(), "no build ⇒ no reconcile-recovered audit");
  }

  // ── helpers ──────────────────────────────────────────────────────────────────

  private PulsarScanScheduler scheduler(
      boolean enabled,
      AtomicInteger enqueueCounter,
      Function<String, Function<PulsarChangeDiscovery, Optional<PulsarTrigger>>> resolver) {
    return scheduler(enabled, enqueueCounter, resolver, new JdbiEventDedupeStore(stores), c -> {});
  }

  private PulsarScanScheduler scheduler(
      boolean enabled,
      AtomicInteger enqueueCounter,
      Function<String, Function<PulsarChangeDiscovery, Optional<PulsarTrigger>>> resolver,
      EventDedupeStore dedupe,
      java.util.function.Consumer<PulsarChangeDiscovery> onRecovered) {
    PulsarScanScheduler.BuildDispatch dispatch =
        (s, jobId, triggeredBy, triggerType, meta, params) -> {
          assertEquals("pulsar", triggerType, "trigger tail must mirror PulsarWebhookApi");
          assertTrue(triggeredBy.startsWith("pulsar:change:"), "triggeredBy shape mismatch");
          return enqueueCounter.incrementAndGet();
        };
    PulsarScanScheduler.ReconcileAudit audit =
        (change, jobId, buildId) -> onRecovered.accept(change);
    Function<String, PulsarClient> clientForNode = PulsarClient::new;
    return new PulsarScanScheduler(
        stores, dedupe, enabled, clientForNode, resolver, dispatch, audit);
  }

  private long seedJob(@NonNull String fullName) {
    JobRow row = new JobRow();
    row.fullName = fullName;
    row.enabled = true;
    row.pipelineScript = "";
    row.configJson = "{}";
    return stores.jobs().insert(row);
  }

  /** Start a WireMock node serving one open change and return its base URL. */
  private String node(@NonNull NodeFixture fixture) {
    WireMockServer wm = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    wm.start();
    nodes.add(wm);
    wm.stubFor(get(urlEqualTo("/_pulsar/repos")).willReturn(json(fixture.reposJson)));
    wm.stubFor(
        get(urlEqualTo("/_pulsar/ledger/" + fixture.repo + "/changes"))
            .willReturn(json(fixture.changesJson)));
    return "http://localhost:" + wm.port();
  }

  /** Start a node serving ONE repo with TWO open changes — for per-discovery isolation tests. */
  private String twoChangeNode(
      @NonNull String repo,
      @NonNull String changeA,
      @NonNull String revA,
      @NonNull String changeB,
      @NonNull String revB) {
    WireMockServer wm = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    wm.start();
    nodes.add(wm);
    wm.stubFor(
        get(urlEqualTo("/_pulsar/repos"))
            .willReturn(json("{\"repos\":[{\"name\":\"" + repo + "\"}]}")));
    wm.stubFor(
        get(urlEqualTo("/_pulsar/ledger/" + repo + "/changes"))
            .willReturn(
                json(
                    "[{\"id\":\""
                        + changeA
                        + "\",\"status\":\"open\",\"revision\":{\"tip\":\""
                        + revA
                        + "\"}},{\"id\":\""
                        + changeB
                        + "\",\"status\":\"open\",\"revision\":{\"tip\":\""
                        + revB
                        + "\"}}]")));
    return "http://localhost:" + wm.port();
  }

  private String nodeReturning502() {
    WireMockServer wm = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    wm.start();
    nodes.add(wm);
    wm.stubFor(get(urlEqualTo("/_pulsar/repos")).willReturn(aResponse().withStatus(502)));
    return "http://localhost:" + wm.port();
  }

  private static NodeFixture oneChange(String repo, String changeId, String revision) {
    // Live shape: bare-array /changes, tip carried inline at revision.tip (no /refs needed).
    return new NodeFixture(
        repo,
        "{\"repos\":[{\"name\":\"" + repo + "\"}]}",
        "[{\"id\":\""
            + changeId
            + "\",\"status\":\"open\",\"revision\":{\"tip\":\""
            + revision
            + "\"}}]");
  }

  private static ResponseDefinitionBuilder json(String body) {
    return aResponse()
        .withStatus(200)
        .withHeader("Content-Type", "application/json")
        .withBody(body);
  }

  private record NodeFixture(String repo, String reposJson, String changesJson) {}
}
