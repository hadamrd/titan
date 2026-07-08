package io.adaptiq.titan.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.adaptiq.titan.api.dto.BuildDto;
import io.adaptiq.titan.api.dto.BuildDto.TriggerMetaDto;
import io.adaptiq.titan.build.Build;
import io.adaptiq.titan.store.rows.BuildRow;
import io.adaptiq.titan.store.rows.JobGithubLinkRow;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for the GitHub-App provenance projection added in issue #892 — the {@link
 * BuildDto#from(Build, JobGithubLinkRow)} mapper and its pure helper {@link
 * BuildDto#enrichWithGithubProvenance}.
 *
 * <p>Adversarial-first per the testing manifesto: every happy-path assertion is paired with the sad
 * path it would regress into (missing linkage, manual trigger, missing commit sha, partial
 * linkage).
 */
class BuildDtoMapperTest {

  private static final String SHA = "abc123def456abc123def456abc123def456abcd";
  private static final String META_JSON =
      "{\"branch\":\"main\",\"commitSha\":\"" + SHA + "\",\"actor\":\"octocat\"}";

  /**
   * A build with everything set; {@code triggerType} and {@code triggerMetaJson} are overridable.
   */
  private static Build build(String triggerType, String triggerMetaJson) {
    return new Build(
        4711L, // id
        7L, // jobId
        12, // buildNumber
        "SUCCESS", // status
        null, // parametersJson
        "octocat", // triggeredBy
        triggerType, // triggerType
        null, // deploymentId
        Instant.parse("2026-06-01T10:00:00Z"), // queuedAt
        Instant.parse("2026-06-01T10:00:01Z"), // startedAt
        Instant.parse("2026-06-01T10:00:30Z"), // finishedAt
        29_000L, // durationMs
        null, // errorMessage
        null, // pipelineModelJson
        null, // startedByInstance
        null, // failureSummary
        null, // replayedFromBuildId
        null, // replayedFromNodeId
        triggerMetaJson, // triggerMetaJson
        null, // displayName
        null, // failureCause
        null, // failureCauseDetail
        null); // pipelineScript
  }

  /** A manual build carrying the given raw {@code parameters_json} blob (issue #1266). */
  private static Build buildWithParams(String parametersJson) {
    return new Build(
        4711L, // id
        7L, // jobId
        12, // buildNumber
        "SUCCESS", // status
        parametersJson, // parametersJson
        "octocat", // triggeredBy
        "manual", // triggerType
        null, // deploymentId
        Instant.parse("2026-06-01T10:00:00Z"), // queuedAt
        Instant.parse("2026-06-01T10:00:01Z"), // startedAt
        Instant.parse("2026-06-01T10:00:30Z"), // finishedAt
        29_000L, // durationMs
        null, // errorMessage
        null, // pipelineModelJson
        null, // startedByInstance
        null, // failureSummary
        null, // replayedFromBuildId
        null, // replayedFromNodeId
        null, // triggerMetaJson
        null, // displayName
        null, // failureCause
        null, // failureCauseDetail
        null); // pipelineScript
  }

  /** A manual build carrying the given per-build pipeline-YAML snapshot (issue #61, spec 24). */
  private static Build buildWithScript(String pipelineScript) {
    return new Build(
        4711L, // id
        7L, // jobId
        12, // buildNumber
        "SUCCESS", // status
        null, // parametersJson
        "octocat", // triggeredBy
        "manual", // triggerType
        null, // deploymentId
        Instant.parse("2026-06-01T10:00:00Z"), // queuedAt
        null, // startedAt
        null, // finishedAt
        null, // durationMs
        null, // errorMessage
        null, // pipelineModelJson
        null, // startedByInstance
        null, // failureSummary
        null, // replayedFromBuildId
        null, // replayedFromNodeId
        null, // triggerMetaJson
        null, // displayName
        null, // failureCause
        null, // failureCauseDetail
        pipelineScript); // pipelineScript
  }

  /** A list-level row carrying the given raw {@code parameters_json} blob (issue #1266). */
  private static BuildRow rowWithParams(String parametersJson) {
    BuildRow row = new BuildRow();
    row.id = 4711L;
    row.jobId = 7L;
    row.buildNumber = 12;
    row.status = "SUCCESS";
    row.parametersJson = parametersJson;
    row.triggeredBy = "octocat";
    row.triggerType = "manual";
    row.queuedAt = Instant.parse("2026-06-01T10:00:00Z");
    return row;
  }

  private static JobGithubLinkRow linkage(String owner, String name) {
    JobGithubLinkRow link = new JobGithubLinkRow();
    link.installId = 99L;
    link.repoId = 100L;
    link.owner = owner;
    link.name = name;
    return link;
  }

  // ── happy path ──────────────────────────────────────────────────────────────

  @Test
  void from_githubAppTriggerWithLinkage_populatesProvenanceRepoAndCommitUrl() {
    BuildDto dto = BuildDto.from(build("github-app:push", META_JSON), linkage("hadamrd", "titan"));

    TriggerMetaDto meta = dto.triggerMeta();
    assertNotNull(meta, "App build with linkage must carry trigger meta");
    assertEquals("github-app", meta.provenance());
    assertEquals("hadamrd/titan", meta.repoFullName());
    assertNotNull(meta.commitUrl());
    assertTrue(
        meta.commitUrl().endsWith("/commit/" + SHA),
        "commitUrl should deep-link the commit, was: " + meta.commitUrl());
    assertEquals("https://github.com/hadamrd/titan/commit/" + SHA, meta.commitUrl());
    // base fields preserved
    assertEquals("main", meta.branch());
    assertEquals(SHA, meta.commitSha());
    assertEquals("octocat", meta.actor());
  }

  // ── sad path: linkage absent (orphaned build) ────────────────────────────────

  @Test
  void from_githubAppTriggerButLinkageEmpty_suppressesAllThreeFields() {
    BuildDto dto = BuildDto.from(build("github-app:push", META_JSON), null);

    TriggerMetaDto meta = dto.triggerMeta();
    assertNotNull(meta, "base webhook meta still present even without linkage");
    assertNull(meta.provenance());
    assertNull(meta.repoFullName());
    assertNull(meta.commitUrl());
    // base webhook facts still flow through
    assertEquals("main", meta.branch());
    assertEquals(SHA, meta.commitSha());
  }

  // ── manual trigger never gets provenance even if a linkage is somehow passed ──

  @Test
  void from_manualTrigger_neverPopulatesProvenanceRegardlessOfLinkage() {
    BuildDto dto = BuildDto.from(build("manual", META_JSON), linkage("hadamrd", "titan"));

    TriggerMetaDto meta = dto.triggerMeta();
    assertNotNull(meta);
    assertNull(meta.provenance());
    assertNull(meta.repoFullName());
    assertNull(meta.commitUrl());
  }

  // ── adversarial: App build with linkage but no commitSha → commitUrl null ─────

  @Test
  void enrich_githubAppWithLinkageButNoCommitSha_leavesCommitUrlNullButSetsProvenance() {
    // branch present, commitSha absent (e.g. a force-push event with no head commit recorded)
    String noSha = "{\"branch\":\"main\",\"actor\":\"octocat\"}";
    BuildDto dto = BuildDto.from(build("github-app:push", noSha), linkage("hadamrd", "titan"));

    TriggerMetaDto meta = dto.triggerMeta();
    assertNotNull(meta);
    assertEquals("github-app", meta.provenance());
    assertEquals("hadamrd/titan", meta.repoFullName());
    assertNull(meta.commitUrl(), "no sha → no deep link, never .../commit/null");
  }

  // ── adversarial: partial linkage (owner or name null) → suppress provenance ───

  @Test
  void from_partialLinkageMissingName_suppressesProvenance() {
    BuildDto dto = BuildDto.from(build("github-app:push", META_JSON), linkage("hadamrd", null));

    TriggerMetaDto meta = dto.triggerMeta();
    assertNotNull(meta);
    assertNull(meta.provenance());
    assertNull(meta.repoFullName());
    assertNull(meta.commitUrl());
  }

  // ── adversarial: App build whose triggerMetaJson is entirely absent ───────────

  @Test
  void from_githubAppTriggerWithNullMetaJson_doesNotThrowAndStillSetsProvenance() {
    BuildDto dto = BuildDto.from(build("github-app:push", null), linkage("hadamrd", "titan"));

    TriggerMetaDto meta = dto.triggerMeta();
    assertNotNull(meta, "provenance must materialize a meta even when webhook json was empty");
    assertEquals("github-app", meta.provenance());
    assertEquals("hadamrd/titan", meta.repoFullName());
    assertNull(meta.commitUrl());
    assertNull(meta.commitSha());
  }

  // ── isGithubAppTrigger discriminator ─────────────────────────────────────────

  // ── parametersUsed projection (#1266) ────────────────────────────────────────

  @Test
  void from_detailMapper_projectsParametersUsedFromParametersJson() {
    BuildDto dto = BuildDto.from(buildWithParams("{\"GREETING\":\"world\",\"MODE\":\"dev\"}"));

    assertNotNull(dto.parametersUsed(), "detail mapper must surface params the build ran with");
    assertEquals(Map.of("GREETING", "world", "MODE", "dev"), dto.parametersUsed());
  }

  @Test
  void from_detailMapperWithLinkage_alsoProjectsParametersUsed() {
    // The App-enriched detail path must carry params too — both detail mappers feed BuildDetailApi.
    Build appBuild =
        new Build(
            4711L,
            7L,
            12,
            "SUCCESS",
            "{\"GREETING\":\"world\"}",
            "octocat",
            "github-app:push",
            null,
            Instant.parse("2026-06-01T10:00:00Z"),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            META_JSON,
            null,
            null,
            null,
            null);
    BuildDto dto = BuildDto.from(appBuild, linkage("hadamrd", "titan"));

    assertEquals(Map.of("GREETING", "world"), dto.parametersUsed());
    // provenance still works alongside params
    assertEquals("github-app", dto.triggerMeta().provenance());
  }

  @Test
  void from_listProjection_neverCarriesParametersUsed() {
    // The list mapper (BuildRow → DTO, used by JobsRecentBuildsApi) must stay byte-identical: no
    // params leak to list payloads even when the row carries a fat parameters_json blob.
    BuildDto dto = BuildDto.from(rowWithParams("{\"GREETING\":\"world\",\"MODE\":\"dev\"}"));

    assertNull(dto.parametersUsed(), "params must never leak to the list-level projection");
  }

  @Test
  void from_nullParametersJson_omitsParametersUsed() {
    assertNull(BuildDto.from(buildWithParams(null)).parametersUsed());
  }

  @Test
  void from_blankParametersJson_omitsParametersUsed() {
    assertNull(BuildDto.from(buildWithParams("")).parametersUsed());
    assertNull(BuildDto.from(buildWithParams("   ")).parametersUsed());
  }

  @Test
  void from_emptyObjectParametersJson_omitsParametersUsed() {
    // An empty object is "no params" — omit so the UI hides the section, no empty card.
    assertNull(BuildDto.from(buildWithParams("{}")).parametersUsed());
  }

  @Test
  void from_malformedParametersJson_omitsParametersUsedAndDoesNotThrow() {
    // Adversarial: a corrupt blob must degrade to omitted, never 500 the detail endpoint.
    assertNull(BuildDto.from(buildWithParams("{not json")).parametersUsed());
    // A non-object JSON value (array / scalar) is also "not params" → omitted.
    assertNull(BuildDto.from(buildWithParams("[\"GREETING\",\"world\"]")).parametersUsed());
    assertNull(BuildDto.from(buildWithParams("\"just a string\"")).parametersUsed());
  }

  @Test
  void from_parametersJsonWithNullValue_skipsNullValuedKey() {
    // A key whose value is JSON null is dropped rather than mapped to the string "null".
    BuildDto dto = BuildDto.from(buildWithParams("{\"GREETING\":\"world\",\"MODE\":null}"));
    assertEquals(Map.of("GREETING", "world"), dto.parametersUsed());
  }

  // ── pipelineScript snapshot projection (issue #61, spec 24) ─────────────────

  @Test
  void from_detailMapper_projectsPipelineScriptSnapshot() {
    String yaml = "agent: linux\nstages:\n  - stage: Build\n    steps:\n      - sh: make\n";
    BuildDto dto = BuildDto.from(buildWithScript(yaml));

    assertEquals(
        yaml,
        dto.pipelineScript(),
        "detail mapper must surface the per-build pipeline-YAML snapshot verbatim");
  }

  @Test
  void from_detailMapperWithLinkage_alsoProjectsPipelineScript() {
    String yaml = "stages:\n  - stage: Build\n    steps:\n      - sh: make\n";
    Build appBuild =
        new Build(
            4711L,
            7L,
            12,
            "SUCCESS",
            null,
            "octocat",
            "github-app:push",
            null,
            Instant.parse("2026-06-01T10:00:00Z"),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            META_JSON,
            null,
            null,
            null,
            yaml);
    BuildDto dto = BuildDto.from(appBuild, linkage("hadamrd", "titan"));

    assertEquals(yaml, dto.pipelineScript(), "App-enriched detail path must carry the script too");
  }

  @Test
  void from_listProjection_neverCarriesPipelineScript() {
    // The list mapper (BuildRow → DTO) must never ship the script blob at list level — same
    // byte-identical-list contract as parametersUsed (#1266).
    BuildRow row = rowWithParams(null);
    row.pipelineScript = "stages: []\n";

    assertNull(
        BuildDto.from(row).pipelineScript(),
        "pipelineScript must never leak to the list-level projection");
  }

  @Test
  void from_nullOrBlankPipelineScript_omitsField() {
    // Pre-migration rows / replay builds carry null; a degenerate blank snapshot is also omitted
    // so clients never receive an empty-string script.
    assertNull(BuildDto.from(buildWithScript(null)).pipelineScript());
    assertNull(BuildDto.from(buildWithScript("")).pipelineScript());
    assertNull(BuildDto.from(buildWithScript("   ")).pipelineScript());
  }

  @Test
  void isGithubAppTrigger_matchesPrefixNotBareEquality() {
    assertTrue(BuildDto.isGithubAppTrigger("github-app"));
    assertTrue(BuildDto.isGithubAppTrigger("github-app:push"));
    assertTrue(BuildDto.isGithubAppTrigger("github-app:pull_request:opened"));
    assertFalse(BuildDto.isGithubAppTrigger("manual"));
    assertFalse(BuildDto.isGithubAppTrigger("webhook"));
    assertFalse(BuildDto.isGithubAppTrigger(null));
  }
}
