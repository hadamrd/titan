# Architecture Decision Records

The load-bearing engineering decisions behind the Titan CI/CD execution engine, one record per decision. Each ADR states the forces, the decision, and the trade-offs accepted.

| ADR | Title | Status |
|---|---|---|
| [0001](ADR-0001-database-backed-engine-state.md) | Database-backed engine state | Accepted |
| [0002](ADR-0002-reconcile-not-resume.md) | Reconcile, don't resume | Accepted |
| [0003](ADR-0003-no-event-history-replay.md) | No event-history replay (why not Temporal) | Accepted |
| [0004](ADR-0004-pull-based-workers.md) | Pull-based workers over a SKIP LOCKED queue | Accepted |
| [0005](ADR-0005-at-least-once-idempotent-steps.md) | At-least-once delivery, idempotent steps | Accepted |
| [0006](ADR-0006-forward-only-flyway-migrations.md) | Forward-only Flyway migrations | Accepted |
| [0007](ADR-0007-postgres-prod-h2-default.md) | Postgres in production, embedded H2 by default | Accepted |
| [0008](ADR-0008-single-source-grammar.md) | Single-source grammar — schema is a generated projection | Accepted |
| [0009](ADR-0009-strict-typed-parser.md) | The parser projects key types, not just key names | Accepted |
| [0010](ADR-0010-fan-out-at-parse-time.md) | Matrix / each / templates fan out at parse time | Accepted |
| [0011](ADR-0011-discriminated-union-config.md) | Discriminated-union typed config | Accepted |
| [0012](ADR-0012-serviceloader-spis.md) | ServiceLoader SPIs for all extension points | Accepted |
| [0013](ADR-0013-single-convention-discovery.md) | Single-convention discovery; the file IS the pipeline | Accepted |
| [0014](ADR-0014-quarkus-gradle-jdbi.md) | Quarkus, Gradle, and JDBI as the server stack | Accepted |
| [0015](ADR-0015-oidc-pkce-humans-pat-machines.md) | OIDC + PKCE for humans, PATs for machines | Accepted |
| [0016](ADR-0016-envelope-encrypted-secrets.md) | Envelope-encrypted secrets via a pluggable backend | Accepted |
| [0017](ADR-0017-react-vite-tanstack-ui.md) | React + Vite + TanStack UI | Accepted |
| [0018](ADR-0018-vendored-cron-grammar.md) | Vendored cron grammar | Accepted |
| [0019](ADR-0019-finite-retries-priority-queue.md) | Finite default retries and a priority queue | Accepted |
