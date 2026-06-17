# Handover — Titan UI E2E tests (Playwright)

**Type:** separate task / thread.
**Mission:** automate the design/33–35 Titan UI verification — currently
done by hand — as Playwright specs in this harness (`rig/e2e/ui/`).

---

## 1. Why this task exists

design/33–35 shipped Titan's build UI: the Pipeline Graph View graph, the
per-node console, the job-page UX. Each was verified **manually** on the live
rig (load page, click node, eyeball). That is not repeatable and not a
regression gate. A pgv version bump, a Jelly DOM change, or a JSON-shape drift
would pass CI silently. This task makes that verification an automated suite.

It is genuinely worth a browser suite (not just unit tests): the integration
risk is "pgv's React bundle, fed Titan's JSON, renders without crashing." Only
a real browser exercises that. Two of the three bugs found by hand this chunk —
the doubled stage URL and the `IntersectionObserver` crash from a missing
`.titan-app-bar` — were *render-time* failures invisible to any non-browser
test.

## 2. The testing split (do not put everything in the browser)

| Layer | Test with | Why |
|---|---|---|
| The JSON/endpoint contract — `stages/tree`, `allSteps`, `execution/node/<id>/log/...` shapes | **controller-harness ITs** (server module, `src/test/java/.../graphview/`) | Fast, deterministic, no rig. `TitanGraphApiActionTest` already unit-tests the DTO builders; a controller-harness IT should assert the live HTTP responses. |
| The *rendered* integration — does pgv's bundle draw the graph/tree/logs, 0 console errors | **Playwright** (this harness) | Only a real browser runs the React bundle. This is the new work. |

Do the IT layer too (or note it as a sibling task) — a browser test asserting
JSON shape is slow and flaky for what an IT does better.

## 3. The existing harness — how it works

`rig/e2e/ui/` — Playwright 1.49, `playwright.config.ts`:

- `setup` project (`auth.setup.ts`) logs in once, writes `storage/admin.json`;
  the `chromium` project consumes it via `storageState`.
- `RF_BASE` env var / `--base-url` sets the target; default is the hosted
  ReleaseFlow rig.
- Specs in `tests/*.spec.ts` — `home`, `deploy-form`, `a11y`, etc. — all
  **ReleaseFlow dashboard**. Use them as the *pattern*, not the target.
- Visual regression via `toHaveScreenshot` (0.2% tolerance); `a11y.spec.ts`
  shows the accessibility-assertion pattern.

## 4. The gap to close

The Titan rig is **different** from the ReleaseFlow rig:

- It is the local `docker compose` rig in `rig/local/` — the controller at
  **`http://localhost:18080`**, **no security** (no login — so the Titan
  project needs **no `setup`/`storageState`**).
- It must be **up and seeded** before the suite runs: `rig/local/up.sh`
  (or `docker compose up -d`), then seed a Titan job + a green build —
  `rig/local/create-titan-job.groovy` POSTed to `/scriptText` with a CSRF
  crumb on a shared cookie jar (see `HANDOVER.md` and the chunk-35 transcript).
  The rig DB is now a local `postgres` container, so it starts empty each
  `down -v`.

**Recommended config change:** add a second Playwright `project` —
`titan` — with `baseURL: http://localhost:18080`, **no `storageState`**, **no
`setup` dependency**; `testDir`/`testMatch` scoped to `tests/titan/**`. Keep the
existing `chromium` project for ReleaseFlow untouched. A `titan-rig.setup.ts`
may bring the rig up + seed, or assume a running rig (decide; document it).

## 5. The specs to write (`tests/titan/`)

Each was manually verified this chunk — expected behaviour is known.

1. **`build-page.spec.ts`** — `/job/titan-e2e-demo/<n>/`
   - exactly **one** `#side-panel`; it has tasks: Status, **Pipeline Console**,
     Console Output, Previous Build.
   - the graph summary renders (`#graph` populated; stage nodes visible).
   - **0 console errors** (`page.on('console')` / `pageerror`).
2. **`stages-console.spec.ts`** — `/job/titan-e2e-demo/<n>/stages/`
   - **0** `#side-panel` (full-width — regression guard for the doubled
     sidebar); `.titan-app-bar` present (regression guard for the
     `IntersectionObserver` crash).
   - the stage tree renders the expected stage count (`.pgv-tree-stage`).
   - **0 console errors** — this is the single highest-value assertion; it is
     what caught the blank-page crash by hand.
3. **`per-node-console.spec.ts`** — the design/35 payoff
   - click a stage in the tree → its steps expand;
   - expand a step → its **log lines** appear (assert known text, e.g.
     `building on titan`). This exercises
     `execution/node/<id>/log/logText/progressiveHtml` end to end.
4. **`job-page.spec.ts`** — `/job/titan-e2e-demo/`
   - the build-history widget (`Builds` list) renders;
   - the **Build Now** task schedules a build (click → a new build appears).
5. **`stage-url.spec.ts`** — regression guard for the doubled-URL bug: a graph
   node's link is context-absolute (`/job/.../stages/?selected-node=`), not
   run-relative; clicking it lands on a 200 page, not a doubled-path 404.

Use `getByRole` / accessibility-tree assertions (the `a11y.spec.ts` pattern)
over brittle CSS where possible. `toHaveScreenshot` visual regression is
optional and secondary — the console-error + DOM-structure assertions are the
load-bearing ones.

## 6. Gotchas (learned the hard way this chunk)

- **Console errors are the signal.** The blank `/stages` page threw exactly one
  console error (`IntersectionObserver … parameter 1 is not of type
  'Element'`) — assert `0` console errors on every page.
- **`.titan-app-bar` is contractually required** by the pgv console bundle
  (its scroll logic `observe()`s it). A full-width template without an app-bar
  silently crashes the React app. The `stages-console` spec's `.titan-app-bar`
  assertion guards this.
- The Titan rig has **no auth** — do not copy `auth.setup.ts` for it.
- The rig DB is ephemeral (local `postgres`, `down -v` wipes it) — the suite
  must seed its own job + build, or run against a known-seeded rig.
- A Titan build is asynchronous (queue + worker) — after seeding, **poll**
  `titan.builds.status` or the UI until the build is terminal before asserting
  on a finished graph.

## 7. Done-when

- A `titan` Playwright project runs `npm test` (or `test:titan`) green against
  a seeded local Titan rig.
- The 5 specs above pass; each asserts 0 console errors.
- The doubled-sidebar, doubled-URL, and `IntersectionObserver` regressions each
  have an explicit guarding assertion.
- A short run note in this harness's README (how to bring the Titan rig up +
  run the Titan project).
- Sibling note: the JSON-contract IT layer (§2) is done or filed as its own
  task.
