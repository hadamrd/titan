package io.adaptiq.titan.scm.github;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.adaptiq.titan.api.FakeTitanStores;
import io.adaptiq.titan.flow.crypto.CredentialKeyProvider;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.GithubInstallationRow;
import io.adaptiq.titan.store.rows.GithubPipelineDiscoveredRow;
import io.adaptiq.titan.store.rows.GithubRepositoryRow;
import io.adaptiq.titan.store.rows.JobRow;
import java.security.KeyPair;
import java.time.Clock;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link GithubRepoScanner} — Child B of epic #831, reworked in #874 to use
 * kohsuke's {@code GitHub} client end-to-end.
 *
 * <p>Covers the acceptance criteria in #833:
 *
 * <ul>
 *   <li>Happy path: fake repo with 6 {@code .titan/pipelines/*.yml} files → all 6 persist with
 *       parsed metadata.
 *   <li>Adversarial: malformed YAML → row persisted with {@code parse_error}, no crash.
 *   <li>Adversarial: 404 on {@code /contents/.titan/pipelines} → empty result, {@code
 *       last_scanned_at} stamped, no crash.
 *   <li>Adversarial: revoked install token (401) → installation marked suspended, exception bubbles
 *       up, no partial pipeline data left behind.
 * </ul>
 */
class GithubRepoScannerTest {

  private static final byte[] KEK_BYTES = new byte[32];

  private WireMockServer wiremock;
  private TitanStores stores;
  private GithubAppService appService;
  private GithubRepoScanner scanner;
  private String testPem;
  private long appId;

  @BeforeEach
  void setUp() throws Exception {
    wiremock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    wiremock.start();
    stores = FakeTitanStores.create();
    appId = 12345L;
    KeyPair kp = TestRsaKeys.newRsaKeyPair();
    testPem = TestRsaKeys.toPkcs8Pem(kp);

    GithubClientFactory factory = new GithubClientFactory("http://localhost:" + wiremock.port());
    appService = new GithubAppService(stores, fixedKeyProvider(), factory, Clock.systemUTC());
    scanner = new GithubRepoScanner(stores, appService);

    stubManifestExchange("seed-code");
    appService.handleManifestCallback("seed-code");
  }

  @AfterEach
  void tearDown() {
    if (wiremock != null) wiremock.stop();
  }

  // ── happy path ────────────────────────────────────────────────────────────

  @Test
  void scanInstall_sixPipelineFiles_persistsAllSixWithMetadata() throws Exception {
    long installId = 42L;
    long repoId = 7L;
    seedInstallAndRepo(installId, repoId, "acme-org", "widgets");
    stubInstallationLookup(installId, "acme-org");
    stubInstallationToken(installId, "ghs_TOKEN");
    stubListRepositories(installId, repoId, "acme-org", "widgets");
    stubGetRepository("acme-org", "widgets", repoId);
    String[] files = {"build.yml", "deploy.yml", "test.yml", "lint.yml", "release.yml", "docs.yml"};
    stubPipelinesDir("acme-org", "widgets", files);
    for (String f : files) {
      stubPipelineFile("acme-org", "widgets", f, simplePipelineYaml("pipeline-" + f));
    }

    GithubRepoScanner.ScanReport report = scanner.scanInstall(installId);

    assertEquals(1, report.installsScanned());
    assertEquals(1, report.reposScanned());
    assertEquals(6, report.pipelinesFound());
    assertEquals(0, report.suspendedInstalls());

    List<GithubPipelineDiscoveredRow> rows = stores.githubPipelinesDiscovered().listByRepo(repoId);
    assertEquals(6, rows.size());
    for (GithubPipelineDiscoveredRow row : rows) {
      assertNull(
          row.parseError, "happy-path file " + row.filename + " must not carry a parse_error");
      assertNotNull(row.parsedMetadata);
      JsonNode meta = JsonMapper.builder().build().readTree(row.parsedMetadata);
      assertTrue(meta.has("stages"), "metadata must include stages: " + row.parsedMetadata);
      assertTrue(meta.has("triggers"));
      assertTrue(meta.has("parameters"));
      assertTrue(row.contentSha.startsWith("sha-"));
    }

    GithubRepositoryRow repo = stores.githubRepositories().listByInstall(installId).get(0);
    assertNotNull(repo.lastScannedAt);
  }

  // ── adversarial: malformed YAML ───────────────────────────────────────────

  @Test
  void scanInstall_malformedYaml_persistsRowWithParseError() {
    long installId = 42L;
    long repoId = 7L;
    seedInstallAndRepo(installId, repoId, "acme-org", "widgets");
    stubInstallationLookup(installId, "acme-org");
    stubInstallationToken(installId, "ghs_TOKEN");
    stubListRepositories(installId, repoId, "acme-org", "widgets");
    stubGetRepository("acme-org", "widgets", repoId);
    stubPipelinesDir("acme-org", "widgets", new String[] {"broken.yml", "ok.yml"});
    stubPipelineFile("acme-org", "widgets", "broken.yml", "this: is: not [valid yaml }");
    stubPipelineFile("acme-org", "widgets", "ok.yml", simplePipelineYaml("ok-pipeline"));

    GithubRepoScanner.ScanReport report = scanner.scanInstall(installId);

    assertEquals(2, report.pipelinesFound(), "every walked file lands a row, error or not");
    List<GithubPipelineDiscoveredRow> rows = stores.githubPipelinesDiscovered().listByRepo(repoId);
    assertEquals(2, rows.size());
    GithubPipelineDiscoveredRow broken =
        rows.stream().filter(r -> r.filename.equals("broken.yml")).findFirst().orElseThrow();
    assertNotNull(broken.parseError, "malformed YAML must populate parse_error");
    assertNull(broken.parsedMetadata, "no metadata when parse failed");
    GithubPipelineDiscoveredRow ok =
        rows.stream().filter(r -> r.filename.equals("ok.yml")).findFirst().orElseThrow();
    assertNull(ok.parseError);
    assertNotNull(ok.parsedMetadata);
  }

  // ── adversarial: no .titan/pipelines dir (404) ────────────────────────────

  @Test
  void scanInstall_noPipelinesDirectory_marksScannedAndReturnsZero() {
    long installId = 42L;
    long repoId = 7L;
    seedInstallAndRepo(installId, repoId, "acme-org", "widgets");
    stubInstallationLookup(installId, "acme-org");
    stubInstallationToken(installId, "ghs_TOKEN");
    stubListRepositories(installId, repoId, "acme-org", "widgets");
    stubGetRepository("acme-org", "widgets", repoId);
    wiremock.stubFor(
        get(urlMatching("/repos/acme-org/widgets/contents/\\.titan/pipelines(\\?.*)?"))
            .willReturn(aResponse().withStatus(404).withBody("{\"message\":\"Not Found\"}")));

    GithubRepoScanner.ScanReport report = scanner.scanInstall(installId);

    assertEquals(1, report.reposScanned());
    assertEquals(0, report.pipelinesFound());
    GithubRepositoryRow repo = stores.githubRepositories().listByInstall(installId).get(0);
    assertNotNull(repo.lastScannedAt);
    assertTrue(stores.githubPipelinesDiscovered().listByRepo(repoId).isEmpty());
  }

  // ── adversarial: revoked installation token (401) ─────────────────────────

  @Test
  void scanInstall_revokedToken_marksInstallSuspendedAndThrows() {
    long installId = 42L;
    long repoId = 7L;
    seedInstallAndRepo(installId, repoId, "acme-org", "widgets");
    stubInstallationLookup(installId, "acme-org");
    wiremock.stubFor(
        post(urlEqualTo("/app/installations/" + installId + "/access_tokens"))
            .willReturn(aResponse().withStatus(401).withBody("{\"message\":\"Bad credentials\"}")));

    GithubApiException ex =
        assertThrows(GithubApiException.class, () -> scanner.scanInstall(installId));
    assertEquals(401, ex.status());

    Optional<GithubInstallationRow> install =
        stores.githubInstallations().findByInstallId(installId);
    assertTrue(install.isPresent());
    assertNotNull(install.get().suspendedAt, "401 must suspend the installation");
    assertTrue(stores.githubPipelinesDiscovered().listByRepo(repoId).isEmpty());
  }

  // ── scanSingleRepo: event-driven re-parse on push (issue #886) ────────────

  @Test
  void scanSingleRepo_knownRepo_parsesAndUpsertsRows() {
    long installId = 42L;
    long repoId = 7L;
    seedInstallAndRepo(installId, repoId, "acme-org", "widgets");
    stubInstallationLookup(installId, "acme-org");
    stubInstallationToken(installId, "ghs_TOKEN");
    stubGetRepository("acme-org", "widgets", repoId);
    stubPipelinesDir("acme-org", "widgets", new String[] {"build.yml"});
    stubPipelineFile("acme-org", "widgets", "build.yml", simplePipelineYaml("event-driven"));

    int parsed = scanner.scanSingleRepo(installId, repoId);

    assertEquals(1, parsed);
    List<GithubPipelineDiscoveredRow> rows = stores.githubPipelinesDiscovered().listByRepo(repoId);
    assertEquals(1, rows.size());
    assertEquals("build.yml", rows.get(0).filename);
    assertNull(rows.get(0).parseError);
  }

  @Test
  void scanSingleRepo_unknownRepoId_returnsZeroNoRoundtrip() {
    long installId = 42L;
    long repoId = 7L;
    seedInstallAndRepo(installId, repoId, "acme-org", "widgets");
    // NOTE: no GitHub stubs — if scanner makes any HTTP call here, WireMock returns 404 + we crash.

    int parsed = scanner.scanSingleRepo(installId, /* unknown repo */ 99999L);

    assertEquals(0, parsed, "unknown repo id must short-circuit before any GitHub round-trip");
  }

  @Test
  void scanSingleRepo_revokedToken_marksInstallSuspendedAndThrows() {
    long installId = 42L;
    long repoId = 7L;
    seedInstallAndRepo(installId, repoId, "acme-org", "widgets");
    stubInstallationLookup(installId, "acme-org");
    wiremock.stubFor(
        post(urlEqualTo("/app/installations/" + installId + "/access_tokens"))
            .willReturn(aResponse().withStatus(401).withBody("{\"message\":\"Bad credentials\"}")));

    GithubApiException ex =
        assertThrows(GithubApiException.class, () -> scanner.scanSingleRepo(installId, repoId));
    assertEquals(401, ex.status());
    assertNotNull(
        stores.githubInstallations().findByInstallId(installId).orElseThrow().suspendedAt,
        "401 inside scanSingleRepo must still suspend the install");
  }

  // ── scanSingleRepo: branch-aware (issue #887) ─────────────────────────────

  @Test
  void scanSingleRepo_branchAware_parsesAgainstSpecifiedBranchAndKeysRowByBranch() {
    long installId = 42L;
    long repoId = 7L;
    seedInstallAndRepo(installId, repoId, "acme-org", "widgets");
    stubInstallationLookup(installId, "acme-org");
    stubInstallationToken(installId, "ghs_TOKEN");
    stubGetRepository("acme-org", "widgets", repoId);
    stubPipelinesDir("acme-org", "widgets", new String[] {"build.yml"});
    stubPipelineFile("acme-org", "widgets", "build.yml", simplePipelineYaml("feature-branch-yaml"));

    int parsed = scanner.scanSingleRepo(installId, repoId, "feature/x");

    assertEquals(1, parsed);
    List<GithubPipelineDiscoveredRow> mainRows =
        stores.githubPipelinesDiscovered().listByRepoAndBranch(repoId, "main");
    assertTrue(mainRows.isEmpty(), "main-branch row must NOT exist — we scanned feature/x");
    List<GithubPipelineDiscoveredRow> featureRows =
        stores.githubPipelinesDiscovered().listByRepoAndBranch(repoId, "feature/x");
    assertEquals(1, featureRows.size(), "feature/x row must be keyed under that branch");
    assertEquals("feature/x", featureRows.get(0).branch);
    assertEquals("build.yml", featureRows.get(0).filename);

    // Verify kohsuke actually sent the ?ref=feature/x query — that's the whole point of #887.
    assertTrue(
        wiremock.getAllServeEvents().stream()
            .anyMatch(
                e -> {
                  String url = e.getRequest().getUrl();
                  return url.contains("/contents/.titan/pipelines") && url.contains("ref=");
                }),
        "scanner must have appended ?ref=<branch> to the contents call");
  }

  @Test
  void scanSingleRepo_featureBranchScan_doesNotOverwriteExistingMainRow() {
    long installId = 42L;
    long repoId = 7L;
    seedInstallAndRepo(installId, repoId, "acme-org", "widgets");

    // Pre-existing main-branch row (the canonical UI-display row per design 65).
    stores
        .githubPipelinesDiscovered()
        .insert(repoId, "main", "build.yml", "sha-main", "{\"branch\":\"main\"}", null);

    stubInstallationLookup(installId, "acme-org");
    stubInstallationToken(installId, "ghs_TOKEN");
    stubGetRepository("acme-org", "widgets", repoId);
    stubPipelinesDir("acme-org", "widgets", new String[] {"build.yml"});
    stubPipelineFile("acme-org", "widgets", "build.yml", simplePipelineYaml("on-feature"));

    scanner.scanSingleRepo(installId, repoId, "feature/x");

    List<GithubPipelineDiscoveredRow> mainRows =
        stores.githubPipelinesDiscovered().listByRepoAndBranch(repoId, "main");
    assertEquals(1, mainRows.size(), "main-branch row MUST remain untouched");
    assertEquals(
        "sha-main",
        mainRows.get(0).contentSha,
        "main-branch row content must be the pre-existing one, not overwritten");
    assertEquals(
        1,
        stores.githubPipelinesDiscovered().listByRepoAndBranch(repoId, "feature/x").size(),
        "feature/x row must coexist with the main row");
  }

  @Test
  void scanSingleRepo_rescanSameBranch_replacesThatBranchRowsOnly() {
    long installId = 42L;
    long repoId = 7L;
    seedInstallAndRepo(installId, repoId, "acme-org", "widgets");

    // Seed a stale row on feature/x + a canonical main row.
    stores
        .githubPipelinesDiscovered()
        .insert(repoId, "feature/x", "old.yml", "sha-old", "{}", null);
    stores
        .githubPipelinesDiscovered()
        .insert(repoId, "main", "build.yml", "sha-main", "{\"branch\":\"main\"}", null);

    stubInstallationLookup(installId, "acme-org");
    stubInstallationToken(installId, "ghs_TOKEN");
    stubGetRepository("acme-org", "widgets", repoId);
    stubPipelinesDir("acme-org", "widgets", new String[] {"build.yml"});
    stubPipelineFile("acme-org", "widgets", "build.yml", simplePipelineYaml("new"));

    scanner.scanSingleRepo(installId, repoId, "feature/x");

    List<GithubPipelineDiscoveredRow> featureRows =
        stores.githubPipelinesDiscovered().listByRepoAndBranch(repoId, "feature/x");
    assertEquals(1, featureRows.size(), "stale old.yml row on feature/x must be removed");
    assertEquals("build.yml", featureRows.get(0).filename);

    assertEquals(
        1,
        stores.githubPipelinesDiscovered().listByRepoAndBranch(repoId, "main").size(),
        "main row must survive a feature/x rescan");
  }

  @Test
  void scanSingleRepo_branchHasNoPipelinesDir_clearsOnlyThatBranchRows() {
    long installId = 42L;
    long repoId = 7L;
    seedInstallAndRepo(installId, repoId, "acme-org", "widgets");

    stores.githubPipelinesDiscovered().insert(repoId, "main", "build.yml", "sha-main", "{}", null);
    stores
        .githubPipelinesDiscovered()
        .insert(repoId, "feature/x", "build.yml", "sha-old", "{}", null);

    stubInstallationLookup(installId, "acme-org");
    stubInstallationToken(installId, "ghs_TOKEN");
    stubGetRepository("acme-org", "widgets", repoId);
    wiremock.stubFor(
        get(urlMatching("/repos/acme-org/widgets/contents/\\.titan/pipelines(\\?.*)?"))
            .willReturn(aResponse().withStatus(404).withBody("{\"message\":\"Not Found\"}")));

    int parsed = scanner.scanSingleRepo(installId, repoId, "feature/x");

    assertEquals(0, parsed);
    assertTrue(
        stores.githubPipelinesDiscovered().listByRepoAndBranch(repoId, "feature/x").isEmpty(),
        "feature/x rows wiped when the dir is missing on that branch");
    assertEquals(
        1,
        stores.githubPipelinesDiscovered().listByRepoAndBranch(repoId, "main").size(),
        "main rows must NOT be touched by a feature/x 404");
  }

  // ── design 66: discovered pipelines ARE jobs (no Enable step) ─────────────

  @Test
  void scanInstall_defaultBranch_autoCreatesJobRowsPerDiscoveredFile() {
    long installId = 42L;
    long repoId = 7L;
    seedInstallAndRepo(installId, repoId, "acme-org", "widgets");
    stubInstallationLookup(installId, "acme-org");
    stubInstallationToken(installId, "ghs_TOKEN");
    stubListRepositories(installId, repoId, "acme-org", "widgets");
    stubGetRepository("acme-org", "widgets", repoId);
    stubPipelinesDir("acme-org", "widgets", new String[] {"build.yml", "deploy.yml"});
    stubPipelineFile("acme-org", "widgets", "build.yml", simplePipelineYaml("build"));
    stubPipelineFile("acme-org", "widgets", "deploy.yml", simplePipelineYaml("deploy"));

    scanner.scanInstall(installId);

    List<JobRow> jobs = stores.jobs().listAllByGithubRepo(installId, repoId);
    assertEquals(
        2, jobs.size(), "default-branch scan must auto-create one job per discovered file");
    java.util.Set<String> fullNames =
        jobs.stream().map(j -> j.fullName).collect(java.util.stream.Collectors.toSet());
    assertTrue(fullNames.contains("acme-org/widgets/build"));
    assertTrue(fullNames.contains("acme-org/widgets/deploy"));
    for (JobRow j : jobs) {
      assertTrue(j.enabled, "auto-created job must be enabled (design 66: no manual Enable)");
      assertEquals(Long.valueOf(installId), j.githubInstallationId);
      assertEquals(Long.valueOf(repoId), j.githubRepoId);
      assertNotNull(j.pipelineScript, "pipeline_script must carry the raw YAML");
      assertTrue(
          j.pipelineScript.contains("stages:"),
          "pipeline_script must be the discovered YAML, not the metadata JSON");
    }
  }

  @Test
  void scanInstall_parseFailure_doesNotCreateJobRow() {
    long installId = 42L;
    long repoId = 7L;
    seedInstallAndRepo(installId, repoId, "acme-org", "widgets");
    stubInstallationLookup(installId, "acme-org");
    stubInstallationToken(installId, "ghs_TOKEN");
    stubListRepositories(installId, repoId, "acme-org", "widgets");
    stubGetRepository("acme-org", "widgets", repoId);
    stubPipelinesDir("acme-org", "widgets", new String[] {"broken.yml", "ok.yml"});
    stubPipelineFile("acme-org", "widgets", "broken.yml", "this: is: not [valid yaml }");
    stubPipelineFile("acme-org", "widgets", "ok.yml", simplePipelineYaml("ok"));

    scanner.scanInstall(installId);

    List<JobRow> jobs = stores.jobs().listAllByGithubRepo(installId, repoId);
    assertEquals(1, jobs.size(), "only the parseable file gets a job row");
    assertEquals("acme-org/widgets/ok", jobs.get(0).fullName);
  }

  @Test
  void scanInstall_rescan_isIdempotentNoDuplicateJobRows() {
    long installId = 42L;
    long repoId = 7L;
    seedInstallAndRepo(installId, repoId, "acme-org", "widgets");
    stubInstallationLookup(installId, "acme-org");
    stubInstallationToken(installId, "ghs_TOKEN");
    stubListRepositories(installId, repoId, "acme-org", "widgets");
    stubGetRepository("acme-org", "widgets", repoId);
    stubPipelinesDir("acme-org", "widgets", new String[] {"build.yml"});
    stubPipelineFile("acme-org", "widgets", "build.yml", simplePipelineYaml("build"));

    scanner.scanInstall(installId);
    scanner.scanInstall(installId);
    scanner.scanInstall(installId);

    assertEquals(
        1,
        stores.jobs().listAllByGithubRepo(installId, repoId).size(),
        "rescanning must NOT duplicate job rows");
  }

  @Test
  void scanSingleRepo_fileRemovedFromDefaultBranch_deletesMatchingJobRow() {
    long installId = 42L;
    long repoId = 7L;
    seedInstallAndRepo(installId, repoId, "acme-org", "widgets");
    stubInstallationLookup(installId, "acme-org");
    stubInstallationToken(installId, "ghs_TOKEN");
    stubGetRepository("acme-org", "widgets", repoId);

    // First scan: two pipelines, two jobs.
    stubPipelinesDir("acme-org", "widgets", new String[] {"build.yml", "old.yml"});
    stubPipelineFile("acme-org", "widgets", "build.yml", simplePipelineYaml("build"));
    stubPipelineFile("acme-org", "widgets", "old.yml", simplePipelineYaml("old"));
    scanner.scanSingleRepo(installId, repoId, "main");
    assertEquals(2, stores.jobs().listAllByGithubRepo(installId, repoId).size());

    // Second scan: old.yml is gone — re-stub directory with one file only.
    wiremock.resetAll();
    stubInstallationLookup(installId, "acme-org");
    stubInstallationToken(installId, "ghs_TOKEN");
    stubGetRepository("acme-org", "widgets", repoId);
    stubPipelinesDir("acme-org", "widgets", new String[] {"build.yml"});
    stubPipelineFile("acme-org", "widgets", "build.yml", simplePipelineYaml("build"));
    scanner.scanSingleRepo(installId, repoId, "main");

    List<JobRow> after = stores.jobs().listAllByGithubRepo(installId, repoId);
    assertEquals(1, after.size(), "job for deleted YAML must be hard-pruned (design 66)");
    assertEquals("acme-org/widgets/build", after.get(0).fullName);
  }

  @Test
  void scanSingleRepo_featureBranch_doesNotCreateJobRow() {
    long installId = 42L;
    long repoId = 7L;
    seedInstallAndRepo(installId, repoId, "acme-org", "widgets");
    stubInstallationLookup(installId, "acme-org");
    stubInstallationToken(installId, "ghs_TOKEN");
    stubGetRepository("acme-org", "widgets", repoId);
    stubPipelinesDir("acme-org", "widgets", new String[] {"build.yml"});
    stubPipelineFile("acme-org", "widgets", "build.yml", simplePipelineYaml("on-feature"));

    scanner.scanSingleRepo(installId, repoId, "feature/x");

    assertTrue(
        stores.jobs().listAllByGithubRepo(installId, repoId).isEmpty(),
        "feature-branch scans must NOT touch titan.jobs — only default branch drives jobs");
  }

  @Test
  void scanSingleRepo_pipelinesDirRemovedFromDefaultBranch_deletesAllJobsForRepo() {
    long installId = 42L;
    long repoId = 7L;
    seedInstallAndRepo(installId, repoId, "acme-org", "widgets");

    // Seed: one job already auto-created for this repo from a previous scan.
    stubInstallationLookup(installId, "acme-org");
    stubInstallationToken(installId, "ghs_TOKEN");
    stubGetRepository("acme-org", "widgets", repoId);
    stubPipelinesDir("acme-org", "widgets", new String[] {"build.yml"});
    stubPipelineFile("acme-org", "widgets", "build.yml", simplePipelineYaml("build"));
    scanner.scanSingleRepo(installId, repoId, "main");
    assertEquals(1, stores.jobs().listAllByGithubRepo(installId, repoId).size());

    // Now the directory disappears from the default branch.
    wiremock.resetAll();
    stubInstallationLookup(installId, "acme-org");
    stubInstallationToken(installId, "ghs_TOKEN");
    stubGetRepository("acme-org", "widgets", repoId);
    wiremock.stubFor(
        get(urlMatching("/repos/acme-org/widgets/contents/\\.titan/pipelines(\\?.*)?"))
            .willReturn(aResponse().withStatus(404).withBody("{\"message\":\"Not Found\"}")));

    scanner.scanSingleRepo(installId, repoId, "main");

    assertTrue(
        stores.jobs().listAllByGithubRepo(installId, repoId).isEmpty(),
        "every job for the repo must be removed when .titan/pipelines/ disappears from default");
  }

  // ── scanAll skips suspended installs ──────────────────────────────────────

  @Test
  void scanAll_skipsSuspendedInstalls() {
    long installId = 42L;
    stores
        .githubInstallations()
        .insert(installId, "acme-org", "Organization", "Organization", java.time.Instant.now());

    GithubRepoScanner.ScanReport report = scanner.scanAll();

    assertEquals(0, report.installsScanned(), "suspended installs must be skipped");
    assertFalse(
        wiremock.getAllServeEvents().stream()
            .anyMatch(e -> e.getRequest().getUrl().contains("/app/installations/" + installId)));
  }

  // ── helpers — wiremock stubs ──────────────────────────────────────────────

  private void seedInstallAndRepo(long installId, long repoId, String owner, String name) {
    stores.githubInstallations().insert(installId, owner, "Organization", "Organization", null);
    stores.githubRepositories().insert(installId, repoId, owner, name, "main", false);
  }

  private void stubManifestExchange(String code) {
    String escapedPem = testPem.replace("\n", "\\n");
    String body =
        "{\"id\":"
            + appId
            + ",\"client_id\":\"Iv1.client-test\",\"name\":\"Titan CI\",\"slug\":\"titan-test\","
            + "\"html_url\":\"https://github.com/apps/titan-test\","
            + "\"pem\":\""
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

  private void stubInstallationLookup(long installId, String login) {
    // kohsuke's appClient.getApp() hits GET /app first to materialise the GHApp metadata,
    // then getApp().getInstallationById(installId) hits GET /app/installations/{id}.
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
                            + "\"owner\":{\"login\":\"acme-org\"},"
                            + "\"events\":[]}")));
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
                            + "\",\"type\":\"Organization\"},"
                            + "\"target_type\":\"Organization\",\"suspended_at\":null,"
                            + "\"access_tokens_url\":\"http://localhost:"
                            + wiremock.port()
                            + "/app/installations/"
                            + installId
                            + "/access_tokens\"}")));
  }

  private void stubInstallationToken(long installId, String token) {
    wiremock.stubFor(
        post(urlEqualTo("/app/installations/" + installId + "/access_tokens"))
            .willReturn(
                aResponse()
                    .withStatus(201)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"token\":\"" + token + "\",\"expires_at\":\"2030-01-01T00:00:00Z\"}")));
  }

  private void stubListRepositories(long installId, long repoId, String owner, String name) {
    wiremock.stubFor(
        get(urlMatching("/installation/repositories.*"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"total_count\":1,\"repositories\":[{\"id\":"
                            + repoId
                            + ",\"name\":\""
                            + name
                            + "\",\"full_name\":\""
                            + owner
                            + "/"
                            + name
                            + "\","
                            + "\"owner\":{\"login\":\""
                            + owner
                            + "\"},\"default_branch\":\"main\",\"private\":false}]}")));
  }

  /**
   * kohsuke's {@code GitHub.getRepository(owner/name)} hits {@code GET /repos/{owner}/{name}} to
   * materialise the {@code GHRepository} before any subsequent contents call. Stub it as a minimal
   * shape — name, owner, default_branch.
   */
  private void stubGetRepository(String owner, String name, long repoId) {
    wiremock.stubFor(
        get(urlEqualTo("/repos/" + owner + "/" + name))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"id\":"
                            + repoId
                            + ",\"name\":\""
                            + name
                            + "\",\"full_name\":\""
                            + owner
                            + "/"
                            + name
                            + "\","
                            + "\"owner\":{\"login\":\""
                            + owner
                            + "\"},\"default_branch\":\"main\",\"private\":false}")));
  }

  private void stubPipelinesDir(String owner, String repo, String[] files) {
    StringBuilder body = new StringBuilder("[");
    for (int i = 0; i < files.length; i++) {
      if (i > 0) body.append(",");
      body.append(
          "{\"name\":\""
              + files[i]
              + "\",\"path\":\".titan/pipelines/"
              + files[i]
              + "\",\"sha\":\"sha-"
              + files[i]
              + "\",\"size\":42,\"type\":\"file\",\"download_url\":null,"
              + "\"url\":\"http://localhost:"
              + wiremock.port()
              + "/repos/"
              + owner
              + "/"
              + repo
              + "/contents/.titan/pipelines/"
              + files[i]
              + "\"}");
    }
    body.append("]");
    // Use urlMatching to tolerate the `?ref=<branch>` query kohsuke now appends for the
    // branch-aware GHRepository#getDirectoryContent(path, ref) call introduced in #887.
    wiremock.stubFor(
        get(urlMatching(
                "/repos/"
                    + java.util.regex.Pattern.quote(owner)
                    + "/"
                    + java.util.regex.Pattern.quote(repo)
                    + "/contents/\\.titan/pipelines(\\?.*)?"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(body.toString())));
  }

  /**
   * Stub the contents endpoint for a single file. The scanner now uses {@code GHContent#read()}
   * which fetches the raw blob via the URL field on the {@code GHContent} returned by the directory
   * listing. WireMock serves the same path returning the typed shape kohsuke decodes.
   *
   * <p>kohsuke's {@code GHContent} can read content two ways depending on size: 1. Inline base64 in
   * the listing response (small files via {@code getContent()}). 2. Via {@code read()} which hits
   * the {@code download_url} or raw blob endpoint.
   *
   * <p>We make both paths work by serving the typed `application/vnd.github.raw` body on the same
   * contents path (the scanner calls {@code read()} which kohsuke routes to the contents API with
   * the raw media type).
   */
  private void stubPipelineFile(String owner, String repo, String filename, String yamlContent) {
    String b64 = Base64.getEncoder().encodeToString(yamlContent.getBytes());
    // Inline-encoded shape, kohsuke's read() falls back to this if download_url is null.
    String inline =
        "{\"name\":\""
            + filename
            + "\",\"path\":\".titan/pipelines/"
            + filename
            + "\",\"sha\":\"sha-"
            + filename
            + "\",\"size\":"
            + yamlContent.length()
            + ",\"type\":\"file\",\"encoding\":\"base64\",\"content\":\""
            + b64
            + "\"}";
    wiremock.stubFor(
        get(urlPathMatching(
                "/repos/"
                    + owner
                    + "/"
                    + repo
                    + "/contents/\\.titan/pipelines/"
                    + java.util.regex.Pattern.quote(filename)))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(inline)));
    // Also serve the same path with the raw media type — kohsuke's read() sends Accept:
    // application/vnd.github.raw, and some routes use that to fetch the raw blob bytes.
    wiremock.stubFor(
        get(urlPathMatching(
                "/repos/"
                    + owner
                    + "/"
                    + repo
                    + "/contents/\\.titan/pipelines/"
                    + java.util.regex.Pattern.quote(filename)))
            .withHeader(
                "Accept", com.github.tomakehurst.wiremock.client.WireMock.matching(".*raw.*"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/vnd.github.raw")
                    .withBody(yamlContent)));
  }

  /** Minimal valid Titan pipeline YAML — one stage, one step. */
  private static String simplePipelineYaml(String name) {
    return "agent: any\n"
        + "stages:\n"
        + "  - stage: build\n"
        + "    steps:\n"
        + "      - sh: echo "
        + name
        + "\n";
  }

  private static CredentialKeyProvider fixedKeyProvider() {
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
}
