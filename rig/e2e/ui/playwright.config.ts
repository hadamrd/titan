import { defineConfig, devices } from '@playwright/test';

/**
 * Playwright config for the release-flow UI smoke + visual-regression suite.
 *
 * Targets the live test rig at https://titan.test.example.com by default.
 * Override via RF_BASE env var or --base-url on the CLI when running locally
 * against the local controller (http://localhost:8080).
 *
 * Auth: the suite hits the login form once per worker (admin + password from
 * RF_TITAN_PASSWORD env var; loaded from Infisical by the wrapper script
 * rig/k3s/scripts/run-ui-tests.sh). The session cookie is shared via
 * storageState so each test starts logged in without round-tripping the form.
 */

const BASE_URL = process.env.RF_BASE ?? 'https://titan.test.example.com';

export default defineConfig({
  testDir: './tests',
  fullyParallel: false, // The dashboard UI doesn't love concurrent state mutations.
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  workers: 1,
  reporter: [
    ['list'],
    ['html', { outputFolder: 'playwright-report', open: 'never' }],
    ['json', { outputFile: 'playwright-report/results.json' }],
  ],
  use: {
    baseURL: BASE_URL,
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
    ignoreHTTPSErrors: true, // The rig's TLS may be self-signed during boot.
    // storageState is set per-project below — the setup project bootstraps
    // the file, then the chromium project consumes it. Setting it globally
    // breaks the first-run case (Playwright validates the path before the
    // setup project runs).
  },
  projects: [
    {
      name: 'setup',
      testMatch: /.*\.setup\.ts/,
    },
    {
      name: 'chromium',
      use: {
        ...devices['Desktop Chrome'],
        storageState: 'storage/admin.json',
      },
      dependencies: ['setup'],
    },
  ],
  expect: {
    // Visual-regression diffs: 0.2% pixel difference allowed. Tighten as the
    // styling stabilises.
    toHaveScreenshot: { maxDiffPixelRatio: 0.002 },
  },
});
