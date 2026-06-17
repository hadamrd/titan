package io.adaptiq.titan.auth;

/**
 * Closed enum of RBAC scope kinds — the typed discriminator for {@code
 * titan.rbac_user_role.scope_kind} (closes #1131, epic #1114).
 *
 * <p>Per the Titan manifesto's "no stringly-typed cross-module discriminators" rule, the scope-kind
 * string in the DB and in cross-module APIs is always materialised as a {@code ScopeKind} constant
 * — pattern-matched or {@code ==} compared, never string-sniffed. A future scope (e.g. {@code
 * FOLDER}, {@code CREDENTIAL}) is added here in one place; producers and consumers re-compile.
 *
 * <ul>
 *   <li>{@link #ORG} — organization-level scope. {@code scope_id} is the org slug (e.g. {@code
 *       "acme"}) or {@code "global"} for single-tenant deploys.
 *   <li>{@link #REPO} — per-repository scope. {@code scope_id} is the repo identifier ({@code
 *       "<org>/<repo>"}).
 * </ul>
 */
public enum ScopeKind {
  ORG,
  REPO
}
