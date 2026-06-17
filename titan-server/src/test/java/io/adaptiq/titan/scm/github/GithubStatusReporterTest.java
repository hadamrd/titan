package io.adaptiq.titan.scm.github;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import io.adaptiq.titan.api.FakeTitanStores;
import io.adaptiq.titan.build.BuildStateChangedEvent;
import io.adaptiq.titan.flow.crypto.CredentialKeyProvider;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.FlowNodeRow;
import java.security.KeyPair;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kohsuke.github.GHCommitState;

/**
 * WireMock-backed unit tests for {@link GithubStatusReporter} (closes #835).
 *
 * <p>Mirrors the test scaffolding of {@link GithubAppServiceTest}: in-memory H2 via {@link
 * FakeTitanStores}, WireMock stubbing the kohsuke client, a real {@link GithubAppService} producing
 * installation tokens against the WireMock endpoint. The reporter's contract is end-to-end:
 *
 * <ul>
 *   <li>RUNNING / SUCCESS / FAILED → correct {@code state} on POST {@code
 *       /repos/.../statuses/{sha}}.
 *   <li>Non-App-triggered build → no HTTP call.
 *   <li>WireMock 500 → exception logged, no crash, build proceeds.
 *   <li>WireMock 401 then 200 → exactly two POSTs, second uses freshly minted token.
 *   <li>WireMock 401 then 401 → log + skip, no exception escapes.
 * </ul>
 */
class GithubStatusReporterTest {

  private static final byte[] KEK_BYTES = new byte[32];
  private static final long APP_ID = 999_001L;
  private static final long INSTALL_ID = 555_001L;
  private static final long REPO_ID = 333_001L;
  private static final String OWNER = "acme-org";
  private static final String NAME = "widget";
  private static final String SHA = "deadbeef1234567890abcdef1234567890abcdef";

  private WireMockServer wiremock;
  private TitanStores stores;
  private GithubStatusReporter reporter;
  private GithubAppService service;
  private long jobId;
  private long buildId;

  @BeforeEach
  void setUp() throws Exception {
    wiremock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    wiremock.start();
    stores = FakeTitanStores.create();
    KeyPair kp = TestRsaKeys.newRsaKeyPair();
    String pem = TestRsaKeys.toPkcs8Pem(kp);

    Clock fixed = Clock.fixed(Instant.parse("2026-05-25T10:00:00Z"), ZoneId.of("UTC"));
    GithubClientFactory factory = new GithubClientFactory("http://localhost:" + wiremock.port());
    service = new GithubAppService(stores, fixedKeyProvider(), factory, fixed);
    reporter = new GithubStatusReporter(stores, service, factory, "https://titan.example.com");

    // Persist the singleton App row (manifest exchange) so getInstallationToken can sign JWTs.
    stubManifestExchange("seed-code", pem);
    service.handleManifestCallback("seed-code");

    // Persist a job + a github_repositories row + an installation row, then link them.
    seedJobAndRepo();
    // Stub install-token mint (default 200; tests can override).
    stubInstallationTokenOk("ghs_test_token_aaa");
  }

  @AfterEach
  void tearDown() {
    if (wiremock != null) wiremock.stop();
  }

  // ── happy path ────────────────────────────────────────────────────────────

  @Test
  void runningEvent_postsPendingStatus() {
    stubRepoLookup();
    stubCreateStatusOk();

    BuildStateChangedEvent evt = event("RUNNING");
    assertDoesNotThrow(() -> reporter.report(evt));

    wiremock.verify(
        1,
        postRequestedFor(urlEqualTo("/repos/" + OWNER + "/" + NAME + "/statuses/" + SHA))
            .withRequestBody(containing("\"state\":\"pending\""))
            .withRequestBody(containing("\"context\":\"ci/titan\""))
            .withRequestBody(
                containing("\"target_url\":\"https://titan.example.com/builds/" + buildId + "\""))
            .withRequestBody(containing("Running")));
  }

  @Test
  void successEvent_postsSuccessStatus() {
    stubRepoLookup();
    stubCreateStatusOk();

    reporter.report(event("SUCCESS"));

    wiremock.verify(
        1,
        postRequestedFor(urlEqualTo("/repos/" + OWNER + "/" + NAME + "/statuses/" + SHA))
            .withRequestBody(containing("\"state\":\"success\"")));
  }

  @Test
  void failedEvent_postsFailureStatus() {
    stubRepoLookup();
    stubCreateStatusOk();

    reporter.report(event("FAILED"));

    wiremock.verify(
        1,
        postRequestedFor(urlEqualTo("/repos/" + OWNER + "/" + NAME + "/statuses/" + SHA))
            .withRequestBody(containing("\"state\":\"failure\"")));
  }

  // ── #1240: red check NAMES the failed stage ────────────────────────────────

  @Test
  void mapStatus_failed_mapsToFailure() {
    // The worst CI failure mode is a broken build showing a green check. Lock
    // the terminal-FAILED → GitHub-failure mapping (#1240 AC4).
    assertEquals(Optional.of(GHCommitState.FAILURE), GithubStatusReporter.mapStatus("FAILED"));
  }

  @Test
  void describe_failureWithStage_namesTheStage() {
    assertEquals(
        "Build failed: test",
        GithubStatusReporter.describe(GHCommitState.FAILURE, "FAILED", "test"));
  }

  @Test
  void describe_failureWithoutStage_fallsBackToGeneric() {
    // Adversarial: no resolvable failed node (e.g. the build died before any
    // node was baked) must still produce a clean generic description.
    assertEquals(
        "Build failed", GithubStatusReporter.describe(GHCommitState.FAILURE, "FAILED", null));
    assertEquals(
        "Build failed", GithubStatusReporter.describe(GHCommitState.FAILURE, "FAILED", "  "));
  }

  @Test
  void failedEventWithFailedStage_postsFailureNamingStage() {
    stubRepoLookup();
    stubCreateStatusOk();
    // The build's middle `test` stage failed — the reporter must surface that
    // stage name in the commit-status description (#1240 AC2/AC3).
    seedFailedFlowNode("stage-test", "test");

    reporter.report(event("FAILED"));

    wiremock.verify(
        1,
        postRequestedFor(urlEqualTo("/repos/" + OWNER + "/" + NAME + "/statuses/" + SHA))
            .withRequestBody(containing("\"state\":\"failure\""))
            .withRequestBody(containing("Build failed: test")));
  }

  @Test
  void abortedEvent_collapsedToFailure() {
    stubRepoLookup();
    stubCreateStatusOk();

    reporter.report(event("ABORTED"));

    wiremock.verify(
        1,
        postRequestedFor(urlEqualTo("/repos/" + OWNER + "/" + NAME + "/statuses/" + SHA))
            .withRequestBody(containing("\"state\":\"failure\""))
            .withRequestBody(containing("Build aborted")));
  }

  // ── adversarial: non-App build ────────────────────────────────────────────

  @Test
  void manualBuild_notTriggeredByGithubApp_makesNoHttpCall() {
    BuildStateChangedEvent evt =
        new BuildStateChangedEvent(buildId, "SUCCESS", "manual", "{\"actor\":\"alice\"}", jobId, 1);

    assertDoesNotThrow(() -> reporter.report(evt));

    wiremock.verify(0, postRequestedFor(urlMatching("/repos/.*/statuses/.*")));
  }

  @Test
  void cronTriggeredBuild_noInstallNoRepoLink_makesNoHttpCall() {
    BuildStateChangedEvent evt =
        new BuildStateChangedEvent(buildId, "SUCCESS", "cron", null, jobId, 1);

    assertDoesNotThrow(() -> reporter.report(evt));

    wiremock.verify(0, postRequestedFor(urlMatching("/repos/.*/statuses/.*")));
  }

  @Test
  void discriminatedTriggerType_githubAppPush_postsStatus() {
    // Webhook stores discriminated trigger strings like "github-app:push" — the original
    // equality check rejected these on the live rig. Lock the prefix match.
    stubRepoLookup();
    stubCreateStatusOk();
    String meta = "{\"commitSha\":\"" + SHA + "\",\"branch\":\"trunk\"}";
    BuildStateChangedEvent evt =
        new BuildStateChangedEvent(buildId, "SUCCESS", "github-app:push", meta, jobId, 1);

    reporter.report(evt);

    wiremock.verify(
        1,
        postRequestedFor(urlEqualTo("/repos/" + OWNER + "/" + NAME + "/statuses/" + SHA))
            .withRequestBody(containing("\"state\":\"success\"")));
  }

  @Test
  void discriminatedTriggerType_pullRequest_postsStatus() {
    stubRepoLookup();
    stubCreateStatusOk();
    String meta = "{\"commitSha\":\"" + SHA + "\",\"branch\":\"trunk\"}";
    BuildStateChangedEvent evt =
        new BuildStateChangedEvent(
            buildId, "SUCCESS", "github-app:pull_request:opened", meta, jobId, 1);

    reporter.report(evt);

    wiremock.verify(
        1,
        postRequestedFor(urlEqualTo("/repos/" + OWNER + "/" + NAME + "/statuses/" + SHA))
            .withRequestBody(containing("\"state\":\"success\"")));
  }

  @Test
  void githubAppBuildWithoutCommitSha_makesNoHttpCall() {
    // triggerType=github-app but meta has no commitSha key
    BuildStateChangedEvent evt =
        new BuildStateChangedEvent(
            buildId, "SUCCESS", "github-app", "{\"branch\":\"trunk\"}", jobId, 1);

    assertDoesNotThrow(() -> reporter.report(evt));

    wiremock.verify(0, postRequestedFor(urlMatching("/repos/.*/statuses/.*")));
  }

  @Test
  void unknownStatus_makesNoHttpCall() {
    BuildStateChangedEvent evt = event("SLEEPING");

    assertDoesNotThrow(() -> reporter.report(evt));

    wiremock.verify(0, postRequestedFor(urlMatching("/repos/.*/statuses/.*")));
  }

  // ── adversarial: server errors ────────────────────────────────────────────

  @Test
  void serverError500_swallowsExceptionDoesNotCrash() {
    stubRepoLookup();
    wiremock.stubFor(
        post(urlEqualTo("/repos/" + OWNER + "/" + NAME + "/statuses/" + SHA))
            .willReturn(aResponse().withStatus(500).withBody("{\"message\":\"boom\"}")));

    assertDoesNotThrow(() -> reporter.report(event("RUNNING")));
  }

  @Test
  void notFound404_swallowsExceptionDoesNotCrash() {
    stubRepoLookup();
    wiremock.stubFor(
        post(urlEqualTo("/repos/" + OWNER + "/" + NAME + "/statuses/" + SHA))
            .willReturn(aResponse().withStatus(404).withBody("{\"message\":\"not found\"}")));

    assertDoesNotThrow(() -> reporter.report(event("SUCCESS")));
  }

  // ── adversarial: 401 retry ────────────────────────────────────────────────

  @Test
  void unauthorised401_thenSuccessAfterTokenRefresh() {
    stubRepoLookup();
    // POST statuses: first call returns 401, second returns 201. WireMock scenario state machine.
    String scenario = "status-401-then-201";
    wiremock.stubFor(
        post(urlEqualTo("/repos/" + OWNER + "/" + NAME + "/statuses/" + SHA))
            .inScenario(scenario)
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(aResponse().withStatus(401).withBody("{\"message\":\"Bad credentials\"}"))
            .willSetStateTo("first-call-done"));
    wiremock.stubFor(
        post(urlEqualTo("/repos/" + OWNER + "/" + NAME + "/statuses/" + SHA))
            .inScenario(scenario)
            .whenScenarioStateIs("first-call-done")
            .willReturn(
                aResponse()
                    .withStatus(201)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{}")));

    // After the 401 we invalidate + re-mint, so we need a second token-mint stub.
    // Stub already returns 200 with one token; reset and stub twice to count mints.
    wiremock.resetRequests();

    assertDoesNotThrow(() -> reporter.report(event("RUNNING")));

    // Exactly two POSTs to the statuses endpoint.
    wiremock.verify(
        2, postRequestedFor(urlEqualTo("/repos/" + OWNER + "/" + NAME + "/statuses/" + SHA)));
    // After invalidate, the second mint goes back to GitHub.
    wiremock.verify(
        2, postRequestedFor(urlEqualTo("/app/installations/" + INSTALL_ID + "/access_tokens")));
  }

  @Test
  void unauthorised401_thenStill401_logsAndSkipsNoCrash() {
    stubRepoLookup();
    wiremock.stubFor(
        post(urlEqualTo("/repos/" + OWNER + "/" + NAME + "/statuses/" + SHA))
            .willReturn(aResponse().withStatus(401).withBody("{\"message\":\"Bad credentials\"}")));

    assertDoesNotThrow(() -> reporter.report(event("RUNNING")));

    // Exactly two POSTs (one initial, one retry), then we give up.
    wiremock.verify(
        2, postRequestedFor(urlEqualTo("/repos/" + OWNER + "/" + NAME + "/statuses/" + SHA)));
  }

  // ── short-SHA path (App webhook stores short sha in trigger meta) ─────────

  @Test
  void shortSha_postsToShortShaStatusEndpoint() {
    String shortSha = "deadbee";
    String meta = "{\"branch\":\"trunk\",\"commitSha\":\"" + shortSha + "\",\"actor\":\"alice\"}";

    // GitHub accepts short SHAs for statuses — stub that path explicitly.
    wiremock.stubFor(
        get(urlEqualTo("/repos/" + OWNER + "/" + NAME))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"id\":"
                            + REPO_ID
                            + ",\"name\":\""
                            + NAME
                            + "\",\"full_name\":\""
                            + OWNER
                            + "/"
                            + NAME
                            + "\",\"owner\":{\"login\":\""
                            + OWNER
                            + "\"},\"private\":false}")));
    wiremock.stubFor(
        post(urlEqualTo("/repos/" + OWNER + "/" + NAME + "/statuses/" + shortSha))
            .willReturn(
                aResponse()
                    .withStatus(201)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{}")));

    BuildStateChangedEvent evt =
        new BuildStateChangedEvent(buildId, "SUCCESS", "github-app", meta, jobId, 1);
    assertDoesNotThrow(() -> reporter.report(evt));

    wiremock.verify(
        1, postRequestedFor(urlEqualTo("/repos/" + OWNER + "/" + NAME + "/statuses/" + shortSha)));
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  private BuildStateChangedEvent event(String status) {
    String meta = "{\"branch\":\"trunk\",\"commitSha\":\"" + SHA + "\",\"actor\":\"alice\"}";
    return new BuildStateChangedEvent(buildId, status, "github-app", meta, jobId, 1);
  }

  /** Insert a FAILED flow node for {@link #buildId} so the reporter can name the broken stage. */
  private void seedFailedFlowNode(String nodeId, String displayName) {
    FlowNodeRow row = new FlowNodeRow();
    row.buildId = buildId;
    row.nodeId = nodeId;
    row.nodeType = "STAGE";
    row.displayName = displayName;
    row.status = "FAILED";
    row.attempt = 1;
    row.maxAttempts = 1;
    stores.flowNodes().insert(row);
  }

  private void seedJobAndRepo() {
    stores.withTransaction(
        conn -> {
          try (java.sql.PreparedStatement ps =
              conn.prepareStatement(
                  "INSERT INTO titan.github_installations "
                      + "(install_id, account_login, account_type, target_type, suspended_at) "
                      + "VALUES (?, ?, ?, ?, NULL)")) {
            ps.setLong(1, INSTALL_ID);
            ps.setString(2, OWNER);
            ps.setString(3, "Organization");
            ps.setString(4, "Organization");
            ps.executeUpdate();
          } catch (java.sql.SQLException e) {
            throw new RuntimeException(e);
          }
          try (java.sql.PreparedStatement ps =
              conn.prepareStatement(
                  "INSERT INTO titan.github_repositories "
                      + "(install_id, repo_id, owner, name, default_branch, is_private) "
                      + "VALUES (?, ?, ?, ?, ?, ?)")) {
            ps.setLong(1, INSTALL_ID);
            ps.setLong(2, REPO_ID);
            ps.setString(3, OWNER);
            ps.setString(4, NAME);
            ps.setString(5, "main");
            ps.setBoolean(6, false);
            ps.executeUpdate();
          } catch (java.sql.SQLException e) {
            throw new RuntimeException(e);
          }
          try (java.sql.PreparedStatement ps =
              conn.prepareStatement(
                  "INSERT INTO titan.jobs (full_name, pipeline_script, config_json, enabled, "
                      + "github_installation_id, github_repo_id) "
                      + "VALUES (?, ?, '{}', TRUE, ?, ?)")) {
            ps.setString(1, "acme/widget");
            ps.setString(2, "pipeline {}");
            ps.setLong(3, INSTALL_ID);
            ps.setLong(4, REPO_ID);
            ps.executeUpdate();
          } catch (java.sql.SQLException e) {
            throw new RuntimeException(e);
          }
          return null;
        });
    jobId = stores.jobs().findByFullName("acme/widget").orElseThrow().id;

    // Now insert a build for this job so jobId/buildId are valid references — though the reporter
    // does not read the builds table directly, we keep parity with prod for clarity.
    buildId =
        stores.withTransaction(
            conn -> {
              int n = stores.builds().nextBuildNumber(conn, jobId);
              io.adaptiq.titan.store.rows.BuildRow row = new io.adaptiq.titan.store.rows.BuildRow();
              row.jobId = jobId;
              row.buildNumber = n;
              row.status = "QUEUED";
              row.queuedAt = Instant.now();
              row.triggerType = "github-app";
              row.triggerMetaJson =
                  "{\"branch\":\"trunk\",\"commitSha\":\"" + SHA + "\",\"actor\":\"alice\"}";
              return stores.builds().insert(conn, row);
            });

    // Sanity: linkage resolves.
    assertEquals(
        OWNER + "/" + NAME,
        stores.jobs().findGithubLinkage(jobId).orElseThrow().owner
            + "/"
            + stores.jobs().findGithubLinkage(jobId).orElseThrow().name);
  }

  private void stubManifestExchange(String code, String pem) {
    String escapedPem = pem.replace("\n", "\\n");
    String body =
        "{\"id\":"
            + APP_ID
            + ",\"name\":\"Titan Test\",\"slug\":\"titan-test\","
            + "\"html_url\":\"https://github.com/apps/titan-test\","
            + "\"pem\":\""
            + escapedPem
            + "\",\"webhook_secret\":\"hooksecret\"}";
    wiremock.stubFor(
        post(urlEqualTo("/app-manifests/" + code + "/conversions"))
            .willReturn(
                aResponse()
                    .withStatus(201)
                    .withHeader("Content-Type", "application/json")
                    .withBody(body)));
  }

  private void stubInstallationTokenOk(String token) {
    // GET /app and GET /app/installations/{id} are required by kohsuke before createToken.
    wiremock.stubFor(
        get(urlEqualTo("/app"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"id\":"
                            + APP_ID
                            + ",\"name\":\"Titan Test\",\"slug\":\"titan-test\","
                            + "\"html_url\":\"https://github.com/apps/titan-test\","
                            + "\"owner\":{\"login\":\""
                            + OWNER
                            + "\"},\"events\":[]}")));
    wiremock.stubFor(
        get(urlPathMatching("/app/installations/" + INSTALL_ID))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"id\":"
                            + INSTALL_ID
                            + ",\"account\":{\"login\":\""
                            + OWNER
                            + "\",\"type\":\"Organization\"},"
                            + "\"target_type\":\"Organization\",\"suspended_at\":null}")));
    wiremock.stubFor(
        post(urlEqualTo("/app/installations/" + INSTALL_ID + "/access_tokens"))
            .willReturn(
                aResponse()
                    .withStatus(201)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"token\":\""
                            + token
                            + "\",\"expires_at\":\"2026-05-25T11:00:00Z\","
                            + "\"permissions\":{\"statuses\":\"write\"}}")));
  }

  private void stubRepoLookup() {
    wiremock.stubFor(
        get(urlEqualTo("/repos/" + OWNER + "/" + NAME))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"id\":"
                            + REPO_ID
                            + ",\"name\":\""
                            + NAME
                            + "\",\"full_name\":\""
                            + OWNER
                            + "/"
                            + NAME
                            + "\",\"owner\":{\"login\":\""
                            + OWNER
                            + "\"},\"private\":false}")));
  }

  private void stubCreateStatusOk() {
    wiremock.stubFor(
        post(urlEqualTo("/repos/" + OWNER + "/" + NAME + "/statuses/" + SHA))
            .willReturn(
                aResponse()
                    .withStatus(201)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{}")));
  }

  private CredentialKeyProvider fixedKeyProvider() {
    return new CredentialKeyProvider() {
      @Override
      public byte[] credentialKey() {
        return KEK_BYTES.clone();
      }

      @Override
      public byte[] credentialKeyByVersion(int v) {
        return v == 1 ? KEK_BYTES.clone() : null;
      }

      @Override
      public int credentialKeyVersion() {
        return 1;
      }

      @Override
      public String describe() {
        return "test-fixed";
      }
    };
  }
}
