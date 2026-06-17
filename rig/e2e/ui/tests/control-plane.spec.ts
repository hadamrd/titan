import { test, expect } from '@playwright/test';

/**
 * Control plane (`/release-flow/control/`) — design 10-ui-and-personas.md
 * §"Control plane". Five sub-pages: calendar, approvals, freezes, andon,
 * audit. The index lists them as a card grid; each card title links to its
 * sub-page.
 */

const SUB_PAGES = [
  { title: /Calendar/i, href: 'calendar' },
  { title: /Pending approvals/i, href: 'approvals' },
  { title: /Freezes/i, href: 'freezes' },
  { title: /Andon/i, href: 'andon' },
  { title: /Audit/i, href: 'audit' },
];

test.describe('control plane', () => {
  test('index renders 5 sub-page cards (one parsed `<a>` per sub-page)', async ({ page }) => {
    await page.goto('/release-flow/control/');
    await expect(page.locator('h1:has-text("Control plane")')).toBeVisible();

    // Each sub-page link should be a single anchor (regression: a `<p>` close-tag
    // was being dropped before `</a>` which split each card into 2-3 anchor
    // fragments — see commit 3a02952's follow-up).
    for (const { href } of SUB_PAGES) {
      const anchors = page.locator(`a[href="${href}"]`);
      const count = await anchors.count();
      expect(count, `expected exactly 1 anchor for /${href} card; got ${count}`).toBe(1);
    }
  });

  test.describe('each sub-page loads', () => {
    for (const { title, href } of SUB_PAGES) {
      test(`sub-page "${href}" loads via plain navigation`, async ({ page }) => {
        await page.goto(`/release-flow/control/${href}`);
        // Wait for the SPA fragment to hydrate into #main-content. The
        // router fetches asynchronously; we poll for non-empty content.
        // Poll on the actual content match to avoid the
        // "length>0 but content not yet hydrated" race.
        await expect
          .poll(
            async () => await page.locator('#main-content').innerText(),
            { timeout: 12_000, intervals: [200, 400, 800, 1600] },
          )
          .toMatch(title);
      });
    }
  });
});
