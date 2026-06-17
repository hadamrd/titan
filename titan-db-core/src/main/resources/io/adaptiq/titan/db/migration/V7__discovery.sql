-- Titan pipeline discovery — configured sources and the events they produce.
--
-- A discovery_source is a place the engine watches for titan-pipeline.yml files
-- (a local directory, a GitHub repo). The DiscoveryWorker polls each enabled
-- source on a timer; last_polled_at/last_status/last_error hold the mutable
-- poll outcome so the worker need not rewrite the config_json blob per tick.
--
-- A discovery_event is one observed pipeline file at one content revision. The
-- (source_id, repo, commit_sha) dedup constraint makes a re-poll of an
-- unchanged file a no-op: enqueue sees the row already present and inserts
-- nothing. A changed file carries a new commit_sha (blob SHA for GitHub,
-- content hash for a local dir), so it lands as a fresh pending event.
--
-- A 'failed' row of the same tuple is NOT permanently dead: the next scan
-- re-arms it to 'pending' (enqueue), so a transient failure (network blip, DB
-- hiccup) retries instead of stranding that revision forever. attempt_count is
-- incremented on every transition into 'failed'; once it reaches MAX_ATTEMPTS
-- (5, see DiscoveryEventDao) the row is left 'failed' — a genuinely broken
-- pipeline does not retry forever.
--
-- The worker drains events through a token-correlated claim: a per-pass UUID
-- claim_token is stamped onto the rows the claim flips to 'processing', and the
-- worker then SELECTs exactly the rows bearing that token (claimed_at records
-- when the row was claimed). A tick that crashes mid-drain leaves rows stuck
-- 'processing'; reclaimStale sweeps those whose claimed_at is older than a
-- cutoff back to 'pending' — a designed recovery path, not an accident.
--
-- The claim is a portable UPDATE ... WHERE id IN (SELECT ... LIMIT n) — no FOR
-- UPDATE SKIP LOCKED, so the DDL and the claim both run unchanged on H2
-- (MODE=PostgreSQL) and PostgreSQL.
--
-- Portable DDL: VARCHAR for unbounded text, TIMESTAMP for times, snake_case
-- names, BIGINT GENERATED ALWAYS AS IDENTITY surrogate keys, named constraints.

-- =====================================================================
-- discovery_sources — a watched place, plus its last poll outcome.
-- One row per configured source; keyed by its unique name.
-- =====================================================================
CREATE TABLE titan.discovery_sources (
    id             BIGINT        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name           VARCHAR(255)  NOT NULL UNIQUE,
    source_type    VARCHAR(32)   NOT NULL,
    config_json    VARCHAR,
    enabled        BOOLEAN       NOT NULL DEFAULT TRUE,
    last_polled_at TIMESTAMP,
    last_status    VARCHAR(32),
    last_error     VARCHAR,
    created_at     TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- =====================================================================
-- discovery_events — one observed pipeline file at one content revision.
-- The worker claims pending events, parses the YAML, upserts a TitanJob.
-- =====================================================================
CREATE TABLE titan.discovery_events (
    id              BIGINT        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    source_id       BIGINT        NOT NULL,
    repo            VARCHAR(512)  NOT NULL,
    branch          VARCHAR(255),
    commit_sha      VARCHAR(64),
    event_type      VARCHAR(32)   NOT NULL,
    payload_json    VARCHAR,
    status          VARCHAR(16)   NOT NULL DEFAULT 'pending'
                    CHECK (status IN ('pending','processing','done','failed')),
    received_at     TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at    TIMESTAMP,
    outcome_message VARCHAR,
    -- How many times this event has been processed; incremented on every
    -- transition into 'failed'. Caps retry of a genuinely broken pipeline.
    attempt_count   INT           NOT NULL DEFAULT 0,
    -- UUID of the worker pass that currently owns the row; NULL when unclaimed.
    claim_token     VARCHAR(64),
    -- When the row was moved to 'processing'; drives the stale-claim sweep.
    claimed_at      TIMESTAMP,

    CONSTRAINT fk_discovery_events_source
        FOREIGN KEY (source_id) REFERENCES titan.discovery_sources(id) ON DELETE CASCADE,
    CONSTRAINT uq_discovery_events_dedup UNIQUE (source_id, repo, commit_sha)
);

-- Worker drains the queue oldest-pending-first; the same (status, ...) index
-- also serves the stale-claim sweep, which scans status='processing' rows by
-- claimed_at — so claimed_at trails received_at in the index key.
CREATE INDEX idx_discovery_events_status
    ON titan.discovery_events(status, received_at, claimed_at);
-- Per-source lookups (observability, listBySource).
CREATE INDEX idx_discovery_events_source ON titan.discovery_events(source_id);
