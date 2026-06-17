package io.adaptiq.titan.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.JobRow;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for {@link WebhookTriggerMatcher}. Pairs with {@link GithubAppWebhookApiTest}'s
 * "locking" dispatch tests — this class isolates the pure trigger-match logic so the rules are
 * pinned in place without dragging the webhook plumbing into every case.
 */
class WebhookTriggerMatcherTest {

  private static final long INSTALL_ID = 4242L;
  private static final long REPO_ID = 9999L;

  private TitanStores stores;
  private WebhookTriggerMatcher matcher;

  @BeforeEach
  void setUp() {
    stores = FakeTitanStores.create();
    matcher = new WebhookTriggerMatcher(stores);
    // FK: github_pipelines_discovered.repo_id → github_repositories.repo_id.
    stores.githubInstallations().insert(INSTALL_ID, "x", "User", "User", null);
    stores.githubRepositories().insert(INSTALL_ID, REPO_ID, "x", "y", "main", false);
  }

  // ── core push semantics ───────────────────────────────────────────────────

  @Test
  void push_unfilteredPushTrigger_matches() {
    JobRow job = seedJob("a.yml", "[{\"type\":\"push\"}]", "main");
    assertTrue(matcher.shouldDispatch(job, WebhookTriggerMatcher.EventType.PUSH, "main"));
    assertTrue(matcher.shouldDispatch(job, WebhookTriggerMatcher.EventType.PUSH, "feature/x"));
  }

  @Test
  void push_branchStringFilter_matchesExact() {
    JobRow job = seedJob("a.yml", "[{\"type\":\"push\",\"branch\":\"main\"}]", "main");
    assertTrue(matcher.shouldDispatch(job, WebhookTriggerMatcher.EventType.PUSH, "main"));
    assertFalse(matcher.shouldDispatch(job, WebhookTriggerMatcher.EventType.PUSH, "feature/x"));
  }

  @Test
  void push_branchesArrayFilter_matchesAnyMember() {
    JobRow job =
        seedJob("a.yml", "[{\"type\":\"push\",\"branches\":[\"main\",\"trunk\"]}]", "main");
    assertTrue(matcher.shouldDispatch(job, WebhookTriggerMatcher.EventType.PUSH, "main"));
    assertTrue(matcher.shouldDispatch(job, WebhookTriggerMatcher.EventType.PUSH, "trunk"));
    assertFalse(matcher.shouldDispatch(job, WebhookTriggerMatcher.EventType.PUSH, "feature/x"));
  }

  @Test
  void push_globBranch_matchesPrefix() {
    JobRow job = seedJob("a.yml", "[{\"type\":\"push\",\"branch\":\"feature/*\"}]", "main");
    assertTrue(matcher.shouldDispatch(job, WebhookTriggerMatcher.EventType.PUSH, "feature/foo"));
    assertTrue(
        matcher.shouldDispatch(job, WebhookTriggerMatcher.EventType.PUSH, "feature/foo/bar"),
        "glob * matches any sequence including /");
    assertFalse(matcher.shouldDispatch(job, WebhookTriggerMatcher.EventType.PUSH, "main"));
  }

  @Test
  void push_globInBranchesArray_matches() {
    JobRow job =
        seedJob("a.yml", "[{\"type\":\"push\",\"branches\":[\"main\",\"release/*\"]}]", "main");
    assertTrue(matcher.shouldDispatch(job, WebhookTriggerMatcher.EventType.PUSH, "main"));
    assertTrue(matcher.shouldDispatch(job, WebhookTriggerMatcher.EventType.PUSH, "release/1.2"));
    assertFalse(matcher.shouldDispatch(job, WebhookTriggerMatcher.EventType.PUSH, "feature/x"));
  }

  // ── pull_request semantics ────────────────────────────────────────────────

  @Test
  void pullRequest_prTrigger_matches() {
    JobRow job = seedJob("a.yml", "[{\"type\":\"pull_request\"}]", "main");
    assertTrue(
        matcher.shouldDispatch(job, WebhookTriggerMatcher.EventType.PULL_REQUEST, "feature/x"));
  }

  @Test
  void pullRequest_pushOnlyTrigger_doesNotMatch() {
    JobRow job = seedJob("a.yml", "[{\"type\":\"push\"}]", "main");
    assertFalse(
        matcher.shouldDispatch(job, WebhookTriggerMatcher.EventType.PULL_REQUEST, "feature/x"));
  }

  @Test
  void push_prOnlyTrigger_doesNotMatch() {
    JobRow job = seedJob("a.yml", "[{\"type\":\"pull_request\"}]", "main");
    assertFalse(matcher.shouldDispatch(job, WebhookTriggerMatcher.EventType.PUSH, "main"));
  }

  // ── manual / schedule never match webhook events ──────────────────────────

  @Test
  void manualOnlyPipeline_skippedOnPushAndPr() {
    JobRow job = seedJob("a.yml", "[{\"type\":\"manual\"}]", "main");
    assertFalse(matcher.shouldDispatch(job, WebhookTriggerMatcher.EventType.PUSH, "main"));
    assertFalse(matcher.shouldDispatch(job, WebhookTriggerMatcher.EventType.PULL_REQUEST, "main"));
  }

  @Test
  void scheduleOnlyPipeline_skippedOnPushAndPr() {
    JobRow job = seedJob("a.yml", "[{\"type\":\"schedule\",\"cron\":\"0 0 * * *\"}]", "main");
    assertFalse(matcher.shouldDispatch(job, WebhookTriggerMatcher.EventType.PUSH, "main"));
    assertFalse(matcher.shouldDispatch(job, WebhookTriggerMatcher.EventType.PULL_REQUEST, "main"));
  }

  // ── empty triggers list ───────────────────────────────────────────────────

  @Test
  void emptyTriggersList_firesOnPush_notOnPr() {
    // Permissive default: missing/empty triggers → fires on push (mirrors GitLab CI /
    // CircleCI). Pull-request fall-through would double-fire alongside same-branch pushes, so
    // PR is excluded from the implicit default.
    JobRow job = seedJob("a.yml", "[]", "main");
    assertTrue(matcher.shouldDispatch(job, WebhookTriggerMatcher.EventType.PUSH, "main"));
    assertFalse(matcher.shouldDispatch(job, WebhookTriggerMatcher.EventType.PULL_REQUEST, "main"));
  }

  @Test
  void noTriggersField_firesOnPush_notOnPr() {
    JobRow job = seedJobWithRawMetadata("a.yml", "{\"name\":\"x\"}", "main");
    assertTrue(matcher.shouldDispatch(job, WebhookTriggerMatcher.EventType.PUSH, "main"));
    assertFalse(matcher.shouldDispatch(job, WebhookTriggerMatcher.EventType.PULL_REQUEST, "main"));
  }

  // ── multiple-trigger mix ──────────────────────────────────────────────────

  @Test
  void mixedPushAndManual_pushFires_prDoesNot() {
    JobRow job =
        seedJob("a.yml", "[{\"type\":\"push\",\"branch\":\"main\"},{\"type\":\"manual\"}]", "main");
    assertTrue(matcher.shouldDispatch(job, WebhookTriggerMatcher.EventType.PUSH, "main"));
    assertFalse(matcher.shouldDispatch(job, WebhookTriggerMatcher.EventType.PULL_REQUEST, "main"));
  }

  @Test
  void mixedPushAndPr_bothFireForRespectiveEvents() {
    JobRow job = seedJob("a.yml", "[{\"type\":\"push\"},{\"type\":\"pull_request\"}]", "main");
    assertTrue(matcher.shouldDispatch(job, WebhookTriggerMatcher.EventType.PUSH, "main"));
    assertTrue(matcher.shouldDispatch(job, WebhookTriggerMatcher.EventType.PULL_REQUEST, "main"));
  }

  // ── back-compat: no discovered row → dispatch (legacy) ────────────────────

  @Test
  void noDiscoveredRow_dispatches_forLegacyJobs() {
    // Job has the github linkage but no discovered_pipeline row exists.
    JobRow row = new JobRow();
    row.fullName = "x/y/legacy";
    row.enabled = true;
    row.pipelineScript = "";
    row.configJson = "{\"source\":\"manual\"}";
    row.githubInstallationId = INSTALL_ID;
    row.githubRepoId = REPO_ID;
    long id = stores.jobs().insert(row);
    stores.withTransaction(
        conn -> {
          try (var st =
              conn.prepareStatement(
                  "UPDATE titan.jobs SET github_installation_id = ?, github_repo_id = ? WHERE id = ?")) {
            st.setLong(1, INSTALL_ID);
            st.setLong(2, REPO_ID);
            st.setLong(3, id);
            st.executeUpdate();
            return null;
          } catch (java.sql.SQLException e) {
            throw new RuntimeException(e);
          }
        });
    JobRow loaded = stores.jobs().findById(id).orElseThrow();

    assertTrue(
        matcher.shouldDispatch(loaded, WebhookTriggerMatcher.EventType.PUSH, "main"),
        "legacy jobs with no discovered row preserve the old dispatch-everything behaviour");
  }

  // ── PR fallback: head ref not yet scanned, default-branch row is used ─────

  @Test
  void pullRequest_headRefNotScanned_fallsBackToAnyRow() {
    // Seed a discovered row keyed to "main" only.
    JobRow job = seedJob("a.yml", "[{\"type\":\"pull_request\"}]", "main");
    // PR head ref is "feature/foo" — no row exists for that branch.
    assertTrue(
        matcher.shouldDispatch(job, WebhookTriggerMatcher.EventType.PULL_REQUEST, "feature/foo"));
  }

  // ── glob primitives ───────────────────────────────────────────────────────

  @Test
  void globMatches_exactNoWildcard() {
    assertTrue(WebhookTriggerMatcher.globMatches("main", "main"));
    assertFalse(WebhookTriggerMatcher.globMatches("main", "main2"));
  }

  @Test
  void globMatches_wildcardSpansSlash() {
    assertTrue(WebhookTriggerMatcher.globMatches("feature/*", "feature/foo/bar"));
  }

  @Test
  void globMatches_specialRegexCharsAreLiteral() {
    // A '.' in a glob must NOT act like a regex metachar.
    assertTrue(WebhookTriggerMatcher.globMatches("release/1.2", "release/1.2"));
    assertFalse(WebhookTriggerMatcher.globMatches("release/1.2", "release/1x2"));
  }

  @Test
  void extractFilename_pullsFromJson() {
    assertEquals(
        "build.yml", WebhookTriggerMatcher.extractFilename("{\"filename\":\"build.yml\"}"));
  }

  @Test
  void extractFilename_nullOrBlankOrMalformed() {
    assertEquals(null, WebhookTriggerMatcher.extractFilename(null));
    assertEquals(null, WebhookTriggerMatcher.extractFilename(""));
    assertEquals(null, WebhookTriggerMatcher.extractFilename("not-json"));
    assertEquals(null, WebhookTriggerMatcher.extractFilename("{\"x\":1}"));
  }

  // ── required-no-default parameter skip (#919) ─────────────────────────────

  @Test
  void requiredParams_allHaveDefaults_returnsEmpty() {
    // Positive: all required have defaults → no missing → caller enqueues.
    JobRow job =
        seedJobWithRawMetadata(
            "a.yml",
            "{\"triggers\":[{\"type\":\"push\"}],"
                + "\"parameters\":[{\"name\":\"ENV\",\"required\":true,\"hasDefault\":true}]}",
            "main");
    List<String> missing = matcher.unsatisfiedRequiredParams(job, "main", Set.of());
    assertTrue(missing.isEmpty(), "required param with default should not be reported missing");
  }

  @Test
  void requiredParams_noRequired_returnsEmpty() {
    JobRow job =
        seedJobWithRawMetadata(
            "a.yml",
            "{\"triggers\":[{\"type\":\"push\"}],"
                + "\"parameters\":[{\"name\":\"ENV\",\"required\":false,\"hasDefault\":false}]}",
            "main");
    assertTrue(matcher.unsatisfiedRequiredParams(job, "main", Set.of()).isEmpty());
  }

  @Test
  void requiredParams_supplied_returnsEmpty() {
    JobRow job =
        seedJobWithRawMetadata(
            "a.yml",
            "{\"triggers\":[{\"type\":\"push\"}],"
                + "\"parameters\":[{\"name\":\"ENV\",\"required\":true,\"hasDefault\":false}]}",
            "main");
    assertTrue(
        matcher.unsatisfiedRequiredParams(job, "main", Set.of("ENV")).isEmpty(),
        "supplied required param must not appear as missing");
  }

  @Test
  void requiredParams_requiredNoDefaultNotSupplied_returnsName() {
    // Negative: required, no default, not supplied → reported.
    JobRow job =
        seedJobWithRawMetadata(
            "a.yml",
            "{\"triggers\":[{\"type\":\"push\"}],"
                + "\"parameters\":[{\"name\":\"VERSION\",\"required\":true,\"hasDefault\":false}]}",
            "main");
    List<String> missing = matcher.unsatisfiedRequiredParams(job, "main", Set.of());
    assertEquals(List.of("VERSION"), missing);
  }

  @Test
  void requiredParams_mixedDefaultedAndMissing_reportsOnlyMissing() {
    // Adversarial mix: ENV has a default, VERSION is required-no-default and unsupplied,
    // OPTIONAL is non-required. Only VERSION must surface.
    JobRow job =
        seedJobWithRawMetadata(
            "a.yml",
            "{\"triggers\":[{\"type\":\"push\"}],\"parameters\":["
                + "{\"name\":\"ENV\",\"required\":true,\"hasDefault\":true},"
                + "{\"name\":\"VERSION\",\"required\":true,\"hasDefault\":false},"
                + "{\"name\":\"OPTIONAL\",\"required\":false,\"hasDefault\":false}"
                + "]}",
            "main");
    List<String> missing = matcher.unsatisfiedRequiredParams(job, "main", Set.of());
    assertEquals(
        List.of("VERSION"),
        missing,
        "only the required-no-default-not-supplied param must be reported");
  }

  @Test
  void requiredParams_noDiscoveredRow_returnsEmpty() {
    // Legacy job with no discovered row → empty schema → preserve dispatch.
    JobRow row = new JobRow();
    row.fullName = "x/y/legacy";
    row.enabled = true;
    row.pipelineScript = "";
    row.configJson = "{\"source\":\"manual\"}";
    row.githubInstallationId = INSTALL_ID;
    row.githubRepoId = REPO_ID;
    long id = stores.jobs().insert(row);
    JobRow loaded = stores.jobs().findById(id).orElseThrow();
    assertTrue(matcher.unsatisfiedRequiredParams(loaded, "main", Set.of()).isEmpty());
  }

  @Test
  void requiredParams_malformedMetadata_returnsEmpty() {
    // Defensive: malformed JSON must not break dispatch — it just falls back to "no schema".
    JobRow job = seedJobWithRawMetadata("a.yml", "{not-json", "main");
    assertTrue(matcher.unsatisfiedRequiredParams(job, "main", Set.of()).isEmpty());
  }

  // ── seed helpers ──────────────────────────────────────────────────────────

  @NonNull
  private JobRow seedJob(
      @NonNull String filename, @NonNull String triggersJson, @NonNull String branch) {
    String metadata = "{\"triggers\":" + triggersJson + "}";
    return seedJobWithRawMetadata(filename, metadata, branch);
  }

  @NonNull
  private JobRow seedJobWithRawMetadata(
      @NonNull String filename, @NonNull String parsedMetadataJson, @NonNull String branch) {
    // Insert a discovered row.
    stores
        .githubPipelinesDiscovered()
        .insert(REPO_ID, branch, filename, "sha-" + filename, parsedMetadataJson, null);
    // Insert a job pointing at it.
    JobRow row = new JobRow();
    row.fullName = "x/y/" + filename;
    row.enabled = true;
    row.pipelineScript = "";
    row.configJson = "{\"source\":\"github-app\",\"filename\":\"" + filename + "\"}";
    row.githubInstallationId = INSTALL_ID;
    row.githubRepoId = REPO_ID;
    long id = stores.jobs().insert(row);
    // JobDao#insert doesn't carry the github columns — UPDATE raw, mirroring
    // GithubAppWebhookApiTest#seedJobLinkedToRepo.
    stores.withTransaction(
        conn -> {
          try (var st =
              conn.prepareStatement(
                  "UPDATE titan.jobs SET github_installation_id = ?, github_repo_id = ? WHERE id = ?")) {
            st.setLong(1, INSTALL_ID);
            st.setLong(2, REPO_ID);
            st.setLong(3, id);
            st.executeUpdate();
            return null;
          } catch (java.sql.SQLException e) {
            throw new RuntimeException(e);
          }
        });
    return stores.jobs().findById(id).orElseThrow();
  }

  @SuppressWarnings("unused")
  @Nullable
  private static String never() {
    return null;
  }
}
