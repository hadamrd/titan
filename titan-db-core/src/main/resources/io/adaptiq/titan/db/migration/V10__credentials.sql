-- Titan-owned encrypted secrets store (closes #274).
--
-- Credential lookup on the dispatch path: a
-- credential row holds a SecretCipher-sealed value (AES-256-GCM, design/39
-- §3.1) keyed by (scope, key). The plaintext is never written here — the
-- sealing key comes from CredentialKeyProvider.active() out-of-band.
--
-- `scope` is a free-form selector: "global", "folder:/foo", "job:my-pipeline".
-- A future resolution rule walks scopes from most-specific to "global".
--
-- Portable across H2 (test) and PostgreSQL (prod): BIGINT IDENTITY, VARCHAR,
-- TIMESTAMP — the same shape as titan.jobs (V1).

CREATE TABLE titan.credentials (
    id            BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    kind          VARCHAR(32)  NOT NULL
                  CHECK (kind IN ('USERNAME_PASSWORD','SSH_KEY','STRING','FILE')),
    scope         VARCHAR(255) NOT NULL,
    cred_key      VARCHAR(255) NOT NULL,
    sealed_value  VARCHAR      NOT NULL,
    aad           VARCHAR(255) NOT NULL,
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX uq_credentials_scope_key ON titan.credentials(scope, cred_key);
CREATE INDEX        idx_credentials_scope   ON titan.credentials(scope);
