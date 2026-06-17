-- V30 — durable single-row store for server-generated secrets that must survive restarts
-- without being rotated by accident. Closes #849: the artifact-download HMAC signer needs a
-- stable secret across boots so a presigned URL minted by one instance verifies against another
-- (k3s rig: server pods are replicated; a token must be portable for its 5-minute TTL).
--
-- Schema rationale:
--   * `name` PRIMARY KEY — single row per logical secret (e.g. 'artifact-download-hmac').
--     Avoids hard-coding "always row id=1" magic and lets future secrets co-tenant the table.
--   * `value` is the base64-url-encoded secret bytes; never logged, never API-projected.
--   * `created_at` is purely audit/diagnostics — we never rotate-by-age automatically; rotation
--     is an operator action (DELETE the row, restart, the signer reseeds).
--
-- Override path: the runtime `titan.artifact.download.secret` config wins over the DB-seeded
-- value when set (e.g. operator pins a shared secret across a fleet via env var). The DB row is
-- the fallback so a single-node dev rig "just works" without env wiring.

-- NOTE: column is `secret_value`, not `value`. The latter is a reserved keyword in H2 (used by
-- our unit-test in-memory DB) and would unquoted-parse-fail the SELECT. Postgres would tolerate
-- `value` unquoted but consistency across both engines wins.
CREATE TABLE IF NOT EXISTS titan.system_secret (
  name          VARCHAR(64) PRIMARY KEY,
  secret_value  TEXT        NOT NULL,
  created_at    TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
