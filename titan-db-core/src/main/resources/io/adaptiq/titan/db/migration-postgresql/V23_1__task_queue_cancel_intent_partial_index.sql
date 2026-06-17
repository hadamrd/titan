-- PG-only partial index for cancel-intent lookups (#668).
--
-- Most task_queue rows have cancel_requested_at IS NULL. The worker's heartbeat
-- poll only ever asks "is there an unhandled cancel intent on the row I own?";
-- a partial WHERE-not-null index is ~free to maintain and turns the poll into
-- a tiny index lookup instead of a row read off the heap. H2 PG-mode does not
-- accept the WHERE clause on CREATE INDEX, hence the split (see V15_1).
CREATE INDEX IF NOT EXISTS idx_task_queue_cancel_requested
  ON titan.task_queue (id)
  WHERE cancel_requested_at IS NOT NULL;
