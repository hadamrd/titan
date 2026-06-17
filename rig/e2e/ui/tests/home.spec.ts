import { test, expect } from '@playwright/test';

/**
 * Home page smoke checks — the first thing every persona sees.
 * Anchored on design/10-ui-and-personas.md §"Home page" + design/18-moabs.md
 * §5 (Apps + Moabs tabs).
 */

test.describe('home page', () => {
  test('renders Apps + Moabs tabs (design 18 §5)', async ({ page }) => {
    await page.goto('/release-flow/');
    await expect(page).toHaveTitle(/Release Flow/);

    // Title strip + tagline.
    await expect(page.locator('h1:has-text("Release Flow")')).toBeVisible();

    // Tab labels — design 18 retired the old Domains/Components naming.
    const appsTab = page.locator('button#projectsTab, button:has-text("Apps")').first();
    const moabsTab = page.locator('button#componentsTab, button:has-text("Moabs")').first();
    await expect(appsTab).toBeVisible();
    await expect(moabsTab).toBeVisible();
    await expect(appsTab).toHaveText(/Apps/);
    await expect(moabsTab).toHaveText(/Moabs/);
  });

  test('Apps tab shows app cards with owner-team + per-env chips per design 10', async ({ page }) => {
    await page.goto('/release-flow/');
    // Wait for the home grid fragment to hydrate.
    await page.waitForSelector('.rf-app-card', { state: 'visible' });

    const cards = page.locator('.rf-app-card');
    expect(await cards.count()).toBeGreaterThan(0);

    // The first card with envs should expose the per-env list (rf-card-envs ul).
    const cardWithEnvs = page.locator('.rf-app-card', { has: page.locator('.rf-card-envs') }).first();
    if (await cardWithEnvs.count()) {
      const envItems = cardWithEnvs.locator('.rf-card-envs li[data-rf-env]');
      expect(await envItems.count()).toBeGreaterThan(0);
    }

    // At least one card should declare an owner team (the seeded team-a +
    // team-b have one).
    expect(await page.locator('.rf-card-owner').count()).toBeGreaterThan(0);
  });

  test('Moabs tab loads the Moabs grid', async ({ page }) => {
    await page.goto('/release-flow/');
    await page.locator('button#componentsTab').click();
    await page.waitForURL(/\/release-flow\/moabs\/?$/);
    // Either at least one card OR the documented empty state.
    await expect
      .poll(
        async () => {
          const c = await page.locator('.rf-moab-card').count();
          if (c > 0) return 'cards';
          const e = await page.locator('text=/no moabs/i').count();
          return e > 0 ? 'empty' : 'unknown';
        },
        { timeout: 10_000 },
      )
      .not.toBe('unknown');

    const cardCount = await page.locator('.rf-moab-card').count();
    if (cardCount > 0) {
      await expect(page.locator('.rf-moab-card a[href*="/moabs/"]').first()).toBeVisible();
    } else {
      await expect(page.locator('text=/No Moabs/i').first()).toBeVisible();
    }
  });
});
