-- Titan schema V2 — worker-reported node configuration.
--
-- A Titan worker now declares its own node-level config (design/28): the
-- workspace root and the node usage mode, alongside the labels and
-- executor count it already reported. TitanNode is minted from these values
-- instead of hardcoding remoteFS='/titan' and Mode=EXCLUSIVE.
--
-- Both columns are nullable: a row written by an older worker (or a test that
-- does not set them) leaves them NULL, and TitanNode falls back to its
-- defaults ('/titan', NORMAL). Separate ALTER statements — portable across
-- H2 and PostgreSQL.

ALTER TABLE titan.agents ADD COLUMN remote_fs  VARCHAR;
ALTER TABLE titan.agents ADD COLUMN usage_mode VARCHAR(16);
