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

  /** Insert one (user, role) pair. No-op on PK conflict — idempotent grant. */
  @SqlUpdate(
      "INSERT INTO titan.user_roles (user_id, role) VALUES (:userId, :role) "
          + "ON CONFLICT (user_id, role) DO NOTHING")
  void grant(@Bind("userId") String userId, @Bind("role") String role);

  /** Remove one (user, role) pair. */
  @SqlUpdate("DELETE FROM titan.user_roles WHERE user_id = :userId AND role = :role")
  void revoke(@Bind("userId") String userId, @Bind("role") String role);
}
