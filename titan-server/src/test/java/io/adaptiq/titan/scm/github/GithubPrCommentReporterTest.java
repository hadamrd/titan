package io.adaptiq.titan.scm.github;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.patch;
import static com.github.tomakehurst.wiremock.client.WireMock.patchRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import io.adaptiq.titan.api.FakeTitanStores;
import io.adaptiq.titan.build.BuildStateChangedEvent;
import io.adaptiq.titan.flow.crypto.CredentialKeyProvider;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.BuildRow;
import io.adaptiq.titan.store.rows.FlowNodeRow;
import io.adaptiq.titan.store.rows.JobRow;
import java.security.KeyPair;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * WireMock-backed unit tests for {@link GithubPrCommentReporter} (closes #966). Mirrors the
 * scaffolding of {@link GithubStatusReporterTest}: in-memory H2 via {@link FakeTitanStores},
 * WireMock stubbing the kohsuke client, a real {@link GithubAppService} producing installation
 * tokens against the WireMock endpoint.
 *
 * <p>The reporter's contract under test:
 *
 * <ul>
 *   <li>Push build (no {@code prNumber} in trigger meta) → no HTTP call.
 *   <li>PR build, terminal status, no existing comment → exactly one {@code POST
 *       /repos/.../issues/{n}/comments} carrying the dedupe marker.
 *   <li>PR build, terminal status, existing comment with marker → exactly one {@code PATCH
 *       /repos/.../issues/comments/{id}} and ZERO {@code POST}s.
 *   <li>Body shape: contains the pipeline name, the status, the rig build URL, and a stage row per
 *       STAGE-type flow node with the right emoji.
 * </ul>
 */
class GithubPrCommentReporterTest {

  private static final byte[] KEK_BYTES = new byte[32];
  private static final long APP_ID = 999_002L;
  private static final long INSTALL_ID = 555_002L;
  private static final long REPO_ID = 333_002L;
  private static final String OWNER = "acme-org";
  private static final String NAME = "widget";
  private static final int PR_NUMBER = 42;
  private static final long EXISTING_COMMENT_ID = 9_001L;

  private WireMockServer wiremock;
  private TitanStores stores;
  private GithubPrCommentReporter reporter;
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

    Clock fixed = Clock.fixed(Instant.parse("2026-05-26T10:00:00Z"), ZoneId.of("UTC"));
    GithubClientFactory factory = new GithubClientFactory("http://localhost:" + wiremock.port());
    service = new GithubAppService(stores, fixedKeyProvider(), factory, fixed);
    reporter =
        new GithubPrCommentReporter(
            stores, service, factory, "https://titan.example.com", /* featureEnabled */ true);

    // Persist the App singleton + install + repo + job + build.
    stubManifestExchange("seed-code", pem);
    service.handleManifestCallback("seed-code");
    seedJobBuildAndStages();
    stubInstallationTokenOk("ghs_test_token_pr");
  }

  @AfterEach
  void tearDown() {
    if (wiremock != null) wiremock.stop();
  }

  // ── filter: no prNumber ───────────────────────────────────────────────────

  @Test
  void onBuildFinish_pushBuild_noComment() {
    // Push build: trigger meta has commitSha but no prNumber.
    String meta = "{\"branch\":\"trunk\",\"commitSha\":\"deadbeef\",\"actor\":\"alice\"}";
    BuildStateChangedEvent evt =
        new BuildStateChangedEvent(buildId, "SUCCESS", "github-app:push", meta, jobId, 1);

    assertDoesNotThrow(() -> reporter.report(evt));

    wiremock.verify(0, postRequestedFor(urlMatching("/repos/.*/issues/.*/comments")));
    wiremock.verify(0, patchRequestedFor(urlMatching("/repos/.*/issues/comments/.*")));
  }

  @Test
  void onBuildFinish_nonTerminalStatus_noComment() {
    String meta = prMeta();
    BuildStateChangedEvent evt =
        new BuildStateChangedEvent(
            buildId, "RUNNING", "github-app:pull_request:opened", meta, jobId, 1);

    assertDoesNotThrow(() -> reporter.report(evt));

    wiremock.verify(0, postRequestedFor(urlMatching("/repos/.*/issues/.*/comments")));
  }

  // ── happy path: POST on first call ────────────────────────────────────────

  @Test
  void onBuildFinish_prBuild_postsComment() {
    stubRepoLookup();
    stubIssueLookup();
    stubListCommentsEmpty();
    stubCreateCommentOk();

    reporter.report(event("SUCCESS"));

    wiremock.verify(
        1,
        postRequestedFor(
                urlEqualTo("/repos/" + OWNER + "/" + NAME + "/issues/" + PR_NUMBER + "/comments"))
            .withRequestBody(containing("<!-- titan-build-comment -->"))
            .withRequestBody(containing("SUCCESS"))
            .withRequestBody(containing("https://titan.example.com/builds/" + buildId)));
    wiremock.verify(0, patchRequestedFor(urlMatching("/repos/.*/issues/comments/.*")));
  }

  // ── dedupe: PATCH on second call ──────────────────────────────────────────

  @Test
  void onBuildFinish_prBuild_existingComment_patches() {
    stubRepoLookup();
    stubIssueLookup();
    stubListCommentsWithExisting();
    stubPatchCommentOk();

    reporter.report(event("FAILED"));

    wiremock.verify(
        1,
        patchRequestedFor(
                urlEqualTo(
                    "/repos/" + OWNER + "/" + NAME + "/issues/comments/" + EXISTING_COMMENT_ID))
            .withRequestBody(containing("<!-- titan-build-comment -->"))
            .withRequestBody(containing("FAILED")));
    wiremock.verify(
        0,
        postRequestedFor(
            urlEqualTo("/repos/" + OWNER + "/" + NAME + "/issues/" + PR_NUMBER + "/comments")));
  }

  // ── body rendering shape ──────────────────────────────────────────────────

  @Test
  void bodyRendering_success_hasCheckmarkAndStageRow() {
    JobRow job = stores.jobs().findByFullName("acme/widget").orElseThrow();
    BuildRow build = stores.builds().findById(buildId).orElseThrow();
    List<FlowNodeRow> nodes = stores.flowNodes().listByBuild(buildId);

    String body = reporter.renderBody(job, build, nodes, "SUCCESS", buildId);

    assertTrue(body.startsWith("<!-- titan-build-comment -->"), "body must start with marker");
    assertTrue(body.contains(":white_check_mark: SUCCESS"), "success emoji + status");
    assertTrue(body.contains("| Build |"), "stage row from seed");
    assertTrue(body.contains("| Test |"), "second stage row from seed");
    assertTrue(
        body.contains("https://titan.example.com/builds/" + buildId), "rig build link present");
  }

  @Test
  void bodyRendering_failure_hasXMark() {
    JobRow job = stores.jobs().findByFullName("acme/widget").orElseThrow();
    BuildRow build = stores.builds().findById(buildId).orElseThrow();
    List<FlowNodeRow> nodes = stores.flowNodes().listByBuild(buildId);

    String body = reporter.renderBody(job, build, nodes, "FAILED", buildId);

    assertTrue(body.contains(":x: FAILED"), "failure emoji + status");
  }

  @Test
  void bodyRendering_aborted_hasNoEntryEmoji() {
    JobRow job = stores.jobs().findByFullName("acme/widget").orElseThrow();
    BuildRow build = stores.builds().findById(buildId).orElseThrow();
    List<FlowNodeRow> nodes = stores.flowNodes().listByBuild(buildId);

    String body = reporter.renderBody(job, build, nodes, "ABORTED", buildId);

    assertTrue(body.contains(":no_entry: ABORTED"), "aborted emoji + status");
  }

  // ── adversarial: GitHub returns 500 → swallowed ───────────────────────────

  @Test
  void serverError500_swallowsExceptionDoesNotCrash() {
    stubRepoLookup();
    stubIssueLookup();
    stubListCommentsEmpty();
    wiremock.stubFor(
        post(urlEqualTo("/repos/" + OWNER + "/" + NAME + "/issues/" + PR_NUMBER + "/comments"))
            .willReturn(aResponse().withStatus(500).withBody("{\"message\":\"boom\"}")));

    assertDoesNotThrow(() -> reporter.report(event("SUCCESS")));
  }

  // ── adversarial: feature flag off → no HTTP at all ────────────────────────

  @Test
  void featureFlagDisabled_makesNoHttpCall() {
    GithubPrCommentReporter disabled =
        new GithubPrCommentReporter(
            stores,
            service,
            new GithubClientFactory("http://localhost:" + wiremock.port()),
            "https://titan.example.com",
            /* featureEnabled */ false);

    String meta = prMeta();
    disabled.onBuildStateChanged(
        new BuildStateChangedEvent(
            buildId, "SUCCESS", "github-app:pull_request:opened", meta, jobId, 1));

    wiremock.verify(0, getRequestedFor(urlMatching("/repos/.*/issues/.*/comments")));
    wiremock.verify(0, postRequestedFor(urlMatching("/repos/.*/issues/.*/comments")));
  }

  // ── adversarial: second invocation patches the same comment ───────────────

  @Test
  void twoInvocations_sameJob_secondPatchesNotPosts() {
    stubRepoLookup();
    stubIssueLookup();

    // Use WireMock scenario state machine: first listComments returns empty (→ POST), then after
    // POST the listComments returns the comment we just created (→ PATCH).
    String scenario = "list-comments-empty-then-populated";
    wiremock.stubFor(
        get(urlEqualTo("/repos/" + OWNER + "/" + NAME + "/issues/" + PR_NUMBER + "/comments"))
            .inScenario(scenario)
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("[]"))
            .willSetStateTo("post-done"));
    wiremock.stubFor(
        get(urlEqualTo("/repos/" + OWNER + "/" + NAME + "/issues/" + PR_NUMBER + "/comments"))
            .inScenario(scenario)
            .whenScenarioStateIs("post-done")
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        commentListJson(EXISTING_COMMENT_ID, "<!-- titan-build-comment -->\n…"))));
    stubCreateCommentOk();
    stubPatchCommentOk();

    reporter.report(event("SUCCESS"));
    reporter.report(event("FAILED"));

    wiremock.verify(
        1,
        postRequestedFor(
            urlEqualTo("/repos/" + OWNER + "/" + NAME + "/issues/" + PR_NUMBER + "/comments")));
    wiremock.verify(
        1,
        patchRequestedFor(
            urlEqualTo(
                "/repos/" + OWNER + "/" + NAME + "/issues/comments/" + EXISTING_COMMENT_ID)));
  }

  // ── pure-helper coverage ──────────────────────────────────────────────────

  @Test
  void extractPrNumber_missing_returnsNull() {
    assertEquals(null, GithubPrCommentReporter.extractPrNumber(null));
    assertEquals(null, GithubPrCommentReporter.extractPrNumber(""));
    assertEquals(null, GithubPrCommentReporter.extractPrNumber("{\"branch\":\"trunk\"}"));
    assertEquals(null, GithubPrCommentReporter.extractPrNumber("not-json"));
  }

  @Test
  void extractPrNumber_present_returnsInt() {
    assertEquals(
        Integer.valueOf(7),
        GithubPrCommentReporter.extractPrNumber("{\"prNumber\":7,\"branch\":\"x\"}"));
  }

  @Test
  void formatMillis_humanFriendly() {
    assertEquals("950ms", GithubPrCommentReporter.formatMillis(950));
    assertEquals("3s", GithubPrCommentReporter.formatMillis(3_500));
    assertEquals("1m 5s", GithubPrCommentReporter.formatMillis(65_000));
    assertEquals("1h 1m", GithubPrCommentReporter.formatMillis(3_660_000));
  }

  @Test
  void statusEmoji_allTerminalKnown() {
    assertFalse(GithubPrCommentReporter.statusEmoji("SUCCESS").equals(":grey_question:"));
    assertFalse(GithubPrCommentReporter.statusEmoji("FAILED").equals(":grey_question:"));
    assertFalse(GithubPrCommentReporter.statusEmoji("ABORTED").equals(":grey_question:"));
    assertFalse(GithubPrCommentReporter.statusEmoji("UNSTABLE").equals(":grey_question:"));
    assertFalse(GithubPrCommentReporter.statusEmoji("SKIPPED").equals(":grey_question:"));
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  private static String prMeta() {
    return "{\"branch\":\"feature/x\",\"commitSha\":\"deadbeef\",\"actor\":\"alice\",\"prNumber\":"
        + PR_NUMBER
        + "}";
  }

  private BuildStateChangedEvent event(String status) {
    return new BuildStateChangedEvent(
        buildId, status, "github-app:pull_request:opened", prMeta(), jobId, 1);
  }

  private void seedJobBuildAndStages() {
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
                  "INSERT INTO titan.jobs (full_name, display_name, pipeline_script, config_json, "
                      + "enabled, github_installation_id, github_repo_id) "
                      + "VALUES (?, ?, ?, '{}', TRUE, ?, ?)")) {
            ps.setString(1, "acme/widget");
            ps.setString(2, "Widget CI");
            ps.setString(3, "pipeline {}");
            ps.setLong(4, INSTALL_ID);
            ps.setLong(5, REPO_ID);
            ps.executeUpdate();
          } catch (java.sql.SQLException e) {
            throw new RuntimeException(e);
          }
          return null;
        });
    jobId = stores.jobs().findByFullName("acme/widget").orElseThrow().id;

    Instant start = Instant.parse("2026-05-26T09:55:00Z");
    Instant end = Instant.parse("2026-05-26T09:56:30Z");
    buildId =
        stores.withTransaction(
            conn -> {
              int n = stores.builds().nextBuildNumber(conn, jobId);
              BuildRow row = new BuildRow();
              row.jobId = jobId;
              row.buildNumber = n;
              row.status = "SUCCESS";
              row.queuedAt = start;
              row.startedAt = start;
              row.finishedAt = end;
              row.durationMs = 90_000L;
              row.triggerType = "github-app:pull_request:opened";
              row.triggerMetaJson = prMeta();
              return stores.builds().insert(conn, row);
            });

    // Two stage nodes — "Build" SUCCESS, "Test" SUCCESS. Sufficient for body-shape assertions.
    FlowNodeRow build = new FlowNodeRow();
    build.buildId = buildId;
    build.nodeId = "stage-build";
    build.nodeType = "STAGE";
    build.displayName = "Build";
    build.status = "SUCCESS";
    build.startedAt = start;
    build.completedAt = start.plusSeconds(30);
    build.durationMs = 30_000L;
    stores.flowNodes().insert(build);

    FlowNodeRow test = new FlowNodeRow();
    test.buildId = buildId;
    test.nodeId = "stage-test";
    test.nodeType = "STAGE";
    test.displayName = "Test";
    test.status = "SUCCESS";
    test.startedAt = start.plusSeconds(30);
    test.completedAt = end;
    test.durationMs = 60_000L;
    stores.flowNodes().insert(test);

    // A non-stage node we expect the reporter to filter out.
    FlowNodeRow step = new FlowNodeRow();
    step.buildId = buildId;
    step.nodeId = "step-1";
    step.nodeType = "STEP";
    step.displayName = "echo hi";
    step.status = "SUCCESS";
    step.startedAt = start;
    step.completedAt = start.plusSeconds(1);
    step.durationMs = 1_000L;
    stores.flowNodes().insert(step);
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
                            + "\",\"expires_at\":\"2026-05-26T11:00:00Z\","
                            + "\"permissions\":{\"issues\":\"write\",\"pull_requests\":\"write\"}}")));
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

  private void stubIssueLookup() {
    // kohsuke's GHRepository.getIssue(n) does a GET on /repos/{owner}/{repo}/issues/{n}.
    wiremock.stubFor(
        get(urlEqualTo("/repos/" + OWNER + "/" + NAME + "/issues/" + PR_NUMBER))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"number\":"
                            + PR_NUMBER
                            + ",\"id\":1,\"title\":\"fix bug\",\"state\":\"open\","
                            + "\"comments_url\":\"http://localhost:"
                            + wiremock.port()
                            + "/repos/"
                            + OWNER
                            + "/"
                            + NAME
                            + "/issues/"
                            + PR_NUMBER
                            + "/comments\",\"url\":\"http://localhost:"
                            + wiremock.port()
                            + "/repos/"
                            + OWNER
                            + "/"
                            + NAME
                            + "/issues/"
                            + PR_NUMBER
                            + "\"}")));
  }

  private void stubListCommentsEmpty() {
    wiremock.stubFor(
        get(urlEqualTo("/repos/" + OWNER + "/" + NAME + "/issues/" + PR_NUMBER + "/comments"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("[]")));
  }

  private void stubListCommentsWithExisting() {
    wiremock.stubFor(
        get(urlEqualTo("/repos/" + OWNER + "/" + NAME + "/issues/" + PR_NUMBER + "/comments"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        commentListJson(
                            EXISTING_COMMENT_ID, "<!-- titan-build-comment -->\nold"))));
  }

  private String commentListJson(long id, String body) {
    String escaped = body.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    return "[{\"id\":"
        + id
        + ",\"body\":\""
        + escaped
        + "\",\"url\":\"http://localhost:"
        + wiremock.port()
        + "/repos/"
        + OWNER
        + "/"
        + NAME
        + "/issues/comments/"
        + id
        + "\",\"user\":{\"login\":\"titan-test[bot]\",\"type\":\"Bot\"}}]";
  }

  private void stubCreateCommentOk() {
    wiremock.stubFor(
        post(urlEqualTo("/repos/" + OWNER + "/" + NAME + "/issues/" + PR_NUMBER + "/comments"))
            .willReturn(
                aResponse()
                    .withStatus(201)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"id\":9999,\"body\":\"...\",\"url\":\"http://localhost:"
                            + wiremock.port()
                            + "/repos/"
                            + OWNER
                            + "/"
                            + NAME
                            + "/issues/comments/9999\"}")));
  }

  private void stubPatchCommentOk() {
    wiremock.stubFor(
        patch(
                urlEqualTo(
                    "/repos/" + OWNER + "/" + NAME + "/issues/comments/" + EXISTING_COMMENT_ID))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"id\":"
                            + EXISTING_COMMENT_ID
                            + ",\"body\":\"...\",\"url\":\"http://localhost:"
                            + wiremock.port()
                            + "/repos/"
                            + OWNER
                            + "/"
                            + NAME
                            + "/issues/comments/"
                            + EXISTING_COMMENT_ID
                            + "\"}")));
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
