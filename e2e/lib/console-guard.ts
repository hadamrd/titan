/**
 * console-guard — shared zero-error assertion helper for the v3 parkour suite.
 *
 * The parkour suite (issue #1061) runs the whole v3 UI surface through the
 * grinder: long names, unicode, missing optional fields, flaky network. Every
 * spec needs the same "did the page actually paint cleanly?" check. That's
 * this module.
 *
 * Why a module instead of inlining: PR #1036's `scopes`-missing crash was
 * exactly the kind of bug a uniform console-guard catches — the API returned
 * a shape the UI didn't handle, the golden specs passed (no console error
 * because they hit a different code path), and the regression only surfaced
 * once an operator hit /profile#tokens with a real account. A single guard
 * applied to EVERY parkour route closes that hole.
 *
 * Design choices:
 *   - We attach via `page.on('console', ...)` not `page.on('pageerror', ...)`
 *     because React's error boundary swallows the throw and re-prints it as
 *     `console.error`. The "did we render undefined" sentinel below covers
 *     the boundary-rendered-fallback case.
 *   - The noise filter mirrors `16-click-everything-smoke.spec.ts` so we
 *     don't drift from the golden contract.
 *   - The DOM sentinels (`[object Object]`, `undefined`, `NaN`) catch the
 *     classes of bug where the JS runtime is happy but the rendered text is
 *     garbage — i.e. the page didn't crash but the user sees junk.
 */
import type { Page, ConsoleMessage } from '@playwright/test'
import { expect } from '@playwright/test'

/**
 * Console patterns we ignore. Mirror of `16-click-everything-smoke.spec.ts`.
 *
 * Drift here = drift between the golden smoke contract and the parkour
 * contract; if you add a new noisy log line that doesn't indicate a real
 * defect, add it BOTH here and in the smoke spec.
 */
export const CONSOLE_NOISE =
  /favicon|sourcemap|Failed to load resource|\[vite\]|net::ERR_ABORTED/i

/**
 * Forbidden substrings in the rendered body text. Every one of these maps to
 * a real bug class we've shipped at least once:
 *
 *   - `[object Object]` — a component stringified an object instead of
 *     pulling a field out of it. PR #1037's exhibit was this exact pattern
 *     on /profile.
 *   - `undefined`        — a formatter consumed an optional field without a
 *     default. #412 (job-name "#1" + NaN durations) was this class.
 *   - `NaN`              — duration math on `undefined - undefined`. #412.
 *   - `null` (standalone)— a JSON field rendered raw. We accept it inside
 *     longer words ("nullable", a literal copy choice), so the regex is
 *     anchored on word boundaries.
 */
export const FORBIDDEN_RENDERED_TEXT: { label: string; pattern: RegExp }[] = [
  { label: '[object Object]', pattern: /\[object Object\]/ },
  { label: 'bare undefined', pattern: /\bundefined\b/ },
  { label: 'NaN', pattern: /\bNaN\b/ },
  { label: 'bare null',  pattern: /(^|[\s>])null([\s<]|$)/ },
]

export interface ConsoleGuard {
  /** Stop listening to the page and assert zero unexpected console errors. */
  assertClean(label: string): void
  /** Snapshot of console errors collected so far (for debugging). */
  errors(): string[]
}

/**
 * Attach the console listener. Returns a guard whose `assertClean(label)`
 * detaches the listener and fails the test if any non-noise console error
 * was observed.
 *
 * Call as the FIRST line of a test that needs the guard. Always pair with
 * `guard.assertClean(<route>)` before the test ends — calling assertClean
 * detaches the listener so the next test doesn't inherit it.
 */
export function attachConsoleGuard(page: Page): ConsoleGuard {
  const errors: string[] = []
  const onConsole = (msg: ConsoleMessage) => {
    if (msg.type() === 'error' && !CONSOLE_NOISE.test(msg.text())) {
      errors.push(msg.text())
    }
  }
  page.on('console', onConsole)
  return {
    assertClean(label: string) {
      page.off('console', onConsole)
      expect(
        errors,
        `console errors on ${label}:\n${errors.map((e) => '  - ' + e).join('\n')}`,
      ).toEqual([])
    },
    errors() {
      return [...errors]
    },
  }
}

/**
 * Read the page's rendered body text and assert none of the
 * FORBIDDEN_RENDERED_TEXT sentinels appear. Used by the parkour stress spec
 * AFTER react-query has had a chance to settle.
 *
 * `label` is included in the failure message to point the operator at the
 * exact route that rendered the garbage.
 */
export async function assertNoGarbageText(page: Page, label: string): Promise<void> {
  const text = await page.evaluate(() => document.body.innerText)
  for (const { label: forbiddenLabel, pattern } of FORBIDDEN_RENDERED_TEXT) {
    expect(
      pattern.test(text),
      `${label} rendered forbidden text "${forbiddenLabel}" — UI consumed a malformed DTO`,
    ).toBe(false)
  }
}

/**
 * Wait for the SPA to settle: skeletons cleared, network idle, plus a
 * configurable extra "settle" pause for any debounced state. Returns once
 * the page is as quiet as it will get.
 *
 * Default settle is 2_000ms per the acceptance criteria of #1061 (assert
 * console errors after a 2-second settle). Reduce for stress polls where
 * we deliberately want to read mid-flight state.
 */
export async function settle(page: Page, settleMs = 2_000): Promise<void> {
  // Skeletons may legitimately render briefly; wait them out, but don't
  // hard-fail here — the test calling settle() is the one asserting the
  // final state. A perpetually-skeletal page will fall out via the
  // FORBIDDEN_RENDERED_TEXT or the console-guard.
  await page
    .locator('.skeleton, [data-skeleton]')
    .first()
    .waitFor({ state: 'hidden', timeout: 10_000 })
    .catch(() => {
      /* no skeleton ever appeared — fine */
    })
  await page.waitForLoadState('networkidle', { timeout: 10_000 }).catch(() => {
    /* refetchInterval can keep this busy — fine, parkour is best-effort */
  })
  if (settleMs > 0) {
    // Deliberate pause: this is the "did a delayed effect throw later?"
    // window. Acceptance criterion #2 of #1061 names 2 seconds explicitly.
    await page.waitForTimeout(settleMs)
  }
}
