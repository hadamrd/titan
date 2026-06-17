-- V36__scm_event_cursor.sql
-- Issue #1118 — webhook reconcile loop.
--
-- Two tables that together implement the Axis-2 "catch missed webhook deliveries" guarantee:
--
--   scm_event_cursor — per-(provider, repo) high-water-mark. The reconcile loop reads
--     last_event_id to ask the provider "give me events strictly newer than X" and writes
--     the latest dispatched id back. last_event_at + last_reconciled_at are operator-facing
--     timestamps surfaced by the lag gauge.
--
--   scm_event_seen — idempotency boundary. EVERY dispatch path (webhook hot-path + reconcile
--     replay) inserts a row here BEFORE enqueuing the build. The unique constraint on
--     (provider, event_id) is the single source of truth for dedupe: a second writer hitting
--     the same (provider, event_id) gets a duplicate-key error and skips dispatch. Two paths,
--     one row → exactly-once dispatch (closes the canonical reconcile race condition).
--
-- Forward-only. Indices created with IF NOT EXISTS so a re-run is safe.

CREATE TABLE IF NOT EXISTS titan.scm_event_cursor (
  provider             VARCHAR(32)  NOT NULL,
  repo_external_id     VARCHAR(255) NOT NULL,
  last_event_id        VARCHAR(255),
  last_event_at        TIMESTAMP,
  last_reconciled_at   TIMESTAMP,
  PRIMARY KEY (provider, repo_external_id)
);

CREATE TABLE IF NOT EXISTS titan.scm_event_seen (
  provider    VARCHAR(32)  NOT NULL,
  event_id    VARCHAR(255) NOT NULL,
  seen_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  source      VARCHAR(16)  NOT NULL,  -- 'webhook' or 'reconcile'
  PRIMARY KEY (provider, event_id)
);

CREATE INDEX IF NOT EXISTS idx_scm_event_seen_seen_at
  ON titan.scm_event_seen (seen_at);
