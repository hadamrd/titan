package io.adaptiq.titan.auth;

/**
 * Typed discriminator for the {@code decision} column on {@code titan.rbac_audit} (closes #1131,
 * epic #1114).
 *
 * <p>Per the manifesto's no-stringly-typed-cross-module-discriminators rule: the value flowing from
 * {@link ScopedAuthz#requires} into {@link io.adaptiq.titan.store.RbacAuditDao#insert} is this
 * enum, not a raw string literal. The DB layer calls {@link #name()} at the boundary.
 *
 * <p>The SQL schema enforces the same set as a {@code CHECK} constraint — a future enum value would
 * fail the constraint until paired with a migration.
 */
public enum RbacDecision {
  /** Caller's effective role &gt;= required role. */
  ALLOW,
  /** Caller's effective role &lt; required role; the gated method threw {@code 403}. */
  DENY
}
