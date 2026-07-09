/**
 * 21-builds-page-no-rot — regression guard for #825, ported to the calm-list
 * /builds layout (#77 — the pre-redesign `.row.build-row` grid is gone).
 *
 * The /builds page had six concurrent visual defects that together made the
 * V1 landing screen look broken at first glance. This spec asserts each one
 * stays fixed — SAME oracles, current DOM:
 *
 *   1. No "NaNm NaNs" duration text anywhere on the page.
 *   2. The status pill (success / failed / running…) and the build label
 *      MUST have measurable horizontal separation — no mashed
 *      "deploy-v1.2SUCCESS" token. Ported to `.cl-build-num` / `.cl-pill`
 *      inside `.calm-list-row.build-row` (BuildRow.tsx).
 *   3. RUNNING rows render a real elapsed duration (matches `\d+s` or
 *      `\d+m \d+s`), never `—`, because the page knows startedAt. Ported to
 *      `[data-status="RUNNING"]` rows + the `builds-row-duration-*` cell.
 *   4. Per-row label never reads `build N` (noise duplicating the #N chip).
 *      Ported to the `.cl-build-num` label + the `.cl-meta` line.
 *   5. Metric tiles: any rendered sparkline SVG has at least one data point —
 *      no fake empty squiggles. (The current summary strip renders no sparks;
 *      the guard stays so a reintroduced empty-svg spark fails again.)
 *   6. Header subtitle does not confuse jobs-count with builds-count: the
 *      subtitle's leading count MUST be the builds total (labeled "builds")
 *      and any jobs count must be labeled "jobs" — verified against the API
 *      totals, not just the copy shape. A bare "… across N jobs" subtitle
 *      (the page's only numeral being a jobs count) is the original defect
 *      and fails here.
 *
 * Tagged @golden — must stay green for V1.
 */
import { test, expect, type Page, type APIRequestContext } from '@playwright/test'
import { authEnv, loginViaKeycloak } from '../../fixtures/auth-v3'

const ENV = authEnv()
const API_BASE = process.env.TITAN_API_URL ?? 'http://localhost:18080'

const STATUS_WORDS = ['success', 'failed', 'running', 'queued', 'aborted', 'unstable']

async function extractAccessToken(page: Page): Promise<string> {
  const token = await page.evaluate(() => {
    for (let i = 0; i < window.sessionStorage.length; i++) {
      const key = window.sessionStorage.key(i)
      if (!key || !key.startsWith('oidc.user:')) continue
      try {
        const raw = window.sessionStorage.getItem(key)
        if (!raw) continue
        const parsed = JSON.parse(raw) as { access_token?: string }
        if (parsed.access_token) return parsed.access_token
      } catch {
        // ignore
      }
    }
    return null
  })
  if (!token) throw new Error('no oidc.user access_token in sessionStorage post-login')
  return token
}

async function apiTotal(
  request: APIRequestContext,
  bearer: string,
  path: string,
): Promise<number> {
  const r = await request.get(`${API_BASE}${path}`, {
    headers: { Authorization: `Bearer ${bearer}`, Accept: 'application/json' },
  })
  if (!r.ok()) throw new Error(`GET ${path} → HTTP ${r.status()}`)
  const body = (await r.json()) as { total: number }
  return body.total
}

test.describe('@golden /builds page — no UI rot (#825)', () => {
  test.beforeEach(async ({ page }) => {
    await loginViaKeycloak(page, ENV)
    await page.goto(ENV.uiBaseUrl + '/builds', { waitUntil: 'domcontentloaded' })
    // Wait for skeletons to clear so we read REAL data (old + new skeleton
    // markers — the calm-list loader is `builds-list-loading` / `.tt-skel-row`).
    await expect
      .poll(
        async () =>
          await page
            .locator(
              '.skeleton, [data-skeleton], .tt-skel-row, [data-testid="builds-list-loading"]',
            )
            .count(),
        { timeout: 10_000 },
      )
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

  test('defect 2: status pill separates from the build label', async ({ page }) => {
    // Pick any calm-list build row and assert the `.cl-build-num` label does
    // not visually butt against the `.cl-pill` status chip.
    const row = page.locator('.calm-list-row.build-row').first()
    await expect(row).toBeVisible()

    const labelBox = await row.locator('.cl-build-num').first().boundingBox()
    const pillBox = await row.locator('.cl-pill').first().boundingBox()
    expect(labelBox, 'build label (.cl-build-num) missing').not.toBeNull()
    expect(pillBox, 'status pill (.cl-pill) missing').not.toBeNull()
    if (labelBox && pillBox) {
      const gap = pillBox.x - (labelBox.x + labelBox.width)
      expect(gap, `build label and status pill are touching (gap=${gap}px)`)
        .toBeGreaterThanOrEqual(4)
    }

    // Belt-and-braces DOM-level check across ALL rows: the status pill is its
    // own element whose text is EXACTLY one status word (never a mashed
    // "deploy-v1.2SUCCESS"-style token), and the label element carries no
    // status word fused into it.
    const rows = page.locator('.calm-list-row.build-row')
    const rowCount = await rows.count()
    expect(rowCount, 'no build rows rendered').toBeGreaterThan(0)
    for (let i = 0; i < rowCount; i++) {
      const pillText = (await rows.nth(i).locator('.cl-pill').first().innerText()).trim()
      expect(
        STATUS_WORDS,
        `row #${i} status pill text "${pillText}" is not a bare status word — ` +
          `label/status mashed into one token`,
      ).toContain(pillText.toLowerCase())
      const labelText = (
        await rows.nth(i).locator('.cl-build-num').first().innerText()
      ).trim()
      expect(
        labelText,
        `row #${i} build label "${labelText}" has a status word fused into it`,
      ).not.toMatch(new RegExp(`\\S(${STATUS_WORDS.join('|')})$`, 'i'))
    }
  })

  test('defect 3: RUNNING rows show real elapsed duration, not em-dash', async ({
    page,
  }) => {
    // RUNNING rows carry data-status on the row Link (BuildRow.tsx).
    const runningRows = page.locator('.calm-list-row.build-row[data-status="RUNNING"]')
    const count = await runningRows.count()
    test.skip(count === 0, 'no RUNNING builds in rig — defect 3 not assertable')

    const first = runningRows.first()
    const durationText = (
      await first.locator('[data-testid^="builds-row-duration-"]').innerText()
    ).trim()
    expect(
      /^\d+m \d+s$|^\d+s$/.test(durationText),
      `RUNNING row duration cell was "${durationText}" — expected a live elapsed ` +
        `duration (Ns or Nm Ns); the page knows startedAt so "—"/"running…" is a lie`,
    ).toBe(true)
    // And NEVER NaN/em-dash for a row whose startedAt the page knows.
    expect(durationText).not.toMatch(/NaN|—/)
  })

  test('defect 4: row label / meta line is never "build N" noise', async ({ page }) => {
    const labels = await page
      .locator('.calm-list-row.build-row .cl-build-num')
      .allInnerTexts()
    for (const s of labels) {
      expect(s.trim(), `row label "${s}" still reads "build N"`).not.toMatch(
        /^build \d+$/i,
      )
    }
    const metas = await page.locator('.calm-list-row.build-row .cl-meta').allInnerTexts()
    for (const s of metas) {
      expect(s.trim(), `row meta line "${s}" still reads "build N"`).not.toMatch(
        /^build \d+$/i,
      )
    }
  })

  test('defect 5: every rendered sparkline has at least one data point', async ({
    page,
  }) => {
    // We render a sparkline only when there is real series data — assert any
    // SVG that does appear is non-empty (has at least one <path> or <line>).
    // The current summary strip ships no sparks; the guard covers both the
    // legacy `.metric-spark` hook and any svg inside a `.metric` tile.
    const sparks = page.locator('.metric-spark svg, .metric svg')
    const sparkCount = await sparks.count()
    for (let i = 0; i < sparkCount; i++) {
      const svg = sparks.nth(i)
      const childCount = await svg.locator('path, polyline, line, rect, circle').count()
      expect(
        childCount,
        `metric sparkline #${i} renders an empty SVG (fake sparkline)`,
      ).toBeGreaterThan(0)
    }
  })

  test('defect 6: header subtitle does not confuse jobs-count with builds-count', async ({
    page,
    request,
  }) => {
    const bearer = await extractAccessToken(page)
    const subtitle = page.getByTestId('page-description')
    await expect(subtitle).toBeVisible()

    // Shape first: the subtitle must carry a count labeled "build(s)" AND a
    // count labeled "job(s)". The original defect ("Showing builds across N
    // jobs" — the page's only numeral being a jobs count) fails right here,
    // as does the softer "Pipeline runs across N jobs" variant.
    const shape = /(\d+)\s+builds?\s+across\s+(\d+)\s+jobs?/i
    await expect
      .poll(async () => (await subtitle.innerText()).trim(), {
        message:
          'subtitle never settled on "<n> builds across <m> jobs" — a bare ' +
          'jobs count as the only numeral is the #825 defect-6 confusion',
        timeout: 10_000,
      })
      .toMatch(shape)

    // Semantics second: the number labeled "builds" must BE the builds total
    // and the number labeled "jobs" the jobs total (per the API). This is
    // what kills the adversarial reintroduction where a jobs count gets
    // relabeled as builds. The rig is shared, so totals can drift between
    // page load and this check — reload inside the poll until a consistent
    // snapshot is observed (no sleeps; bounded by the poll timeout).
    await expect
      .poll(
        async () => {
          const text = (await subtitle.innerText()).trim()
          const m = text.match(shape)
          if (!m) return `subtitle lost its shape: "${text}"`
          const uiBuilds = Number(m[1])
          const uiJobs = Number(m[2])
          const [apiBuilds, apiJobs] = await Promise.all([
            apiTotal(request, bearer, '/api/v1/builds?offset=0&limit=1'),
            apiTotal(request, bearer, '/api/v1/jobs?offset=0&limit=1'),
          ])
          if (uiBuilds === apiBuilds && uiJobs === apiJobs) return 'ok'
          // Possibly a stale page vs a moving rig — refresh and re-check.
          await page.reload({ waitUntil: 'domcontentloaded' })
          await expect(page.locator('.build-row').first()).toBeVisible({
            timeout: 10_000,
          })
          return (
            `subtitle counts (builds=${uiBuilds}, jobs=${uiJobs}) disagree with the ` +
            `API (builds=${apiBuilds}, jobs=${apiJobs}) — count labeled "builds" is ` +
            `not the builds total (jobs/builds confusion)`
          )
        },
        { timeout: 20_000, intervals: [500, 1_000, 2_000] },
      )
      .toBe('ok')
  })
})
