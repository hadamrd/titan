-- Postgres-only partial index on active (non-revoked) tokens (#434). The
-- list/lookup hot path filters by user_subject AND revoked_at IS NULL; a
-- partial index keeps it O(log active-tokens) even as revoked rows
-- accumulate. H2 PG-mode rejects WHERE on CREATE INDEX, so the partial
-- form ships here (same pattern as V15_1).
CREATE INDEX idx_pat_active
    ON titan.personal_access_tokens (user_subject)
    WHERE revoked_at IS NULL;
