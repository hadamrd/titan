package io.adaptiq.titan.store;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.store.rows.JobGithubLinkRow;
import io.adaptiq.titan.store.rows.JobRow;
import io.adaptiq.titan.store.rows.JobWithLastBuildRow;
import java.util.List;
import java.util.Optional;
import org.jdbi.v3.sqlobject.config.RegisterFieldMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindFields;
import org.jdbi.v3.sqlobject.statement.GetGeneratedKeys;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

/**
 * JDBI SqlObject access for {@code titan.jobs}.
 *
 * <p>Row POJOs ({@link JobRow}) are mapped field-by-field: JDBI's field mapper converts snake_case
 * columns to camelCase fields automatically. SQL failures surface as JDBI runtime exceptions;
 * {@code TitanStores} wires the {@code onDemand} proxy so callers never see checked {@code
 * SQLException}.
 */
@RegisterFieldMapper(JobRow.class)
public interface JobDao {

  String COLS =
      "id, full_name, display_name, folder_path, pipeline_script, config_json, "
          + "created_by, created_at, updated_at, enabled, "
          + "github_installation_id, github_repo_id";

  // ──────────────────────────────────────────────
  // Single-row queries
  // ──────────────────────────────────────────────
  @SqlQuery("SELECT " + COLS + " FROM titan.jobs WHERE id = :id")
  @NonNull
  Optional<JobRow> findById(@Bind("id") long id);

  @SqlQuery("SELECT " + COLS + " FROM titan.jobs WHERE full_name = :fullName")
  @NonNull
  Optional<JobRow> findByFullName(@Bind("fullName") @NonNull String fullName);

  // ──────────────────────────────────────────────
  // List queries
  // ──────────────────────────────────────────────
  @SqlQuery("SELECT " + COLS + " FROM titan.jobs ORDER BY full_name")
  @NonNull
  List<JobRow> listAll();

  @SqlQuery("SELECT " + COLS + " FROM titan.jobs WHERE enabled = TRUE ORDER BY full_name")
  @NonNull
  List<JobRow> listEnabled();

  /**
   * Find enabled jobs linked to a given GitHub installation + repo (closes #834 — webhook
   * dispatch). Returns only enabled jobs — a disabled job that matches a push is silently skipped,
   * never enqueued. Multiple matches are allowed (one repo can back many jobs, e.g. a CI + a
   * nightly).
   */
  @SqlQuery(
      "SELECT "
          + COLS
          + " FROM titan.jobs "
          + "WHERE github_installation_id = :installId "
          + "  AND github_repo_id = :repoId "
          + "  AND enabled = TRUE "
          + "ORDER BY full_name")
  @NonNull
  List<JobRow> findByGithubRepo(@Bind("installId") long installId, @Bind("repoId") long repoId);

  /**
   * Resolve a job's GitHub-App linkage tuple ({@code installId}, {@code repoId}, {@code owner},
   * {@code name}) — used by {@link io.adaptiq.titan.scm.github.GithubStatusReporter} (closes #835)
   * to figure out where to POST a commit status when the build's {@code trigger_type} indicates a
   * GitHub-App-originated build.
   *
   * <p>Returns empty if either {@code github_installation_id} is null on the job, or the matching
   * {@code github_repositories} row was deleted (e.g. the repo was removed from the install). The
   * status post is silently skipped in that case — never crashes the build.
   */
  @SqlQuery(
      "SELECT j.github_installation_id AS install_id, j.github_repo_id AS repo_id, "
          + "r.owner AS owner, r.name AS name "
          + "FROM titan.jobs j "
          + "JOIN titan.github_repositories r ON r.repo_id = j.github_repo_id "
          + "WHERE j.id = :jobId "
          + "  AND j.github_installation_id IS NOT NULL "
          + "  AND j.github_repo_id IS NOT NULL")
  @RegisterFieldMapper(JobGithubLinkRow.class)
  @NonNull
  Optional<JobGithubLinkRow> findGithubLinkage(@Bind("jobId") long jobId);

  /**
   * Every job, left-joined to its most-recent build (by {@code build_number}). Backs the
   * fleet-health pill on the {@code /jobs} page (issue #529).
   *
   * <p>Uses a correlated subquery on {@code MAX(build_number)} per job — portable across Postgres
   * and H2 without {@code LATERAL} or window functions. Jobs with no builds yet surface the {@code
   * lastBuild*} columns as {@code NULL} via {@code LEFT JOIN}. Ordered by {@code full_name} for
   * predictable pagination.
   */
  @SqlQuery(
      "SELECT j.id, j.full_name, j.display_name, j.folder_path, j.pipeline_script, j.config_json, "
          + "j.created_by, j.created_at, j.updated_at, j.enabled, "
          + "b.id AS last_build_id, b.build_number AS last_build_number, "
          + "b.status AS last_build_status, b.duration_ms AS last_build_duration_ms, "
          + "b.finished_at AS last_build_finished_at "
          + "FROM titan.jobs j "
          + "LEFT JOIN titan.builds b "
          + "  ON b.job_id = j.id "
          + "  AND b.build_number = ("
          + "    SELECT MAX(b2.build_number) FROM titan.builds b2 WHERE b2.job_id = j.id"
          + "  ) "
          + "ORDER BY j.full_name")
  @RegisterFieldMapper(JobWithLastBuildRow.class)
  @NonNull
  List<JobWithLastBuildRow> listAllWithLastBuild();

  /**
   * Same shape as {@link #listAllWithLastBuild()} but server-side filtered by an ILIKE substring
   * match against {@code full_name} OR {@code display_name} (closes #693 — Cmd+K palette
   * server-side search). The {@code search} parameter is wrapped in {@code %...%} as a JDBI bind —
   * never concatenated into SQL — so a payload like {@code "'; DROP TABLE titan.jobs; --"} returns
   * zero rows, never a 500.
   *
   * <p>{@code display_name} is nullable; we wrap it in {@code COALESCE(..., '')} so the OR clause
   * cannot silently filter out display-name-less rows. Match is case-insensitive — ILIKE is the
   * Postgres native; the H2 unit-test profile uses {@code MODE=PostgreSQL} to honour it.
   *
   * <p>The HTTP layer normalises blank/null search to the unfiltered call ({@link
   * #listAllWithLastBuild()}); this method assumes {@code search} is a non-blank substring token
   * already wrapped with {@code %...%}.
   */
  @SqlQuery(
      "SELECT j.id, j.full_name, j.display_name, j.folder_path, j.pipeline_script, j.config_json, "
          + "j.created_by, j.created_at, j.updated_at, j.enabled, "
          + "b.id AS last_build_id, b.build_number AS last_build_number, "
          + "b.status AS last_build_status, b.duration_ms AS last_build_duration_ms, "
          + "b.finished_at AS last_build_finished_at "
          + "FROM titan.jobs j "
          + "LEFT JOIN titan.builds b "
          + "  ON b.job_id = j.id "
          + "  AND b.build_number = ("
          + "    SELECT MAX(b2.build_number) FROM titan.builds b2 WHERE b2.job_id = j.id"
          + "  ) "
          + "WHERE j.full_name ILIKE :search OR COALESCE(j.display_name, '') ILIKE :search "
          + "ORDER BY j.full_name")
  @RegisterFieldMapper(JobWithLastBuildRow.class)
  @NonNull
  List<JobWithLastBuildRow> searchAllWithLastBuild(@Bind("search") @NonNull String search);

  // ──────────────────────────────────────────────
  // Row locking
  // ──────────────────────────────────────────────
  @SqlQuery("SELECT id FROM titan.jobs WHERE id = :id FOR UPDATE")
  @NonNull
  Optional<Long> selectIdForUpdate(@Bind("id") long id);

  /**
   * Acquire a {@code FOR UPDATE} row lock on a job, on the caller's transactional connection — the
   * multi-controller coalescing mutex of the trigger firing engine (design/50 D4). Returns {@code
   * false} if the job row no longer exists. The lock is held until the caller's transaction commits
   * or rolls back.
   */
  default boolean lockForUpdate(@NonNull java.sql.Connection conn, long id) {
    return TitanStores.onConnection(conn, JobDao.class, dao -> dao.selectIdForUpdate(id))
        .isPresent();
  }

  // ──────────────────────────────────────────────
  // Mutations
  // ──────────────────────────────────────────────
  /**
   * Insert a new job. Returns the generated id. {@code created_at}/{@code updated_at} fall to their
   * column defaults ({@code CURRENT_TIMESTAMP}); {@code config_json} defaults to {@code "{}"} when
   * null.
   */
  @SqlUpdate(
      "INSERT INTO titan.jobs (full_name, display_name, folder_path, pipeline_script, "
          + "config_json, created_by, enabled, github_installation_id, github_repo_id) "
          + "VALUES (:fullName, :displayName, :folderPath, :pipelineScript, "
          + "COALESCE(:configJson, '{}'), :createdBy, :enabled, "
          + ":githubInstallationId, :githubRepoId)")
  @GetGeneratedKeys
  long insert(@BindFields JobRow row);

  /**
   * Update the pipeline script + config_json for a job already linked to a GitHub repo — used by
   * the discovery-driven auto-create path (design 66). Bumps {@code updated_at} so the UI reflects
   * the freshly-scanned YAML.
   */
  @SqlUpdate(
      "UPDATE titan.jobs SET pipeline_script = :pipelineScript, config_json = :configJson, "
          + "display_name = :displayName, updated_at = CURRENT_TIMESTAMP "
          + "WHERE id = :id")
  void updateScriptAndConfig(
      @Bind("id") long id,
      @Bind("pipelineScript") @NonNull String pipelineScript,
      @Bind("configJson") @NonNull String configJson,
      @Bind("displayName") String displayName);

  /**
   * List all jobs linked to a (installId, repoId) regardless of {@code enabled} — used by the
   * discovery-driven prune step (design 66) so we can remove rows whose underlying {@code
   * .titan/pipelines/*.yml} file disappeared from the default branch.
   */
  @SqlQuery(
      "SELECT "
          + COLS
          + " FROM titan.jobs "
          + "WHERE github_installation_id = :installId "
          + "  AND github_repo_id = :repoId "
          + "ORDER BY full_name")
  @NonNull
  List<JobRow> listAllByGithubRepo(@Bind("installId") long installId, @Bind("repoId") long repoId);

  /** Update mutable fields. Sets {@code updated_at} to now. */
  @SqlUpdate(
      "UPDATE titan.jobs SET display_name = :displayName, folder_path = :folderPath, "
          + "pipeline_script = :pipelineScript, config_json = :configJson, enabled = :enabled, "
          + "updated_at = CURRENT_TIMESTAMP WHERE id = :id")
  void update(@BindFields JobRow row);

  @SqlUpdate("DELETE FROM titan.jobs WHERE id = :id")
  void delete(@Bind("id") long id);
}
