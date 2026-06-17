# Pipeline includes — locked precedence (issue #1128)

This fixture demonstrates the `include:` PDL primitive for factoring out a shared
pipeline prologue across many repos. The shared file lives in `_common.yml`; the
consumer is `pipeline.yml`.

## The merge contract (high → low precedence)

```
local file > last include in list > … > first include in list
```

### Stages
`stages.<id>` declared **locally** fully **replaces** an included `stages.<id>` of
the same id. There is **no per-step merge** inside a stage — replace whole stage.

### Maps (`env`, `libraries`)
Shallow-merged. On a key collision, the **local** key wins.

### Top-level scalars (e.g. `agent`)
Local wins outright.

---

## Positive example — local stage replaces included stage

`_common.yml` ships a generic `prologue-test`. A consumer wants a stricter variant:

```yaml
# _common.yml (shared, included by N repos)
stages:
  - stage: prologue-install
    steps: [{ sh: "npm ci" }]
  - stage: prologue-test
    steps: [{ sh: "npm test" }]
```

```yaml
# pipeline.yml (one consumer; rest stay on the default)
include: ./_common.yml
stages:
  - stage: prologue-test
    steps:
      - sh: "npm test -- --runInBand --coverage"
      - sh: "publish-coverage.sh"
```

Merged result: **two** stages — `prologue-install` (from the include, unchanged)
and `prologue-test` (the consumer's two steps, fully replacing the included one).
The build's persisted `PipelineModel` reflects this merged shape; the build-detail
UI shows the flattened step list with no separate "includes" tab.

## Trap — env key collision (local wins)

```yaml
# _common.yml
env:
  NODE_ENV: production
  CACHE_BUST: "1"
```

```yaml
# pipeline.yml
include: ./_common.yml
env:
  NODE_ENV: staging   # ← wins
  DEBUG: "true"       # ← added (no collision)
```

Effective `env`: `NODE_ENV=staging`, `CACHE_BUST=1`, `DEBUG=true`. Watch for the
silent override — a debugging step that asserts the included value will quietly
get the local one.

---

## Resolution rules (enforced by parser)

- **Path form** — `include:` accepts a path-relative string
  (`./.titan/_common.yml`) or a list of fragments. Paths are resolved against the
  directory of the including pipeline file.
- **Absolute paths rejected** — `/etc/foo.yml` → parse error.
- **`../` escapes rejected** — any path that, after normalization, escapes the
  workspace root → parse error.
- **Cycle detection** — `a.yml → b.yml → a.yml` raises `PipelineParseException`
  naming the cycle path; never a stack overflow.
- **Max depth = 5** — exceeding it is a parse error with a clear message.
- **Symlink escape rejected** — an included file containing a nested `include:`
  whose resolved path is a symlink pointing outside the workspace root is
  rejected.

## Out of scope (intentional)

- Remote includes (HTTP/git URLs in the bare-string form). Use the
  `{ repo, ref, path }` object form for cross-repo fragments (#1120).
- Per-step deep merge inside a stage — whole-stage replace only.
- `${...}` variable substitution into included fragments.
- A UI affordance to view raw include source — merged view only.
