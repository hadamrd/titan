package io.adaptiq.titan.store.rows;

import java.time.Instant;

/**
 * Mutable POJO mapping to a row in {@code titan.rbac_user_role} — the scoped RBAC role-assignment
 * table (closes #1131, epic #1114).
 *
 * <p>Public fields per the project convention (JDBI {@code @RegisterFieldMapper}). The scope-kind /
 * role strings are validated into {@link io.adaptiq.titan.auth.ScopeKind} and {@link
 * io.adaptiq.titan.auth.Authz.TitanRole} at the service boundary; unknown values are silently
 * dropped to enforce default-deny.
 */
public class RbacUserRoleRow {
  public String userId;
  public String scopeKind;
  public String scopeId;
  public String role;
  public Instant grantedAt;
}
