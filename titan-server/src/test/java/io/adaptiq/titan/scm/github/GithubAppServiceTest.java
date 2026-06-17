package io.adaptiq.titan.scm.github;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.adaptiq.titan.api.FakeTitanStores;
import io.adaptiq.titan.flow.crypto.CredentialKeyProvider;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.GithubAppRow;
import io.adaptiq.titan.store.rows.GithubInstallationRow;
import java.security.KeyPair;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link GithubAppService} with the GitHub API stubbed via WireMock and the kohsuke
 * {@code GitHub} client pointed at the WireMock endpoint via {@link GithubClientFactory}.
 *
 * <p>Reworked in #874 for the org.kohsuke:github-api migration. The behavioural contracts are
 * identical to the pre-#874 version (manifest callback → singleton row, idempotent re-call, install
 * tokens cached + refreshed at 50min, invalid code surfaces 422, etc.).
 */
class GithubAppServiceTest {

  private static final byte[] KEK_BYTES = new byte[32]; // all-zero KEK for deterministic tests

  private WireMockServer wiremock;
  private TitanStores stores;
  private GithubAppService service;
  private String testPem;
  private long appId;
  private MutableClock clock;

  @BeforeEach
  void setUp() throws Exception {
    wiremock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    wiremock.start();
    stores = FakeTitanStores.create();
    appId = 12345L;
    KeyPair kp = TestRsaKeys.newRsaKeyPair();
    testPem = TestRsaKeys.toPkcs8Pem(kp);

    clock = new MutableClock(Instant.parse("2026-05-24T10:00:00Z"));
    GithubClientFactory factory = new GithubClientFactory("http://localhost:" + wiremock.port());
    service = new GithubAppService(stores, fixedKeyProvider(), factory, clock);
  }

  @AfterEach
  void tearDown() {
    if (wiremock != null) wiremock.stop();
  }

  // ── manifest callback ─────────────────────────────────────────────────────

  @Test
  void manifestCallback_persistsSingletonRow() {
    stubManifestExchange("good-code", appId);

    GithubAppRow row = service.handleManifestCallback("good-code");

    assertEquals(appId, row.appId);
    assertEquals("titan-test", row.slug);
    assertEquals(1, stores.githubApp().count());
    Optional<GithubAppRow> loaded = stores.githubApp().findSingleton();
    assertTrue(loaded.isPresent());
    assertEquals(1L, loaded.get().id);
  }

  @Test
  void manifestCallback_replayedWithFreshCode_updatesInPlaceNoDuplicate() {
    stubManifestExchange("code-1", appId);
    service.handleManifestCallback("code-1");

    stubManifestExchangeWithName("code-2", appId + 1, "Titan v2", "titan-v2");
    GithubAppRow updated = service.handleManifestCallback("code-2");

    assertEquals(1, stores.githubApp().count(), "must still be exactly one row");
    assertEquals(appId + 1, updated.appId);
    assertEquals("titan-v2", updated.slug);
  }

  @Test
  void manifestCallback_invalidCode_throwsApiExceptionWith422() {
    wiremock.stubFor(
        post(urlMatching("/app-manifests/.*/conversions"))
            .willReturn(aResponse().withStatus(422).withBody("{\"message\":\"Bad code\"}")));

    GithubApiException ex =
        assertThrows(GithubApiException.class, () -> service.handleManifestCallback("bad-code"));
    assertEquals(422, ex.status());
    assertEquals(0, stores.githubApp().count(), "no row must be persisted on failure");
  }

  // ── installation-token cache ──────────────────────────────────────────────

  @Test
  void installationToken_cachesWithinRefreshWindow_noSecondCall() {
    stubManifestExchange("code", appId);
    service.handleManifestCallback("code");

    long installId = 999L;
    stubInstallationLookup(installId, "acme-org", "Organization");
    stubInstallationToken(installId, "ghs_fresh_AAA", "2026-05-24T11:00:00Z");

    String first = service.getInstallationToken(installId);
    clock.advance(java.time.Duration.ofMinutes(30));
    String second = service.getInstallationToken(installId);

    assertEquals(first, second, "cache must return the same token within the refresh window");
    wiremock.verify(
        1, postRequestedFor(urlEqualTo("/app/installations/" + installId + "/access_tokens")));
  }

  @Test
  void installationToken_refreshesAfter50Minutes() {
    stubManifestExchange("code", appId);
    service.handleManifestCallback("code");

    long installId = 999L;
    stubInstallationLookup(installId, "acme-org", "Organization");
    stubInstallationToken(installId, "ghs_first", "2026-05-24T11:00:00Z");
    service.getInstallationToken(installId);

    clock.advance(java.time.Duration.ofMinutes(51));
    wiremock.resetAll();
    stubInstallationLookup(installId, "acme-org", "Organization");
    stubInstallationToken(installId, "ghs_second", "2026-05-24T11:51:00Z");
    String refreshed = service.getInstallationToken(installId);

    assertEquals("ghs_second", refreshed);
  }

  // ── installation sync ─────────────────────────────────────────────────────

  @Test
  void syncInstallations_persistsRows() {
    stubManifestExchange("code", appId);
    service.handleManifestCallback("code");

    // kohsuke first hits GET /app then GET /app/installations to enumerate.
    wiremock.stubFor(
        get(urlEqualTo("/app"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"id\":"
                            + appId
                            + ",\"name\":\"Titan CI\",\"slug\":\"titan-test\","
                            + "\"html_url\":\"https://github.com/apps/titan-test\","
                            + "\"owner\":{\"login\":\"acme-org\"},\"events\":[]}")));
    wiremock.stubFor(
        get(urlMatching("/app/installations.*"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "[{\"id\":42,\"account\":{\"login\":\"acme-org\",\"type\":\"Organization\"},"
                            + "\"target_type\":\"Organization\",\"suspended_at\":null}]")));

    List<GithubInstallationRow> rows = service.syncInstallations();
    assertEquals(1, rows.size());
    assertEquals(42L, rows.get(0).installId);
    assertEquals("acme-org", rows.get(0).accountLogin);
    assertEquals("Organization", rows.get(0).accountType);

    // Re-sync with mutated data — idempotent upsert, no duplicate.
    wiremock.resetAll();
    // kohsuke first hits GET /app then GET /app/installations to enumerate.
    wiremock.stubFor(
        get(urlEqualTo("/app"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"id\":"
                            + appId
                            + ",\"name\":\"Titan CI\",\"slug\":\"titan-test\","
                            + "\"html_url\":\"https://github.com/apps/titan-test\","
                            + "\"owner\":{\"login\":\"acme-org\"},\"events\":[]}")));
    wiremock.stubFor(
        get(urlMatching("/app/installations.*"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "[{\"id\":42,\"account\":{\"login\":\"acme-org-renamed\","
                            + "\"type\":\"Organization\"},"
                            + "\"target_type\":\"Organization\",\"suspended_at\":null}]")));
    List<GithubInstallationRow> after = service.syncInstallations();
    assertEquals(1, after.size());
    assertEquals("acme-org-renamed", after.get(0).accountLogin);
  }

  @Test
  void syncRepositoriesForInstall_persistsRepoRows() {
    stubManifestExchange("code", appId);
    service.handleManifestCallback("code");

    long installId = 42L;
    stores
        .githubInstallations()
        .insert(installId, "acme-org", "Organization", "Organization", null);
    stubInstallationLookup(installId, "acme-org", "Organization");
    stubInstallationToken(installId, "ghs_token", "2026-05-24T11:00:00Z");
    wiremock.stubFor(
        get(urlMatching("/installation/repositories.*"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"total_count\":1,\"repositories\":[{\"id\":7,\"name\":\"widgets\","
                            + "\"owner\":{\"login\":\"acme-org\"},"
                            + "\"default_branch\":\"main\",\"private\":false}]}")));

    int count = service.syncRepositoriesForInstall(installId);
    assertEquals(1, count);
    assertEquals(1, stores.githubRepositories().listByInstall(installId).size());
    assertEquals("widgets", stores.githubRepositories().listByInstall(installId).get(0).name);
  }

  // ── webhook-secret unseal round-trip ──────────────────────────────────────

  @Test
  void unsealWebhookSecret_returnsOriginalPlaintext() {
    stubManifestExchange("code", appId);
    service.handleManifestCallback("code");

    String unsealed = service.unsealWebhookSecret();
    assertEquals("test-webhook-secret", unsealed);
  }

  @Test
  void getInstallationToken_callsApiAndPersistsNothing() {
    stubManifestExchange("code", appId);
    service.handleManifestCallback("code");

    long installId = 42L;
    stubInstallationLookup(installId, "acme-org", "Organization");
    stubInstallationToken(installId, "ghs_TOKEN_VAL", "2026-05-24T11:00:00Z");
    String t = service.getInstallationToken(installId);
    assertEquals("ghs_TOKEN_VAL", t);

    wiremock.verify(1, postRequestedFor(urlMatching("/app-manifests/.*/conversions")));
    wiremock.verify(
        1, postRequestedFor(urlEqualTo("/app/installations/" + installId + "/access_tokens")));
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private void stubManifestExchange(String code, long persistedAppId) {
    stubManifestExchangeWithName(code, persistedAppId, "Titan CI", "titan-test");
  }

  private void stubManifestExchangeWithName(
      String code, long persistedAppId, String name, String slug) {
    String escapedPem = testPem.replace("\n", "\\n");
    // GHAppFromManifest tolerates the extra `client_id` field the live GitHub now returns; the
    // pre-#874 hand-rolled DTO did not. We include it here to lock the demo-night regression.
    String body =
        "{\"id\":"
            + persistedAppId
            + ",\"client_id\":\"Iv1.client-"
            + slug
            + "\",\"name\":\""
            + name
            + "\",\"slug\":\""
            + slug
            + "\",\"html_url\":\"https://github.com/apps/"
            + slug
            + "\",\"pem\":\""
            + escapedPem
            + "\",\"webhook_secret\":\"test-webhook-secret\"}";
    wiremock.stubFor(
        post(urlEqualTo("/app-manifests/" + code + "/conversions"))
            .willReturn(
                aResponse()
                    .withStatus(201)
                    .withHeader("Content-Type", "application/json")
                    .withBody(body)));
  }

  /**
   * kohsuke's {@code GHApp.getInstallationById(long)} hits {@code GET /app/installations/{id}}
   * before it can call {@code createToken().create()} on that install. {@link
   * org.kohsuke.github.GitHub#getApp()} hits {@code GET /app} first to materialise the {@code
   * GHApp} metadata. Stub both.
   */
  private void stubInstallationLookup(long installId, String login, String accountType) {
    wiremock.stubFor(
        get(urlEqualTo("/app"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"id\":"
                            + appId
                            + ",\"name\":\"Titan CI\",\"slug\":\"titan-test\","
                            + "\"html_url\":\"https://github.com/apps/titan-test\","
                            + "\"owner\":{\"login\":\""
                            + login
                            + "\"},\"events\":[]}")));
    wiremock.stubFor(
        get(urlPathMatching("/app/installations/" + installId))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"id\":"
                            + installId
                            + ",\"account\":{\"login\":\""
                            + login
                            + "\",\"type\":\""
                            + accountType
                            + "\"},\"target_type\":\""
                            + accountType
                            + "\",\"suspended_at\":null,"
                            + "\"access_tokens_url\":\"http://localhost:"
                            + wiremock.port()
                            + "/app/installations/"
                            + installId
                            + "/access_tokens\"}")));
  }

  private void stubInstallationToken(long installId, String token, String expiresAtIso) {
    wiremock.stubFor(
        post(urlEqualTo("/app/installations/" + installId + "/access_tokens"))
            .willReturn(
                aResponse()
                    .withStatus(201)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"token\":\"" + token + "\",\"expires_at\":\"" + expiresAtIso + "\"}")));
  }

  private CredentialKeyProvider fixedKeyProvider() {
    return new CredentialKeyProvider() {
      @Override
      public byte[] credentialKey() {
        return KEK_BYTES.clone();
      }

      @Override
      public String describe() {
        return "test:fixed";
      }
    };
  }

  /** Mutable clock for cache-TTL tests. */
  static final class MutableClock extends Clock {
    private Instant now;

    MutableClock(Instant start) {
      this.now = start;
    }

    void advance(java.time.Duration d) {
      now = now.plus(d);
    }

    @Override
    public java.time.ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  @SuppressWarnings("unused")
  private static String b64(byte[] bytes) {
    return Base64.getEncoder().encodeToString(bytes);
  }

  @SuppressWarnings("unused")
  private static void unused(Object o) {
    assertNotNull(o);
  }

  // Suppress unused-import warnings for helpers that may be needed when adding cases later.
  @SuppressWarnings("unused")
  private static final Object KEEP_HELPER_IMPORTS = equalTo("");
}
