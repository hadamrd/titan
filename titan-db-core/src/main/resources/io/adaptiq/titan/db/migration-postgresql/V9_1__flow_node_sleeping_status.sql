-- Durable sleep (timer subsystem, Phase 2): add SLEEPING to the flow_nodes
-- status CHECK. PostgreSQL-only — production and the orchestrator ITs run
-- PostgreSQL, where the inline column CHECK is reliably named
-- `flow_nodes_status_check`. H2 DAO tests never write the SLEEPING status, so
-- the portable schema does not need this change.

ALTER TABLE titan.flow_nodes DROP CONSTRAINT flow_nodes_status_check;

ALTER TABLE titan.flow_nodes ADD CONSTRAINT flow_nodes_status_check
    CHECK (status IN ('PENDING','QUEUED','RUNNING','SUCCESS','FAILED','ABORTED','SKIPPED','SLEEPING'));
