/**
 * 20-real-pipeline-end-to-end — drive a REAL pipeline through the engine and
 * assert the UI reflects engine state (not seed-shape).
 *
 * Why this spec exists:
 *   Every existing v3 spec asserts UI behavior against SEEDED builds — the
 *   seed-data.sh fixture pre-wires task_archive + titan.logs + started_at by
 *   hand. Those specs test the SEED SHAPE, not the engine. Three engine bugs
 *   slipped through the seeded-spec moat in three weeks:
 *
 *     - PR #490: archive id collision (task_archive linkage)
 *     - PR #490: started_at null clobber (Duration rendered as em-dash)
 *     - PR #463: EventSource 401 on /logs SSE (bearer not propagated)
 *
 *   Each would have been caught by ONE spec that drives a real pipeline +
 *   asserts the UI reflects real data. This is that spec.
 *
 * Strategy:
 *   1. Fire a build via `rig/local/dogfood-fire.sh` — that script mirrors the
 *      production trigger path (inserts builds row + ORCHESTRATE/SYNTHESIZE
 *      task on the `default` queue) so the orchestrator, worker, log SSE all
 *      run end-to-end. It bypasses ONLY the OIDC gate, not the engine.
 *   2. Poll the API until SUCCESS — the engine must actually run.
 *   3. Open the build page; assert Duration is a real value (catches
 *      started_at-null), assert Logs tab shows ≥1 line (catches archive
 *      linkage / SSE 401), assert no 401 on /logs (catches EventSource auth).
 */
import { execSync } from 'node:child_process'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { test, expect, type Page } from '@playwright/test'
import { authEnv, loginViaKeycloak } from '../../fixtures/auth-v3'

/**
 * Pull the OIDC access token out of the SPA's sessionStorage post-login. The
 * SPA stores the User under `oidc.user:<authority>:<client_id>` per
 * oidc-client-ts defaults (titan-ui/src/auth/oidcConfig.ts — userStore is
 * sessionStorage). We prefer this over the ROPC `fetchBearerToken` because
 * the ROPC path depends on the `titan-e2e` Keycloak client being present in
 * the imported realm; in practice that import sometimes lags behind the
 * realm-config file. Reading what the SPA actually obtained is the single
 * source of truth.
 */
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
        // ignore non-JSON entries
      }
    }
    return null
  })
  if (!token) throw new Error('no oidc.user access_token in sessionStorage post-login')
  return token
}

const ENV = authEnv()
const API_BASE = process.env.TITAN_API_URL ?? 'http://localhost:18080'
// Resolve the repo root from this file's location so the script path works
// regardless of where Playwright is launched from. e2e/specs/v3/ → repo root
// is three levels up. ESM scope — derive __dirname from import.meta.url.
const THIS_DIR = path.dirname(fileURLToPath(import.meta.url))
const REPO_ROOT = path.resolve(THIS_DIR, '../../..')
const DOGFOOD_FIRE = path.join(REPO_ROOT, 'rig/local/dogfood-fire.sh')

test.describe('v3 real-pipeline-end-to-end', () => {
  test('fires a real titan-demo build, polls until SUCCESS, asserts UI reflects engine state', async ({
    page,
  }) => {
    test.setTimeout(90_000)

    // 1. Fire a real build via the dogfood CLI (production trigger path).
    //    dogfood-fire.sh prints `http://localhost:5180/builds/<id>` in its
    //    final block; parse the build id out of that.
    const out = execSync(`bash ${DOGFOOD_FIRE} 2>&1`, { encoding: 'utf8' })
    const m = out.match(/\/builds\/(\d+)/)
    expect(m, `dogfood-fire output missing build id — output was:\n${out}`).toBeTruthy()
    const buildId = Number(m![1])
    expect(buildId).toBeGreaterThan(0)

    // 2. Login via the SPA's PKCE flow, then pull the access token out of
    //    sessionStorage — that's what oidc-client-ts stashes there. This is
    //    the single source of truth for "what bearer does the SPA actually
    //    have?", and it works whether or not the dedicated ROPC client made
    //    it into the running Keycloak realm.
    await loginViaKeycloak(page, ENV)
    const bearer = await extractAccessToken(page)
    // 3. Poll the API until the build reaches a terminal status. The titan-
    //    demo pipeline sleeps 2s and prints a few lines — typical wall time
    //    is 5-10s, give 60s headroom for cold worker / queue contention.
    const start = Date.now()
    let status = 'QUEUED'
    let lastBody = ''
    while (Date.now() - start < 60_000 && status !== 'SUCCESS' && status !== 'FAILED') {
      const r = await page.request.get(`${API_BASE}/api/v1/builds/${buildId}`, {
        headers: { Authorization: `Bearer ${bearer}` },
      })
      if (r.ok()) {
        const body = (await r.json()) as { status?: string }
        lastBody = JSON.stringify(body)
        status = body.status ?? status
      } else {
        lastBody = `HTTP ${r.status()} ${await r.text().catch(() => '')}`
      }
      if (status === 'SUCCESS' || status === 'FAILED') break
      await new Promise((res) => setTimeout(res, 1_000))
    }
    // Catches: engine wedge / orchestrator stall — the production path must
    // actually run, not just seed-shape correctly.
    expect(
      status,
      `build ${buildId} never reached SUCCESS within 45s — engine broken. Last body: ${lastBody}`,
    ).toBe('SUCCESS')

    // 4. Open the build page. Session is already authenticated from step 2.
    await page.goto(`${ENV.uiBaseUrl}/builds/${buildId}`)
    await expect(page.getByRole('heading', { name: /builds.*#/i })).toBeVisible({
      timeout: 10_000,
    })

    // 5. Duration must show a real value, NOT em-dash.
    //    Catches: PR #490 started_at null clobber — when started_at was
    //    clobbered to null on archive, formatBuildDuration returned `—`.
    //    We locate the Duration card by its label, then read the sibling
    //    `.metric-value`.
    const durationMetric = page.locator('.metric', { hasText: 'Duration' }).first()
    await expect(durationMetric).toBeVisible({ timeout: 5_000 })
    const dur = (await durationMetric.locator('.metric-value').textContent())?.trim() ?? ''
    expect(
      dur,
      'Duration rendered as em-dash — started_at probably null (catches PR #490 clobber). ' +
        'formatBuildDuration returns "—" iff startedAt is null/unparseable.',
    ).not.toMatch(/^—\s*$/)
    // Sub-minute → `Ns`, otherwise `Mm Ns` (see titan-ui/src/lib/format.ts).
    expect(
      dur,
      `Duration "${dur}" does not match expected format (Ns or Mm Ns) — formatter regressed`,
    ).toMatch(/^\d+(s|m\s+\d+s)$/)

    // 6. Capture /logs SSE responses while we open the Logs tab, so we can
    //    assert (a) ≥1 200 and (b) zero 401.
    const logsResponses: Array<{ url: string; status: number }> = []
    page.on('response', (r) => {
      if (r.url().includes(`/api/v1/builds/${buildId}/logs`)) {
        logsResponses.push({ url: r.url(), status: r.status() })
      }
    })

    // 7. Logs tab must show ≥ 1 real line, NOT "No log output yet".
    //    Catches: PR #463 EventSource 401 + PR #490 archive id collision —
    //    in either case the UI silently shows the empty state.
    await page.getByRole('tab', { name: /^logs$/i }).click()
    await expect(page.getByRole('tab', { name: /^logs$/i })).toHaveAttribute(
      'aria-selected',
      'true',
    )
    // The empty state literal lives in titan-ui/src/components/LogScrubber.tsx.
    await expect(page.getByText('No log output yet.')).not.toBeVisible({ timeout: 10_000 })
    const logLines = page.locator('.log-line')
    await expect(logLines.first()).toBeVisible({ timeout: 10_000 })
    const lineCount = await logLines.count()
    expect(
      lineCount,
      'Logs tab empty for a REAL build that ran to SUCCESS — task_archive linkage broken ' +
        '(catches PR #490 archive id collision).',
    ).toBeGreaterThan(0)

    // 8. The titan-demo pipeline echoes the literal "Hello from Titan" — assert
    //    it appears, as an independent oracle that the right log stream is
    //    wired to the right build.
    await expect(
      page.getByText('Hello from Titan', { exact: false }).first(),
    ).toBeVisible({ timeout: 5_000 })

    // 9. Give the SSE a moment to settle, then assert zero 401s.
    //    Catches: PR #463 EventSource 401 — bearer not sent on EventSource.
    await page.waitForTimeout(2_000)
    expect(
      logsResponses.length,
      'no /logs SSE request observed — EventSource never opened',
    ).toBeGreaterThan(0)
    const got401 = logsResponses.find((r) => r.status === 401)
    expect(
      got401,
      `401 on /logs — bearer not sent on EventSource (catches PR #463). Observed: ${JSON.stringify(
        logsResponses,
      )}`,
    ).toBeUndefined()
    expect(
      logsResponses.some((r) => r.status === 200),
      `no 200 on /logs — SSE never succeeded. Observed: ${JSON.stringify(logsResponses)}`,
    ).toBe(true)
  })
})
