-- Titan schema V24 — per-user starred (pinned) jobs (#703).
--
-- Lets every authenticated user pin up to 10 jobs to the sidebar 'Starred'
-- section for one-click access without scrolling /jobs. Ownership is the OIDC
-- subject claim — even ADMIN cannot see another user's stars, mirroring the
-- PAT row-level security model (V18).
--
-- Schema invariants:
--   * (user_subject, job_id) is the natural PK — double-star is a no-op.
--   * ON DELETE CASCADE on jobs.id — when a job is deleted, every user's
--     stale star row vanishes automatically. Without this, the list endpoint
--     would have to LEFT JOIN + filter dead rows on every call.
--   * starred_at orders the list view (newest-pin first). The composite index
--     (user_subject, starred_at DESC) lines up exactly with the listForUser
--     query so it's a single index scan, no sort buffer.
--   * 10-cap is enforced at the API layer (StarredJobsApi), not the DB —
--     a CHECK constraint would couple the cap to schema versioning, and a
--     trigger-counted INSERT would lose the ability to return a clean 409
--     with the current count in the problem body.
--
-- Portable across H2 (test) and PostgreSQL (prod): no PG-only syntax here.
-- TIMESTAMP WITH TIME ZONE is spelled in full (H2 PG-mode rejects TIMESTAMPTZ
-- — see V15/V23 for the same pattern).

CREATE TABLE titan.user_starred_jobs (
    user_subject  VARCHAR(255)              NOT NULL,
    job_id        BIGINT                    NOT NULL,
    starred_at    TIMESTAMP WITH TIME ZONE  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT user_starred_jobs_pk PRIMARY KEY (user_subject, job_id),
    CONSTRAINT user_starred_jobs_job_fk
        FOREIGN KEY (job_id) REFERENCES titan.jobs(id) ON DELETE CASCADE
);

-- Composite index lines up with listForUser's ORDER BY starred_at DESC.
-- The PK already covers (user_subject, job_id) for point lookups (star/unstar).
CREATE INDEX idx_user_starred_jobs_user_starred_at
    ON titan.user_starred_jobs(user_subject, starred_at DESC);
