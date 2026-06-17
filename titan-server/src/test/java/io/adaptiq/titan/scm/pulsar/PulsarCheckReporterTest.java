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
  void runningBuild_postsPendingCheck() {
    wiremock.stubFor(post(urlEqualTo(EVENTS_URL)).willReturn(aResponse().withStatus(201)));

    reporter.report(event("RUNNING"));

    wiremock.verify(
        1,
        postRequestedFor(urlEqualTo(EVENTS_URL))
            .withRequestBody(containing("\"conclusion\":\"pending\"")));
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
