-- Envelope-encryption columns for titan.credentials.
--
-- The V10 schema sealed the payload directly under the operator's KEK. V11
-- moves to envelope encryption: every row carries its own random data-encryption
-- key (DEK) that seals the payload; the DEK itself is wrapped under the KEK and
-- stored in `wrapped_dek`. Two layers, two keys per row.
--
-- Why now: KEK rotation under the V10 model required unsealing every row's
-- payload (potentially megabytes for FILE credentials) and re-sealing it under
-- the new key — minutes of work, locked-table risk. Under V11 the rotation
-- re-wraps the small `wrapped_dek` blob only; the sealed_value bytes are
-- untouched. The same primitive is what every reputable secrets system uses
-- (AWS KMS, GCP KMS, Vault Transit, Tink) for this exact reason.
--
-- Compatibility: the envelope columns are nullable so V10 rows (sealed directly
-- under the KEK, no DEK) continue to decrypt via the legacy path —
-- DbEnvelopeBackend dispatches on the presence of `wrapped_dek`. A future
-- migration may rewrite V10 rows into V11 envelope form; until then both shapes
-- coexist.

ALTER TABLE titan.credentials
    ADD COLUMN wrapped_dek  VARCHAR;          -- AES-256-GCM(DEK, KEK, AAD=dek:<id>:v<kekVersion>)
ALTER TABLE titan.credentials
    ADD COLUMN kek_version  INTEGER;          -- which KEK was used to wrap the DEK
ALTER TABLE titan.credentials
    ADD COLUMN dek_version  INTEGER;          -- reserved for future per-secret DEK rotation
