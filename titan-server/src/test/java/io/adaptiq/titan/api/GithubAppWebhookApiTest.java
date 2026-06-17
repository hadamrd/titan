package io.adaptiq.titan.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.scm.github.GithubApiException;
import io.adaptiq.titan.scm.github.GithubAppService;
import io.adaptiq.titan.scm.github.GithubRepoScanner;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.JobRow;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link GithubAppWebhookApi} — covers acceptance criteria from issue #834:
 *
 * <ul>
 *   <li>Valid signature + push to a known repo → 202 + build enqueued.
 *   <li>Invalid signature → 401, no enqueue, body not parsed.
 *   <li>Replay (same X-GitHub-Delivery within 10 min) → 204, no second enqueue.
 *   <li>Unknown installation → 404 + structured problem+json.
 *   <li>Suspended installation → 410 Gone.
 *   <li>Adversarial: push with installation.id not registered → 404 fast-fail.
 * </ul>
 *
 * <p>Strategy mirrors {@code GithubWebhookApiTest}: real H2-backed {@link TitanStores} via {@link
 * FakeTitanStores}, the SUT constructed with a {@code Supplier<Optional<String>>} test seam so we
 * never go anywhere near {@link io.adaptiq.titan.scm.github.GithubAppService} or its encryption
 * dependencies.
 */
class GithubAppWebhookApiTest {

  private static final String SECRET = "test-app-webhook-secret";
  private static final long INSTALL_ID = 4242L;
  private static final long REPO_ID = 9999L;

  private TitanStores stores;
  private GithubAppWebhookApi api;
  private RecordingScanner scanner;

  @BeforeEach
  void setUp() {
    stores = FakeTitanStores.create();
    scanner = new RecordingScanner(stores);
    api = new GithubAppWebhookApi(stores, () -> Optional.of(SECRET), scanner);
  }

  // ── 1. valid signature + push to known repo → 202 + enqueue ───────────────

  @Test
  void validPush_knownInstallAndRepo_enqueuesBuild() {
    seedInstallation(INSTALL_ID, "kmajdoub", null);
    long jobId = seedJobLinkedToRepo("kmajdoub/repo", INSTALL_ID, REPO_ID);

    byte[] body = pushBody(INSTALL_ID, REPO_ID, "refs/heads/trunk");
    Response resp = api.receive(headers(sig(SECRET, body), "push", "delivery-aaa"), body);

    assertEquals(202, resp.getStatus());
    assertEquals(1, stores.builds().listByJob(jobId).size());
    assertEquals("github-app", stores.builds().listByJob(jobId).get(0).triggerType);
  }

  // ── 2. invalid signature → 401, no enqueue, no parse ──────────────────────

  @Test
  void invalidSignature_returns401_noEnqueue() {
    seedInstallation(INSTALL_ID, "kmajdoub", null);
    long jobId = seedJobLinkedToRepo("kmajdoub/repo", INSTALL_ID, REPO_ID);

    byte[] body = pushBody(INSTALL_ID, REPO_ID, "refs/heads/trunk");
    Response resp = api.receive(headers(sig("wrong-secret", body), "push", "delivery-bbb"), body);

    assertEquals(401, resp.getStatus());
    assertTrue(stores.builds().listByJob(jobId).isEmpty());
  }

  // ── 3. replay same delivery id → 204 idempotent skip ──────────────────────

  @Test
  void replay_sameDeliveryId_returns204_noSecondEnqueue() {
    seedInstallation(INSTALL_ID, "kmajdoub", null);
    long jobId = seedJobLinkedToRepo("kmajdoub/repo", INSTALL_ID, REPO_ID);

    byte[] body = pushBody(INSTALL_ID, REPO_ID, "refs/heads/trunk");
    String signature = sig(SECRET, body);
    String deliveryId = "delivery-ccc";

    Response first = api.receive(headers(signature, "push", deliveryId), body);
    Response second = api.receive(headers(signature, "push", deliveryId), body);

    assertEquals(202, first.getStatus());
    assertEquals(204, second.getStatus());
    assertEquals(1, stores.builds().listByJob(jobId).size(), "second delivery must not re-enqueue");
  }

  // ── 4. unknown installation → 404 problem+json ────────────────────────────

  @Test
  void unknownInstallation_returns404_problemJson_noWrites() {
    long jobId = seedJobLinkedToRepo("kmajdoub/repo", INSTALL_ID, REPO_ID);
    // NOTE: NO installation seeded — INSTALL_ID is unknown.

    byte[] body = pushBody(INSTALL_ID, REPO_ID, "refs/heads/trunk");
    Response resp = api.receive(headers(sig(SECRET, body), "push", "delivery-ddd"), body);

    assertEquals(404, resp.getStatus());
    assertNotNull(resp.getMediaType());
    assertEquals("application/problem+json", resp.getMediaType().toString());
    assertTrue(stores.builds().listByJob(jobId).isEmpty());
  }

  // ── 5. suspended installation → 410 Gone ──────────────────────────────────

  @Test
  void suspendedInstallation_returns410_noEnqueue() {
    seedInstallation(INSTALL_ID, "kmajdoub", Instant.parse("2026-01-01T00:00:00Z"));
    long jobId = seedJobLinkedToRepo("kmajdoub/repo", INSTALL_ID, REPO_ID);

    byte[] body = pushBody(INSTALL_ID, REPO_ID, "refs/heads/trunk");
    Response resp = api.receive(headers(sig(SECRET, body), "push", "delivery-eee"), body);

    assertEquals(410, resp.getStatus());
    assertTrue(stores.builds().listByJob(jobId).isEmpty());
  }

  // ── 6. missing signature → 401 ────────────────────────────────────────────

  @Test
  void missingSignature_returns401() {
    byte[] body = pushBody(INSTALL_ID, REPO_ID, "refs/heads/trunk");
    Response resp = api.receive(headers(null, "push", "delivery-fff"), body);
    assertEquals(401, resp.getStatus());
  }

  // ── 7. unknown event → 204 no work ────────────────────────────────────────

  @Test
  void unknownEvent_returns204() {
    seedInstallation(INSTALL_ID, "kmajdoub", null);
    byte[] body = pushBody(INSTALL_ID, REPO_ID, "refs/heads/trunk");
    Response resp = api.receive(headers(sig(SECRET, body), "watch", "delivery-ggg"), body);
    assertEquals(204, resp.getStatus());
  }

  // ── 8. push with no matching job → 204 (authenticated, no work) ───────────

  @Test
  void pushKnownInstallButNoJobLink_returns204_noEnqueue() {
    seedInstallation(INSTALL_ID, "kmajdoub", null);
    // Insert a job with NO github_repo_id link.
    JobRow row = new JobRow();
    row.fullName = "kmajdoub/orphan";
    row.enabled = true;
    row.pipelineScript = "";
    row.configJson = "{}";
    long jobId = stores.jobs().insert(row);

    byte[] body = pushBody(INSTALL_ID, REPO_ID, "refs/heads/trunk");
    Response resp = api.receive(headers(sig(SECRET, body), "push", "delivery-hhh"), body);

    assertEquals(204, resp.getStatus());
    assertTrue(stores.builds().listByJob(jobId).isEmpty());
  }

  // ── 9. installation registered as suspended → unsuspend webhook flips it ──

  @Test
  void installationUnsuspendEvent_clearsSuspendedAt() {
    seedInstallation(INSTALL_ID, "kmajdoub", Instant.parse("2026-01-01T00:00:00Z"));
    String json =
        "{\"action\":\"unsuspend\",\"installation\":{\"id\":"
            + INSTALL_ID
            + ",\"account\":{\"login\":\"kmajdoub\",\"type\":\"User\"},\"target_type\":\"User\"}}";
    byte[] body = json.getBytes(StandardCharsets.UTF_8);
    Response resp = api.receive(headers(sig(SECRET, body), "installation", "delivery-iii"), body);
    assertEquals(202, resp.getStatus());
    assertEquals(
        null,
        stores.githubInstallations().findByInstallId(INSTALL_ID).orElseThrow().suspendedAt,
        "suspended_at should be cleared on unsuspend");
  }

  // ── 10. installation_repositories added → row inserted ────────────────────

  @Test
  void installationRepositoriesAdded_insertsRow() {
    seedInstallation(INSTALL_ID, "kmajdoub", null);
    String json =
        "{\"action\":\"added\",\"installation\":{\"id\":"
            + INSTALL_ID
            + "},\"repositories_added\":[{\"id\":7777,\"full_name\":\"kmajdoub/new\",\"private\":false}]}";
    byte[] body = json.getBytes(StandardCharsets.UTF_8);
    Response resp =
        api.receive(headers(sig(SECRET, body), "installation_repositories", "delivery-jjj"), body);
    assertEquals(202, resp.getStatus());
    assertEquals(1, stores.githubRepositories().listByInstall(INSTALL_ID).size());
  }

  // ── 11. no app registered → 401 instead of 500 ────────────────────────────

  @Test
  void noAppRegistered_returns401() {
    GithubAppWebhookApi noSecretApi = new GithubAppWebhookApi(stores, Optional::empty);
    byte[] body = pushBody(INSTALL_ID, REPO_ID, "refs/heads/trunk");
    Response resp = noSecretApi.receive(headers("sha256=deadbeef", "push", "delivery-kkk"), body);
    assertEquals(401, resp.getStatus());
  }

  // ── 12. push invokes event-driven re-parse (issue #886) ───────────────────

  @Test
  void validPush_invokesScannerForExactlyThatRepo() {
    seedInstallation(INSTALL_ID, "kmajdoub", null);
    seedJobLinkedToRepo("kmajdoub/repo", INSTALL_ID, REPO_ID);

    byte[] body = pushBody(INSTALL_ID, REPO_ID, "refs/heads/trunk");
    Response resp = api.receive(headers(sig(SECRET, body), "push", "delivery-scan-1"), body);

    assertEquals(202, resp.getStatus());
    assertEquals(1, scanner.calls.size(), "scanner must be invoked exactly once on push");
    assertEquals(INSTALL_ID, scanner.calls.get(0).installId);
    assertEquals(REPO_ID, scanner.calls.get(0).repoId);
    assertEquals("trunk", scanner.calls.get(0).branch, "scanner must receive the push branch");
  }

  // ── 12b. branch-aware re-parse (issue #887): feature branch ───────────────

  @Test
  void validPush_onFeatureBranch_invokesScannerWithThatBranch() {
    seedInstallation(INSTALL_ID, "kmajdoub", null);
    seedJobLinkedToRepo("kmajdoub/repo", INSTALL_ID, REPO_ID);

    byte[] body = pushBody(INSTALL_ID, REPO_ID, "refs/heads/feature/some-thing");
    Response resp = api.receive(headers(sig(SECRET, body), "push", "delivery-887-1"), body);

    assertEquals(202, resp.getStatus());
    assertEquals(1, scanner.calls.size());
    assertEquals(
        "feature/some-thing",
        scanner.calls.get(0).branch,
        "scanner must see the push event's exact branch — that's what #887 is about");
  }

  // ── 13. adversarial: scanner failure MUST NOT regress build dispatch ──────

  @Test
  void pushWithFailingScanner_stillEnqueuesBuild() {
    seedInstallation(INSTALL_ID, "kmajdoub", null);
    long jobId = seedJobLinkedToRepo("kmajdoub/repo", INSTALL_ID, REPO_ID);
    scanner.throwOnNext = new GithubApiException("simulated GitHub outage", 502, null);

    byte[] body = pushBody(INSTALL_ID, REPO_ID, "refs/heads/trunk");
    Response resp = api.receive(headers(sig(SECRET, body), "push", "delivery-scan-2"), body);

    assertEquals(202, resp.getStatus(), "webhook must succeed even if scanner throws");
    assertEquals(
        1,
        stores.builds().listByJob(jobId).size(),
        "build dispatch must NOT regress when discovery scanner fails");
  }

  // ── 14. tag push (no branch) skips scanner — nothing to re-evaluate ───────

  @Test
  void pushTag_doesNotInvokeScanner() {
    seedInstallation(INSTALL_ID, "kmajdoub", null);
    seedJobLinkedToRepo("kmajdoub/repo", INSTALL_ID, REPO_ID);

    byte[] body = pushBody(INSTALL_ID, REPO_ID, "refs/tags/v1.0.0");
    Response resp = api.receive(headers(sig(SECRET, body), "push", "delivery-scan-3"), body);

    assertEquals(204, resp.getStatus());
    assertTrue(scanner.calls.isEmpty(), "tag pushes are not actionable for discovery");
  }

  // ── 15. unknown install → 404 before scanner is even consulted ────────────

  @Test
  void unknownInstall_doesNotInvokeScanner() {
    byte[] body = pushBody(INSTALL_ID, REPO_ID, "refs/heads/trunk");
    Response resp = api.receive(headers(sig(SECRET, body), "push", "delivery-scan-4"), body);

    assertEquals(404, resp.getStatus());
    assertTrue(scanner.calls.isEmpty(), "must not waste a GitHub round-trip on an unknown install");
  }

  // ── 16. design 66: auto-enabled discovery → push dispatches w/o manual enable ─

  @Test
  void autoEnabledByScanner_nextPushEnqueuesWithoutManualEnable() {
    // Simulate the exact sequence design 66 mandates: the scanner upserts a job row on first scan,
    // and the NEXT push event finds it via findByGithubRepo and dispatches. No manual Enable API
    // call is made anywhere in this flow.
    seedInstallation(INSTALL_ID, "kmajdoub", null);

    // The scanner-driven path: a fresh job row with both github linkage columns set, no API hit.
    JobRow row = new JobRow();
    row.fullName = "kmajdoub/repo/build";
    row.displayName = "build";
    row.pipelineScript = "agent: any\nstages:\n  - stage: x\n    steps:\n      - sh: echo ok\n";
    row.configJson = "{\"source\":\"github-app\",\"filename\":\"build.yml\"}";
    row.enabled = true;
    row.githubInstallationId = INSTALL_ID;
    row.githubRepoId = REPO_ID;
    long jobId = stores.jobs().insert(row);

    byte[] body = pushBody(INSTALL_ID, REPO_ID, "refs/heads/trunk");
    Response resp = api.receive(headers(sig(SECRET, body), "push", "delivery-design66"), body);

    assertEquals(202, resp.getStatus(), "auto-enabled job must dispatch on push w/o manual enable");
    assertEquals(1, stores.builds().listByJob(jobId).size());
  }

  // ── LOCKING test: per-pipeline trigger match (the live-rig bug) ───────────

  /**
   * Mirrors the live-rig regression from titan.test.example.com: a repo with 8 pipelines received
   * one push to main and enqueued all 8 builds (2 of which failed because their YAML said "manual
   * only" or "PR only"). After the fix, only the pipelines whose triggers actually accept push+main
   * may dispatch.
   */
  @Test
  void push_dispatchesOnlyPipelinesWhoseTriggersMatch() {
    seedInstallation(INSTALL_ID, "kmajdoub", null);
    // 8 pipelines, 3 should dispatch on a push to main:
    long jobPushMain = seedDiscoveredJob("build.yml", "[{\"type\":\"push\"}]", "main");
    long jobPushExplicitMain =
        seedDiscoveredJob("ci.yml", "[{\"type\":\"push\",\"branch\":\"main\"}]", "main");
    long jobPushBranchesArr =
        seedDiscoveredJob(
            "release.yml", "[{\"type\":\"push\",\"branches\":[\"main\",\"release/*\"]}]", "main");
    // 5 that should NOT dispatch:
    long jobManual = seedDiscoveredJob("with-approval.yml", "[{\"type\":\"manual\"}]", "main");
    long jobPrOnly = seedDiscoveredJob("pr-check.yml", "[{\"type\":\"pull_request\"}]", "main");
    long jobOtherBranch =
        seedDiscoveredJob("develop.yml", "[{\"type\":\"push\",\"branch\":\"develop\"}]", "main");
    long jobSchedule =
        seedDiscoveredJob(
            "nightly.yml", "[{\"type\":\"schedule\",\"cron\":\"0 0 * * *\"}]", "main");
    long jobEmpty = seedDiscoveredJob("noop.yml", "[]", "main");

    byte[] body = pushBody(INSTALL_ID, REPO_ID, "refs/heads/main");
    Response resp = api.receive(headers(sig(SECRET, body), "push", "delivery-lock"), body);

    assertEquals(202, resp.getStatus());
    assertEquals(1, stores.builds().listByJob(jobPushMain).size());
    assertEquals(1, stores.builds().listByJob(jobPushExplicitMain).size());
    assertEquals(1, stores.builds().listByJob(jobPushBranchesArr).size());
    assertTrue(stores.builds().listByJob(jobManual).isEmpty(), "manual must NOT fire on push");
    assertTrue(stores.builds().listByJob(jobPrOnly).isEmpty(), "PR-only must NOT fire on push");
    assertTrue(
        stores.builds().listByJob(jobOtherBranch).isEmpty(),
        "wrong-branch must NOT fire on push to main");
    assertTrue(
        stores.builds().listByJob(jobSchedule).isEmpty(), "schedule must NOT fire on a webhook");
    // Empty triggers list defaults to "fires on push" (permissive default, like GitLab CI).
    assertEquals(
        1,
        stores.builds().listByJob(jobEmpty).size(),
        "empty triggers must fire on push (default-permissive)");
  }

  @Test
  void pullRequest_dispatchesOnlyPrTriggeredPipelines() {
    seedInstallation(INSTALL_ID, "kmajdoub", null);
    long jobPr = seedDiscoveredJob("a.yml", "[{\"type\":\"pull_request\"}]", "main");
    long jobPush = seedDiscoveredJob("b.yml", "[{\"type\":\"push\"}]", "main");
    long jobMixed =
        seedDiscoveredJob("c.yml", "[{\"type\":\"push\"},{\"type\":\"pull_request\"}]", "main");
    long jobManual = seedDiscoveredJob("d.yml", "[{\"type\":\"manual\"}]", "main");

    byte[] body = pullRequestBody(INSTALL_ID, REPO_ID, "opened", "feature/x");
    Response resp =
        api.receive(headers(sig(SECRET, body), "pull_request", "delivery-pr-lock"), body);

    assertEquals(202, resp.getStatus());
    assertEquals(1, stores.builds().listByJob(jobPr).size());
    assertTrue(stores.builds().listByJob(jobPush).isEmpty(), "push-only must NOT fire on PR");
    assertEquals(1, stores.builds().listByJob(jobMixed).size(), "mixed push+PR fires on PR");
    assertTrue(stores.builds().listByJob(jobManual).isEmpty(), "manual must NOT fire on PR");
  }

  // ── #919: required-no-default param missing → skip, NOT enqueue a doomed build ─

  @Test
  void push_requiredParamWithoutDefault_skipsPipelineNotEnqueue() {
    // Live regression from build #56 on titan.test.example.com — the webhook fan-in enqueued a
    // build that immediately failed parameter bake because the pipeline declared a required
    // parameter without a default that the webhook had no way to supply. The fix: skip with a
    // structured log instead of polluting build history.
    seedInstallation(INSTALL_ID, "kmajdoub", null);
    long jobMissing =
        seedDiscoveredJobWithMetadata(
            "release.yml",
            "{\"triggers\":[{\"type\":\"push\"}],\"parameters\":["
                + "{\"name\":\"VERSION\",\"required\":true,\"hasDefault\":false}]}",
            "main");
    long jobOk =
        seedDiscoveredJobWithMetadata(
            "build.yml",
            "{\"triggers\":[{\"type\":\"push\"}],\"parameters\":["
                + "{\"name\":\"ENV\",\"required\":true,\"hasDefault\":true}]}",
            "main");

    byte[] body = pushBody(INSTALL_ID, REPO_ID, "refs/heads/main");
    Response resp = api.receive(headers(sig(SECRET, body), "push", "delivery-919"), body);

    assertEquals(202, resp.getStatus());
    assertTrue(
        stores.builds().listByJob(jobMissing).isEmpty(),
        "required-no-default missing param: webhook MUST skip enqueueing the doomed build");
    assertEquals(
        1,
        stores.builds().listByJob(jobOk).size(),
        "sibling pipeline with all required-defaulted MUST still dispatch");
  }

  /** Like {@link #seedDiscoveredJob} but takes the full {@code parsed_metadata} blob. */
  private long seedDiscoveredJobWithMetadata(
      @NonNull String filename, @NonNull String metadata, @NonNull String branch) {
    if (stores.githubRepositories().listByInstall(INSTALL_ID).isEmpty()) {
      stores.githubRepositories().insert(INSTALL_ID, REPO_ID, "kmajdoub", "repo", "main", false);
    }
    stores
        .githubPipelinesDiscovered()
        .insert(REPO_ID, branch, filename, "sha-" + filename, metadata, null);
    JobRow row = new JobRow();
    row.fullName = "kmajdoub/repo/" + filename;
    row.enabled = true;
    row.pipelineScript = "";
    row.configJson = "{\"source\":\"github-app\",\"filename\":\"" + filename + "\"}";
    long jobId = stores.jobs().insert(row);
    stores.withTransaction(
        conn -> {
          try (var st =
              conn.prepareStatement(
                  "UPDATE titan.jobs SET github_installation_id = ?, github_repo_id = ? WHERE id = ?")) {
            st.setLong(1, INSTALL_ID);
            st.setLong(2, REPO_ID);
            st.setLong(3, jobId);
            st.executeUpdate();
            return null;
          } catch (java.sql.SQLException e) {
            throw new RuntimeException(e);
          }
        });
    return jobId;
  }

  // ── #938: repository_dispatch.client_payload populates supplied params ────

  @Test
  void repositoryDispatch_clientPayloadPopulatesParametersJson_andSatisfiesRequired() {
    // The headline #938 contract: a pipeline declares required-no-default TAG. The webhook
    // arrives with client_payload.TAG=v1.0. The build MUST enqueue AND the queued row MUST carry
    // TAG=v1.0 in parameters_json — the value, not just the precheck, must propagate end-to-end.
    seedInstallation(INSTALL_ID, "kmajdoub", null);
    long jobId =
        seedDiscoveredJobWithMetadata(
            "deploy.yml",
            "{\"triggers\":[],\"parameters\":["
                + "{\"name\":\"TAG\",\"required\":true,\"hasDefault\":false}]}",
            "main");

    byte[] body = repositoryDispatchBody(INSTALL_ID, REPO_ID, "deploy", "{\"TAG\":\"v1.0\"}");
    Response resp =
        api.receive(headers(sig(SECRET, body), "repository_dispatch", "delivery-rd-1"), body);

    assertEquals(202, resp.getStatus());
    var builds = stores.builds().listByJob(jobId);
    assertEquals(1, builds.size(), "client_payload.TAG must satisfy the required-no-default skip");
    String paramsJson = builds.get(0).parametersJson;
    assertNotNull(paramsJson, "parameters_json must be populated from client_payload");
    assertTrue(
        paramsJson.contains("\"TAG\"") && paramsJson.contains("\"v1.0\""),
        "TAG=v1.0 must propagate end-to-end into builds.parameters_json; got " + paramsJson);
  }

  @Test
  void repositoryDispatch_missingRequiredParam_skipsBuild() {
    // Sad path: client_payload omits TAG → matcher reports missing → no build row inserted.
    seedInstallation(INSTALL_ID, "kmajdoub", null);
    long jobId =
        seedDiscoveredJobWithMetadata(
            "deploy.yml",
            "{\"triggers\":[],\"parameters\":["
                + "{\"name\":\"TAG\",\"required\":true,\"hasDefault\":false}]}",
            "main");

    byte[] body = repositoryDispatchBody(INSTALL_ID, REPO_ID, "deploy", "{\"OTHER\":\"x\"}");
    Response resp =
        api.receive(headers(sig(SECRET, body), "repository_dispatch", "delivery-rd-2"), body);

    assertEquals(202, resp.getStatus());
    assertTrue(
        stores.builds().listByJob(jobId).isEmpty(),
        "repository_dispatch without required TAG must NOT enqueue a doomed build");
  }

  @Test
  void repositoryDispatch_extraKeysIgnored_buildEnqueues() {
    // Adversarial: client_payload carries keys the pipeline does NOT declare. They must be
    // preserved in parameters_json (Titan does not enforce a strict schema at this layer — that's
    // the parameter step's job) but must not cause the dispatch to fail.
    seedInstallation(INSTALL_ID, "kmajdoub", null);
    long jobId =
        seedDiscoveredJobWithMetadata("deploy.yml", "{\"triggers\":[],\"parameters\":[]}", "main");

    byte[] body =
        repositoryDispatchBody(INSTALL_ID, REPO_ID, "deploy", "{\"UNDECLARED\":\"x\",\"COUNT\":7}");
    Response resp =
        api.receive(headers(sig(SECRET, body), "repository_dispatch", "delivery-rd-3"), body);

    assertEquals(202, resp.getStatus());
    assertEquals(1, stores.builds().listByJob(jobId).size());
    String paramsJson = stores.builds().listByJob(jobId).get(0).parametersJson;
    assertNotNull(paramsJson);
    assertTrue(paramsJson.contains("\"UNDECLARED\""));
    assertTrue(paramsJson.contains("\"COUNT\""));
  }

  @Test
  void repositoryDispatch_missingClientPayload_emptyParams_buildEnqueuesIfNoRequired() {
    // Malformed-but-tolerated: client_payload entirely absent. Build still enqueues (no required
    // params on this pipeline) and parameters_json stays null.
    seedInstallation(INSTALL_ID, "kmajdoub", null);
    long jobId =
        seedDiscoveredJobWithMetadata("deploy.yml", "{\"triggers\":[],\"parameters\":[]}", "main");

    byte[] body =
        ("{\"action\":\"deploy\",\"installation\":{\"id\":"
                + INSTALL_ID
                + "},\"repository\":{\"id\":"
                + REPO_ID
                + ",\"full_name\":\"x/y\"}}")
            .getBytes(StandardCharsets.UTF_8);
    Response resp =
        api.receive(headers(sig(SECRET, body), "repository_dispatch", "delivery-rd-4"), body);

    assertEquals(202, resp.getStatus());
    assertEquals(1, stores.builds().listByJob(jobId).size());
    assertEquals(
        null,
        stores.builds().listByJob(jobId).get(0).parametersJson,
        "no client_payload → parameters_json must remain null (matches pre-#938 shape)");
  }

  @NonNull
  private static byte[] repositoryDispatchBody(
      long installId, long repoId, @NonNull String action, @NonNull String clientPayloadJson) {
    return ("{\"action\":\""
            + action
            + "\",\"client_payload\":"
            + clientPayloadJson
            + ",\"installation\":{\"id\":"
            + installId
            + "},\"repository\":{\"id\":"
            + repoId
            + ",\"full_name\":\"x/y\"},\"sender\":{\"login\":\"alice\"}}")
        .getBytes(StandardCharsets.UTF_8);
  }

  @Test
  void push_featureBranch_matchesGlob() {
    seedInstallation(INSTALL_ID, "kmajdoub", null);
    long job =
        seedDiscoveredJob(
            "ci.yml", "[{\"type\":\"push\",\"branch\":\"feature/*\"}]", "feature/foo");

    byte[] body = pushBody(INSTALL_ID, REPO_ID, "refs/heads/feature/foo");
    Response resp = api.receive(headers(sig(SECRET, body), "push", "delivery-glob"), body);

    assertEquals(202, resp.getStatus());
    assertEquals(1, stores.builds().listByJob(job).size());
  }

  /**
   * Seed a discovered-pipeline row + a job linked to it. The job's {@code config_json} carries
   * {@code filename} so {@link WebhookTriggerMatcher#extractFilename} can find it.
   */
  private long seedDiscoveredJob(
      @NonNull String filename, @NonNull String triggersJson, @NonNull String branch) {
    // FK: github_pipelines_discovered.repo_id → github_repositories.repo_id. Only insert once
    // per test (cascade-delete would wipe prior discovered rows).
    if (stores.githubRepositories().listByInstall(INSTALL_ID).isEmpty()) {
      stores.githubRepositories().insert(INSTALL_ID, REPO_ID, "kmajdoub", "repo", "main", false);
    }
    String metadata = "{\"triggers\":" + triggersJson + "}";
    stores
        .githubPipelinesDiscovered()
        .insert(REPO_ID, branch, filename, "sha-" + filename, metadata, null);
    JobRow row = new JobRow();
    row.fullName = "kmajdoub/repo/" + filename;
    row.enabled = true;
    row.pipelineScript = "";
    row.configJson = "{\"source\":\"github-app\",\"filename\":\"" + filename + "\"}";
    long jobId = stores.jobs().insert(row);
    stores.withTransaction(
        conn -> {
          try (var st =
              conn.prepareStatement(
                  "UPDATE titan.jobs SET github_installation_id = ?, github_repo_id = ? WHERE id = ?")) {
            st.setLong(1, INSTALL_ID);
            st.setLong(2, REPO_ID);
            st.setLong(3, jobId);
            st.executeUpdate();
            return null;
          } catch (java.sql.SQLException e) {
            throw new RuntimeException(e);
          }
        });
    return jobId;
  }

  @NonNull
  private static byte[] pullRequestBody(
      long installId, long repoId, @NonNull String action, @NonNull String headRef) {
    return ("{\"action\":\""
            + action
            + "\",\"installation\":{\"id\":"
            + installId
            + "},\"repository\":{\"id\":"
            + repoId
            + ",\"full_name\":\"x/y\"},\"pull_request\":{\"head\":{\"ref\":\""
            + headRef
            + "\",\"sha\":\"deadbeef\"}},\"sender\":{\"login\":\"alice\"}}")
        .getBytes(StandardCharsets.UTF_8);
  }

  // ── HMAC primitive coverage ───────────────────────────────────────────────

  @Test
  void verifyHmac_rejectsMissingPrefix() {
    byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
    assertFalse(GithubAppWebhookApi.verifyHmac(SECRET, body, hex(SECRET, body)));
  }

  @Test
  void verifyHmac_acceptsCorrectSignature() {
    byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
    assertTrue(GithubAppWebhookApi.verifyHmac(SECRET, body, "sha256=" + hex(SECRET, body)));
  }

  @Test
  void verifyHmac_rejectsTamperedBody() {
    byte[] body = "{\"x\":1}".getBytes(StandardCharsets.UTF_8);
    byte[] tampered = "{\"x\":2}".getBytes(StandardCharsets.UTF_8);
    assertFalse(GithubAppWebhookApi.verifyHmac(SECRET, tampered, "sha256=" + hex(SECRET, body)));
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private void seedInstallation(
      long installId, @NonNull String login, java.time.Instant suspendedAt) {
    stores.githubInstallations().insert(installId, login, "User", "User", suspendedAt);
  }

  /** Seed a job and explicitly UPDATE its github_installation_id / github_repo_id columns. */
  private long seedJobLinkedToRepo(@NonNull String fullName, long installId, long repoId) {
    JobRow row = new JobRow();
    row.fullName = fullName;
    row.enabled = true;
    row.pipelineScript = "";
    row.configJson = "{}";
    long jobId = stores.jobs().insert(row);
    // The JobDao insert doesn't carry the github columns; do a raw UPDATE through a
    // transaction-borrowed Connection.
    stores.withTransaction(
        conn -> {
          try (var st =
              conn.prepareStatement(
                  "UPDATE titan.jobs SET github_installation_id = ?, github_repo_id = ? WHERE id = ?")) {
            st.setLong(1, installId);
            st.setLong(2, repoId);
            st.setLong(3, jobId);
            st.executeUpdate();
            return null;
          } catch (java.sql.SQLException e) {
            throw new RuntimeException(e);
          }
        });
    return jobId;
  }

  @NonNull
  private static byte[] pushBody(long installId, long repoId, @NonNull String ref) {
    return ("{\"ref\":\""
            + ref
            + "\",\"after\":\"deadbeefcafe1234\",\"installation\":{\"id\":"
            + installId
            + "},\"repository\":{\"id\":"
            + repoId
            + ",\"full_name\":\"x/y\"},\"head_commit\":{\"id\":\"abc1234567\"},\"pusher\":{\"name\":\"alice\"}}")
        .getBytes(StandardCharsets.UTF_8);
  }

  @NonNull
  private static String sig(@NonNull String secret, @NonNull byte[] body) {
    return "sha256=" + hex(secret, body);
  }

  @NonNull
  private static String hex(@NonNull String secret, @NonNull byte[] body) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      byte[] digest = mac.doFinal(body);
      StringBuilder sb = new StringBuilder(digest.length * 2);
      for (byte b : digest) {
        sb.append(String.format("%02x", b));
      }
      return sb.toString();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @NonNull
  private static HttpHeaders headers(String signature, String event, String deliveryId) {
    Map<String, String> map = new HashMap<>();
    if (signature != null) {
      map.put("X-Hub-Signature-256", signature);
    }
    if (event != null) {
      map.put("X-GitHub-Event", event);
    }
    if (deliveryId != null) {
      map.put("X-GitHub-Delivery", deliveryId);
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

  /**
   * Test stub for {@link GithubRepoScanner}: records invocations + optionally throws. Subclasses
   * the real class with {@code null} appService — safe because every code path we exercise is
   * overridden here and never delegates to the parent.
   */
  static final class RecordingScanner extends GithubRepoScanner {

    final List<Call> calls = new java.util.ArrayList<>();
    RuntimeException throwOnNext;

    @SuppressWarnings("NullAway")
    RecordingScanner(@NonNull TitanStores stores) {
      // Parent constructor declares @NonNull for appService; that's a static-analysis hint, not a
      // runtime guard. Tests never invoke any parent method that dereferences appService, so the
      // null reference is unreachable in this stub.
      super(stores, fakeAppService());
    }

    @SuppressWarnings({"NullAway", "ConstantConditions"})
    private static GithubAppService fakeAppService() {
      return null;
    }

    @Override
    public int scanSingleRepo(long installId, long repoId, @NonNull String branch) {
      calls.add(new Call(installId, repoId, branch));
      if (throwOnNext != null) {
        RuntimeException e = throwOnNext;
        throwOnNext = null;
        throw e;
      }
      return 0;
    }

    @Override
    public int scanSingleRepo(long installId, long repoId) {
      return scanSingleRepo(installId, repoId, "main");
    }

    record Call(long installId, long repoId, String branch) {}
  }
}
