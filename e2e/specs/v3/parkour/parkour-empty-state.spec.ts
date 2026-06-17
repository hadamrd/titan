/**
 * parkour-empty-state — fresh-DB rigs render INTENTIONAL empty copy, never
 * `undefined` / `null` / a blank screen (issue #1061, acceptance criterion 5).
 *
 * On a brand-new rig (zero builds, zero workers, zero queue entries) the
 * /builds, /queue, /workers pages each have to render something a human
 * can act on — "No builds yet", "Queue is empty", "No workers connected".
 * The bug class we're catching is the page that ships with no empty-state
 * branch at all: the table render maps over a `data` array that's `undefined`
 * on first paint and the page renders the word "undefined" or shows a
 * stuck spinner forever.
 *
 * This spec is OPT-IN: it auto-skips unless TITAN_PARKOUR_EMPTY_DB=1, because
 * the regular seeded rig has 6 builds + a running worker and would always
 * fail "expected 0 rows". Operators run it after `task dev:down -v` + a
 * fresh `task dev:titan` to actually validate against an empty DB.
 */
import { test, expect } from '@playwright/test'
import { authEnv, loginViaKeycloak } from '../../../fixtures/auth-v3'
import { attachConsoleGuard, settle, assertNoGarbageText } from '../../../lib/console-guard'
import { countBuilds } from '../../../fixtures/parkour'

const ENV = authEnv()
const FRESH_DB = process.env.TITAN_PARKOUR_EMPTY_DB === '1'

/**
 * Pages that MUST render an intentional empty state, paired with at least
 * one substring of the expected copy. The substrings are loose so a copy
 * tweak ("No builds yet" → "No builds to show") doesn't break the test —
 * but tight enough that "undefined" / "null" / "[object Object]" would
 * fail the contract instead of matching.
 */
const EMPTY_STATE_ROUTES: { path: string; copy: RegExp }[] = [
  { path: '/builds',  copy: /no\s+builds|nothing\s+to\s+show|empty/i },
  { path: '/queue',   copy: /queue\s+is\s+empty|no\s+queued|nothing\s+queued|empty/i },
  { path: '/workers', copy: /no\s+workers|nothing\s+connected|empty/i },
]

test.describe('@parkour empty-state — fresh-DB rigs render intentional copy', () => {
  test.beforeAll(async () => {
    test.skip(!FRESH_DB, 'set TITAN_PARKOUR_EMPTY_DB=1 to run against a fresh rig')
    const n = await countBuilds().catch(() => -1)
    test.skip(
      n !== 0,
      `rig is not fresh — found ${n} builds in DB (run task dev:down -v && task dev:titan)`,
    )
  })

  test.beforeEach(async ({ page }) => {
    await loginViaKeycloak(page, ENV)
  })

  for (const { path, copy } of EMPTY_STATE_ROUTES) {
    test(`route ${path} on empty DB shows intentional empty copy`, async ({ page }) => {
      const guard = attachConsoleGuard(page)
      await page.goto(ENV.uiBaseUrl + path, { waitUntil: 'domcontentloaded' })
      await settle(page, 2_000)
      // No garbage text first: an empty state that ALSO says "undefined" is
      // worse than a missing empty state because it looks more polished.
      await assertNoGarbageText(page, `${path} (empty DB)`)
      // Then the actual copy gate.
      const text = await page.evaluate(() => document.body.innerText)
      expect(
        copy.test(text),
        `${path} did not render expected empty-state copy (pattern ${copy}) — ` +
          `instead the body was:\n${text.slice(0, 300)}`,
      ).toBe(true)
      guard.assertClean(`empty ${path}`)
    })
  }
})
