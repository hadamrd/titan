-- Titan schema V31 — branch-aware GitHub pipeline discovery (#887, design/65 follow-up).
--
-- GitHub Actions evaluates workflows against the tree-of-commit, not the default-branch tree.
-- Today every `github_pipelines_discovered` row caches the default branch's parse, so a YAML
-- edited on `feature/x` doesn't take effect until merge. This migration widens the table key:
--
--   OLD: one row per (repo_id, filename)        → default-branch-only
--   NEW: one row per (repo_id, branch, filename) → per-branch parses feed per-event builds
--
-- Per design 65: the default-branch row remains the canonical one for UI display purposes
-- (`GET /api/v1/github-app/installations` keeps showing default-branch rows). Per-branch rows
-- exist to feed per-push build dispatch without polluting the user-facing pipeline list.
--
-- Backfill policy: existing rows are stamped with the repo's `default_branch`, falling back to
-- 'main' when the repo row has no default_branch set. The demo rig today only has main-branch
-- rows so this is a no-op in practice; the explicit backfill is defensive.
--
-- Portable across H2 (test) and PostgreSQL (prod): BIGINT, VARCHAR, TIMESTAMP only.

-- 1. Add the column nullable so existing rows survive the ADD COLUMN.
ALTER TABLE titan.github_pipelines_discovered
    ADD COLUMN branch VARCHAR(255);

-- 2. Backfill from github_repositories.default_branch where possible.
UPDATE titan.github_pipelines_discovered d
SET branch = COALESCE(
    (SELECT r.default_branch FROM titan.github_repositories r WHERE r.repo_id = d.repo_id),
    'main')
WHERE d.branch IS NULL;

-- 3. Belt-and-suspenders for any row whose repo no longer exists (FK is ON DELETE CASCADE so
--    this should be empty, but the NOT NULL constraint below would still fail without it).
UPDATE titan.github_pipelines_discovered SET branch = 'main' WHERE branch IS NULL;

-- 4. Lock it down: NOT NULL + default for any future stray INSERTs that forget the column.
ALTER TABLE titan.github_pipelines_discovered
    ALTER COLUMN branch SET NOT NULL;

ALTER TABLE titan.github_pipelines_discovered
    ALTER COLUMN branch SET DEFAULT 'main';

-- 5. Replace the (repo_id, filename) UNIQUE constraint with (repo_id, branch, filename).
ALTER TABLE titan.github_pipelines_discovered
    DROP CONSTRAINT uq_github_pipeline_repo_file;

ALTER TABLE titan.github_pipelines_discovered
    ADD CONSTRAINT uq_github_pipeline_repo_branch_file UNIQUE (repo_id, branch, filename);

-- 6. Helper index for the "list this repo's default-branch rows for the UI" query, which is
--    the hot read path. The existing idx_github_pipeline_repo covers (repo_id) listings.
CREATE INDEX idx_github_pipeline_repo_branch
    ON titan.github_pipelines_discovered(repo_id, branch);
