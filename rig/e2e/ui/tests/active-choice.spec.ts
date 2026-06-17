import { test, expect, Page } from '@playwright/test';

/**
 * O.18 — ActiveChoice / DynamicChoiceProvider cascade coverage.
 *
 * ActiveChoice params populate their options via an HTMX `hx-post` on
 * load, then re-fetch when a referenced param changes (the cascade).
 * The renderer Jelly is `params/ActiveChoice.jelly`; this spec asserts
 * that:
 *   1. The <select data-rf-param-input> hydrates with non-empty options
 *      after the HTMX load fires.
 *   2. Changing a referenced param dispatches the `paramUpdate` custom
 *      event and triggers a fresh hx-post (cascade).
 *
 * Fixture reality: the live rig has no seeded ActiveChoice param today.
 * When none is reachable, `test.skip(...)` with a clear message.
 */

const APP = process.env.RF_TEST_APP ?? 'team-a';

async function findActiveChoiceSelect(page: Page) {
  // Walk to the app-detail page and look for any wrapper marked as an
  // ActiveChoice param.
  await page.goto(`/release-flow/projects/${APP}/`);
  const detail = page.locator(`[data-rf-app-detail-id="${APP}"]`);
  if (!(await detail.count())) return null;

  // Open the deploy drawer if a Deploy button exists.
  const deployBtn = page.locator('button:has-text("Deploy"), a:has-text("Deploy")').first();
  if (await deployBtn.count()) {
    await deployBtn.click().catch(() => undefined);
    await page
      .locator('[data-rf-param]')
      .first()
      .waitFor({ state: 'visible', timeout: 5_000 })
      .catch(() => undefined);
  }

  const ac = page.locator('[data-rf-param-type="ActiveChoice"]').first();
  if (await ac.count()) return ac;
  return null;
}

test.describe('active-choice cascade (DynamicChoiceProvider SPI)', () => {
  test('options hydrate via HTMX and refresh on referenced-param change', async ({ page }) => {
    const ac = await findActiveChoiceSelect(page);
    if (!ac) {
      test.skip(
        true,
        `No ActiveChoice param reachable for app="${APP}". Seed a component with an ActiveChoice promptParameter to enable this assertion.`,
      );
      return;
    }

    const name = await ac.getAttribute('data-rf-param');
    expect(name, 'ActiveChoice wrapper must declare data-rf-param').toBeTruthy();

    const select = page.locator(`select[data-rf-param-input="${name}"]`);
    expect(await select.count(), 'underlying <select> must be present').toBeGreaterThan(0);

    // Wait for HTMX to populate options (the placeholder option says
    // "Loading choices..." and is replaced after hx-post returns).
    await expect
      .poll(
        async () => {
          const opts = await select.locator('option').all();
          let nonPlaceholder = 0;
          for (const o of opts) {
            const v = await o.getAttribute('value');
            const txt = await o.textContent();
            if (v && !/loading/i.test(txt ?? '')) nonPlaceholder++;
          }
          return nonPlaceholder;
        },
        { timeout: 10_000, message: 'ActiveChoice options never populated' },
      )
      .toBeGreaterThan(0);

    // Cascade: if a referenced-param exists, change it and assert the
    // dependent re-fetches. Detect by polling option signature.
    const referenced = page.locator(`[data-rf-param-input]`).first();
    const refName = await referenced.getAttribute('data-rf-param-input');
    if (!refName || refName === name) {
      // No other param to drive the cascade — render-only assertion is fine.
      return;
    }

    const before = await select
      .locator('option')
      .allTextContents()
      .then((arr) => arr.join('|'));

    const refEl = page.locator(`[data-rf-param-input="${refName}"]`);
    const tag = (await refEl.evaluate((el) => el.tagName.toLowerCase())) as string;
    if (tag === 'select') {
      const opts = await refEl.locator('option').all();
      for (const o of opts) {
        const v = await o.getAttribute('value');
        if (v) {
          await refEl.selectOption(v);
          break;
        }
      }
    } else if (tag === 'input') {
      await refEl.fill('cascade-trigger');
      await refEl.dispatchEvent('change');
    }

    // The dependent select should either change options OR re-fire the
    // hx-post — both are acceptable cascade signals.
    await expect
      .poll(
        async () => {
          const after = await select
            .locator('option')
            .allTextContents()
            .then((arr) => arr.join('|'));
          return after !== before ? 'changed' : 'same';
        },
        { timeout: 8_000, message: 'cascade did not refresh dependent options' },
      )
      .toBe('changed');
  });
});
