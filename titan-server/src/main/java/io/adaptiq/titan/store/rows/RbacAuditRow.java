package io.adaptiq.titan.store.rows;

import java.time.Instant;

/**
 * Mutable POJO mapping to a row in {@code titan.rbac_audit} — the typed audit trail for every
 * {@link io.adaptiq.titan.auth.RequiresRole @RequiresRole}-gated call (closes #1131, epic #1114).
 *
 * <p>Public fields per the project convention (JDBI {@code @RegisterFieldMapper}). The {@code
 * decision} string is one of {@code ALLOW} or {@code DENY} (mirrors {@link
 * io.adaptiq.titan.auth.RbacDecision}); the SQL CHECK constraint rejects anything else.
 *
 * <p>{@code userId} and {@code effectiveRole} are nullable — anonymous callers get a {@code null}
 * user (still audited so unauthenticated probes are traceable), and the effective-role column is
 * left null for the rare "could not resolve" case so future code can distinguish "no row" from
 * "explicit VIEWER grant". The ScopedAuthz emitter currently always resolves to a concrete role and
 * fills it in.
 */
public class RbacAuditRow {
  public long id;
  public Instant occurredAt;
  public String userId;
  public String endpoint;
  public String scopeKind;
  public String scopeId;
  public String requiredRole;
  public String effectiveRole;
  public String decision;
}
