# ADR-0018: Vendored cron grammar

**Status:** Accepted

**Context** — Cron triggers need the expressiveness operators expect from mature schedulers: `H`-hashing for load spreading, aliases like `@daily`, and `TZ=` prefixes. Reimplementing that grammar from scratch risks subtle behavioral drift, but pulling in an external runtime dependency just to get it would couple the engine to a heavyweight library it otherwise does not use.

**Decision** — A battle-tested cron grammar is vendored as pure Java under `titan-trigger-api/.../trigger/cron/internal/`, preserving `H`-hashing, `@daily`, and `TZ=` semantics with no external runtime dependency. That `internal` package is the one place its original imports are permitted.

**Consequences**
- Operators get familiar, battle-tested cron semantics without pulling in an external runtime.
- The vendored code is isolated to `internal/`; the corresponding legacy plugin-host imports anywhere else are a flagged anti-pattern.
- Upstream cron fixes must be ported in manually rather than picked up via a dependency bump.
