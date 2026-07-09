package io.adaptiq.titan.store;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.adaptiq.titan.api.dto.BuildsQuery;
import io.adaptiq.titan.db.TitanDataException;
import io.adaptiq.titan.store.rows.ActiveBuildRow;
import io.adaptiq.titan.store.rows.BuildRow;
import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.statement.Query;
import org.jdbi.v3.sqlobject.SqlObject;
import org.jdbi.v3.sqlobject.config.RegisterFieldMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindFields;
import org.jdbi.v3.sqlobject.customizer.BindList;
import org.jdbi.v3.sqlobject.statement.GetGeneratedKeys;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

/**
 * JDBI SqlObject access for {@code titan.builds}.
 *
 * <p>The {@code Connection}-taking overloads of {@link #nextBuildNumber} and {@link #insert} let a
 * caller run those statements inside an externally-managed transaction (see {@code
 * TitanStores.withTransaction}). They are {@code default} methods that re-attach this SqlObject to
 * the supplied connection rather than {@code @Sql*}-annotated methods (JDBI cannot bind a raw
 * {@code Connection} parameter).
 */
@RegisterFieldMapper(BuildRow.class)
public interface BuildDao extends SqlObject {

  String COLS =
      "id, job_id, build_number, status, parameters_json, triggered_by, trigger_type, "
          + "deployment_id, queued_at, started_at, finished_at, duration_ms, "
          + "error_message, pipeline_model_json, started_by_instance, failure_summary, "
          + "replayed_from_build_id, replayed_from_node_id, deadline_at, trigger_meta_json, "
          + "display_name, external_check_run_id, failure_cause, failure_cause_detail, "
          + "pipeline_script";

  // ---------------------------------------------------------------
  //  Single-row queries
  // ---------------------------------------------------------------
  @SqlQuery("SELECT " + COLS + " FROM titan.builds WHERE id = :id")
  @NonNull
  Optional<BuildRow> findById(@Bind("id") long id);

  @SqlQuery(
      "SELECT " + COLS + " FROM titan.builds WHERE job_id = :jobId AND build_number = :buildNumber")
  @NonNull
  Optional<BuildRow> findByJobAndNumber(
      @Bind("jobId") long jobId, @Bind("buildNumber") int buildNumber);

  // ---------------------------------------------------------------
  //  List queries
  // ---------------------------------------------------------------
  @SqlQuery("SELECT " + COLS + " FROM titan.builds WHERE job_id = :jobId ORDER BY queued_at DESC")
  @NonNull
  List<BuildRow> listByJob(@Bind("jobId") long jobId);

  /**
   * Bulk fetch: the most-recent {@code limit} builds per job, across the supplied {@code jobIds},
   * in a single round-trip (issue #650 — kills the N+1 /jobs sparkline introduced by #648).
   * Implemented with {@code ROW_NUMBER() OVER (PARTITION BY job_id ORDER BY queued_at DESC)} so
   * PostgreSQL does one index scan per partition and stops as soon as each job's quota is filled.
   *
   * <p>Result rows are returned ordered by {@code job_id}, then newest-first within each job — the
   * caller groups by {@code jobId}. Unknown ids in the input simply produce no rows (so the
   * caller's result map omits them rather than 404ing). An empty input list is the caller's
   * responsibility: the {@code IN ()} that {@link BindList} would generate is invalid SQL, so
   * {@link io.adaptiq.titan.api.JobsRecentBuildsApi} short-circuits with an empty map before
   * calling.
   */
  @SqlQuery(
      "SELECT "
          + COLS
          + " FROM ("
          + "  SELECT "
          + COLS
          + ", ROW_NUMBER() OVER (PARTITION BY job_id ORDER BY queued_at DESC, id DESC) AS rn "
          + "  FROM titan.builds WHERE job_id IN (<jobIds>)"
          + ") ranked WHERE rn <= :limit "
          + "ORDER BY job_id, rn")
  @NonNull
  List<BuildRow> findRecentBuildsForJobs(
      @BindList("jobIds") @NonNull List<Long> jobIds, @Bind("limit") int limit);

  @SqlQuery("SELECT " + COLS + " FROM titan.builds WHERE status = :status ORDER BY queued_at DESC")
  @NonNull
  List<BuildRow> listByStatus(@Bind("status") @NonNull String status);

  /**
   * Per-job, per-status listing (#1101 concurrency gate). Returns rows in {@code started_at} order
   * — oldest-first when {@code status='RUNNING'} (so {@code cancel_oldest} picks the head), and
   * {@code queued_at}-fallback for rows that have not started yet. Excludes the build whose id is
   * {@code excludeBuildId} so the gate ignores the build that triggered the check.
   */
  @SqlQuery(
      "SELECT "
          + COLS
          + " FROM titan.builds "
          + "WHERE job_id = :jobId AND status = :status AND id <> :excludeBuildId "
          + "ORDER BY COALESCE(started_at, queued_at) ASC, id ASC")
  @NonNull
  List<BuildRow> listByJobAndStatus(
      @Bind("jobId") long jobId,
      @Bind("status") @NonNull String status,
      @Bind("excludeBuildId") long excludeBuildId);

  /**
   * Most-recent terminal build of {@code jobId} whose id is strictly less than {@code beforeId}
   * (#1102 — Slack-on-fail / recovery detection). "Terminal" here is any non-pending, non-running,
   * non-queued status — {@code SUCCESS}, {@code FAILED}, {@code ABORTED}, {@code UNSTABLE}. Used by
   * {@code BuildCloser} at terminal-write time to decide whether the build currently closing is a
   * fail→success transition (a "recovery") or just an ordinary green / red.
   *
   * <p>Returns {@link Optional#empty()} when this is the first finished build for the job (an
   * ordinary fail then is NOT a "first-failure-after-passes" — there have been zero passes).
   *
   * <p>Filters on {@code finished_at IS NOT NULL} (not just {@code status}) so a build whose status
   * column carries a transient terminal label without a {@code finished_at} (degenerate, but
   * possible during a re-tick race) is ignored as in-flight. Ordered by {@code finished_at DESC},
   * tie-broken on {@code id DESC}.
   */
  @SqlQuery(
      "SELECT "
          + COLS
          + " FROM titan.builds "
          + "WHERE job_id = :jobId AND id < :beforeId AND finished_at IS NOT NULL "
          + "AND status IN ('SUCCESS', 'FAILED', 'ABORTED', 'UNSTABLE') "
          + "ORDER BY finished_at DESC, id DESC LIMIT 1")
  @NonNull
  Optional<BuildRow> findPreviousFinishedForJob(
      @Bind("jobId") long jobId, @Bind("beforeId") long beforeId);

  /**
   * Every build whose {@code finished_at} falls on or after {@code since}, newest-finished first
   * (issue #1103 — backs the daily-digest job's last-24h scan).
   *
   * <p>Builds that have not yet finished ({@code finished_at IS NULL}) are excluded — a digest of
   * "what happened overnight" wants only terminal builds, never builds still running at the moment
   * the cron fires.
   */
  @SqlQuery(
      "SELECT "
          + COLS
          + " FROM titan.builds WHERE finished_at IS NOT NULL AND finished_at >= :since "
          + "ORDER BY finished_at DESC")
  @NonNull
  List<BuildRow> findFinishedSince(@Bind("since") @NonNull Instant since);

  // ---------------------------------------------------------------
  //  Global filterable list (#682) — backs GET /api/v1/builds
  // ---------------------------------------------------------------

  /**
   * Global filterable, paginated build list. Backs {@code GET /api/v1/builds} (closes #682).
   *
   * <p>Every clause in {@link BuildsQuery} is optional and combined with AND. An empty query
   * ({@code BuildsQuery#isUnfiltered()}) degenerates to "every build, newest first" — the same
   * shape callers of the legacy unfiltered list expect.
   *
   * <p>Filter semantics:
   *
   * <ul>
   *   <li>{@code status}: {@code status IN (...)} — supplied tokens are validated upstream by the
   *       HTTP controller against {@code BuildsApi.ALLOWED_STATUSES} so we never bind an unknown
   *       value here.
   *   <li>{@code branch}: exact match on {@code trigger_meta_json->>'branch'}. The Postgres JSON
   *       text accessor binds parameters, never concatenates them; the column is a JSON blob added
   *       in V22 so the {@code ->>} operator is the only typed read path.
   *   <li>{@code search}: ILIKE against {@code triggered_by}, {@code failure_summary}, {@code
   *       error_message}, and {@code trigger_meta_json->>'commitSha'}. If the search token parses
   *       as a positive integer, we also accept it as an exact {@code build_number} match (the OR
   *       is wide on purpose so operators don't have to think about which column carries the string
   *       they remember). If the token is a hex string of length 7-40 (a commit-SHA prefix — the
   *       form SREs paste from a CI log or PR), we ALSO accept it as a {@code commitSha LIKE
   *       '<prefix>%'} match so {@code abc1234} surfaces every build at {@code abc1234deadbeef...}
   *       (closes #776). Prefix detection requires 7+ chars to keep false-positive collision risk
   *       negligible (git's own short-sha default). The token is wrapped in {@code %...%} bindings
   *       — never concatenated into SQL — so a payload like {@code "'; DROP TABLE titan.builds;
   *       --"} returns zero rows, never a 500.
   *   <li>{@code since}: {@code queued_at >= :since}.
   * </ul>
   *
   * <p>Pagination: {@code LIMIT :limit OFFSET :offset}. Both are non-negative (enforced by the
   * {@link BuildsQuery} constructor).
   */
  @NonNull
  default List<BuildRow> findAll(@NonNull BuildsQuery q) {
    Handle h = getHandle();
    StringBuilder sql = new StringBuilder("SELECT ").append(COLS).append(" FROM titan.builds");
    List<String> where = new ArrayList<>();
    if (!q.status().isEmpty()) {
      where.add("status IN (<statuses>)");
    }
    if (q.branch() != null && !q.branch().isBlank()) {
      where.add("trigger_meta_json::jsonb->>'branch' = :branch");
    }
    if (q.search() != null && !q.search().isBlank()) {
      // Build the OR clause; bindings are parameterised — no concat of user input.
      // NOTE: trigger_meta_json is VARCHAR (V22 used a portable type so H2 unit tests also
      // accept it), so we cast to jsonb at read time for the typed accessor. The cast is
      // safe because the receiver only ever writes valid JSON or NULL.
      StringBuilder s =
          new StringBuilder(
              "(triggered_by ILIKE :search "
                  + "OR failure_summary ILIKE :search "
                  + "OR error_message ILIKE :search "
                  + "OR (trigger_meta_json IS NOT NULL "
                  + "    AND trigger_meta_json::jsonb->>'commitSha' ILIKE :search)");
      Integer asNumber = tryParseBuildNumber(q.search());
      if (asNumber != null) {
        s.append(" OR build_number = :searchBuildNumber");
      }
      if (isShortCommitShaPrefix(q.search())) {
        s.append(
            " OR (trigger_meta_json IS NOT NULL "
                + "    AND trigger_meta_json::jsonb->>'commitSha' ILIKE :searchShaPrefix)");
      }
      s.append(")");
      where.add(s.toString());
    }
    if (q.since() != null) {
      where.add("queued_at >= :since");
    }
    if (q.triggeredBy() != null && !q.triggeredBy().isBlank()) {
      // Case-insensitive equality on the actor — chip is self-scoped ("Triggered by me",
      // closes #746). Parameterised; never concatenated.
      where.add("LOWER(triggered_by) = LOWER(:triggeredBy)");
    }
    if (q.headSha() != null && !q.headSha().isBlank()) {
      // Exact-match head-SHA lookup (closes #967). Portable LIKE against the JSON text
      // — works on both PostgreSQL (production) and H2 PG-mode (unit tests) without a
      // dialect split. Anchored on the literal token `"commitSha":"<sha>"` so a SHA
      // appearing only as a substring of some other field cannot collide. The caller
      // (BuildsApi) validates the value is exactly 40 hex chars before binding, so
      // there are no LIKE metacharacters in the pattern and no SQL-injection surface.
      // V32_1 adds a PG-only partial expression index on (trigger_meta_json::jsonb)
      // ->>'commitSha' to back this query at production scale; the LIKE here will use
      // it via the planner's expression-rewrite where possible, else fall through to
      // a seq scan on the H2 test path (handful of rows — fine).
      where.add(
          "trigger_meta_json IS NOT NULL " + "AND LOWER(trigger_meta_json) LIKE :headShaPattern");
    }
    if (q.afterTs() != null && q.afterId() != null) {
      // Cursor pagination (#1098). Lexicographic comparison on (queued_at, id) — stable
      // across mid-stream inserts because the cursor encodes the boundary row, not an
      // offset. Equivalent to row-value `(queued_at, id) < (:afterTs, :afterId)` but
      // expanded for H2 / PG portability.
      where.add("(queued_at < :afterTs OR (queued_at = :afterTs AND id < :afterId))");
    }
    if (!where.isEmpty()) {
      sql.append(" WHERE ").append(String.join(" AND ", where));
    }
    sql.append(" ORDER BY queued_at DESC, id DESC LIMIT :limit OFFSET :offset");

    Query query = h.createQuery(sql.toString());
    if (!q.status().isEmpty()) {
      query.bindList("statuses", q.status());
    }
    if (q.branch() != null && !q.branch().isBlank()) {
      query.bind("branch", q.branch());
    }
    if (q.search() != null && !q.search().isBlank()) {
      query.bind("search", "%" + q.search() + "%");
      Integer asNumber = tryParseBuildNumber(q.search());
      if (asNumber != null) {
        query.bind("searchBuildNumber", asNumber);
      }
      if (isShortCommitShaPrefix(q.search())) {
        // Bind the prefix with a trailing % so LIKE only matches at the start of the SHA. The
        // prefix is hex-validated upstream so there are no LIKE metacharacters to escape.
        query.bind("searchShaPrefix", q.search().trim() + "%");
      }
    }
    if (q.since() != null) {
      query.bind("since", q.since());
    }
    if (q.triggeredBy() != null && !q.triggeredBy().isBlank()) {
      query.bind("triggeredBy", q.triggeredBy());
    }
    if (q.headSha() != null && !q.headSha().isBlank()) {
      // Lowercase the validated 40-hex SHA and wrap in the anchored JSON token so the
      // LIKE pattern matches only commitSha values, never collateral substrings.
      query.bind(
          "headShaPattern",
          "%\"commitsha\":\"" + q.headSha().trim().toLowerCase(java.util.Locale.ROOT) + "\"%");
    }
    if (q.afterTs() != null && q.afterId() != null) {
      query.bind("afterTs", q.afterTs());
      query.bind("afterId", q.afterId());
    }
    query.bind("limit", q.limit());
    query.bind("offset", q.offset());
    return query.mapTo(BuildRow.class).list();
  }

  /** Count matching the same filter set as {@link #findAll(BuildsQuery)}. */
  default long countAll(@NonNull BuildsQuery q) {
    Handle h = getHandle();
    StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM titan.builds");
    List<String> where = new ArrayList<>();
    if (!q.status().isEmpty()) {
      where.add("status IN (<statuses>)");
    }
    if (q.branch() != null && !q.branch().isBlank()) {
      where.add("trigger_meta_json::jsonb->>'branch' = :branch");
    }
    if (q.search() != null && !q.search().isBlank()) {
      StringBuilder s =
          new StringBuilder(
              "(triggered_by ILIKE :search "
                  + "OR failure_summary ILIKE :search "
                  + "OR error_message ILIKE :search "
                  + "OR (trigger_meta_json IS NOT NULL "
                  + "    AND trigger_meta_json::jsonb->>'commitSha' ILIKE :search)");
      Integer asNumber = tryParseBuildNumber(q.search());
      if (asNumber != null) {
        s.append(" OR build_number = :searchBuildNumber");
      }
      if (isShortCommitShaPrefix(q.search())) {
        s.append(
            " OR (trigger_meta_json IS NOT NULL "
                + "    AND trigger_meta_json::jsonb->>'commitSha' ILIKE :searchShaPrefix)");
      }
      s.append(")");
      where.add(s.toString());
    }
    if (q.since() != null) {
      where.add("queued_at >= :since");
    }
    if (q.triggeredBy() != null && !q.triggeredBy().isBlank()) {
      where.add("LOWER(triggered_by) = LOWER(:triggeredBy)");
    }
    if (q.headSha() != null && !q.headSha().isBlank()) {
      // Mirrors findAll — see comment there for rationale (portable LIKE pattern).
      where.add(
          "trigger_meta_json IS NOT NULL " + "AND LOWER(trigger_meta_json) LIKE :headShaPattern");
    }
    if (!where.isEmpty()) {
      sql.append(" WHERE ").append(String.join(" AND ", where));
    }
    Query query = h.createQuery(sql.toString());
    if (!q.status().isEmpty()) {
      query.bindList("statuses", q.status());
    }
    if (q.branch() != null && !q.branch().isBlank()) {
      query.bind("branch", q.branch());
    }
    if (q.search() != null && !q.search().isBlank()) {
      query.bind("search", "%" + q.search() + "%");
      Integer asNumber = tryParseBuildNumber(q.search());
      if (asNumber != null) {
        query.bind("searchBuildNumber", asNumber);
      }
      if (isShortCommitShaPrefix(q.search())) {
        query.bind("searchShaPrefix", q.search().trim() + "%");
      }
    }
    if (q.since() != null) {
      query.bind("since", q.since());
    }
    if (q.triggeredBy() != null && !q.triggeredBy().isBlank()) {
      query.bind("triggeredBy", q.triggeredBy());
    }
    if (q.headSha() != null && !q.headSha().isBlank()) {
      query.bind(
          "headShaPattern",
          "%\"commitsha\":\"" + q.headSha().trim().toLowerCase(java.util.Locale.ROOT) + "\"%");
    }
    Long n = query.mapTo(Long.class).one();
    return n == null ? 0L : n;
  }

  /**
   * Parse a search token as a positive {@code build_number}, stripping a leading {@code #}. Returns
   * {@code null} for anything that is not a small positive integer. Cap at {@code 2^31-1} so we
   * never overflow the {@code int} bind type — anything larger cannot be a real build number
   * anyway.
   */
  /**
   * Whether a search token looks like a commit-SHA prefix worth a {@code commitSha LIKE
   * '<prefix>%'} match (closes #776). A token qualifies when it is 7-40 characters of pure
   * hexadecimal — git's own short-sha default starts at 7, and a full SHA-1 is 40. Shorter tokens
   * (6 or less) are rejected to avoid false-positive collisions; longer tokens are rejected because
   * they cannot be a SHA. Case-insensitive (operators paste either form). Leading/trailing
   * whitespace is trimmed before inspection. Returning {@code true} guarantees there are no LIKE
   * metacharacters in the value, so the caller can append a {@code %} suffix without escaping.
   */
  static boolean isShortCommitShaPrefix(@NonNull String raw) {
    String t = raw.trim();
    int n = t.length();
    if (n < 7 || n > 40) {
      return false;
    }
    for (int i = 0; i < n; i++) {
      char c = t.charAt(i);
      boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
      if (!hex) {
        return false;
      }
    }
    return true;
  }

  @Nullable
  static Integer tryParseBuildNumber(@NonNull String raw) {
    String t = raw.trim();
    if (t.startsWith("#")) {
      t = t.substring(1).trim();
    }
    if (t.isEmpty()) {
      return null;
    }
    for (int i = 0; i < t.length(); i++) {
      char c = t.charAt(i);
      if (c < '0' || c > '9') {
        return null;
      }
    }
    try {
      long v = Long.parseLong(t);
      if (v <= 0 || v > Integer.MAX_VALUE) {
        return null;
      }
      return (int) v;
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /**
   * Every in-flight build — {@code titan.builds} rows whose status is non-terminal ({@code QUEUED}
   * or {@code RUNNING}) — joined to its job. Backs the dashboard widget that shows running Titan
   * builds.
   *
   * <p>Ordered RUNNING-before-QUEUED, then oldest first, so the longest-running build sorts to the
   * top.
   */
  @SqlQuery(
      "SELECT b.id, b.build_number, b.status, b.queued_at, b.started_at, "
          + "j.full_name AS job_full_name, j.display_name AS job_display_name "
          + "FROM titan.builds b JOIN titan.jobs j ON j.id = b.job_id "
          + "WHERE b.status IN ('QUEUED', 'RUNNING') "
          + "ORDER BY CASE b.status WHEN 'RUNNING' THEN 0 ELSE 1 END, b.queued_at")
  @RegisterFieldMapper(ActiveBuildRow.class)
  @NonNull
  List<ActiveBuildRow> listActive();

  // ---------------------------------------------------------------
  //  Coalescing (design/50 D5) — is a build already in flight?
  // ---------------------------------------------------------------
  /** Count of in-flight builds ({@code QUEUED} or {@code RUNNING}) for a job. */
  @SqlQuery(
      "SELECT COUNT(*) FROM titan.builds WHERE job_id = :jobId "
          + "AND status IN ('QUEUED', 'RUNNING')")
  int countNonTerminalBuilds(@Bind("jobId") long jobId);

  /**
   * Whether a build is already in flight for the job — the trigger-coalescing check (design/50 D5).
   * Runs on the caller's transactional connection so it sees a consistent snapshot under the
   * job-row lock the firing engine holds.
   */
  default boolean hasNonTerminalBuild(@NonNull Connection conn, long jobId) {
    return TitanStores.onConnection(conn, BuildDao.class, dao -> dao.countNonTerminalBuilds(jobId))
        > 0;
  }

  // ---------------------------------------------------------------
  //  Build number allocation (standalone + transactional)
  // ---------------------------------------------------------------
  /**
   * Returns the next build number for a job (max + 1, or 1 if none) — <strong>read-only, NOT an
   * allocation</strong>. Runs on an autocommit connection with no lock, so two concurrent callers
   * can observe the same value. Use it for display/diagnostics/tests; every real allocation must go
   * through {@link #nextBuildNumber(Connection, long)} inside a transaction (issue #69).
   */
  @SqlQuery("SELECT COALESCE(MAX(build_number), 0) + 1 FROM titan.builds WHERE job_id = :jobId")
  int nextBuildNumber(@Bind("jobId") long jobId);

  /**
   * Allocate the next build number for a job inside the caller's transaction — atomic under
   * concurrency (closes #69).
   *
   * <p>First acquires the {@code FOR UPDATE} row lock on the {@code titan.jobs} row (the design/50
   * D4 per-job mutex the trigger firing engine already holds via {@code JobDao.lockForUpdate}),
   * then computes {@code MAX(build_number) + 1} on the same connection. A concurrent allocator for
   * the same job blocks on the row lock until this transaction commits, then sees the committed
   * insert — so numbers are unique and contiguous with no retry loop. Without the lock, two
   * concurrent webhook deliveries both read the same max and one insert dies on {@code
   * uq_builds_job_number} (issue #69: silent build drop).
   *
   * <p>Re-acquiring the lock in a transaction that already holds it (the trigger engine path) is a
   * no-op. Portable across PostgreSQL and the H2 PG-mode test harness — both support {@code SELECT
   * ... FOR UPDATE}.
   *
   * <p>The caller must insert the build on the SAME connection before committing; the allocation is
   * only atomic while the transaction (and thus the lock) is open.
   *
   * @throws TitanDataException if the job row no longer exists — allocating a number for a deleted
   *     job would only defer the failure to the FK on insert.
   */
  default int nextBuildNumber(@NonNull Connection conn, long jobId) {
    boolean jobRowLocked =
        TitanStores.onConnection(conn, JobDao.class, dao -> dao.selectIdForUpdate(jobId))
            .isPresent();
    if (!jobRowLocked) {
      throw new TitanDataException(
          "cannot allocate build number: job " + jobId + " no longer exists");
    }
    return TitanStores.onConnection(conn, BuildDao.class, dao -> dao.nextBuildNumber(jobId));
  }

  // ---------------------------------------------------------------
  //  Insert (standalone + transactional)
  // ---------------------------------------------------------------
  /**
   * Insert a new build. Returns the generated id. {@code queued_at} falls to the binding of {@link
   * BuildRow#queuedAt}; callers set it explicitly. {@code status} defaults to {@code 'QUEUED'} when
   * null.
   */
  @SqlUpdate(
      "INSERT INTO titan.builds (job_id, build_number, status, parameters_json, "
          + "triggered_by, trigger_type, deployment_id, pipeline_model_json, "
          + "started_by_instance, queued_at, started_at, finished_at, duration_ms, "
          + "replayed_from_build_id, replayed_from_node_id, trigger_meta_json) "
          + "VALUES (:jobId, :buildNumber, COALESCE(:status, 'QUEUED'), :parametersJson, "
          + ":triggeredBy, :triggerType, :deploymentId, :pipelineModelJson, "
          + ":startedByInstance, COALESCE(:queuedAt, CURRENT_TIMESTAMP), "
          + ":startedAt, :finishedAt, :durationMs, "
          + ":replayedFromBuildId, :replayedFromNodeId, :triggerMetaJson)")
  @GetGeneratedKeys
  long insert(@BindFields BuildRow row);

  /**
   * Record the build's structured trigger metadata (issue #589) — branch / short SHA / actor JSON
   * derived from a webhook payload. Written once by the receiver after {@link #insert} has
   * allocated the id. No-op when {@code triggerMetaJson} is null.
   */
  @SqlUpdate("UPDATE titan.builds SET trigger_meta_json = :json WHERE id = :id")
  void updateTriggerMetaJson(@Bind("id") long id, @Bind("json") @NonNull String triggerMetaJson);

  // ---------------------------------------------------------------
  //  Replay-from-node (issue #307) — forward tracing
  // ---------------------------------------------------------------
  /**
   * Every build that was replayed from {@code parentBuildId} — the forward-trace half of the replay
   * relationship (issue #307). Used by the build-detail page and audit views to show "this build
   * has N children". Newest first.
   */
  @SqlQuery(
      "SELECT "
          + COLS
          + " FROM titan.builds WHERE replayed_from_build_id = :parentBuildId "
          + "ORDER BY queued_at DESC")
  @NonNull
  List<BuildRow> findBuildsByParent(@Bind("parentBuildId") long parentBuildId);

  /**
   * Transaction-safe insert. Returns the generated id. Caller provides an active connection and
   * manages commit/rollback.
   */
  default long insert(@NonNull Connection conn, @NonNull BuildRow row) {
    return TitanStores.onConnection(conn, BuildDao.class, dao -> dao.insert(row));
  }

  // ---------------------------------------------------------------
  //  Updates
  // ---------------------------------------------------------------
  /**
   * Update build status and timing fields.
   *
   * <p>Null-safe: {@code started_at}, {@code finished_at}, {@code duration_ms} and {@code
   * error_message} are written with {@code COALESCE}, so a caller passing {@code null} leaves the
   * existing column value intact. This is what makes the terminal write in {@link
   * io.adaptiq.titan.flow.TitanOrchestrator#finishIfDone} idempotent and non-destructive — it sets
   * {@code finished_at} without clobbering the {@code started_at} written at bake (#489 follow-up).
   * The status itself is always overwritten — it is the closed-status transition.
   */
  @SqlUpdate(
      "UPDATE titan.builds SET status = :status, "
          + "started_at = COALESCE(:startedAt, started_at), "
          + "finished_at = COALESCE(:finishedAt, finished_at), "
          + "duration_ms = COALESCE(:durationMs, duration_ms), "
          + "error_message = COALESCE(:errorMessage, error_message) "
          + "WHERE id = :id")
  void updateStatus(
      @Bind("id") long id,
      @Bind("status") @NonNull String status,
      @Bind("startedAt") @Nullable Instant startedAt,
      @Bind("finishedAt") @Nullable Instant finishedAt,
      @Bind("durationMs") @Nullable Long durationMs,
      @Bind("errorMessage") @Nullable String errorMessage);

  /**
   * Record the build's {@code failure_summary} (design/45 §1/§4) — the customer-facing reason a
   * build failed <em>before any flow node ran or failed</em> (a synthesis or bake failure). A build
   * that fails at a node carries no summary: the reason is on the failed node instead.
   */
  @SqlUpdate("UPDATE titan.builds SET failure_summary = :failureSummary WHERE id = :id")
  void updateFailureSummary(
      @Bind("id") long id, @Bind("failureSummary") @NonNull String failureSummary);

  /**
   * Record the diagnosed root cause of a FAILED build and the matching log snippet (issue #1105).
   * Written best-effort and async by {@code BuildFailureClassifier} after the terminal transition;
   * the {@code detail} snippet is {@code null} for an {@code unknown} verdict.
   */
  @SqlUpdate(
      "UPDATE titan.builds SET failure_cause = :cause, failure_cause_detail = :detail WHERE id = :id")
  void updateFailureCause(
      @Bind("id") long id,
      @Bind("cause") @NonNull String cause,
      @Bind("detail") @Nullable String detail);

  /** Store the immutable pipeline model snapshot for a build. */
  @SqlUpdate("UPDATE titan.builds SET pipeline_model_json = :json WHERE id = :id")
  void updatePipelineModelJson(
      @Bind("id") long id, @Bind("json") @NonNull String pipelineModelJson);

  /**
   * Store the pipeline-YAML snapshot this build is being synthesized from (issue #61, spec 24).
   * Written by {@code QueueHandlerSupport.enqueueWorkerSynthesis} at SYNTHESIZE dispatch time —
   * idempotent across the handler's re-dispatch paths (same source, same value).
   */
  @SqlUpdate("UPDATE titan.builds SET pipeline_script = :script WHERE id = :id")
  void updatePipelineScript(@Bind("id") long id, @Bind("script") @NonNull String pipelineScript);

  /**
   * Store the build's effective parameters — the declared parameters resolved against the supplied
   * values (defaults applied, types coerced). Written at bake so the build record and {@code ${{
   * params.* }}} resolution both see the actual values used.
   */
  @SqlUpdate("UPDATE titan.builds SET parameters_json = :json WHERE id = :id")
  void updateParametersJson(@Bind("id") long id, @Bind("json") @NonNull String parametersJson);

  /**
   * Atomically activate a build by moving it from QUEUED to RUNNING. Returns true if exactly one
   * row was updated.
   */
  default boolean activateIfQueued(long id, @NonNull Instant startedAt) {
    return activateIfQueuedUpdate(id, startedAt) == 1;
  }

  @SqlUpdate(
      "UPDATE titan.builds SET status = 'RUNNING', started_at = :startedAt "
          + "WHERE id = :id AND status = 'QUEUED'")
  int activateIfQueuedUpdate(@Bind("id") long id, @Bind("startedAt") @NonNull Instant startedAt);

  // ---------------------------------------------------------------
  //  Pipeline-root timeout (issue #244)
  // ---------------------------------------------------------------
  /**
   * Record the pipeline-root wall-clock deadline (issue #244). Called at BAKE when the pipeline
   * model carries a root-level {@code timeout:}. {@code null} is a no-op for the reaper.
   */
  @SqlUpdate("UPDATE titan.builds SET deadline_at = :deadlineAt WHERE id = :id")
  void updateDeadline(@Bind("id") long id, @Bind("deadlineAt") @Nullable Instant deadlineAt);

  // ---------------------------------------------------------------
  //  setBuildName step (#762) — controller-native renamer
  // ---------------------------------------------------------------
  /**
   * Record the build's human-friendly display name (#762). Idempotent on replay: re-running the
   * {@code setBuildName:} step with the same resolved value rewrites the same value. Last-write
   * wins when a pipeline invokes the step more than once. Caller is responsible for length /
   * blankness validation — the SQL trusts its input.
   */
  @SqlUpdate("UPDATE titan.builds SET display_name = :displayName WHERE id = :id")
  void setDisplayName(@Bind("id") long id, @Bind("displayName") @NonNull String displayName);

  /**
   * Record the GitHub Check-Run id for a build (#965). Written once by {@link
   * io.adaptiq.titan.scm.github.GithubCheckRunReporter} after the create-check-run POST succeeds.
   * The matching PATCH at terminal-status time reads this column to address the right resource. No
   * checked overwrite — a second write is a defensive no-op handled by the reporter (it only issues
   * the POST when the column is still null on the in-memory snapshot).
   */
  @SqlUpdate("UPDATE titan.builds SET external_check_run_id = :checkRunId WHERE id = :id")
  void setExternalCheckRunId(
      @Bind("id") long id, @Bind("checkRunId") @NonNull Long externalCheckRunId);

  /**
   * Every {@code RUNNING} build whose pipeline-root deadline has elapsed (issue #244). Returned ids
   * are what the {@code QueueProcessor} reaper marks {@code FAILED}. Uses the partial index {@code
   * idx_builds_deadline_at} so the scan is O(running-with-timeout-overdue).
   */
  @SqlQuery(
      "SELECT id FROM titan.builds "
          + "WHERE status = 'RUNNING' AND deadline_at IS NOT NULL AND deadline_at < :now")
  @NonNull
  List<Long> findOverdueRunningBuilds(@Bind("now") @NonNull Instant now);

  // ---------------------------------------------------------------
  //  Delete
  // ---------------------------------------------------------------
  @SqlUpdate("DELETE FROM titan.builds WHERE id = :id")
  void delete(@Bind("id") long id);

  // ---------------------------------------------------------------
  //  Per-job build retention (issue #637)
  // ---------------------------------------------------------------
  /**
   * The ids of every build whose age in the job rank (newest = rank 0) exceeds {@code keepLast} —
   * the over-cap tail dropped by the per-job retention pruner. Ordered by {@code id} (build ids are
   * monotonic per job; the largest id is the newest build), oldest first so the pruner deletes in
   * append order. Returns at most {@code limit} ids per call so a job with a sudden spike does not
   * have to drop everything in one transaction.
   *
   * <p>{@code keepLast <= 0} is the operator opt-out — the pruner short-circuits before calling
   * this so a {@code 0} default leaks no retention work to the DB.
   */
  @SqlQuery(
      "SELECT id FROM titan.builds WHERE job_id = :jobId "
          + "ORDER BY id DESC LIMIT :limit OFFSET :keepLast")
  @NonNull
  List<Long> findIdsOverRetentionCap(
      @Bind("jobId") long jobId, @Bind("keepLast") int keepLast, @Bind("limit") int limit);
}
