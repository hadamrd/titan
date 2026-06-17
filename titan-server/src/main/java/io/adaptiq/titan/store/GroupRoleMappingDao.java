package io.adaptiq.titan.store;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.store.rows.GroupRoleMappingRow;
import java.util.List;
import java.util.Optional;
import org.jdbi.v3.sqlobject.SqlObject;
import org.jdbi.v3.sqlobject.config.RegisterFieldMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.GetGeneratedKeys;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

/**
 * JDBI SqlObject access for {@code titan.group_role_mapping} — the per-org SSO group → Titan role
 * mapping table (closes #1136).
 *
 * <p>Read on every OIDC login by {@link io.adaptiq.titan.auth.GroupRoleResolver}; mutated by the
 * org-admin-only REST surface in {@code SsoMappingApi}. Each mutation also writes an {@link
 * io.adaptiq.titan.audit.AuditAction#SSO_MAPPING_CREATE SSO_MAPPING_*} audit row.
 */
@RegisterFieldMapper(GroupRoleMappingRow.class)
public interface GroupRoleMappingDao extends SqlObject {

  String COLS = "id, org_id, group_path, role, created_at, updated_at";

  /** List every mapping for an org, deterministically ordered for the admin UI. */
  @SqlQuery(
      "SELECT "
          + COLS
          + " FROM titan.group_role_mapping WHERE org_id = :orgId "
          + "ORDER BY group_path, role")
  @NonNull
  List<GroupRoleMappingRow> listByOrg(@Bind("orgId") String orgId);

  /**
   * List every mapping whose {@code group_path} is in the caller's resolved claim set. Cross-org
   * intentionally — the resolver wants to compute role assignments for ALL orgs the user touches in
   * a single round trip.
   */
  @SqlQuery("SELECT " + COLS + " FROM titan.group_role_mapping WHERE group_path IN (<paths>)")
  @NonNull
  List<GroupRoleMappingRow> listByGroupPaths(
      @org.jdbi.v3.sqlobject.customizer.BindList(
              value = "paths",
              onEmpty = org.jdbi.v3.sqlobject.customizer.BindList.EmptyHandling.NULL_STRING)
          List<String> paths);

  /** Look up a single mapping row by id. */
  @SqlQuery("SELECT " + COLS + " FROM titan.group_role_mapping WHERE id = :id")
  @NonNull
  Optional<GroupRoleMappingRow> findById(@Bind("id") long id);

  /** Look up the row backing a particular {@code (orgId, groupPath)} pair. */
  @SqlQuery(
      "SELECT "
          + COLS
          + " FROM titan.group_role_mapping WHERE org_id = :orgId AND group_path = :groupPath")
  @NonNull
  Optional<GroupRoleMappingRow> findByOrgAndGroup(
      @Bind("orgId") String orgId, @Bind("groupPath") String groupPath);

  /**
   * Insert a new mapping row. The caller is expected to have validated {@code groupPath} and {@code
   * role} via {@link io.adaptiq.titan.auth.GroupMappingValidator} first.
   */
  @SqlUpdate(
      "INSERT INTO titan.group_role_mapping (org_id, group_path, role) "
          + "VALUES (:orgId, :groupPath, :role)")
  @GetGeneratedKeys
  long insert(
      @Bind("orgId") String orgId, @Bind("groupPath") String groupPath, @Bind("role") String role);

  /** Update the {@code role} of an existing row. Returns 1 on hit, 0 on miss. */
  @SqlUpdate(
      "UPDATE titan.group_role_mapping "
          + "SET role = :role, updated_at = CURRENT_TIMESTAMP "
          + "WHERE id = :id")
  int updateRole(@Bind("id") long id, @Bind("role") String role);

  /** Delete a mapping row. Returns 1 on hit, 0 on miss. */
  @SqlUpdate("DELETE FROM titan.group_role_mapping WHERE id = :id")
  int delete(@Bind("id") long id);
}
