-- Titan schema V32 — auto-enable discovered pipelines (design/66, follow-up to design/65).
--
-- Design 66: a discovered `.titan/pipelines/*.yml` file IS a job. There is no manual "Enable"
-- step. Going forward GithubRepoScanner upserts a titan.jobs row for every successfully-parsed
-- default-branch file. This migration backfills the same shape for installations whose pipelines
-- were discovered before the scanner gained that behaviour.
--
-- The full_name shape mirrors GithubAppApi#jobMatching:
--     full_name = "<owner>/<name>/<shortName>"   (shortName = filename - .yml/.yaml)
--
-- Skipped rows:
--   * parse_error IS NOT NULL → never auto-enable a malformed YAML.
--   * a row already exists for the same (github_installation_id, github_repo_id) with a matching
--     full_name → preserve the existing manually-created job; we do not overwrite its
--     pipeline_script (the operator may have edited it).
--   * we only backfill against the repo's default branch row (each (repo_id, filename) typically
--     has one default-branch row + zero-or-more feature-branch rows; feature-branch rows must not
--     spawn jobs — see design 66).
--
-- pipeline_script for the backfilled row is left as an empty string and config_json carries the
-- discovered filename — the next scan tick will replace the script with the parsed YAML. This
-- keeps the migration deterministic (no YAML fetch from inside a DDL transaction).
--
-- Portable across H2 (test) and PostgreSQL (prod): BIGINT, VARCHAR, TIMESTAMP only.

INSERT INTO titan.jobs (
    full_name, display_name, folder_path, pipeline_script, config_json,
    created_by, enabled, github_installation_id, github_repo_id
)
SELECT
    r.owner || '/' || r.name || '/' ||
        CASE
            WHEN d.filename LIKE '%.yml'  THEN SUBSTRING(d.filename FROM 1 FOR CHAR_LENGTH(d.filename) - 4)
            WHEN d.filename LIKE '%.yaml' THEN SUBSTRING(d.filename FROM 1 FOR CHAR_LENGTH(d.filename) - 5)
            ELSE d.filename
        END                                                              AS full_name,
    CASE
        WHEN d.filename LIKE '%.yml'  THEN SUBSTRING(d.filename FROM 1 FOR CHAR_LENGTH(d.filename) - 4)
        WHEN d.filename LIKE '%.yaml' THEN SUBSTRING(d.filename FROM 1 FOR CHAR_LENGTH(d.filename) - 5)
        ELSE d.filename
    END                                                                  AS display_name,
    r.owner || '/' || r.name                                             AS folder_path,
    ''                                                                   AS pipeline_script,
    '{"source":"github-app","filename":"' || d.filename || '","branch":"' || d.branch || '"}'
                                                                          AS config_json,
    'github-app:discovery-backfill'                                      AS created_by,
    TRUE                                                                 AS enabled,
    r.install_id                                                         AS github_installation_id,
    r.repo_id                                                            AS github_repo_id
FROM titan.github_pipelines_discovered d
JOIN titan.github_repositories r ON r.repo_id = d.repo_id
WHERE d.parse_error IS NULL
  AND d.branch = COALESCE(r.default_branch, 'main')
  AND NOT EXISTS (
      SELECT 1 FROM titan.jobs j
      WHERE j.github_installation_id = r.install_id
        AND j.github_repo_id          = r.repo_id
        AND j.full_name = r.owner || '/' || r.name || '/' ||
            CASE
                WHEN d.filename LIKE '%.yml'  THEN SUBSTRING(d.filename FROM 1 FOR CHAR_LENGTH(d.filename) - 4)
                WHEN d.filename LIKE '%.yaml' THEN SUBSTRING(d.filename FROM 1 FOR CHAR_LENGTH(d.filename) - 5)
                ELSE d.filename
            END
  );
