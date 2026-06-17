-- V35__user_roles.sql
-- Issue #1121 — first enforceable RBAC slice.
--
-- A flat (user_id, role) table. v1 supports exactly four roles:
--   ADMIN, MAINTAINER, DEVELOPER, VIEWER
-- The policy table (action -> required roles) is hardcoded in Java in
-- io.adaptiq.titan.auth.Authz; this DB table only stores WHICH roles a
-- user holds. Future #1114 will lift roles into groups + per-folder scoping;
-- this migration is intentionally minimal.
--
-- Default-deny: a caller with no row here is treated as VIEWER by the
-- Authz helper (NOT an implicit DEVELOPER). The seam exists so we can ship
-- the helper without a user-management UI in v1.

CREATE TABLE IF NOT EXISTS titan.user_roles (
  user_id    VARCHAR(255) NOT NULL,
  role       VARCHAR(64)  NOT NULL,
  granted_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (user_id, role)
);

CREATE INDEX IF NOT EXISTS idx_user_roles_user ON titan.user_roles (user_id);
