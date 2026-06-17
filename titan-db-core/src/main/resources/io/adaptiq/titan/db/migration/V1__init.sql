-- Titan execution-engine schema — squashed baseline (was V8__execution_engine.sql).
--
-- The 7 engine tables for the stateless, multi-master pipeline engine.
-- See design/23-schema-spec.md for the full specification (locked under DEC-036).
--
-- Portable across H2 (embedded/test) and PostgreSQL (production).
-- VARCHAR for text blobs, TIMESTAMP for times, snake_case names,
-- BIGINT GENERATED ALWAYS AS IDENTITY for surrogate keys.
--
-- Tables live in the dedicated `titan` schema. The historical `rf_` table-name
-- prefix is dropped — schema-qualification (titan.jobs) now disambiguates the
-- engine tables from the CD tables in the `releaseflow` schema.

CREATE SCHEMA IF NOT EXISTS titan;

-- =====================================================================
-- jobs — Job configuration (replaces config.xml + XStream).
-- One row per pipeline job.
-- =====================================================================
CREATE TABLE titan.jobs (
    id              BIGINT        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    full_name       VARCHAR(512)  NOT NULL UNIQUE,
    display_name    VARCHAR(255),
    folder_path     VARCHAR(512),
    pipeline_script VARCHAR          NOT NULL,
    config_json     VARCHAR          NOT NULL DEFAULT '{}',
    created_by      VARCHAR(255),
    created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    enabled         BOOLEAN       NOT NULL DEFAULT TRUE
);

CREATE INDEX idx_jobs_folder  ON titan.jobs(folder_path);
CREATE INDEX idx_jobs_enabled ON titan.jobs(enabled);

-- =====================================================================
-- builds — Build records (replaces build.xml).
-- One row per build execution.
-- =====================================================================
CREATE TABLE titan.builds (
    id                  BIGINT        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    job_id              BIGINT        NOT NULL,
    build_number        INT           NOT NULL,
    status              VARCHAR(16)   NOT NULL DEFAULT 'QUEUED'
                        CHECK (status IN ('QUEUED','RUNNING','SUCCESS',
                                          'FAILED','ABORTED','UNSTABLE')),
    parameters_json     VARCHAR,
    triggered_by        VARCHAR(255),
    trigger_type        VARCHAR(32),
    deployment_id       BIGINT,
    queued_at           TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at          TIMESTAMP,
    finished_at         TIMESTAMP,
    duration_ms         BIGINT,
    error_message       VARCHAR,
    pipeline_model_json VARCHAR,
    started_by_instance VARCHAR(64),

    CONSTRAINT fk_builds_job
        FOREIGN KEY (job_id) REFERENCES titan.jobs(id) ON DELETE CASCADE,
    CONSTRAINT uq_builds_job_number UNIQUE (job_id, build_number)
);

CREATE INDEX idx_builds_job_queued  ON titan.builds(job_id, queued_at DESC);
CREATE INDEX idx_builds_status      ON titan.builds(status);
CREATE INDEX idx_builds_deployment  ON titan.builds(deployment_id);

-- =====================================================================
-- task_queue — The task queue (heart of the stateless engine).
-- Workers claim tasks via SELECT FOR UPDATE SKIP LOCKED.
-- =====================================================================
-- `type`     — START_PIPELINE bakes a build's DAG; EXECUTE_COMMAND runs a leaf step
--               on an agent; ORCHESTRATE advances a build's DAG; CANCEL_TASK aborts.
-- `status`   — QUEUED -> CLAIMED -> PROCESSING -> COMPLETED|FAILED|CANCELLED. A
--               reaped task goes CLAIMED/PROCESSING -> QUEUED (retry) or -> FAILED.
-- `claim_token` — the lease token (doc-26 Tier A, doc-27 G3). Written on claim,
--               verified on completion; a zombie worker's stale completion is
--               rejected because its token no longer matches.
-- `available_at` — visibility / delayed-delivery gate: a task is claimable only
--               once `available_at <= now()`. The reaper bumps it on re-queue.
CREATE TABLE titan.task_queue (
    id                         BIGINT        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    type                       VARCHAR(32)   NOT NULL
                               CHECK (type IN ('START_PIPELINE','EXECUTE_COMMAND',
                                               'ORCHESTRATE','CANCEL_TASK',
                                               'EXECUTE','CANCEL')),
    queue_name                 VARCHAR(128)  NOT NULL DEFAULT 'default',
    status                     VARCHAR(16)   NOT NULL DEFAULT 'QUEUED'
                               CHECK (status IN ('QUEUED','CLAIMED','PROCESSING',
                                                 'COMPLETED','FAILED',
                                                 'TIMED_OUT','CANCELLED')),
    priority                   INT           NOT NULL DEFAULT 0,
    payload_json               VARCHAR          NOT NULL,
    result_json                VARCHAR,
    attempts                   INT           NOT NULL DEFAULT 0,
    max_attempts               INT           NOT NULL DEFAULT 3,
    visibility_timeout_seconds INT           NOT NULL DEFAULT 3600,
    claim_token                UUID,
    claimed_by                 VARCHAR(128),
    claimed_at                 TIMESTAMP,
    available_at               TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    build_id                   BIGINT,
    node_id                    VARCHAR(64),
    task_token                 UUID          NOT NULL DEFAULT gen_random_uuid(),
    created_at                 TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at               TIMESTAMP,

    CONSTRAINT fk_task_queue_build
        FOREIGN KEY (build_id) REFERENCES titan.builds(id) ON DELETE CASCADE
);

-- Workers poll this index. PostgreSQL production additionally gets a *partial*
-- index `ix_task_queue_claimable` (WHERE status = 'QUEUED') from the
-- vendor-specific repeatable migration under migration/postgresql/ — H2 does
-- not support partial indexes, so this portable full index is the H2 baseline.
CREATE INDEX idx_taskq_poll ON titan.task_queue(status, queue_name, priority DESC, created_at);
CREATE INDEX ix_task_queue_claimable ON titan.task_queue(queue_name, priority DESC, created_at);
-- Controller polls for orchestration tasks.
CREATE INDEX idx_taskq_orchestrate ON titan.task_queue(status, type, created_at);
-- Visibility-timeout reaper finds claimed tasks that timed out.
CREATE INDEX idx_taskq_timeout ON titan.task_queue(status, claimed_at);
-- Idempotent completion by task_token.
CREATE UNIQUE INDEX idx_taskq_token ON titan.task_queue(task_token);
-- Build correlation.
CREATE INDEX idx_taskq_build ON titan.task_queue(build_id);

-- =====================================================================
-- task_archive — Completed/failed tasks moved here by the reaper.
-- Same columns as task_queue but NO identity generation (ids are
-- copied) and only minimal indexes for audit queries.
-- =====================================================================
CREATE TABLE titan.task_archive (
    id                         BIGINT        NOT NULL PRIMARY KEY,
    type                       VARCHAR(32)   NOT NULL,
    queue_name                 VARCHAR(128)  NOT NULL DEFAULT 'default',
    status                     VARCHAR(16)   NOT NULL,
    priority                   INT           NOT NULL DEFAULT 0,
    payload_json               VARCHAR          NOT NULL,
    result_json                VARCHAR,
    attempts                   INT           NOT NULL DEFAULT 0,
    max_attempts               INT           NOT NULL DEFAULT 3,
    visibility_timeout_seconds INT           NOT NULL DEFAULT 3600,
    claim_token                UUID,
    claimed_by                 VARCHAR(128),
    claimed_at                 TIMESTAMP,
    available_at               TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    build_id                   BIGINT,
    node_id                    VARCHAR(64),
    task_token                 UUID          NOT NULL,
    created_at                 TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at               TIMESTAMP
);

CREATE INDEX idx_task_archive_build     ON titan.task_archive(build_id);
CREATE INDEX idx_task_archive_completed ON titan.task_archive(completed_at DESC);

-- =====================================================================
-- flow_nodes — Execution DAG (stages / steps / parallel branches).
-- Composite PK: (build_id, node_id).
-- =====================================================================
CREATE TABLE titan.flow_nodes (
    build_id        BIGINT        NOT NULL,
    node_id         VARCHAR(64)   NOT NULL,
    parent_ids      VARCHAR(512),
    node_type       VARCHAR(16)   NOT NULL
                    CHECK (node_type IN ('STAGE','STEP','PARALLEL_BRANCH')),
    display_name    VARCHAR(255),
    step_descriptor VARCHAR(128),
    step_args_json  VARCHAR,
    status          VARCHAR(16)   NOT NULL DEFAULT 'PENDING'
                    CHECK (status IN ('PENDING','QUEUED','RUNNING',
                                      'SUCCESS','FAILED','ABORTED','SKIPPED')),
    agent_id        VARCHAR(128),
    agent_label     VARCHAR(128),
    started_at      TIMESTAMP,
    completed_at    TIMESTAMP,
    duration_ms     BIGINT,
    result_json     VARCHAR,
    log_task_id     UUID,

    PRIMARY KEY (build_id, node_id),
    CONSTRAINT fk_flow_nodes_build
        FOREIGN KEY (build_id) REFERENCES titan.builds(id) ON DELETE CASCADE
);

CREATE INDEX idx_flow_nodes_parent ON titan.flow_nodes(build_id, parent_ids);
CREATE INDEX idx_flow_nodes_status ON titan.flow_nodes(build_id, status);

-- =====================================================================
-- agents — Worker registry (heartbeat-based liveness).
-- =====================================================================
-- `num_executors`  — executor slots. Cosmetic for Titan
--                    (doc-27 G5): the queue's SKIP LOCKED claim, not the
--                    executor count, prevents over-assignment. Default 1.
-- `max_concurrent` — retained legacy cap; kept for back-compat.
-- `last_heartbeat` — liveness signal. `register()` stamps it to now() so a
--                    freshly-registered agent never reads offline (doc-27 G1).
CREATE TABLE titan.agents (
    agent_id          VARCHAR(128)  PRIMARY KEY,
    display_name      VARCHAR(255),
    labels            VARCHAR(512),
    status            VARCHAR(16)   NOT NULL DEFAULT 'ONLINE'
                      CHECK (status IN ('ONLINE','OFFLINE','BUSY','DRAINING')),
    num_executors     INT           NOT NULL DEFAULT 1,
    max_concurrent    INT           NOT NULL DEFAULT 2,
    current_tasks     INT           NOT NULL DEFAULT 0,
    os_info           VARCHAR(255),
    java_version      VARCHAR(64),
    capabilities_json VARCHAR,
    last_heartbeat    TIMESTAMP,
    registered_at     TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen_by      VARCHAR(64),
    endpoint_url      VARCHAR(512)
);

CREATE INDEX idx_agents_status    ON titan.agents(status);
CREATE INDEX idx_agents_heartbeat ON titan.agents(last_heartbeat);

-- =====================================================================
-- logs — Build log storage (chunked, per-task).
-- =====================================================================
CREATE TABLE titan.logs (
    id          BIGINT        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    task_id     UUID          NOT NULL,
    chunk_index INT           NOT NULL,
    stream      VARCHAR(8)    NOT NULL DEFAULT 'stdout'
                CHECK (stream IN ('stdout','stderr','system')),
    data        VARCHAR          NOT NULL,
    produced_at TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    is_final    BOOLEAN       NOT NULL DEFAULT FALSE,

    CONSTRAINT uq_logs_task_chunk UNIQUE (task_id, chunk_index)
);

CREATE INDEX idx_logs_task_chunk ON titan.logs(task_id, chunk_index);
