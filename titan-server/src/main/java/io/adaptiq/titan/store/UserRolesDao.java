package io.adaptiq.titan.store;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.store.rows.UserRoleRow;
import java.util.List;
import org.jdbi.v3.sqlobject.SqlObject;
import org.jdbi.v3.sqlobject.config.RegisterFieldMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

/**
 * JDBI SqlObject access for {@code titan.user_roles} — the LEGACY v1 flat RBAC table (V35, #1121).
 *
 * <p><strong>Legacy fallback only (#126).</strong> The canonical role store is {@code
 * titan.rbac_user_role} (V38, {@link RbacUserRoleDao}) — the table the AdminUsersApi grant surface
 * writes. The only remaining reader of this DAO is {@link io.adaptiq.titan.auth.ScopedAuthz}'s
 * documented last-chance fallthrough, kept so pre-V38 SQL-seeded ADMIN rows keep working. {@code
 * io.adaptiq.titan.auth.Authz} no longer reads it directly. Do NOT add new writers or readers —
 * grant through {@code rbac_user_role}. The table itself is retained (no destructive drop); a
 * future migration may lift surviving rows into {@code rbac_user_role (ORG, 'global')} and retire
 * the fallback, per the V38 migration header.
 */
@RegisterFieldMapper(UserRoleRow.class)
public interface UserRolesDao extends SqlObject {

  /** Roles held by {@code userId}. Empty list = no row = default-deny → treated as VIEWER. */
  @SqlQuery(
      "SELECT user_id, role, granted_at FROM titan.user_roles WHERE user_id = :userId ORDER BY"
          + " role")
  @NonNull
  List<UserRoleRow> findByUserId(@Bind("userId") String userId);

  /**
   * Insert one (user, role) pair. No-op on PK conflict — idempotent grant.
   *
   * <p>Uses the target-less {@code ON CONFLICT DO NOTHING} form rather than {@code ON CONFLICT
   * (user_id, role)}: the composite PK is the table's only constraint, so the two are semantically
   * identical on Postgres, but the target-less form ALSO parses under H2's PostgreSQL compatibility
   * mode (H2 rejects an explicit conflict-target column list). Same convention as {@link
   * RbacUserRoleDao#grant} — keeps the DAO exercisable by H2-backed {@code @QuarkusTest} classes
   * (e.g. {@code BuildReplayApiTest} seeds its ADMIN row through here; issue #41).
   */
  @SqlUpdate(
      "INSERT INTO titan.user_roles (user_id, role) VALUES (:userId, :role) "
          + "ON CONFLICT DO NOTHING")
  void grant(@Bind("userId") String userId, @Bind("role") String role);

  /** Remove one (user, role) pair. */
  @SqlUpdate("DELETE FROM titan.user_roles WHERE user_id = :userId AND role = :role")
  void revoke(@Bind("userId") String userId, @Bind("role") String role);
}
