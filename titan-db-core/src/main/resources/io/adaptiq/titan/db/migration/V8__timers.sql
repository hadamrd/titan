-- Titan durable timer subsystem — controller-native scheduled wake-ups.
-- A timer is the persisted fact "at fire_at, re-evaluate build_id/node_id".
-- The TimerSweeper claims due ARMED rows, enqueues an ORCHESTRATE/ADVANCE task,
-- and marks the timer FIRED. The kind-specific work is the orchestrator's job.
-- See docs/superpowers/specs/2026-05-18-titan-timer-subsystem-design.md.
--
-- Portable DDL: VARCHAR for unbounded text, TIMESTAMP for times, snake_case
-- names, BIGINT GENERATED ALWAYS AS IDENTITY surrogate keys, named constraints.

CREATE TABLE titan.timers (
    id           BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    build_id     BIGINT       NOT NULL,
    node_id      VARCHAR(255) NOT NULL,
    kind         VARCHAR(16)  NOT NULL
                 CHECK (kind IN ('SLEEP','TIMEOUT','RETRY_BACKOFF','GATE_RESUME')),
    fire_at      TIMESTAMP    NOT NULL,
    status       VARCHAR(16)  NOT NULL DEFAULT 'ARMED'
                 CHECK (status IN ('ARMED','CLAIMED','FIRED','CANCELLED')),
    payload_json VARCHAR,
    claim_token  VARCHAR(64),
    claimed_at   TIMESTAMP,
    created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    fired_at     TIMESTAMP
);

-- The sweeper's claim query: due ARMED rows, oldest fire_at first.
CREATE INDEX idx_timers_due ON titan.timers(status, fire_at);
-- Idempotent arm + build-wide cancel.
CREATE INDEX idx_timers_target ON titan.timers(build_id, node_id, kind);
-- The reclaim query: stale CLAIMED rows by claimed_at.
CREATE INDEX idx_timers_stale ON titan.timers(status, claimed_at);
