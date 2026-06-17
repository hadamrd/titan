-- Titan build triggers (design/50) — per-trigger runtime state.
--
-- Trigger *definitions* (the cron spec, tz, the minted UUID) live in
-- titan.jobs.config_json as a plain JSON DTO — they are job configuration.
-- This table holds only the *mutable runtime state* a trigger accumulates as
-- it fires: keeping last_fired_at out of the config_json blob avoids a
-- whole-row rewrite per fire and the lost-update race against a concurrent
-- Configure save (design/50 D6).
--
-- The firing engine (TriggerEngine, design/50 D4) decides dueness from the
-- last_fired_at baseline; a row absent for a (job, trigger) pair means "never
-- fired" and the engine stamps the baseline on the trigger's first sight.
--
-- Portable DDL: VARCHAR/TIMESTAMP/BIGINT, no partial index (H2 + PostgreSQL).
CREATE TABLE titan.job_triggers (
    job_id        BIGINT       NOT NULL,
    trigger_id    VARCHAR(64)  NOT NULL,
    last_fired_at TIMESTAMP,
    last_error    VARCHAR,

    PRIMARY KEY (job_id, trigger_id),
    CONSTRAINT fk_job_triggers_job
        FOREIGN KEY (job_id) REFERENCES titan.jobs(id) ON DELETE CASCADE
);
