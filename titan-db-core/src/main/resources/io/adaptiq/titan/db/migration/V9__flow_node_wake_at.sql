-- Durable sleep (timer subsystem, Phase 2): a flow node parked by a `sleep` /
-- `waitUntil` step records the wall-clock instant it should wake at. NULL for
-- every node that is not sleeping. See the timer-subsystem design doc.
--
-- Portable: a single ADD COLUMN, the established pattern (cf. V4, V5).

ALTER TABLE titan.flow_nodes ADD COLUMN wake_at TIMESTAMP;
