package io.adaptiq.titan.store.rows;

import java.time.Instant;

/**
 * Mutable POJO mapping to a row in {@code titan.user_roles} — the v1 RBAC role-assignment table
 * (closes #1121).
 *
 * <p>Public fields per the project convention (JDBI {@code @RegisterFieldMapper}). The full
 * hierarchical role model (groups, inherited roles, per-folder scoping) lands on top in #1114.
 */
public class UserRoleRow {
  public String userId;
  public String role;
  public Instant grantedAt;
}
