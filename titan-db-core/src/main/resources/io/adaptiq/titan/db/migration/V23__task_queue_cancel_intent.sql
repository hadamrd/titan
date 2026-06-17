-- V23: durable cancel-intent signal on task_queue (#668).
--
-- Today /api/v1/builds/{id}/cancel terminalises live tasks straight to status='CANCELLED'.
-- That conflates two orthogonal facts:
--   (a) the controller has requested a cancel
--   (b) the task has actually reached a terminal state
--
-- Conflating them races the worker: a worker mid-claim sees status flip out from
-- under its claim_token-guarded UPDATE, the lease no longer matches, and the
-- step's real outcome (CANCELLED) is silently dropped. It also leaves no
-- breadcrumb for a worker that polls in between — by the time it reads the row,
-- "why was I cancelled?" is unanswerable.
--
-- cancel_requested_at is the proper intent signal: monotonic, additive, set once
-- by the abort path and never cleared. The reaper, the worker's heartbeat, and
-- any future propagation channel can all read it independently of the row's
-- claim/terminal status. Idempotent by construction (the abort path only sets
-- it on rows still in QUEUED/CLAIMED/PROCESSING and only when null).
--
-- TIMESTAMP WITH TIME ZONE (PG alias TIMESTAMPTZ) — spelled in full so the shared
-- migration parses on H2 as well (H2 PG-mode rejects the TIMESTAMPTZ alias).
-- See V15 for the same pattern.
ALTER TABLE titan.task_queue
  ADD COLUMN cancel_requested_at TIMESTAMP WITH TIME ZONE NULL;

-- Mirror the same column on task_archive so a build's finished tasks retain the
-- intent breadcrumb after the per-tick archive sweep. Without this,
-- moveCompletedToArchive would silently drop the field on the transfer, and a
-- post-mortem "was this cancelled by user request?" query against task_archive
-- would have nothing to look at.
ALTER TABLE titan.task_archive
  ADD COLUMN cancel_requested_at TIMESTAMP WITH TIME ZONE NULL;
