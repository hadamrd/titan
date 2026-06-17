# ADR-0006: Forward-only Flyway migrations

**Status:** Accepted

**Context** — Database-backed state (ADR-0001) makes the schema a versioned artifact that must evolve safely across releases. Auto-generated rollback scripts are a notorious source of data loss and are hard to review and test.

**Decision** — Schema is versioned with Flyway and migrations are forward-only. They apply at server startup; there is no automatic rollback. A failed migration aborts startup loudly. Downgrade is an operational procedure: restore from backup and run the older jar.

**Consequences**
- Migrations are reviewable, testable, append-only `V*__*.sql` files; the diff is the change.
- A bad migration fails fast at boot instead of silently leaving a half-migrated database.
- Downgrades require a backup and a deliberate ops step, not a one-click revert.
- Postgres and the embedded H2 default each have their own migration set (`migration-postgresql/` and `migration/`) where SQL dialects differ.
