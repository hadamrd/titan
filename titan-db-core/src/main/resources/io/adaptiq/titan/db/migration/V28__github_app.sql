-- Titan schema V28 — GitHub App foundation (#832, epic #831).
--
-- The pull-based GitHub App golden-path onboarding (design/63):
--   * Admin clicks "Create Titan GitHub App" in Titan
--   * Titan POSTs an App Manifest to GitHub; GitHub redirects back with a temp code
--   * Titan exchanges the code for app id + PEM + webhook_secret and persists ONE row
--   * Admin installs the App on an org; installations + repos populate from the API
--
-- Security invariants (CONSTITUTION §6, plaintext-secret ban):
--   * `pem_encrypted` and `webhook_secret_encrypted` carry envelope-encrypted bytes
--     produced by EnvelopeCipher (per-row DEK, KEK from CredentialKeyProvider).
--     They are NEVER returned by any API and NEVER logged.
--   * The single-row constraint on titan.github_app (id = 1, CHECK + PRIMARY KEY)
--     means the manifest-callback handler upserts in place — repeated callbacks
--     update the row rather than creating duplicates (adversarial test row #7).
--
-- Portable across H2 (test) and PostgreSQL (prod): BIGINT, VARCHAR, TIMESTAMP only.

-- =====================================================================
-- titan.github_app — the single registered GitHub App (one per tenant).
-- =====================================================================
CREATE TABLE titan.github_app (
    id                          BIGINT       PRIMARY KEY CHECK (id = 1),
    app_id                      BIGINT       NOT NULL,
    name                        VARCHAR(255) NOT NULL,
    slug                        VARCHAR(255) NOT NULL,
    html_url                    VARCHAR(512) NOT NULL,
    pem_sealed_value            VARCHAR      NOT NULL,
    pem_wrapped_dek             VARCHAR      NOT NULL,
    pem_kek_version             INT          NOT NULL,
    webhook_secret_sealed_value VARCHAR      NOT NULL,
    webhook_secret_wrapped_dek  VARCHAR      NOT NULL,
    webhook_secret_kek_version  INT          NOT NULL,
    created_at                  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- =====================================================================
-- titan.github_installations — one row per org/user the App is installed on.
-- =====================================================================
CREATE TABLE titan.github_installations (
    id              BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    install_id      BIGINT       NOT NULL UNIQUE,
    account_login   VARCHAR(255) NOT NULL,
    account_type    VARCHAR(32)  NOT NULL,
    target_type     VARCHAR(32)  NOT NULL,
    suspended_at    TIMESTAMP,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_github_install_login ON titan.github_installations(account_login);

-- =====================================================================
-- titan.github_repositories — repos visible to a given installation.
-- Populated by the sync endpoint; scanner (Child B) reads from here.
-- =====================================================================
CREATE TABLE titan.github_repositories (
    id              BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    install_id      BIGINT       NOT NULL,
    repo_id         BIGINT       NOT NULL UNIQUE,
    owner           VARCHAR(255) NOT NULL,
    name            VARCHAR(255) NOT NULL,
    default_branch  VARCHAR(255),
    is_private      BOOLEAN      NOT NULL DEFAULT FALSE,
    last_scanned_at TIMESTAMP,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_github_repo_install
        FOREIGN KEY (install_id) REFERENCES titan.github_installations(install_id)
        ON DELETE CASCADE
);

CREATE INDEX idx_github_repo_install ON titan.github_repositories(install_id);
CREATE INDEX idx_github_repo_owner_name ON titan.github_repositories(owner, name);

-- =====================================================================
-- titan.jobs — link jobs to the installation + repo that produced them.
-- NULLABLE: jobs created via the manual onboarding path keep NULL here.
-- =====================================================================
ALTER TABLE titan.jobs ADD COLUMN github_installation_id BIGINT;
ALTER TABLE titan.jobs ADD COLUMN github_repo_id         BIGINT;

CREATE INDEX idx_jobs_github_install ON titan.jobs(github_installation_id);
CREATE INDEX idx_jobs_github_repo    ON titan.jobs(github_repo_id);
