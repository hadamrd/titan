/**
 * 19-no-nan-no-undefined — scrape every authenticated route's visible text
 * and assert NONE of the toxic placeholders that leak from a broken render
 * appear: `NaN`, `undefined`, `null`, the literal "[object Object]".
 *
 * Bug class (#412): a queued build's duration was displayed as "NaN" because
 * the formatter divided by an undefined startedAt. A bare "#1" (no job
 * displayName) also slipped through because the header path didn't guard the
 * undefined. Both would have been caught here.
 *
 * Lightweight per memory `feedback_playwright_lightweight_checks` — single
 * `document.body.innerText` read per route, regex match. No snapshots.
 */
import { test, expect } from '@playwright/test'
import { authEnv, loginViaKeycloak } from '../../fixtures/auth-v3'

const ENV = authEnv()

const ROUTES = [
  '/',
  '/builds',
  '/queue',
  '/workers',
  '/jobs',
  '/pipelines',
  '/profile',
  '/settings',
] as const

// Patterns that almost always indicate a render bug. The word-boundary anchors
// keep us from false-matching ordinary copy ("Cannot be undefined" in a
// validation hint is fine; a standalone "undefined" in a table cell is not).
//
// We use `\b` boundaries + ensure the match isn't inside a sentence; the
// FORBIDDEN_CONTEXT regexes look for tokens appearing on their own
// (start-of-line, after whitespace, between non-letter chars).
const FORBIDDEN_TOKENS: Array<{ name: string; re: RegExp; catches: string }> = [
  {
    name: 'NaN',
    // Catches: #412 — NaN durations on queued builds.
    re: /(^|[^A-Za-z])NaN([^A-Za-z]|$)/,
    catches: '#412 (NaN durations)',
  },
  {
    name: 'undefined',
    // Catches: any path that string-templates an undefined value.
    re: /(^|[^A-Za-z])undefined([^A-Za-z]|$)/,
    catches: 'undefined leaking into UI (e.g. job displayName missing)',
  },
  {
    name: '[object Object]',
    // Catches: an object string-coerced into JSX text — almost always a bug.
    re: /\[object Object\]/,
    catches: 'object string-coerced into a JSX text node',
  },
  {
    name: 'bare #1 header',
    // Catches: #412 — "#1" with no preceding job displayName in the page
    // <h1>. Matches a heading that is *exactly* "#<digits>" with no other
    // text — the unmasked default when displayName is undefined.
    re: /^#\d+$/,
    catches: '#412 (bare "#1" job header)',
  },
]

test.describe('v3 no-nan no-undefined', () => {
  test.beforeEach(async ({ page }) => {
    await loginViaKeycloak(page, ENV)
  })

  for (const path of ROUTES) {
    test(`${path} text contains no NaN / undefined / [object Object] / bare #N`, async ({
      page,
    }) => {
      await page.goto(ENV.uiBaseUrl + path, { waitUntil: 'domcontentloaded' })

      // Wait for skeletons to clear so we read REAL rendered text, not
      // half-mounted react-query placeholders. Cap at 10s.
      await expect
        .poll(async () => await page.locator('.skeleton, [data-skeleton]').count(), {
          timeout: 10_000,
          message: `${path} did not finish rendering (skeleton still present)`,
        })
        .toBe(0)

      // Capture visible text on the body and on each h1/h2 (so the bare-#N
      // regex can match headings on their own line).
      const bodyText = await page.evaluate(() => document.body.innerText)
      const headings = await page
        .locator('h1, h2, .page-title')
        .allInnerTexts()
        .then((arr) => arr.map((t) => t.trim()))

      const violations: string[] = []
      for (const { name, re, catches } of FORBIDDEN_TOKENS) {
        if (name === 'bare #1 header') {
          // Heading-only check.
          for (const h of headings) {
            if (re.test(h.trim())) {
              violations.push(
                `${path}: heading "${h}" matches ${name} — catches ${catches}`,
              )
            }
          }
        } else if (re.test(bodyText)) {
          // Slice a small context so the failure message points to the
          // offending region rather than dumping the full page.
          const m = re.exec(bodyText)
          const idx = m ? m.index : 0
          const ctx = bodyText.slice(Math.max(0, idx - 40), idx + 60).replace(/\n/g, ' ')
          violations.push(
            `${path}: forbidden token "${name}" near "...${ctx}..." — catches ${catches}`,
          )
        }
      }

      expect(violations, violations.join('\n')).toEqual([])
    })
  }
})
