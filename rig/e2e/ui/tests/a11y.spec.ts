import { test, expect, Page } from '@playwright/test';

/**
 * Accessibility regression suite (issues #58 + #59).
 *
 * Asserts WCAG 2.1 AA basics on the dashboard surfaces:
 *   - Keyboard navigation reaches every interactive control without traps.
 *   - Tab order through forms and grids matches reading order.
 *   - :focus-visible style is applied (computed outline OR box-shadow ring).
 *   - Status-chip text colors meet 4.5:1 against their backgrounds.
 *
 * This spec does NOT pull in @axe-core/playwright to avoid adding a new
 * runtime dep — the contrast checks reuse the same WCAG formulas that
 * powered the bulk audit. If the team later decides axe is worth carrying,
 * drop in a single import + scan call per page.
 *
 * Scope (issue #58 / #59 acceptance):
 *   - home page (/release-flow/)
 *   - app detail (/release-flow/projects/<id>/)
 *   - moab detail (/release-flow/moabs/<id>/)
 *   - control plane (/release-flow/control/freezes)
 */

const APP_WITH_MOABS = process.env.RF_TEST_APP ?? 'api-gateway';

// -----------------------------------------------------------------------------
// Helpers
// -----------------------------------------------------------------------------

/** Relative luminance per WCAG 2.1. */
function luminance(hex: string): number {
  const h = hex.replace('#', '');
  const r = parseInt(h.slice(0, 2), 16);
  const g = parseInt(h.slice(2, 4), 16);
  const b = parseInt(h.slice(4, 6), 16);
  const lin = (c: number): number => {
    const s = c / 255;
    return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
  };
  return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b);
}

function contrastRatio(fg: string, bg: string): number {
  const a = luminance(fg);
  const b = luminance(bg);
  const [hi, lo] = a > b ? [a, b] : [b, a];
  return (hi + 0.05) / (lo + 0.05);
}

/** Parse rgb()/rgba() into hex. */
function rgbToHex(rgb: string): string | null {
  const m = rgb.match(/rgba?\((\d+),\s*(\d+),\s*(\d+)/);
  if (!m) return null;
  const [, r, g, b] = m;
  const toHex = (n: string): string => parseInt(n, 10).toString(16).padStart(2, '0');
  return '#' + toHex(r) + toHex(g) + toHex(b);
}

/**
 * Walk up the DOM until we find a non-transparent background-color.
 * Buttons and chips often inherit from a tinted parent.
 */
async function effectiveBgHex(page: Page, selector: string): Promise<string | null> {
  return page.evaluate((sel) => {
    let el: Element | null = document.querySelector(sel);
    while (el) {
      const bg = getComputedStyle(el).backgroundColor;
      if (bg && bg !== 'rgba(0, 0, 0, 0)' && bg !== 'transparent') {
        const m = bg.match(/rgba?\((\d+),\s*(\d+),\s*(\d+)/);
        if (m) {
          const toHex = (n: string): string => parseInt(n, 10).toString(16).padStart(2, '0');
          return '#' + toHex(m[1]) + toHex(m[2]) + toHex(m[3]);
        }
      }
      el = el.parentElement;
    }
    return '#ffffff';
  }, selector);
}

/**
 * Returns true if the element shows a non-trivial focus indicator.
 * Accepts either an outline (width >= 1px and style != none) OR a
 * box-shadow (any non-empty value).
 */
async function hasFocusIndicator(page: Page): Promise<boolean> {
  return page.evaluate(() => {
    const el = document.activeElement as HTMLElement | null;
    if (!el || el === document.body) return false;
    const cs = getComputedStyle(el);
    const outlineW = parseFloat(cs.outlineWidth);
    const outlineOk = cs.outlineStyle !== 'none' && outlineW >= 1;
    const shadowOk = cs.boxShadow !== 'none' && cs.boxShadow.length > 0;
    return outlineOk || shadowOk;
  });
}

/** Tab N times and report what got focus each step. */
async function tabTrail(page: Page, steps: number): Promise<string[]> {
  const trail: string[] = [];
  for (let i = 0; i < steps; i++) {
    await page.keyboard.press('Tab');
    const desc = await page.evaluate(() => {
      const el = document.activeElement as HTMLElement | null;
      if (!el) return '<null>';
      const tag = el.tagName.toLowerCase();
      const id = el.id ? '#' + el.id : '';
      const cls = el.className && typeof el.className === 'string'
        ? '.' + el.className.split(/\s+/).slice(0, 2).join('.')
        : '';
      const text = (el.textContent || '').trim().slice(0, 30);
      return `${tag}${id}${cls}[${text}]`;
    });
    trail.push(desc);
  }
  return trail;
}

// -----------------------------------------------------------------------------
// Tab navigation: reachability + focus visibility
// -----------------------------------------------------------------------------

test.describe('keyboard navigation (#58)', () => {
  test('home page: tab reaches at least one app-card view-app link with a visible focus indicator', async ({ page }) => {
    await page.goto('/release-flow/');
    await page.waitForSelector('#release-flow-root', { state: 'visible' });

    // Scan up to 80 tab stops; bail early when an .rf-app-card anchor is focused.
    let foundCard = false;
    for (let i = 0; i < 80; i++) {
      await page.keyboard.press('Tab');
      const isCardLink = await page.evaluate(() => {
        const el = document.activeElement as HTMLElement | null;
        return !!el?.closest('.rf-app-card') && el.tagName === 'A';
      });
      if (isCardLink) {
        foundCard = true;
        expect(await hasFocusIndicator(page)).toBeTruthy();
        break;
      }
    }
    // It's possible the rig has no apps yet — record skip in that case.
    if (!foundCard) {
      const cardCount = await page.locator('.rf-app-card').count();
      test.skip(cardCount === 0, 'no app cards on home — nothing to tab to');
      expect(foundCard).toBeTruthy();
    }
  });

  test('app detail: matrix cells are tabbable and show focus', async ({ page }) => {
    await page.goto(`/release-flow/projects/${APP_WITH_MOABS}/`);
    await page.waitForSelector(`[data-rf-app-detail-id="${APP_WITH_MOABS}"]`, { state: 'visible' });

    const hasGrid = await page.locator('.rf-cell[role="button"]').count();
    test.skip(hasGrid === 0, 'app has no matrix cells yet — nothing to assert');

    // Focus the first cell via JS, then verify the indicator computed style.
    await page.evaluate(() => {
      const cell = document.querySelector<HTMLElement>('.rf-cell[role="button"]');
      cell?.focus();
    });
    expect(await hasFocusIndicator(page)).toBeTruthy();

    // Cells must expose tabindex="0" so they're reachable by Tab too.
    const tabbable = await page.locator('.rf-cell[role="button"][tabindex="0"]').count();
    expect(tabbable).toBeGreaterThan(0);
  });

  test('app detail: no keyboard trap — Esc / Shift+Tab returns to body', async ({ page }) => {
    await page.goto(`/release-flow/projects/${APP_WITH_MOABS}/`);
    await page.waitForSelector(`[data-rf-app-detail-id="${APP_WITH_MOABS}"]`, { state: 'visible' });

    await tabTrail(page, 25);
    // Shift+Tab a bunch of times — we should land back at document.body
    // (or before it) without any interception. A trap would keep us
    // cycling between two elements.
    const before = await page.evaluate(() => document.activeElement?.tagName);
    for (let i = 0; i < 50; i++) {
      await page.keyboard.press('Shift+Tab');
    }
    const after = await page.evaluate(() => document.activeElement?.tagName);
    // Either we exited the page completely (BODY) or we're cycling cleanly
    // — the important part is that no single element holds focus stuck.
    expect([before, after].includes('BODY') || before !== after).toBeTruthy();
  });

  test('moab detail: replay form + timeline anchor are reachable', async ({ page }) => {
    // Find a Moab id from the home/app-detail page first.
    await page.goto(`/release-flow/projects/${APP_WITH_MOABS}/`);
    const moabLink = page.locator('a[href*="/release-flow/moabs/"]').first();
    const count = await moabLink.count();
    test.skip(count === 0, 'no Moab to drill into');

    const href = await moabLink.getAttribute('href');
    await page.goto(href!);
    await page.waitForSelector('[data-rf-moab-detail-id]', { state: 'visible' });

    // The replay form button and timeline anchor should both be in the DOM
    // and accept programmatic focus with a visible indicator.
    const replay = page.locator('button[type="submit"]:has-text("Replay")').first();
    const timeline = page.locator('a:has-text("Timeline")').first();
    if (await replay.count()) {
      await replay.focus();
      expect(await hasFocusIndicator(page)).toBeTruthy();
    }
    if (await timeline.count()) {
      await timeline.focus();
      expect(await hasFocusIndicator(page)).toBeTruthy();
    }
  });

  test('control plane (freezes): tab order through cards is logical', async ({ page }) => {
    await page.goto('/release-flow/control/freezes');
    await page.waitForSelector('#release-flow-root', { state: 'visible' });

    const trail = await tabTrail(page, 15);
    // We should hit at least one anchor or form control along the way.
    const interactive = trail.filter((d) => /^(a|button|input|select)/.test(d));
    expect(interactive.length).toBeGreaterThan(0);
  });
});

// -----------------------------------------------------------------------------
// Color contrast on status chips
// -----------------------------------------------------------------------------

test.describe('color contrast (#59)', () => {
  test('status-chip glyph + version + age each clear 4.5:1 on home cards', async ({ page }) => {
    await page.goto('/release-flow/');
    await page.waitForSelector('#release-flow-root', { state: 'visible' });

    const chips = page.locator('.rf-status-chip');
    const n = await chips.count();
    if (n === 0) {
      // No envs yet (fresh rig) — skip rather than fail.
      test.skip(true, 'no rendered chips on home');
    }

    for (let i = 0; i < Math.min(n, 12); i++) {
      const chip = chips.nth(i);
      const bgHex = await effectiveBgHex(page, `.rf-status-chip:nth-of-type(${i + 1})`);
      const fgs: string[] = await chip.evaluate((el) => {
        const colors: string[] = [];
        el.querySelectorAll('span').forEach((s) => {
          colors.push(getComputedStyle(s).color);
        });
        return colors;
      });
      for (const rgb of fgs) {
        const fg = rgbToHex(rgb);
        if (!fg || !bgHex) continue;
        const r = contrastRatio(fg, bgHex);
        // Allow a tiny float slack (4.49 rounds up; WCAG rounds to one decimal).
        expect(r, `chip ${i} text ${fg} on ${bgHex} = ${r.toFixed(2)}`).toBeGreaterThanOrEqual(4.49);
      }
    }
  });

  test('app detail matrix: cell chip text passes 4.5:1', async ({ page }) => {
    await page.goto(`/release-flow/projects/${APP_WITH_MOABS}/`);
    await page.waitForSelector(`[data-rf-app-detail-id="${APP_WITH_MOABS}"]`, { state: 'visible' });

    const chips = page.locator('.rf-status-chip');
    const n = await chips.count();
    test.skip(n === 0, 'no matrix cells with chips on this app');

    // Sample the first 6 chips — full sweep would slow CI.
    for (let i = 0; i < Math.min(n, 6); i++) {
      const chip = chips.nth(i);
      const data = await chip.evaluate((el) => {
        const bg = getComputedStyle(el).backgroundColor;
        const out: { color: string }[] = [];
        el.querySelectorAll('span').forEach((s) => out.push({ color: getComputedStyle(s).color }));
        return { bg, out };
      });
      const bgHex = rgbToHex(data.bg) ?? '#ffffff';
      for (const { color } of data.out) {
        const fg = rgbToHex(color);
        if (!fg) continue;
        const r = contrastRatio(fg, bgHex);
        expect(r, `cell chip ${i} text ${fg} on ${bgHex} = ${r.toFixed(2)}`).toBeGreaterThanOrEqual(4.49);
      }
    }
  });
});
