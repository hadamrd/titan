-- Per-job SCM discovery state (closes #275).
--
-- Records the last commit SHA seen by the @Scheduled DiscoveryService for each
-- job that declares an scm.url / scm.branch in its config_json. On each poll
-- tick the service queries the remote HEAD (git ls-remote-style) and compares
-- against last_seen_sha; a difference triggers a build via the same path as
-- the manual/webhook trigger.
--
-- One row per job_id (primary key). A job without an scm block has no row.
-- last_polled_at stamps the last successful HEAD lookup; last_error stamps the
-- last failure message (null on success). Both null = never polled.
--
-- Portable across H2 (test) and PostgreSQL (prod): BIGINT, VARCHAR, TIMESTAMP.

CREATE TABLE titan.discovery_state (
    job_id          BIGINT       PRIMARY KEY REFERENCES titan.jobs(id) ON DELETE CASCADE,
    last_seen_sha   VARCHAR(128),
    last_polled_at  TIMESTAMP,
    last_status     VARCHAR(16),
    last_error      VARCHAR,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
