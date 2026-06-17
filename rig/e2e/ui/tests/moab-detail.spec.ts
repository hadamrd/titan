import { test, expect } from '@playwright/test';

/**
 * Moab detail — design 18-moabs.md §5: three sub-tabs (History / Apps /
 * Runs). History is the default.
 */

const MOAB = process.env.RF_TEST_MOAB ?? 'trunk-2026.05.09-staged';

test.describe('moab detail', () => {
  test('default tab is History; sub-tab nav present', async ({ page }) => {
    await page.goto(`/release-flow/moabs/${MOAB}/`);
    await page.waitForSelector(`[data-rf-moab-detail-id="${MOAB}"]`, { state: 'visible' });

    // Sub-tab nav has aria-current on the active tab.
    const nav = page.locator('nav[aria-label*="sub-tab"], nav[aria-label*="Sub-tab"]').first();
    await expect(nav).toBeVisible();
    await expect(nav.locator('[aria-current="page"]')).toContainText(/History/i);
  });

  test('all 4 i18n keys on Run actions resolve (no literal keys leaking)', async ({ page }) => {
    await page.goto(`/release-flow/moabs/${MOAB}/`);
    const body = page.locator('body');
    // None of these literal keys should appear in the rendered text.
    const literals = [
      'releaseflow.moab.actions.heading',
      'releaseflow.moab.actions.fire.help',
      'releaseflow.moab.actions.abortColumn',
      'releaseflow.moab.action.abortQueue',
    ];
    for (const lit of literals) {
      await expect(body, `literal i18n key "${lit}" leaked to UI`).not.toContainText(lit);
    }
    // The localized strings should be present.
    await expect(page.locator('text=/Run actions/i').first()).toBeVisible();
    await expect(page.locator('button:has-text("Fire")').first()).toBeVisible();
  });

  test('Apps sub-tab lists pinned apps with version-changed chips', async ({ page }) => {
    await page.goto(`/release-flow/moabs/${MOAB}/apps`);
    await page.waitForSelector(`[data-rf-moab-detail-id="${MOAB}"]`, { state: 'visible' });

    const navActive = page.locator('nav[aria-label*="sub-tab"] [aria-current="page"]');
    await expect(navActive).toContainText(/Apps/i);

    // Filter checkbox.
    await expect(page.locator('input[data-rf-filter-toggle="onlyChanged"]')).toBeVisible();

    // Scope to the moab-detail container so we don't pick up the controller
    // side-panel "Build Queue" table.
    const moabContainer = page.locator(`[data-rf-moab-detail-id="${MOAB}"]`).locator('..');
    const rows = moabContainer.locator('table tbody tr');
    await expect
      .poll(async () => {
        const c = await rows.count();
        const empty = await page.locator('text=/no apps in this moab/i').count();
        return c > 0 ? 'rows' : empty > 0 ? 'empty' : 'unknown';
      })
      .not.toBe('unknown');

    const rowCount = await rows.count();
    if (rowCount === 0) {
      test.skip(true, 'no moab_apps for fixture moab; seed first');
    }
    const cellText = await rows.first().innerText();
    expect(cellText, 'first row should mention changed/unchanged').toMatch(/(changed|unchanged)/i);
  });

  test('Runs sub-tab loads with N4 empty state', async ({ page }) => {
    await page.goto(`/release-flow/moabs/${MOAB}/runs`);
    await expect(page.locator('text=/No runs yet/i').first()).toBeVisible();
  });

  test('History cells do NOT echo the status word next to the version chip', async ({ page }) => {
    // Regression: deployedCell used to render a redundant "Success"/"Failed"
    // text chip beside the version, AND an sr-only sibling <span> that
    // leaked visually after Tailwind purged the utility. The redesigned
    // cell conveys status via colour + glyph + aria-label only.
    await page.goto(`/release-flow/moabs/${MOAB}/`);
    const cells = page
      .locator(`[data-rf-moab-detail-id="${MOAB}"]`)
      .locator('..')
      .locator('.rf-cell');
    await expect(cells.first()).toBeVisible();
    const n = await cells.count();
    for (let i = 0; i < n; i++) {
      const text = await cells.nth(i).innerText();
      expect(text, `cell #${i} should not echo a status word`).not.toMatch(
        /\b(Success|Failed|Pending|Skipped|Running|Queued)\b/,
      );
    }
  });

  test('each History row has a Replay button + Timeline link in its Row-actions cell', async ({
    page,
  }) => {
    await page.goto(`/release-flow/moabs/${MOAB}/`);
    const rows = page
      .locator(`[data-rf-moab-detail-id="${MOAB}"]`)
      .locator('..')
      .locator('tbody tr[data-rf-env-name]');
    await expect(rows.first()).toBeVisible();
    const n = await rows.count();
    expect(n, 'expected at least one history row').toBeGreaterThan(0);
    for (let i = 0; i < n; i++) {
      const row = rows.nth(i);
      // Row-actions cell carries the per-env Replay form (data-rf-action=
      // "replay-env"). Scope to that container so we don't pick up the
      // cell partial's hover-only Replay (data-rf-cell-action="replay").
      const actions = row.locator('form[data-rf-action="replay-env"]');
      await expect(actions, `row #${i} should have a per-env Replay form`).toHaveCount(1);
      await expect(actions.locator('button:has-text("Replay")')).toHaveCount(1);
      // Timeline is the sibling anchor in the same actions cell.
      const timeline = row.locator('a:has-text("Timeline")');
      await expect(timeline, `row #${i} should have a Timeline link`).toHaveCount(1);
      const href = await timeline.getAttribute('href');
      expect(href, `Timeline href should target this Moab + env`).toMatch(/\/moabs\/[^/]+\/runs\?env=/);
    }
  });
});
