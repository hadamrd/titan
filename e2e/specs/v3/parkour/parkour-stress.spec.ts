/**
 * parkour-stress — 5-rapid-poll per route, assert no garbage text leaks out
 * mid-flight (issue #1061, acceptance criterion 3 / Test matrix row 3).
 *
 * React-query has a class of bug where the table is rendered from a stale
 * cache snapshot while a new request is in flight. If the row shape changed
 * between requests (e.g. backend added a nullable field), the brief window
 * between "received new data" and "child component re-rendered" can paint
 * a row whose new column is `undefined`. The user sees "undefined" for one
 * frame, then it disappears.
 *
 * A normal spec doesn't see this — it waits for `networkidle` first. The
 * parkour stress spec deliberately reloads-and-reads 5 times in quick
 * succession without settling, capturing the body text on each pass and
 * asserting NONE of them ever contained a forbidden sentinel.
 *
 * This is the cheapest analogue of a property test on UI rendering: rather
 * than enumerate every possible interleaving, we drive enough churn that
 * the most common ones surface.
 */
import { test, expect } from '@playwright/test'
import { authEnv, loginViaKeycloak } from '../../../fixtures/auth-v3'
import { attachConsoleGuard, FORBIDDEN_RENDERED_TEXT } from '../../../lib/console-guard'
import {
  cleanParkourSeed,
  seedAdversarialRows,
  type ParkourSeed,
} from '../../../fixtures/parkour'

const ENV = authEnv()

// Routes that render LIST data — the stress test is meaningless on a route
// that only renders static config (/settings) because there's no polling
// surface to race against.
const LIST_ROUTES = [
  '/',
  '/builds',
  '/queue',
  '/workers',
  '/jobs',
  '/pipelines',
  '/approvals',
  '/audit',
  '/admin/users',
] as const

test.describe('@parkour stress — 5 rapid polls per route', () => {
  let seed: ParkourSeed | null = null

  test.beforeAll(async () => {
    try {
      seed = await seedAdversarialRows()
    } catch (e) {
      console.warn('[parkour-stress] seed failed — running on default rig:', e)
      seed = null
    }
  })

  test.afterAll(async () => {
    if (seed) {
      await cleanParkourSeed(seed).catch(() => {})
    }
  })

  test.beforeEach(async ({ page }) => {
    await loginViaKeycloak(page, ENV)
  })

  for (const path of LIST_ROUTES) {
    test(`route ${path} stays clean across 5 rapid polls`, async ({ page }) => {
      const guard = attachConsoleGuard(page)
      const samples: string[] = []

      for (let pass = 1; pass <= 5; pass++) {
        await page.goto(ENV.uiBaseUrl + path, { waitUntil: 'domcontentloaded' })
        // Deliberately SHORT wait — we're sampling mid-poll on purpose. A
        // longer wait would let react-query settle and miss the race.
        await page.waitForTimeout(150)
        const text = await page
          .evaluate(() => document.body.innerText)
          .catch(() => '')
        samples.push(text)
      }

      // Aggregate the samples: any single pass exhibiting a forbidden
      // sentinel fails the test. We report ALL failures (not just the
      // first) so a fix in one place doesn't leave silent dirt in another.
      const failures: { pass: number; label: string; excerpt: string }[] = []
      samples.forEach((text, idx) => {
        for (const { label, pattern } of FORBIDDEN_RENDERED_TEXT) {
          const m = pattern.exec(text)
          if (m) {
            failures.push({
              pass: idx + 1,
              label,
              excerpt: text.slice(Math.max(0, m.index - 40), m.index + 60),
            })
          }
        }
      })

      expect(
        failures,
        `${path} rendered forbidden text under rapid-poll stress:\n` +
          failures
            .map((f) => `  pass ${f.pass}: "${f.label}" near …${f.excerpt}…`)
            .join('\n'),
      ).toEqual([])

      guard.assertClean(`stress ${path}`)
    })
  }
})
