import { test, expect } from '@playwright/test';

/**
 * App detail page — design 18-moabs.md §5: rows = Moabs that touched this
 * app, cols = envs, cells = deploy state. Filter: hide rows where the app's
 * pinned version didn't change.
 */

const APP_WITH_MOABS = process.env.RF_TEST_APP ?? 'api-gateway';

test.describe('app detail (Moabs × envs grid)', () => {
  test('direct navigation hydrates SPA shell + grid', async ({ page }) => {
    await page.goto(`/release-flow/projects/${APP_WITH_MOABS}/`);
    await page.waitForSelector(`[data-rf-app-detail-id="${APP_WITH_MOABS}"]`, { state: 'visible' });

    // Header + breadcrumb-style id pill.
    await expect(page.locator(`h1:has-text("${APP_WITH_MOABS}")`).first()).toBeVisible();

    // Filter checkbox.
    await expect(page.locator('input[data-rf-filter-toggle="hideUnchanged"]')).toBeVisible();

    // Either the empty state or the Moabs × envs table.
    const noMoabsBanner = page.locator('text=/(no Moabs|isnt in any Moabs)/i');
    const grid = page.locator('table');
    if (await noMoabsBanner.count()) {
      await expect(noMoabsBanner.first()).toBeVisible();
    } else {
      await expect(grid.first()).toBeVisible();
      // Column headers: Moab + at least one env.
      await expect(grid.locator('th').first()).toContainText(/Moab/i);
    }
  });

  test('navigate via the home grid View-app link (SPA in-place swap)', async ({ page }) => {
    await page.goto('/release-flow/');
    await page.waitForSelector('.rf-app-card', { state: 'visible' });
    const target = page.locator(`.rf-app-card[data-rf-app-id="${APP_WITH_MOABS}"] a:has-text("View app")`).first();
    if (!(await target.count())) {
      test.skip(true, `no card for ${APP_WITH_MOABS}`);
    }
    await target.click();
    await page.waitForURL(new RegExp(`/release-flow/projects/${APP_WITH_MOABS}/?$`));
    await expect(page.locator(`[data-rf-app-detail-id="${APP_WITH_MOABS}"]`)).toBeVisible();
  });

  test('clicking a Moab row name navigates to /moabs/<id>/', async ({ page }) => {
    await page.goto(`/release-flow/projects/${APP_WITH_MOABS}/`);
    const moabLink = page.locator('table tbody tr a[href*="/release-flow/moabs/"]').first();
    if (!(await moabLink.count())) {
      test.skip(true, 'app has no Moabs to drill into yet');
    }
    const href = await moabLink.getAttribute('href');
    await moabLink.click();
    await page.waitForURL(new RegExp(href!.replace(/[/$.+()|^]/g, '\\$&')));
    await expect(page.locator('text=/MOAB/i').first()).toBeVisible();
  });
});
