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
 * JDBI SqlObject access for {@code titan.user_roles} — the v1 RBAC role-assignment table (closes
 * #1121). Read by {@link io.adaptiq.titan.auth.Authz} on every {@code requires(...)} call; written
 * by admin SQL/tooling in v1 (no UI yet — see #1114 for the lifecycle surface).
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
