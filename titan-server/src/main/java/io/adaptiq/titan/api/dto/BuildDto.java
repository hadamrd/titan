package io.adaptiq.titan.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.adaptiq.titan.build.Build;
import io.adaptiq.titan.store.rows.BuildRow;
import io.adaptiq.titan.store.rows.JobGithubLinkRow;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * API response DTO for a single build. Summarises the build without embedding the full pipeline
 * model JSON or parameters blob — those are large and rarely needed at list-level.
 *
 * <p>{@link TriggerMetaDto} is the typed projection of {@code titan.builds.trigger_meta_json}
 * (issue #589) — populated only on webhook-born builds. Manual / dogfood / replay builds carry
 * {@code null} and the field is stripped by {@code JsonInclude.NON_NULL}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BuildDto(
    long id,
    long jobId,
    int buildNumber,
    String status,
    String triggeredBy,
    String triggerType,
    Instant queuedAt,
    Instant startedAt,
    Instant finishedAt,
    Long durationMs,
    String errorMessage,
    String failureSummary,
    @Nullable TriggerMetaDto triggerMeta,
    /**
     * Human-friendly name set by the {@code setBuildName:} pipeline step (#762). Null when the
     * pipeline never invoked the step; {@code JsonInclude.NON_NULL} strips the field in that case
     * so legacy clients continue to receive the same shape.
     */
    @Nullable String displayName,
    /**
     * Diagnosed root cause of a FAILED build (issue #1105) — one lowercase {@code FailureCause}
     * wire name ({@code test_failure}, {@code compile_error}, {@code oom}, {@code timeout}, {@code
     * network}, {@code rate_limit}, {@code unknown}). Null/stripped on success or before the async
     * classifier runs; the UI renders a colored cause badge when present.
     */
    @Nullable String failureCause,
    /**
     * The matching log snippet that drove {@link #failureCause} (issue #1105), surfaced in the
     * build-detail "why this was classified" tooltip. Null for an {@code unknown} verdict.
     */
    @Nullable String failureCauseDetail,
    /**
     * The parameters the build actually ran with — the typed projection of {@code
     * titan.builds.parameters_json} (issue #1266). Populated <em>only</em> on the detail-mapper
     * paths consumed by {@code BuildDetailApi} ({@link #from(Build)} / {@link #from(Build,
     * JobGithubLinkRow)}); the list projection {@link #from(BuildRow)} leaves it {@code null} so
     * list payloads stay byte-identical and the params blob is never shipped at list level.
     *
     * <p>{@code null} (stripped by {@code JsonInclude.NON_NULL}) when the build carried no params,
     * when {@code parameters_json} is blank/an empty object, or when the blob is malformed — a
     * malformed blob degrades to omitted + a WARNING log, never a 500.
     */
    @Nullable Map<String, String> parametersUsed) {

  private static final Logger LOGGER = Logger.getLogger(BuildDto.class.getName());
  private static final ObjectMapper MAPPER = new ObjectMapper();

  /**
   * Structured webhook-trigger metadata (issue #589). Discriminated-union friendly — all fields
   * nullable so the API can surface partial data when a payload omits something (e.g. a force-push
   * with no head commit). Never carries secrets: the receiver strips the webhook body to these
   * three facts before persisting.
   *
   * <p>The trailing three fields are GitHub-App provenance (issue #892), populated only on
   * App-triggered builds whose owning Job still has a resolvable GitHub linkage:
   *
   * <ul>
   *   <li>{@code provenance} — {@code "github-app"} when the build was kicked off by the GitHub
   *       integration, {@code null} for manual / scheduled / generic-webhook builds.
   *   <li>{@code repoFullName} — {@code "{owner}/{name}"} of the linked repo.
   *   <li>{@code commitUrl} — deep link {@code https://github.com/{owner}/{name}/commit/{sha}}.
   * </ul>
   *
   * All three are {@code null} (and stripped by {@code JsonInclude.NON_NULL}) for non-App builds
   * and for App builds whose Job linkage was deleted since — so existing clients deserialize
   * unchanged.
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record TriggerMetaDto(
      @Nullable String branch,
      @Nullable String commitSha,
      @Nullable String actor,
      @Nullable String provenance,
      @Nullable String repoFullName,
      @Nullable String commitUrl) {}

  /** Map from a storage row. */
  public static BuildDto from(BuildRow row) {
    return new BuildDto(
        row.id,
        row.jobId,
        row.buildNumber,
        row.status,
        row.triggeredBy,
        row.triggerType,
        row.queuedAt,
        row.startedAt,
        row.finishedAt,
        row.durationMs,
        row.errorMessage,
        row.failureSummary,
        parseTriggerMeta(row.triggerMetaJson),
        row.displayName,
        row.failureCause,
        row.failureCauseDetail,
        // List projection (#1266): never ship the params blob at list level — stays byte-identical.
        null);
  }

  /**
   * Map from the domain record. Used by the HTTP layer when it talks to {@link
   * io.adaptiq.titan.build.BuildService}.
   */
  public static BuildDto from(Build build) {
    return new BuildDto(
        build.id(),
        build.jobId(),
        build.buildNumber(),
        build.status(),
        build.triggeredBy(),
        build.triggerType(),
        build.queuedAt(),
        build.startedAt(),
        build.finishedAt(),
        build.durationMs(),
        build.errorMessage(),
        build.failureSummary(),
        parseTriggerMeta(build.triggerMetaJson()),
        build.displayName(),
        build.failureCause(),
        build.failureCauseDetail(),
        parseParametersUsed(build.parametersJson()));
  }

  /**
   * GitHub-App provenance discriminator. Mirrors {@code
   * GithubStatusReporter.GITHUB_APP_TRIGGER_PREFIX} — the webhook persists discriminated trigger
   * strings ({@code "github-app:push"}, {@code "github-app:pull_request:opened"}, …) so provenance
   * is matched on the prefix, never bare equality. Declared here (not imported) because the
   * reporter constant is package-private in {@code scm.github} and the DTO layer must not depend on
   * a CDI/github-api-coupled bean.
   */
  static final String GITHUB_APP_PROVENANCE = "github-app";

  /**
   * {@code true} when {@code triggerType} denotes a GitHub-App-originated build (issue #892). Lets
   * the HTTP layer skip the linkage lookup entirely for manual / scheduled / generic-webhook
   * builds, keeping their detail payload byte-identical to pre-#892.
   */
  public static boolean isGithubAppTrigger(@Nullable String triggerType) {
    return triggerType != null && triggerType.startsWith(GITHUB_APP_PROVENANCE);
  }

  /**
   * Map from the domain record, enriching {@link TriggerMetaDto} with GitHub-App provenance (issue
   * #892) when the build is App-triggered AND the owning Job still resolves to a GitHub linkage.
   *
   * @param linkage the {@code (owner, name)} linkage from {@code JobDao.findGithubLinkage(jobId)},
   *     or {@code null} when the Job was deleted / unlinked since (orphaned build) — in which case
   *     provenance is suppressed and the plain meta is returned, never a 500.
   */
  public static BuildDto from(Build build, @Nullable JobGithubLinkRow linkage) {
    TriggerMetaDto meta =
        enrichWithGithubProvenance(
            parseTriggerMeta(build.triggerMetaJson()), build.triggerType(), linkage);
    return new BuildDto(
        build.id(),
        build.jobId(),
        build.buildNumber(),
        build.status(),
        build.triggeredBy(),
        build.triggerType(),
        build.queuedAt(),
        build.startedAt(),
        build.finishedAt(),
        build.durationMs(),
        build.errorMessage(),
        build.failureSummary(),
        meta,
        build.displayName(),
        build.failureCause(),
        build.failureCauseDetail(),
        parseParametersUsed(build.parametersJson()));
  }

  /**
   * Attach GitHub-App provenance to a parsed {@link TriggerMetaDto}. Pure function — no I/O — so
   * the mapper is unit-testable without a DB. Returns {@code base} unchanged for non-App builds and
   * for App builds with a missing / incomplete linkage (sad path). {@code commitUrl} is built only
   * when a {@code commitSha} is present; otherwise it stays {@code null} so the UI suppresses the
   * deep link rather than emit {@code .../commit/null}.
   */
  @Nullable
  static TriggerMetaDto enrichWithGithubProvenance(
      @Nullable TriggerMetaDto base,
      @Nullable String triggerType,
      @Nullable JobGithubLinkRow linkage) {
    if (!isGithubAppTrigger(triggerType)) {
      return base; // manual / scheduled / generic-webhook — no provenance
    }
    if (linkage == null || linkage.owner == null || linkage.name == null) {
      return base; // orphaned linkage — suppress provenance, never 500
    }
    String repoFullName = linkage.owner + "/" + linkage.name;
    String branch = base == null ? null : base.branch();
    String commitSha = base == null ? null : base.commitSha();
    String actor = base == null ? null : base.actor();
    String commitUrl =
        (commitSha == null || commitSha.isEmpty())
            ? null
            : "https://github.com/" + repoFullName + "/commit/" + commitSha;
    return new TriggerMetaDto(
        branch, commitSha, actor, GITHUB_APP_PROVENANCE, repoFullName, commitUrl);
  }

  /**
   * Parse the persisted JSON blob into a typed {@link TriggerMetaDto}. A malformed blob is treated
   * as absent (logged at FINE) — we never want a stale row to 500 the build-detail endpoint.
   */
  @Nullable
  static TriggerMetaDto parseTriggerMeta(@Nullable String json) {
    if (json == null || json.isBlank()) {
      return null;
    }
    try {
      JsonNode root = MAPPER.readTree(json);
      String branch = textOrNull(root, "branch");
      String sha = textOrNull(root, "commitSha");
      String actor = textOrNull(root, "actor");
      if (branch == null && sha == null && actor == null) {
        return null;
      }
      return new TriggerMetaDto(branch, sha, actor, null, null, null);
    } catch (Exception e) {
      LOGGER.log(Level.FINE, "[titan-api] trigger_meta_json parse failed; treating as absent", e);
      return null;
    }
  }

  /**
   * Parse the persisted {@code parameters_json} blob into a typed {@code key = value} map (issue
   * #1266). Mirrors {@link #parseTriggerMeta}'s contract: a {@code null} / blank / empty-object /
   * non-object / malformed blob is treated as <em>absent</em> — returns {@code null} so {@code
   * JsonInclude.NON_NULL} strips the field and existing clients deserialize unchanged. A malformed
   * blob is logged at WARNING (not FINE — a corrupt params row on a detail view is operator-worth
   * noting) and never 500s the endpoint.
   *
   * <p>Values are coerced to their string form via {@code asText}; the persisted shape is a flat
   * {@code String -> String} object (see {@code WebhookPayloadParams.toParametersJson}). Insertion
   * order is preserved ({@link LinkedHashMap}) so the UI lists params stably.
   */
  @Nullable
  static Map<String, String> parseParametersUsed(@Nullable String json) {
    if (json == null || json.isBlank()) {
      return null;
    }
    try {
      JsonNode root = MAPPER.readTree(json);
      if (root == null || !root.isObject()) {
        LOGGER.log(
            Level.WARNING, "[titan-api] parameters_json is not a JSON object; treating as absent");
        return null;
      }
      Map<String, String> out = new LinkedHashMap<>();
      root.fields()
          .forEachRemaining(
              e -> {
                JsonNode v = e.getValue();
                if (!v.isNull()) {
                  out.put(e.getKey(), v.asText());
                }
              });
      return out.isEmpty() ? null : out;
    } catch (Exception e) {
      LOGGER.log(Level.WARNING, "[titan-api] parameters_json parse failed; treating as absent", e);
      return null;
    }
  }

  @Nullable
  private static String textOrNull(JsonNode root, String field) {
    JsonNode n = root.path(field);
    if (n.isMissingNode() || n.isNull()) {
      return null;
    }
    String s = n.asText("");
    return s.isEmpty() ? null : s;
  }
}
