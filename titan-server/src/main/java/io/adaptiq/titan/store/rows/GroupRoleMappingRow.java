package io.adaptiq.titan.store.rows;

import java.time.Instant;

/**
 * Mutable POJO mapping to a row in {@code titan.group_role_mapping} — the per-org SSO group → Titan
 * role assignment table (closes #1136).
 *
 * <p>Public fields per the project convention (JDBI {@code @RegisterFieldMapper}). One row binds
 * {@code (orgId, groupPath) → role}; the {@link io.adaptiq.titan.auth.GroupRoleResolver} folds the
 * caller's {@code groups} claim through these rows to compute the per-org Titan role set on every
 * OIDC login.
 */
public class GroupRoleMappingRow {
  public long id;
  public String orgId;
  public String groupPath;
  public String role;
  public Instant createdAt;
  public Instant updatedAt;
}
