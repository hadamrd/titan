/**
 * 10-log-stream — watch log lines stream into the Logs tab.
 *
 * SRE story: open a build's Logs tab, see real lines paint, watch them keep
 * coming as the build progresses.
 *
 * Data anchors (per rig/local/seed-data.sh):
 *   - titan-hello#1 SUCCESS — 9 log chunks seeded against task_token
 *     4e110000-…-3110 (L560-575). Terminal: the SSE server sends `done`
 *     after the final drain pass + closes the stream.
 *   - PR #430 fleet — 12 builds, each step's flow_node carries a
 *     deterministic log_task_id. The seed populates titan.logs across the
 *     fleet (the brief mentions "106 log rows the seed adds").
 *
 * We anchor on titan-hello#1: it's the smallest, most reliable, fully-
 * populated log fixture in the seed.
 *
 * SSE wiring (titan-ui/src/routes/builds/$buildId.tsx useLogStream + the
 * server-side BuildLogsSse): the client opens EventSource against
 * /api/v1/builds/{id}/logs. The page mounts the Logs tab as `.tab-pane` +
 * renders each line as `.log-line` (LogScrubber.tsx L310).
 *
 * Assertions are LIGHTWEIGHT (memory: Playwright Lightweight Checks):
 *   - log-line DOM count crosses 1
 *   - network request to /api/v1/builds/<id>/logs was made
 *   - the line count is monotonic (we don't snapshot text)
 */
import { test, expect } from '@playwright/test'
import { authEnv, loginViaKeycloak } from '../../fixtures/auth-v3'
import { pgClient } from '../../fixtures/seed-v3'

const ENV = authEnv()

let helloBuildId: number

test.beforeAll(async () => {
  const client = pgClient()
  await client.connect()
  try {
    // titan-hello#1 is the canonical log-bearing anchor (seed-data.sh L477+).
    const res = await client.query<{ id: string }>(
      `SELECT b.id::text AS id
         FROM titan.builds b
         JOIN titan.jobs   j ON j.id = b.job_id
        WHERE j.full_name='titan-hello' AND b.build_number=1
        LIMIT 1`,
    )
    const row = res.rows[0]
    if (!row) {
      throw new Error("titan-hello#1 missing — re-run rig/local/seed-data.sh")
    }
    helloBuildId = Number(row.id)
  } finally {
    await client.end()
  }
})

test.describe('v3 log-stream', () => {
  test.beforeEach(async ({ page }) => {
    await loginViaKeycloak(page, ENV)
  })

  test('Logs tab on titan-hello#1: SSE 200 (NOT 401), ≥1 line within 5s, indicator goes Live', async ({
    page,
  }) => {
    // Catches: EventSource 401 (PR #68) — prior to this tightening the spec
    // only checked the SSE URL was requested, NOT that the server returned
    // 200. A 401 still produced a request entry.
    // Catches: empty task_archive linkage (#66) — prior soft assertion was
    // "≥1 log line within 15s"; tightened to require lines within 5s for the
    // seed-known-good build titan-hello#1 (9 chunks via 4e110000-…-3110).
    // Catches: titan-hello#1 missing logs (#66) — this is the canonical
    // seeded-log fixture; if NO lines render here, the bug is back.
    // Catches: "Reconnecting…" stuck state — indicator must transition to
    // "Live" or "Done" within 3s, not stick on Connecting/Reconnecting.

    // Capture SSE response (NOT just request) for the network-level proof.
    const sseResponses: Array<{ url: string; status: number }> = []
    page.on('response', (resp) => {
      const url = resp.url()
      if (url.includes(`/api/v1/builds/${helloBuildId}/logs`)) {
        sseResponses.push({ url, status: resp.status() })
      }
    })

    await page.goto(`${ENV.uiBaseUrl}/builds/${helloBuildId}`)
    await expect(page.getByRole('heading', { name: /builds.*#/i })).toBeVisible({
      timeout: 10_000,
    })

    // Click Logs tab.
    await page.getByRole('tab', { name: /^logs$/i }).click()
    await expect(page.getByRole('tab', { name: /^logs$/i })).toHaveAttribute(
      'aria-selected',
      'true',
    )

    // Catches: EventSource 401 (#68) — the SSE GET must return 200.
    await expect
      .poll(() => sseResponses.some((r) => r.status === 200), {
        timeout: 10_000,
        message:
          'SSE GET /api/v1/builds/<id>/logs never returned 200 — EventSource auth bug (PR #68)',
      })
      .toBe(true)
    // Adversarial: assert NO 401 was observed at all.
    const got401 = sseResponses.find((r) => r.status === 401)
    expect(
      got401,
      `EventSource returned 401 on /logs — PR #68 bug regression: ${JSON.stringify(got401)}`,
    ).toBeUndefined()

    // Catches: empty task_archive linkage (#66) + titan-hello#1 missing
    // logs (#66) — REQUIRE ≥1 line within 5s. titan-hello#1 has 9 seeded
    // log chunks. If this fails, either:
    //   - the SSE drained nothing (task_archive join returns empty), OR
    //   - the seed didn't run.
    const logLines = page.locator('.log-line')
    await expect(logLines.first()).toBeVisible({
      timeout: 5_000,
    })
    const initialCount = await logLines.count()
    expect(
      initialCount,
      'expected ≥1 log line for titan-hello#1 within 5s (seed provides 9 chunks via task 4e110000-…-3110; catches empty task_archive linkage #66)',
    ).toBeGreaterThan(0)

    // Catches: "Reconnecting…" stuck state — SSE indicator must transition
    // from "Connecting…" to "Live" (or "Done" if the stream already
    // completed) within 3s. The terminal-head's first <span> carries the
    // SSE label (see LogScrubber.tsx SSE_LABEL).
    await expect
      .poll(
        async () => {
          const label = await page
            .locator('.terminal-head span')
            .first()
            .textContent()
            .catch(() => null)
          return label?.trim()
        },
        {
          timeout: 3_000,
          message:
            'SSE indicator never left Connecting/Reconnecting — connection stuck (the "Reconnecting…" stuck-state bug class)',
        },
      )
      .toMatch(/^(Live|Done)$/)
  })

  test('log line count grows from initial → ≥5 as the stream drains', async ({ page }) => {
    // Catches: empty task_archive linkage (#66) — count-grows assertion per
    // the rigour rules; the prior soft form ("≥5 within 30s") allowed silent
    // staleness. Now we capture initialCount + REQUIRE final > initial AND
    // final ≥ 5 within 10s for the 9-chunk seed.
    await page.goto(`${ENV.uiBaseUrl}/builds/${helloBuildId}`)
    await page.getByRole('tab', { name: /^logs$/i }).click()

    // Wait for the first line within 5s (seeded build).
    const logLines = page.locator('.log-line')
    await expect(logLines.first()).toBeVisible({ timeout: 5_000 })
    const initialCount = await logLines.count()

    // Require monotonic growth — the SSE server drains 9 chunks before `done`.
    await expect
      .poll(async () => await logLines.count(), {
        timeout: 10_000,
        message:
          'log lines did not reach ≥5 within 10s — SSE drain stalled (catches #66 empty task_archive linkage)',
      })
      .toBeGreaterThanOrEqual(5)

    // Adversarial: final count must STRICTLY exceed initial when initial < 9.
    const finalCount = await logLines.count()
    if (initialCount < 9) {
      expect(
        finalCount,
        `log count did not grow (initial=${initialCount}, final=${finalCount}) — SSE not actually streaming (catches #66)`,
      ).toBeGreaterThan(initialCount)
    }
  })

  test('log content contains the exact seeded marker "Hello from Titan"', async ({ page }) => {
    // Catches: titan-hello#1 missing logs (#66) — real-data assertion per the
    // rigour rules. The seed-data.sh fixture inserts the literal string
    // "Hello from Titan" as a log chunk; assert against THAT independent
    // oracle, not against a regex tolerance.
    await page.goto(`${ENV.uiBaseUrl}/builds/${helloBuildId}`)
    await page.getByRole('tab', { name: /^logs$/i }).click()

    // Hard 5s budget: titan-hello#1 has 9 chunks pre-seeded; the SSE server
    // drains them on connect.
    await expect(page.getByText('Hello from Titan', { exact: false }).first()).toBeVisible({
      timeout: 5_000,
    })
  })

  test('Logs tab on a RUNNING build still mounts the stream pane', async ({ page }) => {
    // For a RUNNING build the SSE stream stays open (no `done` event). We
    // just want to assert the pane mounts + the request is made; the count
    // assertion is best-effort because logs depend on whether the build
    // has any task_archive rows yet.
    const client = pgClient()
    await client.connect()
    let runningId: number | undefined
    try {
      const res = await client.query<{ id: string }>(
        `SELECT b.id::text AS id FROM titan.builds b
           JOIN titan.jobs j ON j.id=b.job_id
          WHERE b.status='RUNNING'
          ORDER BY b.id DESC LIMIT 1`,
      )
      const row = res.rows[0]
      runningId = row ? Number(row.id) : undefined
    } finally {
      await client.end()
    }

    // Hard-skip with explicit reason rather than test.fixme: if there's no
    // RUNNING build, the seed is broken — surface that as a clear test result.
    if (runningId === undefined) {
      test.skip(true, 'no RUNNING build seeded — re-run seed-data.sh')
      return
    }
    const targetId = runningId

    // Capture full response (status), not just request URL.
    const sseResponses: Array<{ url: string; status: number }> = []
    page.on('response', (resp) => {
      if (resp.url().includes(`/api/v1/builds/${targetId}/logs`)) {
        sseResponses.push({ url: resp.url(), status: resp.status() })
      }
    })

    await page.goto(`${ENV.uiBaseUrl}/builds/${targetId}`)
    await page.getByRole('tab', { name: /^logs$/i }).click()
    await expect(page.locator('.tab-pane').first()).toBeVisible()

    // Catches: EventSource 401 (#68) — even on a RUNNING build (no `done`)
    // the initial SSE response must be 200.
    await expect
      .poll(() => sseResponses.some((r) => r.status === 200), {
        timeout: 10_000,
        message: 'SSE on RUNNING build never returned 200 — EventSource 401 (#68)',
      })
      .toBe(true)
    const got401 = sseResponses.find((r) => r.status === 401)
    expect(got401, `EventSource 401 on RUNNING build /logs — #68 bug`).toBeUndefined()
  })
})
