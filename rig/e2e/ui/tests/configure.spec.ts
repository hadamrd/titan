import { test, expect } from '@playwright/test';

/**
 * Configure page (`/release-flow/configure`) — design 10-ui-and-personas.md
 * §"Configure / admin pages". Just a smoke check that the page renders and
 * the Save / Cancel actions are visible (regression: bg-blue-600 was being
 * stomped by the manual button reset in styles.css, leaving the Save
 * button as white-on-transparent).
 */

test.describe('configure page', () => {
  test('renders Save changes + Cancel buttons with visible backgrounds', async ({ page }) => {
    await page.goto('/release-flow/configure');
    const save = page.locator('button:has-text("Save changes")').first();
    const cancel = page.locator('button:has-text("Cancel")').first();

    await expect(save).toBeVisible();
    await expect(cancel).toBeVisible();

    // Regression: the bg-blue-600 utility was losing to a more-specific
    // button { background-color: transparent } reset, leaving the Save
    // button as white text on a transparent background. Assert the
    // computed background is NOT transparent on the primary action.
    const saveBg = await save.evaluate((el) => getComputedStyle(el).backgroundColor);
    expect(saveBg, 'Save button should have a non-transparent background').not.toBe(
      'rgba(0, 0, 0, 0)',
    );
    expect(saveBg).not.toBe('transparent');
  });
});
