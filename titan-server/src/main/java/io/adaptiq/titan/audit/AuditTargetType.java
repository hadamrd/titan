package io.adaptiq.titan.audit;

/**
 * Closed enum of audit target types. Pairs with {@link AuditAction} to identify the row affected by
 * an action — {@code target_type + target_id} is the natural key for "show me everything that
 * happened to JOB/42".
 */
public enum AuditTargetType {
  /** A row in {@code titan.jobs}. target_id = job id. */
  JOB,
  /** A row in {@code titan.builds}. target_id = build id. */
  BUILD,
  /** A row in {@code titan.personal_access_tokens}. target_id = PAT id (NEVER the token). */
  PAT,
  /** A row in {@code titan.group_role_mapping} — SSO group → Titan role binding (closes #1136). */
  SSO_MAPPING,
  /**
   * A realm user — target of a scoped Titan role grant/revoke (closes #1236). target_id = the user
   * id (the principal name keyed into {@code titan.rbac_user_role.user_id}).
   */
  USER
}
