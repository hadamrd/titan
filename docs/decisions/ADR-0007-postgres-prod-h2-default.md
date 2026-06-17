# ADR-0007: Postgres in production, embedded H2 by default

**Status:** Accepted

**Context** — Titan needs a zero-config single-binary story for evaluation and local development, and a production-grade store for real deployments. The persistence access layer is plain JDBI/SQL rather than a heavyweight ORM, so the engine controls its queries and indexes directly.

**Decision** — Production runs on PostgreSQL (JSONB columns, `SKIP LOCKED`, mature drivers). The default out-of-the-box store is embedded file-mode H2, so a fresh server boots with no external database. Flyway carries dialect-specific migration sets for each.

**Consequences**
- `task dev:titan` and first-run evaluation work with no database to provision.
- The two engines diverge on SQL features (notably `SKIP LOCKED` semantics and JSONB), so dialect-sensitive migrations and queries must be tested on both.
- No MongoDB / SQLite path — one relational model, two engines.
- Postgres is the only first-class production target; other engines are not supported.
