# node-app-with-failing-test

Fixture for issue [#1130](https://github.com/hadamrd/dashboard-plugin/issues/1130) — the
SRE's day-zero failure-triage e2e flow (`e2e/specs/golden-path-failure-triage.spec.ts`).

## Shape

A minimal Node package with a vitest suite. **Exactly one** test fails on
purpose: `expect(1 + 1).toBe(3)`.

```
src/
  sum.js              one-function module under test
  sum.test.js         vitest suite — 2 passing + 1 failing
titan-pipeline.yml    install -> unit-test (FAIL) -> build (SKIPPED)
```

## Fixture sanity check

From a clean clone:

```bash
cd e2e/pipelines/node-app-with-failing-test
pnpm install
pnpm test    # exits non-zero, "Tests  1 failed | 2 passed"
```

The e2e spec then:

1. Triggers a build, asserts it transitions to **FAILURE within 30 s**.
2. Opens the build-detail page, asserts the `unit-test` step renders with a
   red/error accessible status.
3. Clicks the failing step, asserts the log viewport contains a line matching
   `/FAIL /`.
4. Triggers a per-step re-run (or full re-run; see `test.fixme` notes in the
   spec for the per-step affordance gap).
5. Patches the failing test in-place to passing and asserts the next build
   transitions to **SUCCESS**.

## Do not

- Add more failing tests — the spec asserts exactly one.
- Add real network calls — the worker runs `npm install` once; everything
  else must be hermetic.
