package io.adaptiq.titan.scm.github;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.patch;
import static com.github.tomakehurst.wiremock.client.WireMock.patchRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.adaptiq.titan.api.FakeTitanStores;
import io.adaptiq.titan.build.BuildStateChangedEvent;
import io.adaptiq.titan.flow.crypto.CredentialKeyProvider;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.BuildRow;
import java.security.KeyPair;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * WireMock-backed unit tests for {@link GithubCheckRunReporter} (closes #965).
 *
 * <p>Scaffolding mirrors {@link GithubStatusReporterTest} — same H2-backed {@link FakeTitanStores},
 * same WireMock-driven kohsuke client, same manifest-callback bootstrap. Tests assert on the
 * payload sent to {@code POST /repos/.../check-runs} (start) and {@code PATCH
 * /repos/.../check-runs/{id}} (finish), plus the persistence of the returned id.
 */
class GithubCheckRunReporterTest {

  private static final byte[] KEK_BYTES = new byte[32];
  private static final long APP_ID = 999_201L;
  private static final long INSTALL_ID = 555_201L;
  private static final long REPO_ID = 333_201L;
  private static final String OWNER = "acme-org";
  private static final String NAME = "widget";
  private static final String SHA = "deadbeef1234567890abcdef1234567890abcdef";
  private static final long CHECK_RUN_ID = 88_991_010L;

  private WireMockServer wiremock;
  private TitanStores stores;
  private GithubCheckRunReporter reporter;
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
    reporter =
        new GithubCheckRunReporter(stores, service, factory, "https://titan.example.com", true);

    stubManifestExchange("seed-code", pem);
    service.handleManifestCallback("seed-code");
    seedJobAndRepo();
    stubInstallationTokenOk("ghs_test_token_aaa");
  }

  @AfterEach
  void tearDown() {
    if (wiremock != null) wiremock.stop();
  }

  // ── happy path: start ────────────────────────────────────────────────────

  @Test
  void runningEvent_createsCheckRunAndPersistsId() {
    stubRepoLookup();
    stubCreateCheckRunOk();

    reporter.report(event("RUNNING"));

    wiremock.verify(
        1,
        postRequestedFor(urlEqualTo("/repos/" + OWNER + "/" + NAME + "/check-runs"))
            .withRequestBody(containing("\"name\":\"Titan / "))
            .withRequestBody(containing("\"head_sha\":\"" + SHA + "\""))
            .withRequestBody(containing("\"status\":\"in_progress\""))
            .withRequestBody(
                containing(
                    "\"details_url\":\"https://titan.example.com/builds/" + buildId + "\"")));

    BuildRow after = stores.builds().findById(buildId).orElseThrow();
    assertEquals(CHECK_RUN_ID, after.externalCheckRunId);
  }

  @Test
  void queuedEvent_alsoOpensCheckRun() {
    stubRepoLookup();
    stubCreateCheckRunOk();

    reporter.report(event("QUEUED"));

    wiremock.verify(
        1, postRequestedFor(urlEqualTo("/repos/" + OWNER + "/" + NAME + "/check-runs")));
    BuildRow after = stores.builds().findById(buildId).orElseThrow();
    assertEquals(CHECK_RUN_ID, after.externalCheckRunId);
  }

  @Test
  void runningAfterQueued_doesNotCreateSecondCheckRun() {
    // Idempotency: once the column is populated, a follow-up start-class event no-ops.
    stores.builds().setExternalCheckRunId(buildId, CHECK_RUN_ID);

    reporter.report(event("RUNNING"));

    wiremock.verify(
        0, postRequestedFor(urlEqualTo("/repos/" + OWNER + "/" + NAME + "/check-runs")));
  }

  // ── happy path: finish ───────────────────────────────────────────────────

  @Test
  void successEvent_patchesCheckRunWithSuccessConclusion() {
    stubRepoLookup();
    stubUpdateCheckRunOk();
    // Seed the column as if start already happened.
    stores.builds().setExternalCheckRunId(buildId, CHECK_RUN_ID);

    reporter.report(event("SUCCESS"));

    wiremock.verify(
        1,
        patchRequestedFor(
                urlEqualTo("/repos/" + OWNER + "/" + NAME + "/check-runs/" + CHECK_RUN_ID))
            .withRequestBody(containing("\"status\":\"completed\""))
            .withRequestBody(containing("\"conclusion\":\"success\"")));
  }

  @Test
  void failedEvent_patchesCheckRunWithFailureConclusion() {
    stubRepoLookup();
    stubUpdateCheckRunOk();
    stores.builds().setExternalCheckRunId(buildId, CHECK_RUN_ID);

    reporter.report(event("FAILED"));

    wiremock.verify(
        1,
        patchRequestedFor(
                urlEqualTo("/repos/" + OWNER + "/" + NAME + "/check-runs/" + CHECK_RUN_ID))
            .withRequestBody(containing("\"conclusion\":\"failure\"")));
  }

  @Test
  void abortedEvent_patchesCheckRunWithCancelledConclusion() {
    stubRepoLookup();
    stubUpdateCheckRunOk();
    stores.builds().setExternalCheckRunId(buildId, CHECK_RUN_ID);

    reporter.report(event("ABORTED"));

    wiremock.verify(
        1,
        patchRequestedFor(
                urlEqualTo("/repos/" + OWNER + "/" + NAME + "/check-runs/" + CHECK_RUN_ID))
            .withRequestBody(containing("\"conclusion\":\"cancelled\"")));
  }

  @Test
  void unstableEvent_patchesCheckRunWithNeutralConclusion() {
    stubRepoLookup();
    stubUpdateCheckRunOk();
    stores.builds().setExternalCheckRunId(buildId, CHECK_RUN_ID);

    reporter.report(event("UNSTABLE"));

    wiremock.verify(
        1,
        patchRequestedFor(
                urlEqualTo("/repos/" + OWNER + "/" + NAME + "/check-runs/" + CHECK_RUN_ID))
            .withRequestBody(containing("\"conclusion\":\"neutral\"")));
  }

  // ── adversarial: non-App / no SHA / no link ──────────────────────────────

  @Test
  void manualBuild_makesNoHttpCall() {
    BuildStateChangedEvent evt =
        new BuildStateChangedEvent(buildId, "SUCCESS", "manual", "{\"actor\":\"alice\"}", jobId, 1);
    assertDoesNotThrow(() -> reporter.report(evt));

    wiremock.verify(0, postRequestedFor(urlMatching("/repos/.*/check-runs.*")));
    wiremock.verify(0, patchRequestedFor(urlMatching("/repos/.*/check-runs/.*")));
  }

  @Test
  void terminalEventWithoutPriorCheckRunId_isNoOp() {
    // Push triggered an App build but the create-check-run POST failed (no id persisted). The
    // matching terminal event must NOT attempt a PATCH against /check-runs/null.
    reporter.report(event("SUCCESS"));

    wiremock.verify(0, patchRequestedFor(urlMatching("/repos/.*/check-runs/.*")));
  }

  // ── adversarial: feature flag off ────────────────────────────────────────

  @Test
  void featureFlagOff_noTraffic() {
    GithubCheckRunReporter disabled =
        new GithubCheckRunReporter(
            stores, service, freshFactory(), "https://titan.example.com", false);

    disabled.onBuildStateChanged(event("RUNNING"));
    disabled.onBuildStateChanged(event("SUCCESS"));

    wiremock.verify(0, postRequestedFor(urlMatching("/repos/.*/check-runs.*")));
    wiremock.verify(0, patchRequestedFor(urlMatching("/repos/.*/check-runs/.*")));
    // Column stays null.
    assertNull(stores.builds().findById(buildId).orElseThrow().externalCheckRunId);
  }

  // ── adversarial: GitHub errors don't crash the observer ──────────────────

  @Test
  void serverError500OnCreate_swallowsExceptionDoesNotCrash() {
    stubRepoLookup();
    wiremock.stubFor(
        post(urlEqualTo("/repos/" + OWNER + "/" + NAME + "/check-runs"))
            .willReturn(aResponse().withStatus(500).withBody("{\"message\":\"boom\"}")));

    assertDoesNotThrow(() -> reporter.report(event("RUNNING")));
    // No id persisted on failure.
    assertNull(stores.builds().findById(buildId).orElseThrow().externalCheckRunId);
  }

  @Test
  void serverError500OnUpdate_swallowsExceptionDoesNotCrash() {
    stubRepoLookup();
    stores.builds().setExternalCheckRunId(buildId, CHECK_RUN_ID);
    wiremock.stubFor(
        patch(urlEqualTo("/repos/" + OWNER + "/" + NAME + "/check-runs/" + CHECK_RUN_ID))
            .willReturn(aResponse().withStatus(500).withBody("{\"message\":\"boom\"}")));

    assertDoesNotThrow(() -> reporter.report(event("SUCCESS")));
  }

  // ── helpers ──────────────────────────────────────────────────────────────

  private BuildStateChangedEvent event(String status) {
    String meta = "{\"branch\":\"trunk\",\"commitSha\":\"" + SHA + "\",\"actor\":\"alice\"}";
    return new BuildStateChangedEvent(buildId, status, "github-app:push", meta, jobId, 1);
  }

  private GithubClientFactory freshFactory() {
    return new GithubClientFactory("http://localhost:" + wiremock.port());
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

    buildId =
        stores.withTransaction(
            conn -> {
              int n = stores.builds().nextBuildNumber(conn, jobId);
              BuildRow row = new BuildRow();
              row.jobId = jobId;
              row.buildNumber = n;
              row.status = "QUEUED";
              row.queuedAt = Instant.now();
              row.triggerType = "github-app:push";
              row.triggerMetaJson =
                  "{\"branch\":\"trunk\",\"commitSha\":\"" + SHA + "\",\"actor\":\"alice\"}";
              return stores.builds().insert(conn, row);
            });
    assertNotNull(stores.builds().findById(buildId).orElseThrow().queuedAt);
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
                            + "\",\"expires_at\":\"2026-05-25T11:00:00Z\","
                            + "\"permissions\":{\"checks\":\"write\"}}")));
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

  private void stubCreateCheckRunOk() {
    wiremock.stubFor(
        post(urlEqualTo("/repos/" + OWNER + "/" + NAME + "/check-runs"))
            .willReturn(
                aResponse()
                    .withStatus(201)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"id\":"
                            + CHECK_RUN_ID
                            + ",\"head_sha\":\""
                            + SHA
                            + "\",\"status\":\"in_progress\","
                            + "\"name\":\"Titan / acme/widget\",\"url\":\"https://api.github.com/repos/"
                            + OWNER
                            + "/"
                            + NAME
                            + "/check-runs/"
                            + CHECK_RUN_ID
                            + "\"}")));
  }

  private void stubUpdateCheckRunOk() {
    wiremock.stubFor(
        patch(urlEqualTo("/repos/" + OWNER + "/" + NAME + "/check-runs/" + CHECK_RUN_ID))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"id\":"
                            + CHECK_RUN_ID
                            + ",\"head_sha\":\""
                            + SHA
                            + "\",\"status\":\"completed\","
                            + "\"conclusion\":\"success\"}")));
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
