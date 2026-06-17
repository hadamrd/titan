import { test as setup, expect } from '@playwright/test';
import * as fs from 'node:fs';
import * as path from 'node:path';

/**
 * Login once and persist the session cookie. The other test projects re-use
 * this storageState so they don't re-do the login form on every spec.
 *
 * Required env:
 *   RF_TITAN_USER       — defaults to "admin"
 *   RF_TITAN_PASSWORD   — admin password / API token. The wrapper script
 *                           rig/k3s/scripts/run-ui-tests.sh injects this
 *                           from Infisical. NEVER inline.
 */

const STORAGE = 'storage/admin.json';

setup('authenticate', async ({ page }) => {
  const user = process.env.RF_TITAN_USER ?? 'admin';
  const pw = process.env.RF_TITAN_PASSWORD;
  if (!pw) {
    throw new Error('RF_TITAN_PASSWORD env var required (Infisical key RELEASE_FLOW_TEST_TITAN_ADMIN_PASSWORD).');
  }

  await page.goto('/login?from=%2Frelease-flow%2F');
  await page.locator('input[name="j_username"]').fill(user);
  await page.locator('input[name="j_password"]').fill(pw);
  await page.locator('button:has-text("Sign in"), input[type="submit"]').first().click();

  await page.waitForURL(/\/release-flow\/?$/);
  await expect(page.locator('h1:has-text("Release Flow")')).toBeVisible();

  fs.mkdirSync(path.dirname(STORAGE), { recursive: true });
  await page.context().storageState({ path: STORAGE });
});
