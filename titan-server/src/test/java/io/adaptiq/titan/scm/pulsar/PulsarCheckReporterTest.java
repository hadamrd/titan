package io.adaptiq.titan.scm.pulsar;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import io.adaptiq.titan.api.FakeTitanStores;
import io.adaptiq.titan.build.BuildStateChangedEvent;
import io.adaptiq.titan.scm.pulsar.PulsarClient.CheckConclusion;
import io.adaptiq.titan.store.TitanStores;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * WireMock-backed unit tests for {@link PulsarCheckReporter} (issue #1282 — convergence merge-gate
 * payoff). A Pulsar node is faked via WireMock; an in-memory H2 {@link FakeTitanStores} holds the
 * job the change resolves to.
 *
 * <p>Acceptance under test (falsifiable, against the fake node):
 *
 * <ul>
 *   <li>a SUCCEEDED build → POSTs a CI event {@code {kind:"ci", check:"build",
 *       conclusion:"success"}} to the change EVENTS api — the exact payload the node folds into
 *       {@code required_checks} to flip the gate to {@code allowed};
 *   <li>a FAILED build → POSTs {@code conclusion:"failure"} so the gate stays refused;
 *   <li>a start transition → POSTs {@code conclusion:"pending"};
 *   <li>a non-Pulsar build / missing changeId / unknown state → no HTTP call;
 *   <li>transport/non-2xx → typed error caught, retried for a transient 5xx, never a silent success
 *       and never a crash that fails the build.
 * </ul>
 */
class PulsarCheckReporterTest {

  private static final String REPO = "sample";
  private static final String CHANGE_ID = "c1";
  private static final String REVISION = "oid1abc";
  private static final String EVENTS_URL =
      "/_pulsar/ledger/" + REPO + "/changes/" + CHANGE_ID + "/events";

  private WireMockServer wiremock;
  private TitanStores stores;
  private PulsarCheckReporter reporter;
  private long jobId;
  private long buildId = 4242L;

  @BeforeEach
  void setUp() {
    wiremock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    wiremock.start();
    stores = FakeTitanStores.create();
    seedJob(REPO);
    PulsarClient client = new PulsarClient("http://localhost:" + wiremock.port());
    reporter = new PulsarCheckReporter(stores, client, "https://titan.example.com", true);
  }

  @AfterEach
  void tearDown() {
    if (wiremock != null) {
      wiremock.stop();
    }
  }

  // ── happy path ──────────────────────────────────────────────────────────────

  @Test
  void successBuild_postsBuildCheckSuccess_theGateClearingPayload() {
    wiremock.stubFor(post(urlEqualTo(EVENTS_URL)).willReturn(aResponse().withStatus(201)));

    assertDoesNotThrow(() -> reporter.report(event("SUCCESS")));

    // The exact EventKind::CiStatus contract Pulsar's required_checks:["build"] folds against:
    // kind=ci, check=build, success. This is what flips refused_incomplete → allowed. There is no
    // details_url field on a CI event, so it must NOT be on the wire.
    wiremock.verify(
        1,
        postRequestedFor(urlEqualTo(EVENTS_URL))
            .withRequestBody(containing("\"kind\":\"ci\""))
            .withRequestBody(containing("\"check\":\"build\""))
            .withRequestBody(containing("\"conclusion\":\"success\"")));
    wiremock.verify(
        0, postRequestedFor(urlEqualTo(EVENTS_URL)).withRequestBody(containing("details_url")));
  }

  @Test
  void failedBuild_postsBuildCheckFailure_gateStaysRefused() {
    wiremock.stubFor(post(urlEqualTo(EVENTS_URL)).willReturn(aResponse().withStatus(201)));

    reporter.report(event("FAILED"));

    wiremock.verify(
        1,
        postRequestedFor(urlEqualTo(EVENTS_URL))
            .withRequestBody(containing("\"kind\":\"ci\""))
            .withRequestBody(containing("\"check\":\"build\""))
            .withRequestBody(containing("\"conclusion\":\"failure\"")));
  }

  @Test
  void runningBuild_postsInProgressPhase_pendingConclusion() {
    wiremock.stubFor(post(urlEqualTo(EVENTS_URL)).willReturn(aResponse().withStatus(201)));

    reporter.report(event("RUNNING"));

    // issue #5: RUNNING carries phase=in_progress, conclusion stays pending (gate not cleared), and
    // is distinguishable from QUEUED — the queued marker must be ABSENT.
    wiremock.verify(
        1,
        postRequestedFor(urlEqualTo(EVENTS_URL))
            .withRequestBody(containing("\"conclusion\":\"pending\""))
            .withRequestBody(containing("\"phase\":\"in_progress\"")));
    wiremock.verify(
        0,
        postRequestedFor(urlEqualTo(EVENTS_URL))
            .withRequestBody(containing("\"phase\":\"queued\"")));
  }

  @Test
  void queuedBuild_postsQueuedPhase_pendingConclusion() {
    wiremock.stubFor(post(urlEqualTo(EVENTS_URL)).willReturn(aResponse().withStatus(201)));

    reporter.report(event("QUEUED"));

    // issue #5: QUEUED carries phase=queued, conclusion stays pending, and is distinguishable from
    // RUNNING — the in_progress marker must be ABSENT.
    wiremock.verify(
        1,
        postRequestedFor(urlEqualTo(EVENTS_URL))
            .withRequestBody(containing("\"conclusion\":\"pending\""))
            .withRequestBody(containing("\"phase\":\"queued\"")));
    wiremock.verify(
        0,
        postRequestedFor(urlEqualTo(EVENTS_URL))
            .withRequestBody(containing("\"phase\":\"in_progress\"")));
  }

  @Test
  void successBuild_carriesNoPhase_terminalVerdictHasNoInFlightPhase() {
    wiremock.stubFor(post(urlEqualTo(EVENTS_URL)).willReturn(aResponse().withStatus(201)));

    reporter.report(event("SUCCESS"));

    // A finished build has no in-flight phase: the success event must NOT carry a phase field, so
    // the merge-gate-clearing event is the bare {kind,check,conclusion:success} shape.
    wiremock.verify(
        0, postRequestedFor(urlEqualTo(EVENTS_URL)).withRequestBody(containing("\"phase\"")));
  }

  @Test
  void queuedRunningSuccess_postsThreeOrderedLifecycleUpdates_onlyLastClearsGate() {
    wiremock.stubFor(post(urlEqualTo(EVENTS_URL)).willReturn(aResponse().withStatus(201)));

    // One reporter drives the real lifecycle: enqueue → worker pickup → green.
    reporter.report(event("QUEUED"));
    reporter.report(event("RUNNING"));
    reporter.report(event("SUCCESS"));

    java.util.List<com.github.tomakehurst.wiremock.verification.LoggedRequest> posts =
        wiremock.findAll(postRequestedFor(urlEqualTo(EVENTS_URL)));
    assertEquals(3, posts.size(), "expected three ordered lifecycle POSTs");

    String first = posts.get(0).getBodyAsString();
    String second = posts.get(1).getBodyAsString();
    String third = posts.get(2).getBodyAsString();

    // Order: queued (pending) → in_progress (pending) → success — only the third clears the gate.
    assertTrue(
        first.contains("\"phase\":\"queued\"") && first.contains("\"conclusion\":\"pending\""));
    assertTrue(
        second.contains("\"phase\":\"in_progress\"")
            && second.contains("\"conclusion\":\"pending\""));
    assertTrue(third.contains("\"conclusion\":\"success\""), "terminal event must be success");
    assertFalse(third.contains("\"phase\""), "terminal success must carry no phase");
    assertFalse(first.contains("\"conclusion\":\"success\""));
    assertFalse(second.contains("\"conclusion\":\"success\""));
  }

  @Test
  void inProgressEventRejectedWith400_buildNotFailed_terminalSuccessStillPostedIndependently() {
    // The node rejects the in_progress phase event with a 400 (e.g. it refuses the extra field).
    // This proves graceful degradation: the failure is swallowed (4xx not retried, build never
    // fails) AND the later terminal success event is posted on its own transition regardless.
    wiremock.stubFor(
        post(urlEqualTo(EVENTS_URL))
            .withRequestBody(containing("\"phase\":\"in_progress\""))
            .willReturn(aResponse().withStatus(400)));
    wiremock.stubFor(
        post(urlEqualTo(EVENTS_URL))
            .withRequestBody(containing("\"conclusion\":\"success\""))
            .willReturn(aResponse().withStatus(201)));

    assertDoesNotThrow(() -> reporter.report(event("RUNNING")));
    assertDoesNotThrow(() -> reporter.report(event("SUCCESS")));

    // in_progress attempted exactly once (400 is not retried), success still landed.
    wiremock.verify(
        1,
        postRequestedFor(urlEqualTo(EVENTS_URL))
            .withRequestBody(containing("\"phase\":\"in_progress\"")));
    wiremock.verify(
        1,
        postRequestedFor(urlEqualTo(EVENTS_URL))
            .withRequestBody(containing("\"conclusion\":\"success\"")));
  }

  @Test
  void queuedOnlyBuildThatNeverStarts_postsExactlyOneQueuedEvent_neverClearsGate() {
    wiremock.stubFor(post(urlEqualTo(EVENTS_URL)).willReturn(aResponse().withStatus(201)));

    reporter.report(event("QUEUED"));

    wiremock.verify(
        1,
        postRequestedFor(urlEqualTo(EVENTS_URL))
            .withRequestBody(containing("\"phase\":\"queued\"")));
    // Never any gate-clearing success while it sits in the queue.
    wiremock.verify(
        0,
        postRequestedFor(urlEqualTo(EVENTS_URL))
            .withRequestBody(containing("\"conclusion\":\"success\"")));
  }

  @Test
  void unstableBuild_collapsesToFailureConclusion_pulsarAcceptsOnlyPendingSuccessFailure() {
    wiremock.stubFor(post(urlEqualTo(EVENTS_URL)).willReturn(aResponse().withStatus(201)));

    reporter.report(event("UNSTABLE"));

    // The node rejects any conclusion outside {pending,success,failure} with a 400; an unstable
    // build is not a clean green, so it is published as the gate-safe "failure".
    wiremock.verify(
        1,
        postRequestedFor(urlEqualTo(EVENTS_URL))
            .withRequestBody(containing("\"kind\":\"ci\""))
            .withRequestBody(containing("\"conclusion\":\"failure\"")));
  }

  // ── adversarial: builds that must NOT touch the ledger ──────────────────────

  @Test
  void manualBuild_notPulsarTriggered_makesNoHttpCall() {
    BuildStateChangedEvent evt =
        new BuildStateChangedEvent(buildId, "SUCCESS", "manual", "{\"actor\":\"alice\"}", jobId, 1);

    assertDoesNotThrow(() -> reporter.report(evt));

    wiremock.verify(0, postRequestedFor(urlMatching("/_pulsar/.*")));
  }

  @Test
  void githubAppBuild_makesNoHttpCall() {
    // "github-app:push" must not be mistaken for a Pulsar build — different prefix.
    BuildStateChangedEvent evt =
        new BuildStateChangedEvent(
            buildId, "SUCCESS", "github-app:push", "{\"commitSha\":\"deadbeef\"}", jobId, 1);

    assertDoesNotThrow(() -> reporter.report(evt));

    wiremock.verify(0, postRequestedFor(urlMatching("/_pulsar/.*")));
  }

  @Test
  void pulsarBuildWithoutChangeId_makesNoHttpCall() {
    BuildStateChangedEvent evt =
        new BuildStateChangedEvent(
            buildId, "SUCCESS", "pulsar", "{\"commitSha\":\"" + REVISION + "\"}", jobId, 1);

    assertDoesNotThrow(() -> reporter.report(evt));

    wiremock.verify(0, postRequestedFor(urlMatching("/_pulsar/.*")));
  }

  @Test
  void unknownIntermediateState_makesNoHttpCall() {
    assertDoesNotThrow(() -> reporter.report(event("SLEEPING")));

    wiremock.verify(0, postRequestedFor(urlMatching("/_pulsar/.*")));
  }

  @Test
  void unresolvableJob_makesNoHttpCall() {
    BuildStateChangedEvent evt =
        new BuildStateChangedEvent(
            buildId, "SUCCESS", "pulsar", meta(), 999_999L /* no such job */, 1);

    assertDoesNotThrow(() -> reporter.report(evt));

    wiremock.verify(0, postRequestedFor(urlMatching("/_pulsar/.*")));
  }

  @Test
  void disabledByFeatureFlag_makesNoHttpCall() {
    PulsarClient client = new PulsarClient("http://localhost:" + wiremock.port());
    PulsarCheckReporter off =
        new PulsarCheckReporter(stores, client, "https://titan.example.com", false);

    off.onBuildStateChanged(event("SUCCESS"));

    wiremock.verify(0, postRequestedFor(urlMatching("/_pulsar/.*")));
  }

  // ── adversarial: transport / non-2xx ────────────────────────────────────────

  @Test
  void serverError500_isRetriedOnce_thenSwallowed_neverSilentSuccessNorCrash() {
    wiremock.stubFor(post(urlEqualTo(EVENTS_URL)).willReturn(aResponse().withStatus(503)));

    // The build MUST NOT fail because the gate post failed — but the post is attempted twice
    // (initial + one retry for a transient 5xx) and the verdict is simply not published.
    assertDoesNotThrow(() -> reporter.report(event("SUCCESS")));

    wiremock.verify(2, postRequestedFor(urlEqualTo(EVENTS_URL)));
  }

  @Test
  void notFound404_isNotRetried_thenSwallowed() {
    wiremock.stubFor(post(urlEqualTo(EVENTS_URL)).willReturn(aResponse().withStatus(404)));

    assertDoesNotThrow(() -> reporter.report(event("FAILED")));

    // A permanent 4xx (e.g. change merged/abandoned) is not worth a retry.
    wiremock.verify(1, postRequestedFor(urlEqualTo(EVENTS_URL)));
  }

  @Test
  void transient500ThenSuccess_publishesVerdictOnRetry() {
    String scenario = "503-then-201";
    wiremock.stubFor(
        post(urlEqualTo(EVENTS_URL))
            .inScenario(scenario)
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(aResponse().withStatus(503))
            .willSetStateTo("first-done"));
    wiremock.stubFor(
        post(urlEqualTo(EVENTS_URL))
            .inScenario(scenario)
            .whenScenarioStateIs("first-done")
            .willReturn(aResponse().withStatus(201)));

    reporter.report(event("SUCCESS"));

    wiremock.verify(2, postRequestedFor(urlEqualTo(EVENTS_URL)));
  }

  // ── pure mapping unit checks ────────────────────────────────────────────────

  @Test
  void mapConclusion_terminalAndIntermediate() {
    assertEquals(
        Optional.of(CheckConclusion.SUCCESS), PulsarCheckReporter.mapConclusion("SUCCESS"));
    assertEquals(Optional.of(CheckConclusion.FAILURE), PulsarCheckReporter.mapConclusion("FAILED"));
    assertEquals(
        Optional.of(CheckConclusion.FAILURE), PulsarCheckReporter.mapConclusion("ABORTED"));
    assertEquals(
        Optional.of(CheckConclusion.UNSTABLE), PulsarCheckReporter.mapConclusion("UNSTABLE"));
    assertEquals(
        Optional.of(CheckConclusion.PENDING), PulsarCheckReporter.mapConclusion("RUNNING"));
    assertTrue(PulsarCheckReporter.mapConclusion("SLEEPING").isEmpty());
  }

  @Test
  void mapPhase_onlyNonTerminalStatesCarryAPhase() {
    assertEquals(Optional.of(PulsarClient.Phase.QUEUED), PulsarCheckReporter.mapPhase("QUEUED"));
    assertEquals(
        Optional.of(PulsarClient.Phase.IN_PROGRESS), PulsarCheckReporter.mapPhase("RUNNING"));
    // Terminal verdicts carry no in-flight phase.
    assertTrue(PulsarCheckReporter.mapPhase("SUCCESS").isEmpty());
    assertTrue(PulsarCheckReporter.mapPhase("FAILED").isEmpty());
    assertTrue(PulsarCheckReporter.mapPhase("UNSTABLE").isEmpty());
    assertTrue(PulsarCheckReporter.mapPhase("SLEEPING").isEmpty());
  }

  @Test
  void isRetryable_onlyTransientStatuses() {
    assertTrue(PulsarCheckReporter.isRetryable(-1)); // transport error
    assertTrue(PulsarCheckReporter.isRetryable(503));
    assertFalse(PulsarCheckReporter.isRetryable(404));
    assertFalse(PulsarCheckReporter.isRetryable(401));
  }

  // ── helpers ──────────────────────────────────────────────────────────────

  private BuildStateChangedEvent event(String status) {
    return new BuildStateChangedEvent(buildId, status, "pulsar", meta(), jobId, 1);
  }

  private static String meta() {
    return "{\"commitSha\":\"" + REVISION + "\",\"changeId\":\"" + CHANGE_ID + "\"}";
  }

  private void seedJob(String fullName) {
    stores.withTransaction(
        conn -> {
          try (PreparedStatement ps =
              conn.prepareStatement(
                  "INSERT INTO titan.jobs (full_name, pipeline_script, config_json, enabled) "
                      + "VALUES (?, 'pipeline {}', '{}', TRUE)")) {
            ps.setString(1, fullName);
            ps.executeUpdate();
          } catch (SQLException e) {
            throw new RuntimeException(e);
          }
          return null;
        });
    jobId = stores.jobs().findByFullName(fullName).orElseThrow().id;
  }
}
