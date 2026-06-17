# UI smoke + visual-regression tests (Playwright)

Lives next to the Moab e2e harness (`release-flow-plugin/e2e/moab/`). Drives the live
rig dashboard at <https://titan.test.example.com/release-flow/> with
Playwright + Chromium and asserts the design contracts from
`design/10-ui-and-personas.md` and `design/18-moabs.md` §5.

## Why Playwright

The existing controller-harness + HtmlUnit tests in `src/test/java/` give us per-class
HTML rendering coverage but they don't:

- Run against the actual deployed bundle (Vite-built TS, real CSP,
  real htmx + Navigo).
- Catch SPA navigation regressions (the kind that "works as plain
  HTML POST but renders as text on a real browser" — exactly what a
  Playwright run caught when `AppDetailPage.doIndex` was missing).
- Assert localized strings against the UI as rendered, not against
  the bundle (catches the `${%key}` lookup gotcha that put 21 unresolved
  keys onscreen).

This suite covers those gaps. It is **not** a replacement for the Java tests
— they're cheaper and run on every `mvn verify`.

## Spec layout

```
tests/
  auth.setup.ts          — login once, persist storageState (admin.json)
  home.spec.ts           — Apps + Moabs tabs; home grid card shape
  app-detail.spec.ts     — Moabs × envs grid; SPA navigation; click-through
  moab-detail.spec.ts    — History/Apps/Runs sub-tabs; i18n key leak detector
  design-no-leaks.spec.ts — cross-page console-error + raw-key invariants
```

## Run

Local against the local controller (no auth — anonymous reads permitted):

```bash
RF_BASE=http://localhost:8080 npm test
```

Against the live rig (requires Infisical-injected admin password):

```bash
bash ../../scripts/run-ui-tests.sh
```

The wrapper script reads `RELEASE_FLOW_TEST_TITAN_ADMIN_PASSWORD` from
Infisical and exports it as `RF_TITAN_PASSWORD` before invoking
`npm test:rig`.

## CI

Runs in the GitLab Runner per DEC-030 (no GitHub Actions — NG13). The
`.gitlab-ci.yml` job spec:

```yaml
ui-e2e:
  stage: e2e
  image: mcr.microsoft.com/playwright:v1.49.0-jammy
  script:
    - cd rig/e2e/ui
    - npm ci
    - npx playwright install chromium --with-deps
    - bash ../../../scripts/run-ui-tests.sh
  artifacts:
    when: always
    paths:
      - rig/e2e/ui/playwright-report/
    expire_in: 1 week
```

## Visual regression

`expect(...).toHaveScreenshot()` is supported but no baselines are committed
yet — the rendering is still settling. Once stable we'll capture per-page
snapshots and tighten `maxDiffPixelRatio` to 0.001.

## When to add a test

- A user-visible bug ships and needs a regression. Write a spec that fails
  today against the rig, then drive the fix.
- A design doc names a new view surface (e.g., the Component-history page
  in `design/10-ui-and-personas.md` once it lands). Add a smoke check that
  asserts it loads + has the documented anchors.

## When NOT to add a test

- Coverage-for-coverage. Java tests are cheaper for parser / DAO /
  routing logic.
- Anything that requires more than 30s to run. Keep specs lean; this
  suite must be fast enough that CI runs it on every push without
  drag.
