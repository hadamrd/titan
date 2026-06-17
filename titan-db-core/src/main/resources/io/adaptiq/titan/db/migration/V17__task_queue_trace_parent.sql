-- Titan schema V17 — W3C traceparent on task_queue rows (#314).
--
-- Carries the distributed-trace context from the controller (orchestrator
-- enqueuing a task) to the worker (claiming + executing it), so the worker
-- can re-attach to the originating span and emit child spans into the same
-- trace. Format is the W3C traceparent header:
--
--   "00-{32 hex trace-id}-{16 hex parent-id}-{2 hex flags}"
--
-- which is exactly 55 characters; VARCHAR(64) leaves headroom for future
-- W3C versions without another migration. Nullable: any task enqueued
-- without an active OTel span (background reapers, tests, callers without
-- the SDK on the classpath) stores NULL — propagation is best-effort.
--
-- Same column added to task_archive so archived completed tasks retain
-- their trace context for offline analysis (the archive mirrors the live
-- table column-for-column).

ALTER TABLE titan.task_queue   ADD COLUMN trace_parent VARCHAR(64);
ALTER TABLE titan.task_archive ADD COLUMN trace_parent VARCHAR(64);
