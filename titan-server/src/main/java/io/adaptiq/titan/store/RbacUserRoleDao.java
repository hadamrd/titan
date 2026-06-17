package io.adaptiq.titan.store;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.store.rows.RbacUserRoleRow;
import java.util.List;
import org.jdbi.v3.sqlobject.SqlObject;
import org.jdbi.v3.sqlobject.config.RegisterFieldMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

/**
 * JDBI SqlObject access for {@code titan.rbac_user_role} — the scoped RBAC role-assignment table
 * (closes #1131, epic #1114). Read by {@link io.adaptiq.titan.auth.ScopedAuthz} on every per-action
 * permission check; written by admin SQL/tooling in v1 (no UI yet — see #1114 follow-up).
 */
@RegisterFieldMapper(RbacUserRoleRow.class)
public interface RbacUserRoleDao extends SqlObject {

  /**
   * Every role the user holds on this exact (scope_kind, scope_id). Empty list = no row = default
   * deny — fall through to the scoped table's parent (org) or to the global {@code user_roles}
   * fallback at the service layer.
   */
  @SqlQuery(
      "SELECT user_id, scope_kind, scope_id, role, granted_at FROM titan.rbac_user_role "
          + "WHERE user_id = :userId AND scope_kind = :scopeKind AND scope_id = :scopeId "
          + "ORDER BY role")
  @NonNull
  List<RbacUserRoleRow> findByUserAndScope(
      @Bind("userId") String userId,
      @Bind("scopeKind") String scopeKind,
      @Bind("scopeId") String scopeId);

  /**
   * Every role the user holds on any scope of the given kind. Used by the admin listing surface.
   */
  @SqlQuery(
      "SELECT user_id, scope_kind, scope_id, role, granted_at FROM titan.rbac_user_role "
          + "WHERE user_id = :userId AND scope_kind = :scopeKind "
          + "ORDER BY scope_id, role")
  @NonNull
  List<RbacUserRoleRow> findByUserAndScopeKind(
      @Bind("userId") String userId, @Bind("scopeKind") String scopeKind);

  /** Every assignment for a user across every scope. Read by the org-admin listing surface. */
  @SqlQuery(
      "SELECT user_id, scope_kind, scope_id, role, granted_at FROM titan.rbac_user_role "
          + "WHERE user_id = :userId "
          + "ORDER BY scope_kind, scope_id, role")
  @NonNull
  List<RbacUserRoleRow> findByUser(@Bind("userId") String userId);

  /**
   * Every assignment held by any user in {@code userIds}, in one round trip. Backs the admin
   * listing surface so enriching a page of N users costs ONE {@code WHERE user_id IN (...)} SELECT
   * rather than N {@link #findByUser} calls (avoids the N+1 the page would otherwise issue). The
   * caller groups the returned rows by {@code userId} in memory. An empty input binds {@code IN
   * (NULL)}, which matches nothing — so the query is safe to call on an empty page.
   */
  @SqlQuery(
      "SELECT user_id, scope_kind, scope_id, role, granted_at FROM titan.rbac_user_role "
          + "WHERE user_id IN (<userIds>) "
          + "ORDER BY user_id, scope_kind, scope_id, role")
  @NonNull
  List<RbacUserRoleRow> findByUsers(
      @org.jdbi.v3.sqlobject.customizer.BindList(
              value = "userIds",
              onEmpty = org.jdbi.v3.sqlobject.customizer.BindList.EmptyHandling.NULL_STRING)
          List<String> userIds);

  /**
   * Idempotent grant — no-op on PK conflict.
   *
   * <p>Uses the target-less {@code ON CONFLICT DO NOTHING} form rather than {@code ON CONFLICT
   * (user_id, scope_kind, scope_id, role)}: the composite PK is the table's only constraint, so the
   * two are semantically identical on Postgres, but the target-less form ALSO parses under H2's
   * PostgreSQL compatibility mode (H2 rejects an explicit conflict-target column list). That keeps
   * the DAO exercisable by the H2-backed {@code @QuarkusTest} RBAC ITs (e.g. {@code
   * RbacMutatingEndpointsIT} seeds scoped-demotion grants through here).
   */
  @SqlUpdate(
      "INSERT INTO titan.rbac_user_role (user_id, scope_kind, scope_id, role) "
          + "VALUES (:userId, :scopeKind, :scopeId, :role) "
          + "ON CONFLICT DO NOTHING")
  void grant(
      @Bind("userId") String userId,
      @Bind("scopeKind") String scopeKind,
      @Bind("scopeId") String scopeId,
      @Bind("role") String role);

  /** Remove one (user, scope, role) assignment. */
  @SqlUpdate(
      "DELETE FROM titan.rbac_user_role "
          + "WHERE user_id = :userId AND scope_kind = :scopeKind AND scope_id = :scopeId "
          + "AND role = :role")
  void revoke(
      @Bind("userId") String userId,
      @Bind("scopeKind") String scopeKind,
      @Bind("scopeId") String scopeId,
      @Bind("role") String role);

  /**
   * Remove every assignment a user holds on one (scope_kind, scope_id) — the seam behind {@code
   * DELETE /api/v1/admin/users/{userId}/roles/{scopeKind}/{scopeId}} (closes #1236).
   *
   * @return the number of rows deleted. The admin endpoint maps {@code 0} → HTTP 404 (idempotent:
   *     revoking an absent assignment never 500s, it 404s), {@code >0} → success.
   */
  @SqlUpdate(
      "DELETE FROM titan.rbac_user_role "
          + "WHERE user_id = :userId AND scope_kind = :scopeKind AND scope_id = :scopeId")
  int revokeScope(
      @Bind("userId") String userId,
      @Bind("scopeKind") String scopeKind,
      @Bind("scopeId") String scopeId);
}
