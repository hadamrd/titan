-- V38__rbac_user_role.sql
-- Issue #1131 — scoped RBAC role assignments (epic #1114).
--
-- Extends the flat V35 user_roles table with a (scope_kind, scope_id) tuple
-- so a single user can hold different roles on different orgs / repos.
--
--   rbac_user_role(user_id, scope_kind ∈ {ORG, REPO}, scope_id, role)
--
-- Roles (per CONSTITUTION + V35): ADMIN, MAINTAINER, DEVELOPER, VIEWER. The
-- enum is enforced in application code (Authz.TitanRole) — a future role
-- addition is a one-line code change, not a CHECK constraint migration. The
-- scope_kind enum is enforced by the io.adaptiq.titan.auth.ScopeKind enum on
-- the Java side (the manifesto's "no stringly-typed cross-module
-- discriminators" rule).
--
-- The natural key is (user_id, scope_kind, scope_id, role) — a single user
-- can hold MULTIPLE roles on the same scope (highest-role-wins is computed
-- by ScopedAuthz at read time, not by deduplicating rows here). The grant
-- helper is idempotent via ON CONFLICT DO NOTHING.
--
-- Coexistence with V35 user_roles: V35 stays as the "global" role table
-- (no scope). ScopedAuthz checks the scoped table first for the requested
-- scope, then falls back to the global table so existing ADMIN seeds keep
-- working with no migration. The next ticket will lift V35 into
-- rbac_user_role with scope_kind = ORG and scope_id = 'global' and drop
-- the fallback; for this slice we keep both alive.
--
-- Backfill: nothing automatic — admins seed via SQL until the user-management
-- UI lands (separate ticket).

CREATE TABLE IF NOT EXISTS titan.rbac_user_role (
  user_id     VARCHAR(255) NOT NULL,
  scope_kind  VARCHAR(16)  NOT NULL,
  scope_id    VARCHAR(255) NOT NULL,
  role        VARCHAR(64)  NOT NULL,
  granted_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (user_id, scope_kind, scope_id, role)
);

CREATE INDEX IF NOT EXISTS idx_rbac_user_role_lookup
  ON titan.rbac_user_role (user_id, scope_kind, scope_id);

CREATE INDEX IF NOT EXISTS idx_rbac_user_role_scope
  ON titan.rbac_user_role (scope_kind, scope_id);
