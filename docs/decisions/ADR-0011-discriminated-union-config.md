# ADR-0011: Discriminated-union typed config

**Status:** Accepted

**Context** — Pluggable config blocks — triggers, notifiers, artifact stores, secrets backends — come in several kinds with different fields. A tempting shortcut is to infer the kind at runtime by sniffing a URL or guessing from which fields are present. That sniffing is fragile, untyped, and impossible to validate or schema-document cleanly.

**Decision** — Every polymorphic config block carries an explicit `type:` (or `kind:`) discriminator that selects the variant; fields are validated against that variant's declared shape. No inference from URLs or value shapes. This follows the established shapes of GitHub Actions, GitLab CI, and Buildkite rather than reinventing a worse one.

**Consequences**
- Each variant has a single, schema-validated shape; the grammar projection (ADR-0008) documents it.
- Adding a variant is adding a discriminator case plus its `GrammarKey` schema — no change to a sniffing heuristic.
- Config is explicit and self-describing: a reader sees `type: github` and knows exactly which fields apply.
- A missing or unknown discriminator is a parse error, not a silent fallback.
