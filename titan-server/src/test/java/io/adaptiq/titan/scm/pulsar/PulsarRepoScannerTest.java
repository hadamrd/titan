package io.adaptiq.titan.scm.pulsar;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.adaptiq.titan.scm.reconcile.EventDedupeStore;
import io.adaptiq.titan.scm.reconcile.ScmProvider;
import io.adaptiq.titan.scm.reconcile.ScmReconcileException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link PulsarRepoScanner} with a Pulsar node faked via WireMock — mirrors the
 * github/bitbucket scanner ITs (issue #1280).
 *
 * <p>Acceptance criteria under test:
 *
 * <ul>
 *   <li>scanner lists open changes for a repo and yields one discovery item per open change with
 *       {@code {repo, changeId, ref=refs/pulsar/changes/<id>, revision}};
 *   <li>a change already built (seen) is not re-emitted (dedupe);
 *   <li>node 5xx / malformed JSON → typed {@link ScmReconcileException}, not a silent empty scan.
 * </ul>
 */
class PulsarRepoScannerTest {

  private WireMockServer wiremock;
  private FakeDedupeStore dedupe;
  private PulsarRepoScanner scanner;

  @BeforeEach
  void setUp() {
    wiremock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    wiremock.start();
    dedupe = new FakeDedupeStore();
    PulsarClient client = new PulsarClient("http://localhost:" + wiremock.port());
    scanner = new PulsarRepoScanner(client, dedupe);
  }

  @AfterEach
  void tearDown() {
    if (wiremock != null) {
      wiremock.stop();
    }
  }

  // ── happy path ──────────────────────────────────────────────────────────────

  @Test
  void scanRepo_yieldsOneDiscoveryPerOpenChange_withRefAndRevision() throws Exception {
    // Tip comes straight from revision.tip in the /changes payload — no /refs lookup.
    stubChanges(
        "repo-a",
        "[{\"id\":\"c1\",\"status\":\"open\",\"revision\":{\"tip\":\"oid1\"}},"
            + "{\"id\":\"c2\",\"status\":\"open\",\"revision\":{\"tip\":\"oid2\"}}]");

    List<PulsarChangeDiscovery> out = scanner.scanRepo("repo-a");

    assertEquals(2, out.size());
    PulsarChangeDiscovery first = out.get(0);
    assertEquals("repo-a", first.repo());
    assertEquals("c1", first.changeId());
    assertEquals("refs/pulsar/changes/c1", first.ref());
    assertEquals("oid1", first.revision());
    assertEquals("refs/pulsar/changes/c2", out.get(1).ref());
    assertEquals("oid2", out.get(1).revision());
  }

  @Test
  void scanAll_walksEveryRepoTheNodeHosts() throws Exception {
    wiremock.stubFor(
        get(urlEqualTo("/_pulsar/repos")).willReturn(json("{\"repos\":[{\"name\":\"repo-a\"}]}")));
    stubChanges("repo-a", "[{\"id\":\"c1\",\"status\":\"open\",\"revision\":{\"tip\":\"oid1\"}}]");

    List<PulsarChangeDiscovery> out = scanner.scanAll();

    assertEquals(1, out.size());
    assertEquals("c1", out.get(0).changeId());
  }

  // ── dedupe ────────────────────────────────────────────────────────────────

  @Test
  void scanRepo_doesNotReEmitAChangeAlreadySeen_acrossTwoTicksAtSameTip() throws Exception {
    stubChanges(
        "repo-a",
        "[{\"id\":\"c1\",\"status\":\"open\",\"revision\":{\"tip\":\"oid1\"}},"
            + "{\"id\":\"c2\",\"status\":\"open\",\"revision\":{\"tip\":\"oid2\"}}]");

    // First tick emits both.
    assertEquals(2, scanner.scanRepo("repo-a").size());
    // Second tick at the SAME tips emits nothing — dedupe holds.
    assertTrue(scanner.scanRepo("repo-a").isEmpty());
  }

  @Test
  void scanRepo_reEmitsWhenTheChangeTipAdvances() throws Exception {
    // Prior build was for an OLDER tip; the change got a new push (oid2) so it owes a new build.
    dedupe.markSeen(ScmProvider.PULSAR, "repo-a:c1:oid1", EventDedupeStore.Source.RECONCILE);
    stubChanges("repo-a", "[{\"id\":\"c1\",\"status\":\"open\",\"revision\":{\"tip\":\"oid2\"}}]");

    List<PulsarChangeDiscovery> out = scanner.scanRepo("repo-a");

    assertEquals(1, out.size());
    assertEquals("oid2", out.get(0).revision());
  }

  // ── adversarial / sad paths ───────────────────────────────────────────────

  @Test
  void scanRepo_node5xx_surfacesTypedError_notSilentEmptyScan() {
    wiremock.stubFor(
        get(urlEqualTo("/_pulsar/ledger/repo-a/changes")).willReturn(aResponse().withStatus(503)));

    ScmReconcileException ex =
        assertThrows(ScmReconcileException.class, () -> scanner.scanRepo("repo-a"));
    assertEquals(ScmProvider.PULSAR, ex.provider());
    assertEquals("repo-a", ex.repoExternalId());
    assertTrue(ex.getCause() instanceof PulsarApiException);
    assertEquals(503, ((PulsarApiException) ex.getCause()).status());
  }

  @Test
  void scanRepo_malformedJson_surfacesTypedError() {
    stubChanges("repo-a", "{ this is not valid json");

    ScmReconcileException ex =
        assertThrows(ScmReconcileException.class, () -> scanner.scanRepo("repo-a"));
    assertEquals(ScmProvider.PULSAR, ex.provider());
    assertEquals(-1, ((PulsarApiException) ex.getCause()).status());
  }

  @Test
  void scanAll_repoListing5xx_surfacesTypedErrorWithWildcardRepo() {
    wiremock.stubFor(get(urlEqualTo("/_pulsar/repos")).willReturn(aResponse().withStatus(500)));

    ScmReconcileException ex = assertThrows(ScmReconcileException.class, () -> scanner.scanAll());
    assertEquals(ScmProvider.PULSAR, ex.provider());
    assertEquals("*", ex.repoExternalId());
  }

  @Test
  void scanRepo_changeWithBlankOrAbsentTip_isSkipped_notEmittedNotCrashed() throws Exception {
    // `c1` carries a tip; `gone` has no revision/tip (e.g. merged/abandoned), so listOpenChanges
    // never returns it and the scanner naturally skips it — no separate /refs resolve needed.
    stubChanges(
        "repo-a",
        "[{\"id\":\"c1\",\"status\":\"open\",\"revision\":{\"tip\":\"oid1\"}},"
            + "{\"id\":\"gone\",\"status\":\"open\"}]");

    List<PulsarChangeDiscovery> out = scanner.scanRepo("repo-a");

    assertEquals(1, out.size());
    assertEquals("c1", out.get(0).changeId());
  }

  @Test
  void scanRepo_emptyChangeList_yieldsNothing() throws Exception {
    stubChanges("repo-a", "[]");

    assertTrue(scanner.scanRepo("repo-a").isEmpty());
  }

  // ── per-repo failure isolation (PR #1284 review, sev2/correctness) ─────────

  @Test
  void scanAll_isolatesAFailingRepo_andStillReturnsSiblingDiscoveries() throws Exception {
    wiremock.stubFor(
        get(urlEqualTo("/_pulsar/repos"))
            .willReturn(json("{\"repos\":[{\"name\":\"broken\"},{\"name\":\"healthy\"}]}")));
    // `broken` 5xxs on its change listing — must NOT abort the whole tick.
    wiremock.stubFor(
        get(urlEqualTo("/_pulsar/ledger/broken/changes")).willReturn(aResponse().withStatus(503)));
    stubChanges("healthy", "[{\"id\":\"c1\",\"status\":\"open\",\"revision\":{\"tip\":\"oid1\"}}]");

    List<PulsarChangeDiscovery> out = scanner.scanAll();

    assertEquals(1, out.size());
    assertEquals("healthy", out.get(0).repo());
    assertEquals("c1", out.get(0).changeId());
  }

  // ── helpers ──────────────────────────────────────────────────────────────

  private void stubChanges(String repo, String body) {
    wiremock.stubFor(
        get(urlEqualTo("/_pulsar/ledger/" + repo + "/changes")).willReturn(json(body)));
  }

  private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(
      String body) {
    return aResponse()
        .withStatus(200)
        .withHeader("Content-Type", "application/json")
        .withBody(body);
  }

  /** Deterministic in-memory dedupe store — mirrors the reconcile test's fake. */
  static final class FakeDedupeStore implements EventDedupeStore {
    private final Map<String, Instant> seen = new HashMap<>();

    @Override
    public boolean markSeen(ScmProvider provider, String eventId, Source source) {
      return seen.putIfAbsent(provider.wire() + "#" + eventId, Instant.EPOCH) == null;
    }

    @Override
    public void release(ScmProvider provider, String eventId) {
      seen.remove(provider.wire() + "#" + eventId);
    }

    @Override
    public Optional<Instant> firstSeenAt(ScmProvider provider, String eventId) {
      return Optional.ofNullable(seen.get(provider.wire() + "#" + eventId));
    }
  }
}
