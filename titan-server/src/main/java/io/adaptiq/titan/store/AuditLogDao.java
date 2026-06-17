package io.adaptiq.titan.store;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.adaptiq.titan.api.dto.AuditQuery;
import io.adaptiq.titan.store.rows.AuditLogRow;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.statement.Query;
import org.jdbi.v3.sqlobject.SqlObject;
import org.jdbi.v3.sqlobject.config.RegisterFieldMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindFields;
import org.jdbi.v3.sqlobject.statement.GetGeneratedKeys;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

/**
 * JDBI SqlObject access for {@code titan.audit_log} — audit trail for high-risk actions (closes
 * #478). Mirrors the {@link PersonalAccessTokenDao} pattern.
 *
 * <p>Inserts are best-effort from the perspective of the emitting endpoint — the API layer
 * try-catches around {@link #insert(AuditLogRow)} so an audit write failure cannot fail the main
 * action.
 */
@RegisterFieldMapper(AuditLogRow.class)
public interface AuditLogDao extends SqlObject {

  String COLS = "id, occurred_at, actor, action, target_type, target_id, details_json";

  /**
   * Insert a new audit row. Returns the generated id. {@code occurred_at} falls to its column
   * default ({@code CURRENT_TIMESTAMP}) when the row's value is null.
   */
  @SqlUpdate(
      "INSERT INTO titan.audit_log "
          + "(occurred_at, actor, action, target_type, target_id, details_json) "
          + "VALUES (COALESCE(:occurredAt, CURRENT_TIMESTAMP), :actor, :action, :targetType,"
          + " :targetId, :detailsJson)")
  @GetGeneratedKeys
  long insert(@BindFields AuditLogRow row);

  /**
   * List audit rows newest-first, with optional filters. NULL parameters disable the corresponding
   * filter. Used by {@code GET /api/v1/audit}.
   *
   * <p>The {@code COALESCE(:since, occurred_at)} idiom is portable across H2 and PostgreSQL — both
   * treat a comparison against a null bind value as "no filter" when wrapped this way.
   */
  @SqlQuery(
      "SELECT "
          + COLS
          + " FROM titan.audit_log "
          + "WHERE (:actor IS NULL OR actor = :actor) "
          + "  AND (:action IS NULL OR action = :action) "
          + "  AND (:targetType IS NULL OR target_type = :targetType) "
          + "  AND (CAST(:since AS TIMESTAMP) IS NULL OR occurred_at >= :since) "
          + "ORDER BY occurred_at DESC, id DESC "
          + "LIMIT :limit OFFSET :offset")
  @NonNull
  List<AuditLogRow> findRecent(
      @Bind("actor") @Nullable String actor,
      @Bind("action") @Nullable String action,
      @Bind("targetType") @Nullable String targetType,
      @Bind("since") @Nullable Instant since,
      @Bind("limit") int limit,
      @Bind("offset") int offset);

  /** Total count matching the same filter set — paired with {@link #findRecent} for paging. */
  @SqlQuery(
      "SELECT COUNT(*) FROM titan.audit_log "
          + "WHERE (:actor IS NULL OR actor = :actor) "
          + "  AND (:action IS NULL OR action = :action) "
          + "  AND (:targetType IS NULL OR target_type = :targetType) "
          + "  AND (CAST(:since AS TIMESTAMP) IS NULL OR occurred_at >= :since)")
  long countRecent(
      @Bind("actor") @Nullable String actor,
      @Bind("action") @Nullable String action,
      @Bind("targetType") @Nullable String targetType,
      @Bind("since") @Nullable Instant since);

  // ---------------------------------------------------------------
  //  Multi-filter list (#727) — backs GET /api/v1/audit (extended)
  // ---------------------------------------------------------------

  /**
   * Filterable, paginated audit log. Backs the extended {@code GET /api/v1/audit} (closes #727).
   *
   * <p>Filter semantics (every clause optional, AND-composed):
   *
   * <ul>
   *   <li>{@code actor}: case-insensitive substring on {@code actor} (ILIKE {@code %actor%}). Empty
   *       string → no actor filter.
   *   <li>{@code actions}: {@code action IN (...)}. Empty list → no action filter. Tokens are
   *       validated against the {@code AuditAction} enum upstream by {@code AuditApi}.
   *   <li>{@code resource}: case-insensitive substring on either {@code target_type} or {@code
   *       target_id}. Lets operators search "build:42" style strings without thinking about which
   *       column holds the number.
   *   <li>{@code since}: {@code occurred_at >= :since}.
   * </ul>
   *
   * <p>All bindings are parameterised via JDBI — never concatenated. The classic SQLi payload
   * {@code "'; DROP TABLE titan.audit_log; --"} in {@code actor} returns zero rows, never a 500,
   * and the table stays intact.
   */
  @NonNull
  default List<AuditLogRow> findAll(@NonNull AuditQuery q) {
    Handle h = getHandle();
    StringBuilder sql = new StringBuilder("SELECT ").append(COLS).append(" FROM titan.audit_log");
    List<String> where = buildWhere(q);
    if (!where.isEmpty()) {
      sql.append(" WHERE ").append(String.join(" AND ", where));
    }
    sql.append(" ORDER BY occurred_at DESC, id DESC LIMIT :limit OFFSET :offset");
    Query query = h.createQuery(sql.toString());
    bindFilters(query, q);
    query.bind("limit", q.limit());
    query.bind("offset", q.offset());
    return query.mapTo(AuditLogRow.class).list();
  }

  /** Count matching the same filter set as {@link #findAll(AuditQuery)}. */
  default long countAll(@NonNull AuditQuery q) {
    Handle h = getHandle();
    StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM titan.audit_log");
    List<String> where = buildWhere(q);
    if (!where.isEmpty()) {
      sql.append(" WHERE ").append(String.join(" AND ", where));
    }
    Query query = h.createQuery(sql.toString());
    bindFilters(query, q);
    return query.mapTo(Long.class).one();
  }

  private static List<String> buildWhere(AuditQuery q) {
    List<String> where = new ArrayList<>();
    if (q.actor() != null && !q.actor().isBlank()) {
      where.add("actor ILIKE :actor");
    }
    if (q.actions() != null && !q.actions().isEmpty()) {
      where.add("action IN (<actions>)");
    }
    if (q.resource() != null && !q.resource().isBlank()) {
      where.add("(target_type ILIKE :resource OR target_id ILIKE :resource)");
    }
    if (q.since() != null) {
      where.add("occurred_at >= :since");
    }
    if (q.afterTs() != null && q.afterId() != null) {
      // Cursor pagination (#1098). Lexicographic comparison on (occurred_at, id) — stable
      // across mid-stream inserts.
      where.add("(occurred_at < :afterTs OR (occurred_at = :afterTs AND id < :afterId))");
    }
    return where;
  }

  // ---------------------------------------------------------------
  //  Retention purge (#1104) — backs the nightly RetentionJob
  // ---------------------------------------------------------------

  /**
   * The distinct {@code action} codes actually present in {@code titan.audit_log}. The retention
   * job resolves a per-kind cutoff for each one (kind-specific policy &gt; default), so iterating
   * the codes that really exist naturally covers legacy / unknown action strings too — they fall to
   * the default horizon without any {@code NOT IN} gymnastics.
   */
  @SqlQuery("SELECT DISTINCT action FROM titan.audit_log")
  @NonNull
  List<String> distinctActions();

  /**
   * Best-effort batched purge: delete up to {@code limit} rows of one {@code action} whose {@code
   * occurred_at} is strictly older than {@code cutoff}, oldest-first. Returns the number deleted.
   *
   * <p>The {@code id IN (SELECT … ORDER BY occurred_at LIMIT :limit)} idiom is portable across H2
   * and PostgreSQL and lets the caller bound each transaction to a fixed batch (10k by default) —
   * one transaction per batch, looping until a batch comes back short. The {@code (action,
   * occurred_at)} index (V42) makes the inner select an index range scan.
   *
   * @return rows deleted in this batch; {@code < limit} signals the kind is drained
   */
  @SqlUpdate(
      "DELETE FROM titan.audit_log WHERE id IN ("
          + "SELECT id FROM titan.audit_log "
          + "WHERE action = :action AND occurred_at < :cutoff "
          + "ORDER BY occurred_at LIMIT :limit)")
  int deleteOlderThan(
      @Bind("action") @NonNull String action,
      @Bind("cutoff") @NonNull Instant cutoff,
      @Bind("limit") int limit);

  private static void bindFilters(Query query, AuditQuery q) {
    if (q.actor() != null && !q.actor().isBlank()) {
      query.bind("actor", "%" + q.actor() + "%");
    }
    if (q.actions() != null && !q.actions().isEmpty()) {
      query.bindList("actions", q.actions());
    }
    if (q.resource() != null && !q.resource().isBlank()) {
      query.bind("resource", "%" + q.resource() + "%");
    }
    if (q.since() != null) {
      query.bind("since", q.since());
    }
    if (q.afterTs() != null && q.afterId() != null) {
      query.bind("afterTs", q.afterTs());
      query.bind("afterId", q.afterId());
    }
  }
}
