import { defineConfig, devices } from '@playwright/test';

/**
 * Titan E2E harness — design/43.
 *
 * Two test surfaces, one project:
 *   - scenarios.spec.ts  — the generic scenario runner (43-S); loads every
 *     scenarios/*.e2e.yaml, submits the pipeline, polls the DAG API, asserts.
 *   - specs/*.spec.ts    — hand-written UI-flow tests (43-C) through Chromium.
 *
 * Rig resolution (design/43 §3):
 *   - TITAN_RIG_URL set  -> run against that already-running rig (dev loop / CI
 *     where the stack is brought up by the CI job before `playwright test`).
 *   - TITAN_RIG_URL unset + TITAN_RIG_EPHEMERAL=1 -> global-setup brings an
 *     ephemeral docker-compose stack up and tears it down after.
 *   - neither -> defaults to the local dev rig at http://localhost:18080.
 *
 * The ephemeral path is the design's default-once-stable; the env-var path is
 * what keeps the harness runnable today and in CI without flaky compose wiring.
 */

// Default base URL: the v3 Titan-only rig serves the SPA through nginx on
// localhost:5180 (which also reverse-proxies /api → titan-server:8080). The
// legacy `scenarios.spec.ts` (controller-bound) sets TITAN_RIG_URL=http://localhost:18080
// or relies on the ephemeral compose path — both still work.
const RIG_URL = process.env.TITAN_RIG_URL ?? 'http://localhost:5180';
const EPHEMERAL = process.env.TITAN_RIG_EPHEMERAL === '1';
const IS_CI = !!process.env.CI;

export default defineConfig({
  testDir: '.',
  testMatch: ['scenarios.spec.ts', 'specs/**/*.spec.ts'],
  // E2E is slow + the rig has shared state — never parallelise across files.
  fullyParallel: false,
  // v3 specs mutate flow_node rows; keep workers modest. The legacy
  // scenarios.spec.ts still asserts under workers=1 — kept here as the floor.
  workers: Number(process.env.TITAN_PW_WORKERS ?? (IS_CI ? 2 : 1)),
  forbidOnly: IS_CI,
  // CI may re-run flaky network blips; local must surface failures immediately.
  retries: IS_CI ? 2 : 0,
  // Hard per-test wall-clock cap. A scenario submits a pipeline, polls the DAG
  // (each poll itself bounded to TITAN_POLL_TIMEOUT_MS, default 2min), and may
  // wait on a gate (also 2min). Worst legitimate case — submit + gate-wait +
  // poll-to-completion — fits comfortably under this; anything slower is a
  // genuine hang and Playwright KILLS the test here. Never a 20-minute run.
  // Override on a slow rig with TITAN_TEST_TIMEOUT_MS.
  timeout: Number(process.env.TITAN_TEST_TIMEOUT_MS ?? 8 * 60 * 1000),
  // A single API assertion / locator must settle fast.
  expect: { timeout: 15 * 1000 },
  // Global setup brings the ephemeral rig up (a cold JVM + Flyway boot); cap it
  // so a wedged compose surfaces as a timeout, not an unbounded hang.
  globalTimeout: Number(process.env.TITAN_GLOBAL_TIMEOUT_MS ?? 20 * 60 * 1000),
  reporter: [
    ['list'],
    ['html', { open: 'never', outputFolder: 'playwright-report' }],
    ['junit', { outputFile: 'playwright-report/results.xml' }],
  ],
  // Only wire global setup/teardown when the harness owns the rig. Pointing at
  // an existing rig skips compose entirely.
  ...(EPHEMERAL
    ? {
        globalSetup: './fixtures/global-setup.ts',
        globalTeardown: './fixtures/global-teardown.ts',
      }
    : {}),
  use: {
    baseURL: RIG_URL,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
    // The rig runs no security (jcasc.yaml: "No security"); no auth needed.
    ignoreHTTPSErrors: true,
  },
  projects: [
    {
      name: 'titan-e2e',
      use: { ...devices['Desktop Chrome'] },
    },
  ],
});
