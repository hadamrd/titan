-- Titan schema V3 — build artifacts, stashes and fingerprints (design/41 — 32E).
--
-- `archiveArtifacts` and `stash`/`unstash` persist build outputs through the
-- ArtifactStore SPI (design/41 §3). This schema is deliberately METADATA-ONLY:
-- it carries no binary column, so it stays portable across H2 (embedded/test)
-- and PostgreSQL (production) — V1's locked constraint. The bytes live in the
-- ArtifactStore (a filesystem dir, S3, or a Postgres large object); the row
-- records only where: `storage` + `storage_ref` are the universal locator, and
-- the controller resolves a read by dispatching on the row's own `storage`.
--
-- Portable DDL only — VARCHAR/CHAR/BIGINT/TIMESTAMP, GENERATED AS IDENTITY,
-- CURRENT_TIMESTAMP, named constraints — exactly the V1 conventions.

-- One archived artifact or one stash entry.
--   kind        — ARTIFACT persists with the build; STASH is build-scoped and
--                 pruned when the build reaches a terminal state (design/41 E5).
--   name        — workspace-relative path (ARTIFACT) or stash name (STASH).
--   storage     — the ArtifactStore backend the bytes were written to:
--                 'fs' | 'pg' | 's3' | 'gcs' | … — reads dispatch on this.
--   storage_ref — backend-specific locator: a filesystem path, an object key,
--                 or a Postgres large-object oid (so 'pg' needs no OID column).
-- A re-run of the producing step overwrites the same (build, kind, name) row —
-- the UNIQUE constraint makes the handler idempotent (design/32 §4).
CREATE TABLE titan.artifact (
    id           BIGINT        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    build_id     BIGINT        NOT NULL,
    node_id      VARCHAR(64),
    kind         VARCHAR(8)    NOT NULL
                 CHECK (kind IN ('ARTIFACT','STASH')),
    name         VARCHAR(512)  NOT NULL,
    size_bytes   BIGINT        NOT NULL,
    sha256       CHAR(64)      NOT NULL,
    storage      VARCHAR(8)    NOT NULL,
    storage_ref  VARCHAR(1024) NOT NULL,
    created_at   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_artifact_build
        FOREIGN KEY (build_id) REFERENCES titan.builds(id) ON DELETE CASCADE,
    CONSTRAINT uq_artifact_build_kind_name UNIQUE (build_id, kind, name)
);

CREATE INDEX idx_artifact_build  ON titan.artifact(build_id);
CREATE INDEX idx_artifact_sha256 ON titan.artifact(sha256);

-- Fingerprinting (design/41 §8.3). The ArtifactStore content-addresses every
-- blob, so a "fingerprint" is just that SHA-256 indexed across builds — no
-- per-fingerprint files, no MD5, no GC janitor.
--
-- `titan.fingerprint` is the canonical record per content hash: where it was
-- first seen. first_build_id is nullable + ON DELETE SET NULL — deleting the
-- originating build must not delete the cross-build fingerprint.
CREATE TABLE titan.fingerprint (
    hash           CHAR(64)     PRIMARY KEY,
    file_name      VARCHAR(512) NOT NULL,
    first_build_id BIGINT,
    first_seen     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_fingerprint_first_build
        FOREIGN KEY (first_build_id) REFERENCES titan.builds(id) ON DELETE SET NULL
);

-- One edge per (hash, build, role): every build that PRODUCED — or, reserved
-- for a later chunk, USED — a given content hash. The cross-build index.
CREATE TABLE titan.fingerprint_ref (
    hash        CHAR(64)    NOT NULL,
    build_id    BIGINT      NOT NULL,
    node_id     VARCHAR(64),
    role        VARCHAR(8)  NOT NULL
                CHECK (role IN ('PRODUCED','USED')),
    recorded_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_fingerprint_ref_hash
        FOREIGN KEY (hash) REFERENCES titan.fingerprint(hash) ON DELETE CASCADE,
    CONSTRAINT fk_fingerprint_ref_build
        FOREIGN KEY (build_id) REFERENCES titan.builds(id) ON DELETE CASCADE,
    CONSTRAINT uq_fingerprint_ref UNIQUE (hash, build_id, role)
);

CREATE INDEX idx_fingerprint_ref_build ON titan.fingerprint_ref(build_id);
