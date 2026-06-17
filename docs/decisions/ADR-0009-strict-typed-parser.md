# ADR-0009: The parser projects key types, not just key names

**Status:** Accepted

**Context** — ADR-0008 single-sourced the grammar at the key-*name* level, but the parser remained looser than the schema at the *value* level. It collapsed an explicit `null` to "key absent" and silently coerced scalars (`requiresApproval: "yes"` via `asBoolean()`, `dependsOn: 5` to a string, `agent: [a, b]` to `""`). A fat-fingered value on an approval gate or an agent selector was read as something the author never wrote, not rejected — three subtly different languages (intent, schema, parser).

**Decision** — A grammar key's value *type* is part of the grammar. The parser reads every grammar-key value through a `TitanGrammar`-typed accessor that returns the value only if it matches the declared JSON type, and otherwise throws a located, value-echoing `PipelineParseException`. A present key with a `null` value is an error ("omit the key, or give it a value"); a type mismatch is never coerced. This was a hard break with no leniency mode, taken pre-1.0 with no installed pipeline base to protect.

**Consequences**
- The schema and the parser now agree at the value level by construction — both read the same `TitanGrammar` type declarations.
- Documents that relied on coercion or null-as-absent now fail to parse with a clear, located error.
- Scope governs grammar keys only — the root/stage/gate/parameter skeleton and the scope keys. Per-step descriptor argument maps (`sh: { … }`) remain the worker-side `StepDescriptor`/`ParamSpec` contract.
- Enum-membership and cross-field semantic validation are out of scope here; they stay parser-only checks or later projections.
