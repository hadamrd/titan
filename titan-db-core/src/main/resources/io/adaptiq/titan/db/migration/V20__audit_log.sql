-- Titan schema V20 — audit log (#478).
--
-- Auditable record of high-risk actions for the 0.1.0 ship gate. The product
-- needs a queryable trail for: job create/update, build trigger/abort, PAT
-- create/revoke, trigger edits. Today these scatter across Quarkus stdout —
-- this table is the durable, queryable replacement.
--
-- Invariants:
--   * actor is the OIDC preferred_username (preferred) or sub (fallback);
--     never a body-supplied identity. The API layer pulls it from
--     SecurityIdentity.
--   * action is a string code on the wire (e.g. JOB_CREATE, BUILD_TRIGGER)
--     and a sealed enum on the Java side (AuditAction.java).
--   * target_type / target_id identify the row affected (e.g. JOB/42, PAT/7,
--     BUILD/123). target_id is nullable for actions that have no single row
--     target (rare, but reserved).
--   * details_json carries structured per-action payload (e.g.
--     {old_yaml, new_yaml} for edits). NEVER plaintext secrets — the PAT
--     create path records the row id only.
--
-- Indexes:
--   * (occurred_at DESC) — the default /audit list query is "newest first".
--   * (actor, occurred_at DESC) — "what did Alice do?" filter.
--   * (target_type, target_id) — "show me everything that happened to JOB/42".
--
-- Portable across H2 (test) and PostgreSQL (prod): BIGINT IDENTITY, VARCHAR,
-- TIMESTAMP — same shape as titan.personal_access_tokens (V18).

CREATE TABLE titan.audit_log (
    id            BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    occurred_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    actor         VARCHAR(255) NOT NULL,
    action        VARCHAR(64)  NOT NULL,
    target_type   VARCHAR(32)  NOT NULL,
    target_id     VARCHAR(64),
    details_json  VARCHAR(8192)
);

CREATE INDEX idx_audit_log_occurred_at ON titan.audit_log(occurred_at);
CREATE INDEX idx_audit_log_actor ON titan.audit_log(actor, occurred_at);
CREATE INDEX idx_audit_log_target ON titan.audit_log(target_type, target_id);
