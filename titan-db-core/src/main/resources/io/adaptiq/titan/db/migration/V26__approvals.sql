-- Titan schema V26 — human-approval parked-step gate (#715).
--
-- The durable counterpart of `gate:` (V1) and `sleep:` (V8). When a pipeline
-- hits an `approval:` step, the controller parks the step's flow node (status
-- SLEEPING + an armed GATE_RESUME timer) and inserts one row here in PENDING.
-- A human's APPROVE / REJECT decision (POST /api/v1/approvals/{id}/...) flips
-- the row terminal and resumes the node SUCCESS / FAILED on the next advance.
-- If the timeout elapses first the sweep tick flips PENDING -> TIMED_OUT and
-- the node resumes FAILED (auto-reject: timeout).
--
-- Invariants:
--   * (build_id, flow_node_id) is the natural key for the in-flight gate, but
--     id is the public handle the REST endpoints use — a build that replays
--     re-parks at the same node and inserts a fresh row, so the (build, node)
--     pair is not unique across history.
--   * approvers_json is a JSON array of subject strings (OIDC preferred_username
--     or sub) or an empty array. An empty array means: any caller holding
--     APPROVE_BUILD or ADMIN may decide. Resolution is the API layer's job
--     (mirror of GateService); the DB stores the list verbatim.
--   * status is a closed enum -- CHECK constraint enforced. decided_by/decided_at
--     are NULL until the row leaves PENDING; the CHECK below requires both or
--     neither.
--   * expires_at is NOT NULL -- there is no "open forever" approval. The default
--     (`24h`) is applied parser-side, not DB-side.
--
-- Portability: BIGINT GENERATED ALWAYS AS IDENTITY (V18 pattern),
-- TIMESTAMP WITH TIME ZONE spelled in full (V23/V24 pattern -- H2 PG-mode
-- rejects TIMESTAMPTZ), no JSONB (CLOB-ish VARCHAR holds the JSON array --
-- approvers lists are small).

CREATE TABLE titan.approvals (
    id              BIGINT                    GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    build_id        BIGINT                    NOT NULL,
    flow_node_id    VARCHAR(255)              NOT NULL,
    prompt          VARCHAR(2048)             NOT NULL,
    approvers_json  VARCHAR(8192)             NOT NULL DEFAULT '[]',
    status          VARCHAR(16)               NOT NULL,
    decided_by      VARCHAR(255),
    decided_at      TIMESTAMP WITH TIME ZONE,
    expires_at      TIMESTAMP WITH TIME ZONE  NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT approvals_status_ck
        CHECK (status IN ('PENDING','APPROVED','REJECTED','TIMED_OUT')),
    CONSTRAINT approvals_decision_pair_ck
        CHECK ((status = 'PENDING' AND decided_by IS NULL AND decided_at IS NULL)
            OR (status <> 'PENDING' AND decided_by IS NOT NULL AND decided_at IS NOT NULL)),
    CONSTRAINT approvals_build_fk
        FOREIGN KEY (build_id) REFERENCES titan.builds(id) ON DELETE CASCADE
);

-- The sweep query (`status = PENDING AND expires_at <= now()`) is the only
-- write-path query that scans many rows; this index keeps it bounded even with
-- a large APPROVED/REJECTED history.
CREATE INDEX idx_approvals_status_expires
    ON titan.approvals(status, expires_at);

-- The orchestrator looks up the in-flight row for (build, node) on every
-- advance() pass when an approval step is encountered -- index for that lookup.
CREATE INDEX idx_approvals_build_node
    ON titan.approvals(build_id, flow_node_id);
