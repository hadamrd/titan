-- Titan schema V14 — replay-from-node metadata on titan.builds (issue #307).
--
-- Persists the relationship between a replay build and the parent build it
-- was forked from, plus the flow-node it was forked AT. Two reasons to keep
-- this on the build row (not on a side table):
--   1) `replay` is a 1:1 attribute of the new build — there is no many-to-many.
--   2) The forward-trace query ("show me every replay of build X") and the
--      reverse-trace ("what is build Y a replay of?") are both single-row reads.
--
-- ON DELETE SET NULL on the FK: deleting a parent build (a reap) breaks the
-- ancestry link, but the replay row survives as a first-class build of its job.
--
-- Portable across H2 (embedded/test) and PostgreSQL (production). Partial
-- indexes are not supported by H2, so the index is full.

ALTER TABLE titan.builds
  ADD COLUMN replayed_from_build_id BIGINT NULL;

ALTER TABLE titan.builds
  ADD COLUMN replayed_from_node_id  VARCHAR(64) NULL;

ALTER TABLE titan.builds
  ADD CONSTRAINT fk_builds_replayed_from
  FOREIGN KEY (replayed_from_build_id) REFERENCES titan.builds(id) ON DELETE SET NULL;

CREATE INDEX idx_builds_replayed_from ON titan.builds(replayed_from_build_id);
