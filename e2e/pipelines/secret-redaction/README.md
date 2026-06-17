# secret-redaction — PDL secrets reference + log redaction fixture

Reference fixture for **issue #1135**. Drives the PDL secret-reference
contract (`env: API_TOKEN: ${{ secrets.<id> }}`) end-to-end against the
local rig and adversarially asserts the value never leaks to any log
surface.

## Why this exists

Axis `pdl-expressiveness` of the PDL roadmap names this fixture
explicitly under `acceptable_work`. Builds on **#1094** (env grammar +
secret refs) by proving the redaction contract holds — without a
real-shaped fixture, the masking code can pass unit tests but break the
first time a customer pipeline echoes `$TOKEN`.

The customer story (verbatim from #1135):

> Platform team migrating a legacy pipeline that uses scoped credential
> bindings: "I need to know that when I reference a secret in PDL it gets
> injected at runtime AND that an accidental `echo $TOKEN` in my script will
> be masked in the log — same guarantee my old CI gave me."

## The contract being proven

| # | Channel                                | Asserted by                                  |
|---|----------------------------------------|----------------------------------------------|
| 1 | Streamed log (per-line SSE)            | secret-redaction.spec.ts — `streamedLog`     |
| 2 | Persisted log artifact                 | secret-redaction.spec.ts — `consoleLog`      |
| 3 | Build-detail UI log panel              | secret-redaction.spec.ts — DOM scrape        |
| 4 | Mask token actually fires (not absent) | spec asserts `****` appears at least once    |

The fixture deliberately attacks redaction from **four angles in one step**:

- direct echo (`echo "[$API_TOKEN]"`),
- the value embedded in a multi-arg shell line,
- `printenv`-style dump (catches the env-export leak class — masking the
  use-site but leaking the `API_TOKEN=...` line `printenv` emits),
- a second use of the value, to prove the masker is idempotent and not a
  one-shot.

## Running it standalone

```sh
task dev:titan
task e2e -- --grep secret-redaction
```

The spec seeds a credential with id `E2E_REDACTION_TOKEN` carrying a
structured sentinel value (`sentinel-redact-<random>-canary`) so a
partial substring leak still matches a `String.includes` assertion.

## E2E specs that drive it

| spec                                          | asserts                                                                  |
|-----------------------------------------------|--------------------------------------------------------------------------|
| `e2e/tests/secret-redaction.spec.ts`          | mask `****` appears AND raw sentinel never appears in any log surface    |

Until #1094 (env+secret grammar runtime) and the rig secret-seed surface
land, the spec is registered with `test.skip()` so the suite stays
green; the YAML + parser test are complete and ready to drive against.

## Pipeline-model unit test

`titan-pipeline-model/.../SecretRedactionFixtureTest` parses this YAML
and asserts the parse-time invariants the fixture relies on:

- the `use-secret` stage exists with exactly one step,
- the step carries an `env:` map with `API_TOKEN` bound to the literal
  string `${{ secrets.E2E_REDACTION_TOKEN }}` (the parser is supposed
  to preserve the expression — runtime resolves it),
- the top-level `REGION` env is present so plain env + secret env
  coexistence is covered.

That test guards against grammar regressions silently breaking the
fixture between rig runs.
