# Operator Tour

The tour has two halves:

1. **Capture** (issue #1132 — `task tour:run`, see "Capture" section below):
   Playwright walks 10 critical pages and dumps PNGs into `e2e/tour/current/`.
2. **Analyze** (issue #1137 — `task tour:analyze`, see "Analyze" section below):
   a vision model scores each PNG against a per-page rubric, writes structured
   findings, diffs against the previous release baseline, and emits a
   ticket-ready summary.

---

## Capture half (#1132)

`tour.spec.ts` is a Playwright spec that logs in as `dev` via the rig's
real Keycloak PKCE flow, then walks 10 critical pages at two viewports
(desktop 1440×900, mobile 375×812) and dumps 20 full-page PNGs into
`current/`. `report.mjs` then builds `report.html` with a side-by-side
baseline-vs-current gallery and a verdict badge per frame.

### Run it

```
task dev:titan           # bring the rig up (postgres + keycloak + server + ui + worker)
task tour:run            # capture 20 PNGs into e2e/tour/current/ + write report.html
xdg-open e2e/tour/report.html
```

`task tour:run` exits non-zero when there are diffs vs the committed
baseline — that's expected the first time you run it on a fresh rig.

### Accept a baseline

After visually verifying every frame in `report.html` reflects intent
(no clipped panels, no missing empty-states, no overflow on mobile,
etc.):

```
task tour:accept         # cp current/*.png → baseline/ + git add
git commit -m "tour: update baseline for <reason>"
```

### What to do when CI flags a regression

1. Open `report.html` (PR artifact) and skim only the rows badged `diff`
   or `size-drift`.
2. **Real regression** (clipped panel, missing button, contrast loss)
   → file a UI bug + fix it in a separate PR. Do NOT silently re-accept
   the baseline.
3. **Intended visual change** (deliberate redesign, copy update) → on the
   PR that introduces the change, run `task tour:accept` and commit the
   updated baseline alongside the code change.
4. **Determinism drift** (relative timestamp, animated element, random
   ID leaking into the DOM) → fix the leak. Two consecutive runs against
   an unchanged rig MUST produce zero diffs. If they don't, that's a
   spec bug, not a baseline question.

### The 10 pages (locked for this ticket)

| # | Slug                  | URL                                |
| - | --------------------- | ---------------------------------- |
| 1 | `jobs`                | `/jobs`                            |
| 2 | `jobs_id`             | `/jobs/<first-job-id>`             |
| 3 | `builds_succeeded`    | `/builds/<succeeded-build-id>`     |
| 4 | `builds_failed`       | `/builds/<failed-build-id>`        |
| 5 | `repos`               | `/repositories`                    |
| 6 | `repos_id`            | `/repositories/<first-repo-id>`    |
| 7 | `settings`            | `/settings`                        |
| 8 | `audit`               | `/audit`                           |
| 9 | `login`               | `/login` (captured logged-out)     |
| 10 | `not_found`          | `/jobs/does-not-exist-xyz-1132`    |

Each page is captured at desktop AND mobile → 20 PNGs.

### Naming convention for new pages

`<slug>--<viewport>.png` where `<slug>` is `[a-z0-9_]+` (path with `/`
stripped, non-alphanumerics collapsed to `_`). Match the slug used in
`rubric.yaml` so the analyze half can score it without extra wiring.

### Determinism guarantees

The spec pins `Date.now()` and `new Date()` to `2026-01-01T12:00:00Z`
via `page.addInitScript`, and masks any DOM nodes that look
time-relative (`<time>`, `[data-relative]`, `[title*="ago"]`) so reruns
against an unchanged rig produce **zero** pixel-diff regressions.
Animations and caret blink are also frozen via injected CSS.

### Out of scope here

Vision-model findings (epic #1115, ticket #1137), CI gating, fixes for
defects the first baseline reveals — those land in follow-ups.

---

## Analyze half (#1137)

## TL;DR

```
task tour:analyze    # screenshots → findings.json + diff.md ; non-zero on new fails
task tour:diff       # re-diff cached findings against baseline
task tour:test       # node:test unit + integration suite
```

By default the **mock** provider is used — deterministic, zero network. To
hit the real model:

```
TOUR_VISION_PROVIDER=anthropic ANTHROPIC_API_KEY=sk-... task tour:analyze
```

If `TOUR_VISION_PROVIDER=anthropic` but no API key is set, the analyzer
**falls back to mock with a warning** — it never errors out, never blocks
the loop on a missing secret.

## Layout

```
e2e/tour/
├── README.md                   # this file
├── rubric.yaml                 # data-driven rubric (pages × rubric items)
├── rubric-loader.mjs           # validating loader with typed RubricError
├── schema.mjs                  # findings.json schema validator (versioned)
├── diff.mjs                    # diff + Markdown renderer
├── analyzer.mjs                # main pipeline + CLI
├── providers/
│   ├── index.mjs               # selectProvider() — TOUR_VISION_PROVIDER dispatch
│   ├── mock.mjs                # deterministic FNV-1a verdict cycler
│   └── anthropic.mjs           # Claude vision via Messages API
├── __tests__/                  # node:test (no vitest dep)
├── fixtures/screenshots/       # committed sample PNGs the IT runs against
├── baseline/findings.json      # the release baseline (compare against this)
└── current/                    # tour:analyze output: findings.json + diff.md
```

## The rubric

`rubric.yaml` declares **rubric items** (each = one prompt the model
evaluates) and **pages** (each = `path + viewport + items[]`). A page opts
in to only the items that apply to it — `/404` doesn't need a loading
state, `/jobs@mobile` only checks mobile-specific items.

### Adding a page

1. Make sure the capture rig (#1132) is dropping a PNG at
   `current/<slug>--<viewport>.png` where `<slug>` is the route with `/`
   stripped and non-alphanumerics replaced with `_` (root → `root`).
2. Append a `pages:` entry in `rubric.yaml` referencing the items that
   apply.
3. Re-run `task tour:analyze`. Expect a regression on first run; promote
   the new findings to `baseline/findings.json` once they reflect intent.

### Adding a rubric item

1. Add an entry under `rubric_items:` with a clear, single-question
   `prompt` — the model returns `pass | warn | fail` plus a quoted
   evidence phrase.
2. Opt the appropriate pages into it.
3. Add a unit test if the item has non-obvious truth conditions.

## Schema (findings.json — v1)

```json
{
  "schema_version": 1,
  "generated_at": "2026-05-28T12:34:56.000Z",
  "provider": "mock",
  "findings": [
    {
      "page": "/jobs",
      "viewport": "desktop",
      "rubric_item": "failure_state_distinguishable",
      "verdict": "fail",
      "evidence": "queued and failed rows are visually identical",
      "screenshot": "current/jobs--desktop.png"
    }
  ]
}
```

`verdict ∈ {pass, warn, fail, error}`. The `error` verdict is reserved for
analyzer-side failures (model returned malformed JSON, screenshot missing,
network down). It is NOT a UX fail — but it does count as a regression in
the diff so we don't silently lose coverage.

## Diff semantics

`diff.md` lists only **regressions** (new fails/warns or escalations) and
**resolutions** (fail → pass/warn or fail removed). Identical-verdict
cells are omitted to keep the report scannable.

The analyzer exits **non-zero** if there's a new `fail` not present in the
baseline — designed to be wired into CI later. `warn`-level regressions
do NOT gate.

## Providers

| `TOUR_VISION_PROVIDER` | Behaviour                                                          |
| ---------------------- | ------------------------------------------------------------------ |
| unset / `mock`         | Deterministic FNV-1a verdict — same inputs → byte-identical JSON.  |
| `anthropic`            | Claude Messages API (vision). Requires `ANTHROPIC_API_KEY`.        |
| anything else          | Warns and falls back to `mock`.                                    |

### Secrets policy

* `ANTHROPIC_API_KEY` is read from the **process env only**.
* It is **never** written to `findings.json`, `diff.md`, or logs.
* The anthropic provider's regression test (`providers.test.mjs`) asserts
  the key never leaks into the finding payload — if you touch the
  provider, keep that test green.

## Promoting findings → backlog tickets

This is intentionally **manual** in v1 — the issue calls auto-ticket-filing
out of scope. The workflow:

1. Open `e2e/tour/current/diff.md` in your PR.
2. For each regression worth a ticket, copy the bulleted line and the
   evidence quote into a new GH issue (label `ux`, `tour`).
3. Link the screenshot path from the bullet so triage can see the
   offending frame.
4. When the regression is fixed, re-run `task tour:analyze` and confirm
   it appears under "Resolutions" before merging.

When v2 lands an auto-filer, the rubric item name becomes the issue
template key — that's why `rubric_item` is a stable enum-shaped string,
not free text.

## Test strategy

Per the Titan testing manifesto:

* **Adversarial first.** Every provider has a "malformed model response →
  no crash" test. Schema validator has one test per missing-field and one
  for unknown enum.
* **Determinism.** Mock provider runs 100×; output must be byte-identical.
* **Protocol parity.** Mock and anthropic both return the same finding
  shape; the schema validator is the contract both sides honor.
* **Integration.** The end-to-end analyzer runs against committed sample
  PNGs with the mock provider — no flaky network in CI.

Run: `task tour:test`.
