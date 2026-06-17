-- Titan schema V42 — audit-log retention policy (#1104).
--
-- titan.audit_log (V20) grows unbounded. On a busy rig that is 1M+ rows after a
-- few months → query slowdown + disk pressure. This table holds a per-event-kind
-- retention policy that the nightly RetentionJob applies: rows older than the
-- per-kind max_age_days are best-effort purged in batches.
--
-- Model:
--   * kind is the audit action code (the VARCHAR stored in titan.audit_log.action,
--     1:1 with the AuditAction enum on the Java side) OR the reserved sentinel
--     '*' which carries the server-wide default applied to every kind that has no
--     row of its own. '*' is never a real action code, so it can never collide
--     with a concrete kind. Resolution is kind-specific > default ('*').
--   * max_age_days is the retention horizon in days. CHECK (> 0): a 0-day policy
--     would purge the entire history of a kind on the next sweep — that is an
--     operator misconfiguration, not a feature, so the DB refuses it. The
--     override endpoint rejects it with 400 and the resolver raises a typed error
--     (defence in depth against a manual DB edit).
--   * updated_at is bumped on every override so an operator can see when a policy
--     last changed.
--
-- Seed (the acceptance-criteria defaults):
--   * '*'           → 90 days  (the default for most kinds).
--   * auth/security → 365 days (compliance-sensitive; longer trail). Seeded for
--     EVERY security-relevant AuditAction code that exists today:
--       - credential lifecycle:  PAT_CREATE, PAT_REVOKE, PAT_SCOPE_DENIED
--       - authorization decisions: RBAC_CHECK, BUILD_RERUN, PIPELINE_EDIT
--         (all three emit ALLOWED/DENIED RBAC outcomes per their enum javadoc —
--         the deny row is exactly the trail an auditor asks for)
--       - admin SSO role config:  SSO_MAPPING_CREATE, SSO_MAPPING_UPDATE,
--         SSO_MAPPING_DELETE (who changed which group→role mapping, and when —
--         a 90-day window would silently drop role-change history after 3 months)
--     New security kinds get their own seed row in a later migration (forward-only).
--
-- Index: (action, occurred_at) on titan.audit_log makes the per-kind
-- "older than cutoff" batch delete an index range scan instead of a seq scan —
-- the whole point of the feature is not to thrash a huge table.
--
-- Portable across H2 (test) and PostgreSQL (prod): VARCHAR, INTEGER, TIMESTAMP,
-- CHECK — same shape as titan.rbac_audit (V40).

CREATE TABLE titan.audit_retention_policy (
    kind          VARCHAR(64)  PRIMARY KEY,
    max_age_days  INTEGER      NOT NULL,
    updated_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_audit_retention_max_age_positive CHECK (max_age_days > 0)
);

-- Server-wide default — applied to any kind without its own row.
INSERT INTO titan.audit_retention_policy (kind, max_age_days) VALUES ('*', 90);

-- Compliance-sensitive auth/security kinds — longer trail.
-- Credential lifecycle.
INSERT INTO titan.audit_retention_policy (kind, max_age_days) VALUES ('PAT_CREATE', 365);
INSERT INTO titan.audit_retention_policy (kind, max_age_days) VALUES ('PAT_REVOKE', 365);
INSERT INTO titan.audit_retention_policy (kind, max_age_days) VALUES ('PAT_SCOPE_DENIED', 365);
-- Authorization decisions (emit ALLOWED/DENIED outcomes).
INSERT INTO titan.audit_retention_policy (kind, max_age_days) VALUES ('RBAC_CHECK', 365);
INSERT INTO titan.audit_retention_policy (kind, max_age_days) VALUES ('BUILD_RERUN', 365);
INSERT INTO titan.audit_retention_policy (kind, max_age_days) VALUES ('PIPELINE_EDIT', 365);
-- Admin SSO group→role mapping changes.
INSERT INTO titan.audit_retention_policy (kind, max_age_days) VALUES ('SSO_MAPPING_CREATE', 365);
INSERT INTO titan.audit_retention_policy (kind, max_age_days) VALUES ('SSO_MAPPING_UPDATE', 365);
INSERT INTO titan.audit_retention_policy (kind, max_age_days) VALUES ('SSO_MAPPING_DELETE', 365);

-- Make the per-kind "older than cutoff" purge an index range scan.
CREATE INDEX idx_audit_log_action_occurred_at
    ON titan.audit_log(action, occurred_at);
