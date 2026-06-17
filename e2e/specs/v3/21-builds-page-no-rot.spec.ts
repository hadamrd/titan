/**
 * 21-builds-page-no-rot — regression guard for #825.
 *
 * The /builds page had six concurrent visual defects that together made the
 * V1 landing screen look broken at first glance. This spec asserts each one
 * stays fixed:
 *
 *   1. No "NaNm NaNs" duration text anywhere on the page.
 *   2. The status badge (SUCCESS / FAILED / RUNNING…) and the display-name
 *      pill MUST have measurable horizontal separation — no mashed
 *      "deploy-v1.2SUCCESS" token.
 *   3. RUNNING rows render a real elapsed duration (matches `\d+s` or
 *      `\d+m \d+s`), never `—`, because the page knows startedAt.
 *   4. Per-row subtitle never reads `build N` (noise duplicating the #N
 *      chip). It is either branch + short sha, or absent.
 *   5. Metric cards: every visible sparkline SVG has at least one rendered
 *      data point — no fake empty squiggles. (A card without real series
 *      simply omits the spark area.)
 *   6. Header subtitle does not read "Showing builds across N jobs" (the
 *      jobs/builds-count confusion). It mentions builds or the window, not
 *      a bare jobs count.
 *
 * Tagged @golden — must stay green for V1.
 */
import { test, expect } from '@playwright/test'
import { authEnv, loginViaKeycloak } from '../../fixtures/auth-v3'

const ENV = authEnv()

test.describe('@golden /builds page — no UI rot (#825)', () => {
  test.beforeEach(async ({ page }) => {
    await loginViaKeycloak(page, ENV)
    await page.goto(ENV.uiBaseUrl + '/builds', { waitUntil: 'domcontentloaded' })
    // Wait for skeletons to clear so we read REAL data.
    await expect
      .poll(async () => await page.locator('.skeleton, [data-skeleton]').count(), {
        timeout: 10_000,
      })
      .toBe(0)
    // Wait for at least one build row so all assertions have data.
    await expect(page.locator('.build-row').first()).toBeVisible({ timeout: 10_000 })
  })

  test('defect 1: no NaN durations anywhere on the page', async ({ page }) => {
    const bodyText = await page.evaluate(() => document.body.innerText)
    expect(bodyText, 'page text contains NaN — formatter fed undefined').not.toMatch(
      /NaN/,
    )
  })

  test('defect 2: status badge separates from display-name pill', async ({ page }) => {
    // Pick any non-header build row and assert the .build-num cell does not
    // visually butt against the next-column .badge (status chip).
    const row = page.locator('.row.build-row:not(.row-head)').first()
    await expect(row).toBeVisible()

    const buildNumBox = await row.locator('.build-num').first().boundingBox()
    const badgeBox = await row.locator('.badge').first().boundingBox()
    expect(buildNumBox, 'build-num cell missing').not.toBeNull()
    expect(badgeBox, 'status badge missing').not.toBeNull()
    if (buildNumBox && badgeBox) {
      const gap = badgeBox.x - (buildNumBox.x + buildNumBox.width)
      expect(gap, `display-name pill and status badge are touching (gap=${gap}px)`)
        .toBeGreaterThanOrEqual(4)
    }

    // Belt-and-braces text-level check: no row's innerText concatenates the
    // label and status (e.g. "deploy-v1.2SUCCESS"). We look for the pattern
    // [a-z0-9.-]+ immediately followed by a status word with NO whitespace.
    const allRowText = await page.locator('.row.build-row:not(.row-head)').allInnerTexts()
    for (const text of allRowText) {
      // Strip whitespace-normalised — innerText already collapses gaps, but
      // a missing gap shows up as adjacent chars.
      expect(text, `row text "${text}" mashes label+status`).not.toMatch(
        /[A-Za-z0-9.][A-Z]?(SUCCESS|FAILED|RUNNING|QUEUED|ABORTED|UNSTABLE)\b/,
      )
    }
  })

  test('defect 3: RUNNING rows show real elapsed duration, not em-dash', async ({
    page,
  }) => {
    // Find a row whose status chip text is exactly RUNNING.
    const runningRows = page.locator('.row.build-row:not(.row-head)', {
      has: page.locator('.badge', { hasText: /^RUNNING$/ }),
    })
    const count = await runningRows.count()
    test.skip(count === 0, 'no RUNNING builds in rig — defect 3 not assertable')

    const first = runningRows.first()
    // The duration cell sits 6th in our grid (after dot/num/chip/icon/title).
    // Easier: look for the .mono.dim cell whose text matches a real duration.
    const cells = first.locator('span.mono.dim')
    const texts = await cells.allInnerTexts()
    const hasRealDuration = texts.some((t) => /^\d+m \d+s$|^\d+s$/.test(t.trim()))
    expect(
      hasRealDuration,
      `RUNNING row cells were ${JSON.stringify(texts)} — expected one to match Ns or Nm Ns`,
    ).toBe(true)
    // And NEVER NaN/em-dash for a row whose startedAt the page knows.
    expect(texts.join(' | ')).not.toMatch(/NaN/)
  })

  test('defect 4: row subtitle is never "build N" noise', async ({ page }) => {
    const subtitles = await page.locator('.build-title-meta').allInnerTexts()
    for (const s of subtitles) {
      expect(s.trim(), `row subtitle "${s}" still reads "build N"`).not.toMatch(
        /^build \d+$/,
      )
    }
  })

  test('defect 5: every rendered sparkline has at least one data point', async ({
    page,
  }) => {
    // We render a sparkline only when there is real series data — assert any
    // SVG that does appear is non-empty (has at least one <path> or <line>).
    const sparks = page.locator('.metric-spark svg')
    const sparkCount = await sparks.count()
    for (let i = 0; i < sparkCount; i++) {
      const svg = sparks.nth(i)
      const childCount = await svg.locator('path, polyline, line, rect, circle').count()
      expect(
        childCount,
        `metric-spark #${i} renders an empty SVG (fake sparkline)`,
      ).toBeGreaterThan(0)
    }
  })

  test('defect 6: header subtitle does not confuse jobs-count with builds-count', async ({
    page,
  }) => {
    const subtitle = page.locator('.page-subtitle').first()
    await expect(subtitle).toBeVisible()
    const text = (await subtitle.innerText()).trim()
    // The original "Showing builds across N jobs" misled because the table is
    // about *builds*, and the count next to "30 builds shown" was a jobs count.
    expect(text, `subtitle "${text}" still phrases the jobs count ambiguously`).not.toMatch(
      /across \d+ jobs?$/i,
    )
  })
})
