/**
 * Dedicated Playwright config for the operator tour (#1132).
 *
 * Separate from the main e2e playwright.config.ts so the tour can:
 *   - opt out of `globalSetup` / TestContainers wiring,
 *   - run its own viewport-iterated capture without polluting the e2e project,
 *   - be invoked via `task tour:run` against a populated `task dev:titan` rig.
 *
 * Determinism + viewport sizes are driven by the spec itself (it iterates
 * desktop 1440×900 and mobile 375×812 inside a single test process).
 */
import { defineConfig } from '@playwright/test'

const RIG_URL = process.env.TITAN_RIG_URL ?? process.env.TITAN_UI_URL ?? 'http://localhost:5180'

export default defineConfig({
  testDir: '.',
  testMatch: ['tour.spec.ts'],
  fullyParallel: false,
  workers: 1,
  // The capture pass is bounded by per-test setTimeout in the spec (5min).
  timeout: 6 * 60 * 1000,
  expect: { timeout: 15 * 1000 },
  reporter: [['list']],
  use: {
    baseURL: RIG_URL,
    ignoreHTTPSErrors: true,
    // Don't auto-snapshot on failure — the tour IS the screenshot.
    screenshot: 'off',
    video: 'off',
    trace: 'retain-on-failure',
  },
})
