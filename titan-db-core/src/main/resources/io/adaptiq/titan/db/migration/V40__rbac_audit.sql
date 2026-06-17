-- V40__rbac_audit.sql
-- Issue #1131 — dedicated audit table for @RequiresRole-gated calls (epic #1114).
--
-- titan.audit_log (V20) is the generic, queryable trail used by the rest of
-- the app — its target_type enum has no ORG/REPO yet, and the RBAC details
-- shape is shoehorned into a JSON blob. The customer story ("an audit log
-- showing who did what" — multi-tenant security-review evidence) needs a
-- typed, query-friendly table:
--
--   * scope_kind is a first-class column (queryable: "every check on REPO:42").
--   * required_role / effective_role are columns (queryable: "every DENY where
--     required=ADMIN and effective=DEVELOPER" — the "who almost broke prod"
--     report).
--   * decision is a CHECK-constrained enum so the SQL surface can group by
--     allow / deny without parsing JSON.
--
-- We KEEP writing the generic titan.audit_log row in parallel (so existing
-- /api/v1/audit listings keep working unchanged) and ADD a row here on every
-- ScopedAuthz#requires call. The two tables are not transactionally linked
-- because the audit emission itself is best-effort (failures cannot block
-- the underlying request).
--
-- Schema notes:
--   * effective_role is NULLABLE — adversarial spec calls for "user with no
--     row at all → effective recorded as null or VIEWER". The interceptor
--     records VIEWER (the resolved default), but the column allows null for
--     future code paths that want to distinguish "no row" from "explicit
--     VIEWER grant".
--   * endpoint is the JAX-RS Path of the gated method (e.g.
--     "/api/v1/builds/{id}/cancel"); a textual label, not enforced FK.
--   * user_id matches the V35 / V38 convention (OIDC preferred_username or
--     sub; never the bearer token). NULL when the caller was anonymous —
--     the filter emits a DENY row in that case so unauthenticated probes are
--     traceable.

CREATE TABLE IF NOT EXISTS titan.rbac_audit (
    id              BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    occurred_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    user_id         VARCHAR(255),
    endpoint        VARCHAR(255) NOT NULL,
    scope_kind      VARCHAR(16)  NOT NULL,
    scope_id        VARCHAR(255) NOT NULL,
    required_role   VARCHAR(32)  NOT NULL,
    effective_role  VARCHAR(32),
    decision        VARCHAR(8)   NOT NULL,
    CONSTRAINT ck_rbac_audit_decision CHECK (decision IN ('ALLOW', 'DENY'))
);

CREATE INDEX IF NOT EXISTS idx_rbac_audit_occurred_at
    ON titan.rbac_audit (occurred_at);

CREATE INDEX IF NOT EXISTS idx_rbac_audit_user
    ON titan.rbac_audit (user_id, occurred_at);

CREATE INDEX IF NOT EXISTS idx_rbac_audit_scope
    ON titan.rbac_audit (scope_kind, scope_id, occurred_at);

CREATE INDEX IF NOT EXISTS idx_rbac_audit_decision
    ON titan.rbac_audit (decision, occurred_at);
