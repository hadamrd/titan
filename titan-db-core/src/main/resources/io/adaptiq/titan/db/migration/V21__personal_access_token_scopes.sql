-- Titan schema V21 — per-PAT scopes (closes #500).
--
-- Adds `scopes_json` to titan.personal_access_tokens so a token can be
-- narrowed below its owner's full role set. Follow-up to #477 (per-token
-- scopes) — solves the "give a script just enough" use case.
--
-- Semantics (enforced by PatAuthenticationMechanism):
--   * NULL  → legacy behaviour: PAT inherits the creator's full role set
--             (modulo ADMIN, which is never granted via PAT — same as #477).
--   * non-NULL → JSON array of scope strings, e.g.
--             ["READ_JOB","TRIGGER_BUILD"]. The resolved SecurityIdentity
--             roles = (creator's roles) ∩ (PAT scopes).
--
-- The column is plain VARCHAR (JSON serialised at the API layer) to keep
-- the schema portable across H2 (test) and PostgreSQL (prod). The same
-- pattern as V11 credentials_envelope's metadata blob.
--
-- The default NULL guarantees backward compatibility — existing tokens
-- behave exactly as they did before this migration.

ALTER TABLE titan.personal_access_tokens
    ADD COLUMN scopes_json VARCHAR(1000);
