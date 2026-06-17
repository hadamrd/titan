---
name: smoke-tour
description: >-
  Drive the Titan UI like a real user actually clicking through it — log into
  the live rig with Playwright, walk every operator route, and at each one watch
  the browser CONSOLE + NETWORK for runtime crashes, then exercise the page's
  primary interaction (open the modal, expand the row, submit the filter,
  paginate, switch the tab) and watch again. Catches the functional bugs a human
  finds in 3 minutes that tests never do: a page that throws on real data, a
  button that does nothing, a modal that won't open, a 500 surfaced as a blank
  screen. TRIGGER when the user wants to crash-test / smoke-test the UI, "click
  through the app", "actually use the UI and find bugs", hunt runtime errors, or
  after a UI-heavy deploy. SKIP for pure visual/aesthetic review (use `ux-tour`
  — that one judges how it LOOKS; this one judges whether it WORKS) and for
  non-UI work.
---

# Functional smoke tour — use the UI, catch what throws

You are a user, not a screenshot critic. Your job is to **open every page,
click the things a real operator clicks, and catch every runtime crash, broken
interaction, and surfaced server error** — the bugs that pass every unit test
because tests mock the data and never actually render the page with real rows.

This is the complement to `ux-tour`: that skill asks *"does this look
considered?"*; this skill asks *"does this actually work when I use it?"* A page
that looks fine but throws `Cannot read properties of undefined` the moment it
loads real data is THIS skill's catch.

## 0. Setup (once)

- Rig: `https://titan.test.example.com`. Login **`dev` / `dev`** (Keycloak
  realm `titan-dev`). If login 404s, the realm seed is broken — stop and report
  it (`rig/k3s/keycloak/seed-realm.sh`).
- Playwright MCP (`mcp__playwright__browser_*`). The rig has **real data**
  (builds, jobs, audit rows, workers) — that's the point; functional bugs hide
  in real shapes (a null field, an empty list, a long string), not in fixtures.
- **Cache-bust** every navigation: append `?cb=<vary>` (the rig serves
  `no-store`, but a long-lived Playwright tab keeps the old SPA in memory —
  navigate fresh so you test the deployed bundle, and verify
  `document.querySelector('script[src*=index-]')` matches what's served).

## 1. The instrument — what you read at every page

The console + network are your detectors. At each step:

1. `browser_navigate` to the route (`?cb=N`).
2. `browser_console_messages` → **any `error` (or uncaught exception) is a
   finding.** Especially `TypeError`, `Cannot read properties of undefined`,
   `is not a function`, React error boundaries.
3. `browser_network_requests` → any **4xx/5xx** to `/api/v1/*` that the page
   doesn't handle (renders blank / error-boundary instead of an empty/error
   state) is a finding. (A clean `401` pre-login or an expected `404` is not.)
4. `browser_snapshot` → confirm the page actually **rendered content** (not a
   blank shell / error boundary / infinite skeleton).
5. **Then interact** (§2) and re-read console + network — many crashes only fire
   on the click, not the load.

Cheap programmatic cross-check: `browser_evaluate` to read
`window.__lastError` or check for a rendered error-boundary node, and to detect
a permanently-empty `<tbody>` where rows were expected.

## 2. The parcours — every route + its primary interaction

Walk these in order. **Load-check** every page (§1 steps 1–4), then do the
**interaction** and re-check.

| # | Route | Interaction to actually perform |
|---|---|---|
| 1 | `/` overview | let live tiles load; no console error on first data tick |
| 2 | `/builds` | open a build → `/builds/$id`: logs, stage tree, replay button |
| 3 | `/jobs` or `/pipelines` | click a row → detail; click the **per-row Run/trigger** (params modal must open for a parameterized pipeline, #1208) |
| 4 | `/pipelines/$id` | **Run pipeline** (params + preview modal), expand stages, the **stage-timing** panel renders |
| 5 | `/queue` | live queue renders; claim/abort affordances |
| 6 | `/workers` | per-worker rows render with real pool state |
| 7 | `/approvals` | approve/reject a gate if one is pending; empty state otherwise |
| 8 | `/audit` + `/rbac-audit` | **change the Window filter to 30d/all**, toggle action chips, **expand a row** (details JSON), **paginate** — real audit rows have null fields (#1222 class) |
| 9 | `/integrations` (+ github) | connection cards render; install/connect flow opens |
| 10 | `/profile#tokens` | **Generate token** form, scopes checkboxes, the tokens **table** (no clipped columns), **Revoke** dialog |
| 11 | `/system` | system status renders |
| 12 | `/admin` | role/user table renders + an admin action |
| 13 | `/login`, `/onboarding` | first-impression; no console error |

Interaction set that catches the most: **open a modal, expand a row, submit a
filter, change a query param (window/limit), paginate, switch a tab, click the
primary button.** These are exactly what unit tests mock away.

## 3. What's a finding (severity)

- **sev1 — crash / unusable:** uncaught exception, React error boundary, blank
  page, `TypeError`, a primary interaction that throws or does nothing
  (button/modal dead). The `/audit` `.trim()` crash and the `/pipelines` Run
  button that never opened the params modal are both sev1.
- **sev2 — broken state, page survives:** an unhandled 4xx/5xx that renders a
  blank/wrong state instead of a real empty/error state; clipped/overflowing
  content that hides data; a filter/sort that errors silently.
- **sev3 — console noise:** warnings, key warnings, deprecation, a failed
  non-critical request that degrades gracefully.

Cite the route + state + the exact console line / failing request.

## 4. Fix at the root, or file — don't paper over

For each finding, prefer **root-cause fix in the same pass** (this skill's whole
point is to stop the human being the QA):

- Reproduce → find the source (the cell/handler/hook that threw) → fix the
  actual cause (e.g. server `@JsonInclude(NON_NULL)` strips null → field arrives
  `undefined`, not `null`: guard with `typeof x === 'string'` / optional chain /
  loose `== null`, not `!== null`) → **add a regression test** that fails on the
  old code → `tsc` + the route's vitest green.
- Batch the pass's fixes into **one PR** (`fix(ui): functional smoke-tour pass —
  N crashes`), merge, redeploy, then re-walk the fixed routes to confirm the
  console is clean.
- If a fix is non-trivial / cross-cutting, file it: `gh issue create --label
  "smoke-tour:finding,axis:ux-quality,theme:ui" --title "ui: <route> — <crash>"`
  with the console line + repro steps. Don't file dupes (`gh issue list --label
  smoke-tour:finding` first).

## 5. Output

End with a tour report: **routes walked, console-clean vs crashed, each crash +
its root cause + fix/issue#, and the one cross-cutting class** if several share
a root (e.g. "4 pages crash on NON_NULL-stripped fields → audit them all"). That
shared class is the high-leverage fix.

**Why this skill exists:** the loop ships pages that pass every test but throw
the moment a human opens them with real data — the operator kept finding
3-minute crashes (`/audit` undefined-trim, `/pipelines` dead Run button) the
test suite couldn't. Tests assert mocked behavior; this tour exercises the real
thing. Run it after every UI-heavy deploy and periodically — the
`smoke-tour:finding` label lets the loop pick up anything not fixed in-pass.
