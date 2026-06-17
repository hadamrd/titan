import { test, expect } from '@playwright/test';

/**
 * Cross-page invariants — leak detectors. Every Release Flow surface should
 * pass these regardless of seeded data.
 */

const PAGES = [
  '/release-flow/',
  '/release-flow/projects/',
  '/release-flow/moabs/',
];

test.describe('cross-page invariants', () => {
  for (const path of PAGES) {
    test(`no literal i18n keys leak on ${path}`, async ({ page }) => {
      await page.goto(path);
      // Wait for the route to settle (SPA hydration takes a tick).
      // Don't wait for networkidle — the SSE keepalive on /release-flow/events
      // means the request never goes idle. domcontentloaded + a hydration wait
      // is enough for the initial fragment swap.
      await page.waitForLoadState('domcontentloaded');
      await page.waitForTimeout(800);
      const text = await page.locator('body').innerText();
      // Patterns we never want to see in rendered text. Each one matches a
      // literal property-key shape that should have been resolved.
      const leakyPatterns = [
        /releaseflow\.moab\./i,
        /\$\{[%a-zA-Z]/, // raw Jelly placeholder
        /MISSING_TOKEN/, // .gitlab-ci.yml fallback marker — should never show in UI
        /\bReleaseFlow\.[A-Z][a-z]+\.[A-Z][a-z]/, // bundle key form like ReleaseFlow.Foo.Bar
      ];
      for (const re of leakyPatterns) {
        expect(text, `pattern ${re} leaked to UI on ${path}`).not.toMatch(re);
      }
    });

    test(`no console errors on ${path}`, async ({ page }) => {
      const errors: string[] = [];
      page.on('console', (msg) => {
        if (msg.type() === 'error') errors.push(msg.text());
      });
      await page.goto(path);
      // Don't wait for networkidle — the SSE keepalive on /release-flow/events
      // means the request never goes idle. domcontentloaded + a hydration wait
      // is enough for the initial fragment swap.
      await page.waitForLoadState('domcontentloaded');
      await page.waitForTimeout(800);
      // Allow common known warnings; fail on anything else.
      const filtered = errors.filter(
        (e) =>
          !/Failed to load resource.*favicon/i.test(e) &&
          !/Mixed Content.*adoptedStyleSheets/i.test(e),
      );
      expect(filtered, `console errors on ${path}:\n${filtered.join('\n')}`).toHaveLength(0);
    });
  }
});
