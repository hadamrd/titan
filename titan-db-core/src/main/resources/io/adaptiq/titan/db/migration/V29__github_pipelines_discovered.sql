-- Titan schema V29 — GitHub pipeline discovery (#833, epic #831, design/63).
--
-- Persists the result of GithubRepoScanner walking each repo's `.titan/pipelines/`
-- directory: one row per discovered `.yml` file, with the parsed PipelineModel
-- metadata (name, stages, triggers, parameters) projected to JSON for the UI's
-- "discovered pipelines" view, and a `parse_error` column so a malformed file is
-- recorded loudly rather than crashing the whole repo's scan.
--
-- Lifecycle:
--   * The scanner DELETEs all rows for a (repo_id) before re-inserting — repos
--     where a pipeline file was removed must lose its row, not retain a stale
--     entry. content_sha is the GitHub blob SHA, used by the UI to spot "this
--     file changed since the last scan" without re-parsing.
--   * `last_scanned_at` (on github_repositories) is bumped at the END of every
--     scan attempt, including the 404 / "no .titan/pipelines dir" path — so the
--     freshness signal in the UI does not get stuck on broken repos.
--
-- Portable across H2 (test) and PostgreSQL (prod): BIGINT, VARCHAR, TIMESTAMP only.

CREATE TABLE titan.github_pipelines_discovered (
    id                  BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    repo_id             BIGINT       NOT NULL,
    filename            VARCHAR(512) NOT NULL,
    content_sha         VARCHAR(64)  NOT NULL,
    parsed_metadata     VARCHAR,
    parse_error         VARCHAR,
    last_scanned_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_github_pipeline_repo
        FOREIGN KEY (repo_id) REFERENCES titan.github_repositories(repo_id)
        ON DELETE CASCADE,

    CONSTRAINT uq_github_pipeline_repo_file UNIQUE (repo_id, filename)
);

CREATE INDEX idx_github_pipeline_repo ON titan.github_pipelines_discovered(repo_id);
