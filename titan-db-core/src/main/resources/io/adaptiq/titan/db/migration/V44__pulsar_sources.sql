-- Titan schema V44 — Pulsar SCM source registration (#1283, convergence axis 2 / scm-depth).
--
-- A "Pulsar source" is the persisted fact "this Pulsar node, reachable at node_url, is a
-- registered SCM connection". The admin onboarding path (backs the Integrations UI card):
--   * Admin enters a Pulsar node URL in the Integrations "Connect" form
--   * Titan probes reachability (GET /_pulsar/repos via PulsarClient) and, on success,
--     persists ONE row with the observed repo_count + last_polled_at
--   * The card lists registered sources and offers a per-source "Sync" that re-probes
--
-- The poll-based PulsarScannerScheduler (#1281), the webhook sink PulsarWebhookApi (#1287),
-- and the ledger 'build' check (#1282) are the runtime ingestion paths; this table is the
-- operator-facing registry that lets the UI render + manage those connections.
--
-- Portable across H2 (test) and PostgreSQL (prod): BIGINT, VARCHAR, INT, TIMESTAMP only.

-- =====================================================================
-- titan.pulsar_sources — one row per registered Pulsar node connection.
-- =====================================================================
CREATE TABLE titan.pulsar_sources (
    id             BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    node_url       VARCHAR(512) NOT NULL UNIQUE,
    node_name      VARCHAR(255),
    repo_count     INT,
    last_polled_at TIMESTAMP,
    created_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
