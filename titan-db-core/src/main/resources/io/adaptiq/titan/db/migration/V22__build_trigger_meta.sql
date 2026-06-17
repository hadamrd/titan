-- Titan schema V22 — build trigger metadata (closes #589).
--
-- Adds a structured JSON blob to titan.builds carrying the triggering
-- event's key facts: branch, short commit SHA, actor. Populated only on
-- builds born from a webhook receiver (github push today; bitbucket /
-- gitlab when those land). Manual / dogfood / replay builds leave it
-- NULL.
--
-- Shape (validated at the API DTO boundary, not in SQL):
--   {"branch":"trunk","commitSha":"a3f9c12","actor":"kira.rai"}
--
-- VARCHAR(8192) is the same portable shape as V11 credentials_envelope
-- + V14 replay metadata — H2 (unit-test) and PG (prod) both accept it
-- without dialect-specific JSON type juggling. The size cap is a hard
-- backstop against pathological payloads (the receiver writes only a
-- handful of stripped fields, never the whole webhook body).
--
-- Security: the populator MUST NOT copy secrets from the webhook
-- payload into this column (GH push payloads carry no tokens today,
-- but the policy is enforced by what the receiver writes, not by SQL).

ALTER TABLE titan.builds
    ADD COLUMN trigger_meta_json VARCHAR(8192);
