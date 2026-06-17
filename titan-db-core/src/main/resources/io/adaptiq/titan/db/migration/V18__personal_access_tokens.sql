-- Titan schema V18 — personal access tokens (#434).
--
-- Per-user API tokens for headless / CLI / CI use. Tokens are bound to the
-- OIDC subject claim (user_subject) so they scope automatically to whoever
-- minted them — even ADMIN users cannot see tokens they did not create.
--
-- Security invariants:
--   * token_hash stores a BCrypt digest (Quarkus Elytron BcryptUtil). The
--     plaintext token is returned EXACTLY ONCE at generation time (POST
--     response) and is never persisted or logged.
--   * prefix is the first 8 chars of the public token (e.g. "titanpat_a3f4")
--     so the UI can disambiguate rows without exposing a usable secret.
--   * revoked_at is a soft-delete tombstone — rows stay in place for audit;
--     verification paths MUST treat revoked_at IS NOT NULL as failed auth.
--
-- Portable across H2 (test) and PostgreSQL (prod): BIGINT IDENTITY, VARCHAR,
-- TIMESTAMP — the same shape as titan.credentials (V10). The partial index
-- on active tokens lives in the migration-postgresql/ overlay (H2 PG-mode
-- rejects WHERE on CREATE INDEX, same pattern as V15_1).

CREATE TABLE titan.personal_access_tokens (
    id            BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_subject  VARCHAR(255) NOT NULL,
    name          VARCHAR(100) NOT NULL,
    token_hash    VARCHAR(255) NOT NULL,
    prefix        VARCHAR(20)  NOT NULL,
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_used_at  TIMESTAMP,
    revoked_at    TIMESTAMP,
    CONSTRAINT pat_user_name_unique UNIQUE (user_subject, name)
);

CREATE INDEX idx_pat_user ON titan.personal_access_tokens(user_subject);
