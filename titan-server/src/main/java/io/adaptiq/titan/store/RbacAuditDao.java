package io.adaptiq.titan.store;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.adaptiq.titan.store.rows.RbacAuditRow;
import java.time.Instant;
import java.util.List;
import org.jdbi.v3.sqlobject.SqlObject;
import org.jdbi.v3.sqlobject.config.RegisterFieldMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

/**
 * JDBI SqlObject access for {@code titan.rbac_audit} — the typed audit trail for every {@link
 * io.adaptiq.titan.auth.RequiresRole @RequiresRole}-gated call (closes #1131, epic #1114).
 *
 * <p>The table is V40 (sibling to V20 {@code audit_log}). Inserts are best-effort — see {@link
 * io.adaptiq.titan.auth.ScopedAuthz#recordRbacAudit} for the swallow-failures invariant; the only
 * caller is the {@code @RequiresRole} interceptor seam and an audit-write failure must not block
 * the underlying API call.
 */
@RegisterFieldMapper(RbacAuditRow.class)
public interface RbacAuditDao extends SqlObject {

  /**
   * Insert one audit row. {@code userId} and {@code effectiveRole} may be null; the table's CHECK
   * constraint enforces {@code decision IN ('ALLOW', 'DENY')}.
   */
  @SqlUpdate(
      "INSERT INTO titan.rbac_audit "
          + "(user_id, endpoint, scope_kind, scope_id, required_role, effective_role, decision) "
          + "VALUES (:userId, :endpoint, :scopeKind, :scopeId, :requiredRole, :effectiveRole,"
          + " :decision)")
  void insert(
      @Bind("userId") @Nullable String userId,
      @Bind("endpoint") String endpoint,
      @Bind("scopeKind") String scopeKind,
      @Bind("scopeId") String scopeId,
      @Bind("requiredRole") String requiredRole,
      @Bind("effectiveRole") @Nullable String effectiveRole,
      @Bind("decision") String decision);

  /** Count rows for assertions in tests / admin tooling. */
  @SqlQuery("SELECT COUNT(*) FROM titan.rbac_audit")
  long count();

  /** Count rows for a given (scope_kind, scope_id) — admin and adversarial test query. */
  @SqlQuery(
      "SELECT COUNT(*) FROM titan.rbac_audit WHERE scope_kind = :scopeKind AND scope_id ="
          + " :scopeId")
  long countForScope(@Bind("scopeKind") String scopeKind, @Bind("scopeId") String scopeId);

  /** Most-recent {@code limit} rows for an admin "who did what" listing. */
  @SqlQuery(
      "SELECT id, occurred_at, user_id, endpoint, scope_kind, scope_id, required_role,"
          + " effective_role, decision FROM titan.rbac_audit ORDER BY occurred_at DESC, id DESC"
          + " LIMIT :limit")
  @NonNull
  List<RbacAuditRow> recent(@Bind("limit") int limit);

  /** Most-recent rows for a single user — backs the "what did Alice attempt?" filter. */
  @SqlQuery(
      "SELECT id, occurred_at, user_id, endpoint, scope_kind, scope_id, required_role,"
          + " effective_role, decision FROM titan.rbac_audit WHERE user_id = :userId ORDER BY"
          + " occurred_at DESC, id DESC LIMIT :limit")
  @NonNull
  List<RbacAuditRow> recentForUser(@Bind("userId") String userId, @Bind("limit") int limit);

  // ---------------------------------------------------------------
  //  Filtered + paginated list (#1167) — backs GET /api/v1/rbac-audit
  // ---------------------------------------------------------------

  /**
   * Filterable, paginated audit feed for the operator-facing {@code GET /api/v1/rbac-audit} surface
   * (closes #1167). Every filter is optional and AND-composed; a {@code null} bind disables that
   * clause so a blank/empty filter degrades to the unfiltered recent feed — never "match nothing".
   *
   * <ul>
   *   <li>{@code actor}: case-insensitive substring on {@code user_id} ({@code ILIKE %actor%}).
   *       Anonymous (null {@code user_id}) rows never match an actor substring — correct, since an
   *       actor query is by definition asking "who, by name".
   *   <li>{@code decision}: exact match on the verdict ({@code ALLOW}/{@code DENY}). Validated
   *       against {@link io.adaptiq.titan.auth.RbacDecision} upstream by {@code RbacAuditApi}, so a
   *       garbage value is rejected at the HTTP boundary (400), never silently here.
   *   <li>{@code scopeKind}: exact match on {@code scope_kind} ({@code ORG}/{@code REPO}).
   *   <li>{@code since}: {@code occurred_at >= :since} lower bound.
   * </ul>
   *
   * <p>Ordered newest-first ({@code occurred_at DESC, id DESC}) — the stable secondary key on
   * {@code id} guarantees offset/limit pages never overlap or skip a row that shares a timestamp.
   * The {@code CAST(:since AS TIMESTAMP)} idiom is the H2/PostgreSQL-portable "null means no
   * filter" shape borrowed from {@link AuditLogDao#findRecent}.
   *
   * <p>All binds are parameterised — the classic {@code "'; DROP TABLE titan.rbac_audit; --"}
   * payload in {@code actor} returns zero rows, never a 500, and the table stays intact.
   */
  @SqlQuery(
      "SELECT id, occurred_at, user_id, endpoint, scope_kind, scope_id, required_role,"
          + " effective_role, decision FROM titan.rbac_audit"
          + " WHERE (:actor IS NULL OR user_id ILIKE ('%' || :actor || '%'))"
          + "   AND (:decision IS NULL OR decision = :decision)"
          + "   AND (:scopeKind IS NULL OR scope_kind = :scopeKind)"
          + "   AND (CAST(:since AS TIMESTAMP) IS NULL OR occurred_at >= :since)"
          + " ORDER BY occurred_at DESC, id DESC"
          + " LIMIT :limit OFFSET :offset")
  @NonNull
  List<RbacAuditRow> findFiltered(
      @Bind("actor") @Nullable String actor,
      @Bind("decision") @Nullable String decision,
      @Bind("scopeKind") @Nullable String scopeKind,
      @Bind("since") @Nullable Instant since,
      @Bind("limit") int limit,
      @Bind("offset") int offset);

  /** Total rows matching the same filter set as {@link #findFiltered} — paired for paging. */
  @SqlQuery(
      "SELECT COUNT(*) FROM titan.rbac_audit"
          + " WHERE (:actor IS NULL OR user_id ILIKE ('%' || :actor || '%'))"
          + "   AND (:decision IS NULL OR decision = :decision)"
          + "   AND (:scopeKind IS NULL OR scope_kind = :scopeKind)"
          + "   AND (CAST(:since AS TIMESTAMP) IS NULL OR occurred_at >= :since)")
  long countFiltered(
      @Bind("actor") @Nullable String actor,
      @Bind("decision") @Nullable String decision,
      @Bind("scopeKind") @Nullable String scopeKind,
      @Bind("since") @Nullable Instant since);
}
