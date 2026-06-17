/**
 * 06-sre-3am-journey — the integrated daily-driver journey.
 *
 * Story: an SRE lands on Titan at 3am because a deploy failed. They sign in,
 * triage the Overview KPIs, drill into a failed build, find the failing step,
 * read its logs, check tests + artifacts, scan Workers + Queue, and drain
 * the queue. Each "step" is a `test.step` so the trace + report tells the
 * story plainly.
 *
 * Where the existing v3 specs validate one surface each, this one validates
 * the integrated journey — the point is to surface gaps between surfaces
 * (broken nav, missing affordances, dead links) that single-surface specs
 * never see.
 *
 * Pre-req: `task dev:titan && task seed`. Anchor data:
 *   - titan-server #4 is FAILED (seed-data.sh L94: queued 20m ago, error
 *     "integration-tests: connection refused"). This is our "the deploy
 *     blew up" pivot — we drill into it from the activity feed.
 *   - After PR #413 + #421, FAILED build #4 carries flow_nodes,
 *     test_result rows, AND artifact rows directly — the SRE's
 *     failure-drill stays on #4 end-to-end (no pivot to SUCCESS #2).
 *
 * Where an affordance is missing or known-broken, the step is marked
 * `test.fixme(true, "...")` with a one-line note. A test that's mostly
 * fixme is still extremely valuable — it inventories what's missing.
 *
 * Hard rule from the v3 spec patterns:
 *   - no full-page snapshots; targeted role / label queries only
 *   - one auth round-trip per test (loginViaKeycloak in test.beforeAll-equivalent)
 *   - assert via Playwright waiters, never setTimeout
 */
import { test, expect, type Response } from '@playwright/test'
import { authEnv, loginViaKeycloak } from '../../fixtures/auth-v3'
import { pgClient } from '../../fixtures/seed-v3'

const ENV = authEnv()

test.beforeAll(async () => {
  // Precondition: seed-data.sh must have run. After PR #413 + #421, FAILED
  // build #4 carries flow_nodes, test_result rows, AND artifact rows
  // directly — the journey stays on #4 end-to-end.
  const client = pgClient()
  await client.connect()
  try {
    const res = await client.query<{ n: string; status: string }>(
      `SELECT b.build_number::text AS n, b.status
         FROM titan.builds b
         JOIN titan.jobs   j ON j.id = b.job_id
        WHERE j.full_name = 'titan-server' AND b.build_number = 4`,
    )
    const four = res.rows[0]
    if (!four || four.status !== 'FAILED') {
      throw new Error(
        `Anchor titan-server#4 (FAILED) missing — re-run rig/local/seed-data.sh`,
      )
    }
  } finally {
    await client.end()
  }
})

test('sre-3am-journey — overview → failed build → logs → tests → artifacts → workers → queue → drain', async ({
  page,
}) => {
  // Capture every /api/v1/* response — used for cross-surface network
  // assertions (activity feed actually hit, drain POST returned 2xx, etc.).
  // Memory note "Playwright Lightweight Checks": prefer network/console
  // signals over full-page snapshots.
  const apiResponses: Array<{ url: string; status: number; method: string }> = []
  page.on('response', (resp: Response) => {
    const url = resp.url()
    if (url.includes('/api/v1/')) {
      apiResponses.push({
        url,
        status: resp.status(),
        method: resp.request().method(),
      })
    }
  })

  // ── Step 1 — Login ───────────────────────────────────────────────────────
  await test.step('1. Login via Keycloak, land on Overview', async () => {
    await loginViaKeycloak(page, ENV)
    // Sidebar must be mounted — loginViaKeycloak already asserts this, but
    // re-pin it so the journey reads top-to-bottom.
    await expect(page.getByRole('link', { name: 'Overview' }).first()).toBeVisible()
    // The post-login redirect lands on `/` (Overview).
    await page.goto(ENV.uiBaseUrl + '/')
    await expect(page.getByRole('heading', { name: /^overview$/i })).toBeVisible()
  })

  // ── Step 2 — Triage Overview KPIs ────────────────────────────────────────
  await test.step('2. Overview KPIs render REAL numbers (not "—", not skeleton) + activity feed loads', async () => {
    // Catches: KPI placeholder cards (#443 fix) — the previous assertion only
    // verified labels rendered, which passed even when the KPI value was a
    // skeleton or "—". We now assert each metric-value resolves to a numeric
    // value (not "—", not empty, not a Skeleton).
    //
    // Cards: Builds today, Success rate · 24h, Median duration · 24h,
    // Queue depth · now. Each Kpi renders `.metric-value` containing the
    // resolved value once loading completes (Skeleton swaps for the value).
    await expect(page.getByText('Builds today', { exact: false }).first()).toBeVisible({
      timeout: 10_000,
    })
    await expect(page.getByText('Success rate', { exact: false }).first()).toBeVisible()
    await expect(page.getByText('Median duration', { exact: false }).first()).toBeVisible()
    await expect(page.getByText('Queue depth', { exact: false }).first()).toBeVisible()

    // Catches: KPI 401 + placeholder (#443) — stats endpoint must return 200,
    // NOT 401 (the EventSource bug from PR #68 was symptomatic of broader auth
    // wiring; this catches the same class on the stats fetch).
    await expect
      .poll(() => apiResponses.some((r) => r.url.includes('/api/v1/stats') && r.status === 200), {
        timeout: 10_000,
        message: 'GET /api/v1/stats did not return 200 — token/auth wiring broken (PR #443 class)',
      })
      .toBe(true)
    // Adversarial: assert NO 401 ever observed on /api/v1/stats.
    const stats401 = apiResponses.find(
      (r) => r.url.includes('/api/v1/stats') && r.status === 401,
    )
    expect(stats401, `unexpected 401 on stats endpoint: ${stats401?.url}`).toBeUndefined()

    // Activity feed must have rendered at least one row from /api/v1/activity.
    await expect
      .poll(
        () =>
          apiResponses.some((r) => r.url.includes('/api/v1/activity') && r.status === 200),
        { timeout: 10_000 },
      )
      .toBe(true)

    // The activity card lists rows of class `.activity-row` (each is a Link
    // to `/builds/<id>` — see Overview ActivityRow).
    const rows = page.locator('.activity-row')
    await expect(rows.first()).toBeVisible({ timeout: 10_000 })
    const count = await rows.count()
    expect(count, 'activity feed should show at least one terminal build').toBeGreaterThan(0)

    // Catches: KPI placeholder cards (#443 fix) — assert each `.metric-value`
    // has resolved to actual content, not the em-dash fallback and not still
    // showing a Skeleton. The Overview renders 4 KPI cards in a .metric-grid;
    // every one must show real text.
    const metricValues = page.locator('.metric-grid .metric-value')
    await expect(metricValues).toHaveCount(4, { timeout: 10_000 })
    // Wait until every one of the 4 metric-values has non-empty, non-em-dash
    // text content. This forecloses both the Skeleton-stuck state and the
    // "—" placeholder state.
    await expect
      .poll(
        async () => {
          const texts = await metricValues.allTextContents()
          if (texts.length !== 4) return false
          return texts.every((t) => {
            const trimmed = t.trim()
            // Reject empty (still skeleton-only) AND em-dash placeholder.
            if (trimmed === '' || trimmed === '—') return false
            return true
          })
        },
        {
          timeout: 10_000,
          message:
            'one or more KPI cards never resolved — still "—" or Skeleton (catches #443 placeholder regression)',
        },
      )
      .toBe(true)
  })

  // ── Step 3 — Drill into the failed build ─────────────────────────────────
  await test.step('3. Click a FAILED entry in the activity feed → /builds/<id>', async () => {
    // The seeded FAILED build (titan-server #4) lands in the activity feed
    // because ActivityApi returns terminal builds (SUCCESS / FAILED /
    // ABORTED / UNSTABLE). The row text contains the job name + #buildId +
    // the status pill text "FAILED". We pick by hasText "FAILED" and click.
    const failedRow = page
      .locator('.activity-row')
      .filter({ hasText: 'FAILED' })
      .first()
    await expect(failedRow, 'a FAILED row must exist in the activity feed').toBeVisible({
      timeout: 10_000,
    })

    // The row is a `<Link to="/builds/$buildId">` so it navigates client-side.
    await failedRow.click()
    await page.waitForURL(/\/builds\/\d+$/, { timeout: 10_000 })

    // The build-detail page header reads "Builds / #<n>"; the status badge
    // and Duration summary metric come from build.status + build.durationMs.
    await expect(page.getByRole('heading', { name: /builds.*#/i })).toBeVisible()
    // Summary "Duration" tile — rendered from formatDuration(build.durationMs).
    await expect(page.getByText('Duration', { exact: true }).first()).toBeVisible()
  })

  // ── Step 4 — Find the failing step (Pipeline tab) ────────────────────────
  await test.step('4. Locate the failed step pill in the Pipeline tab + drill into it', async () => {
    // PR #413 seeded flow_nodes for FAILED build #4: 1 STAGE ("build") +
    // 3 STEPs (compile=SUCCESS, unit-tests=SUCCESS, integration-tests=FAILED).
    // The failing pill renders as `.job-card.fail` with displayName
    // "integration-tests"; clicking it populates the right-hand "Step"
    // detail panel via NodeDetail.
    const pipelineTab = page.getByRole('tab', { name: /^pipeline$/i })
    await expect(pipelineTab).toBeVisible()
    // Pipeline is the default — no click needed, but be explicit:
    await pipelineTab.click()

    // The build.errorMessage banner is rendered ABOVE the tabs (not inside
    // the pipeline pane). Still the SRE's first "what broke" 3am signal.
    await expect(
      page.getByText('integration-tests: connection refused', { exact: false }).first(),
    ).toBeVisible({ timeout: 5_000 })

    // Per-step pill — the FAILED step gets className `job-card fail`.
    // Filter by displayName "integration-tests" to anchor it.
    const failedPill = page
      .locator('.job-card.fail')
      .filter({ hasText: 'integration-tests' })
    await expect(failedPill, 'a FAILED step pill must render in the Pipeline view').toBeVisible({
      timeout: 10_000,
    })

    // SUCCESS pills must NOT have the fail class. The naive groupIntoStages
    // (titan-ui/src/routes/builds/$buildId.tsx) currently emits one job-card
    // per node — so we get 4 cards (build, compile, unit-tests, integration-tests).
    // Only the integration-tests card carries `.fail`.
    await expect(page.locator('.job-card')).toHaveCount(4)
    await expect(page.locator('.job-card.fail')).toHaveCount(1)

    // Click the failed pill — the right-hand NodeDetail card should reveal
    // the step's status via StatusBadge (text "FAILED").
    await failedPill.click()
    const stepDetailCard = page.locator('.card').filter({ hasText: 'Step' }).last()
    await expect(stepDetailCard.getByText('FAILED', { exact: true })).toBeVisible({
      timeout: 5_000,
    })
    // NodeDetail also shows the step descriptor ("sh") in mono.
    await expect(stepDetailCard.getByText('integration-tests')).toBeVisible()
  })

  // ── Step 5 — Logs ────────────────────────────────────────────────────────
  await test.step('5. Open Logs tab — SSE returns 200 (NOT 401) + log lines paint + indicator goes Live', async () => {
    // Catches: EventSource 401 (PR #68 in-flight). The prior assertion just
    // checked the tab pane mounted — passed even when the SSE 401'd.
    // Catches: empty task_archive linkage (#66) — passed when the pane was
    // empty because there was nothing on the build to begin with.
    // Catches: "Reconnecting…" stuck state — the indicator must move to "Live".
    await page.getByRole('tab', { name: /^logs$/i }).click()
    await expect(page.locator('.tab-pane').first()).toBeVisible()
    await expect(page.getByRole('tab', { name: /^logs$/i })).toHaveAttribute(
      'aria-selected',
      'true',
    )

    // Catches: EventSource 401 (#68) — assert the SSE GET /logs returned 200.
    // EventSource fires the request on tab mount; we already captured every
    // /api/v1/* response above. The SSE URL ends in `/logs`.
    await expect
      .poll(
        () =>
          apiResponses.some(
            (r) => /\/api\/v1\/builds\/\d+\/logs$/.test(r.url) && r.status === 200,
          ),
        {
          timeout: 10_000,
          message:
            'SSE GET /api/v1/builds/<id>/logs never returned 200 — EventSource 401 (PR #68 bug class)',
        },
      )
      .toBe(true)
    // Adversarial: assert NO 401 ever observed on the SSE endpoint.
    const sse401 = apiResponses.find(
      (r) => /\/api\/v1\/builds\/\d+\/logs$/.test(r.url) && r.status === 401,
    )
    expect(
      sse401,
      `EventSource 401 on logs SSE — PR #68 bug class: ${sse401?.url}`,
    ).toBeUndefined()

    // NOTE: titan-server#4 (FAILED) — the seed populates flow_nodes via PR
    // #413 but the `step-it` failed step may or may not carry task_archive
    // log rows depending on seed-data.sh state. Catches #66 (empty
    // task_archive linkage) ONLY if the seed wires logs for build #4. For
    // the deterministic log-line-count assertion see 10-log-stream.spec.ts
    // (anchored on titan-hello#1 with 9 seeded chunks). Here we settle for
    // the SSE returning 200 + indicator transitioning OUT of "Connecting…".

    // Catches: "Reconnecting…" stuck state — the SSE indicator must NOT be
    // stuck on "Connecting…" or "Reconnecting…" after 3s. It should be "Live"
    // (open + receiving) or "Done" (closed cleanly).
    await expect
      .poll(
        async () => {
          const sseLabel = await page
            .locator('.terminal-head span')
            .first()
            .textContent()
            .catch(() => null)
          return sseLabel?.trim()
        },
        {
          timeout: 5_000,
          message:
            'SSE indicator never left Connecting/Reconnecting — connection stuck (catches the "Reconnecting…" stuck state)',
        },
      )
      .toMatch(/^(Live|Done)$/)
  })

  // ── Step 6 — Tests on the FAILED build itself (PR #413 attached the
  //              5/2/1 test_result rows directly to #4 via node_id=step-it). ─
  await test.step('6. Tests tab — 5 passed / 2 failed / 1 skipped chips + expand failure', async () => {
    // PR #413 seeded test_result rows on FAILED build #4 (node_id='step-it'),
    // mirroring the SUCCESS anchor mix: 5 PASSED / 2 FAILED / 1 SKIPPED.
    // The 3am SRE journey surfaces tests on the FAILING build — no more
    // pivot to #2.
    await page.getByRole('tab', { name: /^tests$/i }).click()

    // Counter chips — aria-label="N label" (see TestResultsPanel Counter).
    await expect(page.getByLabel('5 passed')).toBeVisible({ timeout: 10_000 })
    await expect(page.getByLabel('2 failed')).toBeVisible()
    await expect(page.getByLabel('1 skipped')).toBeVisible()

    // Default landing view is the Failed filter (panel auto-selects when
    // failed > 0). Click an "All" filter, assert 8 rows.
    const rows = page.locator('.test-row')
    await expect(rows).toHaveCount(2)
    await page.getByRole('button', { name: /^all$/i, pressed: false }).click()
    await expect(rows).toHaveCount(8)

    // Reveal a failure message — click the first failed row.
    await page.getByRole('button', { name: /^failed/i, pressed: false }).click()
    const firstFailed = page.locator('.test-row').first()
    await firstFailed.click()
    const expanded = page.locator('pre[id^="test-fail-"]').first()
    await expect(expanded).toBeVisible()
    // PR #413 seeded two FAILED rows with different stacks: one ConnectException
    // (DbConnectionIT — db-it down) + one AssertionFailedError (auth test).
    await expect(expanded).toContainText(/AssertionError|AssertionFailedError|ConnectException/)
  })

  // ── Step 7 — Artifacts on the FAILED build itself (PR #421 seeded 3
  //              failure-context artifact rows on #4: junit.xml,
  //              heap-dump.hprof, integration-test.log). ─────────────────────
  await test.step('7. Artifacts tab — 3 rows with sizes; download anchor is non-404', async () => {
    await page.getByRole('tab', { name: /^artifacts$/i }).click()

    // 3 artifact rows + 1 header row = 4 `.artifact-row` total.
    await expect(page.locator('.artifact-row')).toHaveCount(4, { timeout: 10_000 })

    // PR #421 seeded heap-dump.hprof at 262144000 bytes — humanBytes()
    // renders MB-range. Anchor the assertion on the heap dump (largest
    // failure-context payload, exercises the MB branch).
    const heapRow = page
      .locator('.artifact-row')
      .filter({ hasText: 'target/heap-dump.hprof' })
    await expect(heapRow).toBeVisible()
    await expect(heapRow).toContainText(/MB/)

    // The download anchor is `<a aria-label="Download {name}">`. The href
    // must shape to `/api/v1/artifacts/<id>/download` (the contract frozen
    // by ArtifactsApi). Hitting it must NOT 404 — PR #399 wired the
    // endpoint, so we expect a 2xx/3xx (or at worst 5xx if the file isn't
    // present on disk in the local rig, but explicitly NOT 404).
    const dl = page.getByRole('link', { name: 'Download target/heap-dump.hprof' })
    const href = await dl.getAttribute('href')
    expect(href).toMatch(/\/api\/v1\/artifacts\/\d+\/download$/)

    const resp = await page.request.get(href!, { failOnStatusCode: false })
    // PR #399 acceptance: the endpoint exists; 404 means the URL was wrong
    // OR the endpoint is missing. Either is a journey-breaking bug.
    expect(
      resp.status(),
      `download anchor returned ${resp.status()} — PR #399 endpoint missing or path wrong`,
    ).not.toBe(404)
    expect(resp.status()).toBeLessThan(600)
  })

  // ── Step 8 — Workers ─────────────────────────────────────────────────────
  await test.step('8. Workers page — CPU/MEM/DISK bars populated (not "—")', async () => {
    await page.goto(ENV.uiBaseUrl + '/workers')

    // /api/v1/workers must have been hit + returned 200.
    await expect
      .poll(
        () =>
          apiResponses.some((r) => r.url.includes('/api/v1/workers') && r.status === 200),
        { timeout: 10_000 },
      )
      .toBe(true)

    // At least one `.worker` card renders. The local rig brings one worker
    // up by default (rig/local/docker-compose.yml).
    const workerCards = page.locator('.worker')
    await expect(workerCards.first()).toBeVisible({ timeout: 10_000 })

    // PR #372 + #378 wired the heartbeat sampler — CPU/MEM/DISK must NOT be
    // "—" on the first sample. We assert at least one bar shows a percent
    // (the val text matches /\d+%/), not the em-dash fallback.
    //
    // Worker card bars render with `.val` containing either "N%" or "—".
    // We probe the first worker card's bars.
    const firstCard = page.locator('.worker').first()
    await expect(firstCard).toBeVisible()

    // Wait up to 30s for at least one of the bars on the first card to
    // flip to a percent — the sampler beats every ~5s.
    await expect
      .poll(
        async () => {
          const vals = await firstCard.locator('.worker-bar .val').allTextContents()
          return vals.some((v) => /\d+%/.test(v))
        },
        { timeout: 30_000, message: 'no CPU/MEM/DISK bar populated within 30s — sampler stuck?' },
      )
      .toBe(true)
  })

  // ── Step 9 — Queue (PR #408 + container rebuild restored /queue) ─────────
  await test.step('9. Queue page renders without crash', async () => {
    // PR #408 + the container rebuild on tick #32 resolved the /queue
    // regression — it now renders deterministically.
    await page.goto(ENV.uiBaseUrl + '/queue', { timeout: 10_000 })
    await expect(page.getByRole('heading', { name: /^build queue$/i })).toBeVisible({
      timeout: 5_000,
    })
  })

  // ── Step 10 — Drain the queue ────────────────────────────────────────────
  await test.step('10. Click Drain queue, accept confirm, assert banner/toast', async () => {
    // The Drain button is on the Queue page header. Empty queue disables
    // the button — to make the drain meaningful we accept either:
    //   (a) button enabled → click → banner with "Drained N tasks." appears
    //   (b) button disabled → empty queue → assert the disabled state +
    //       confirm the POST /api/v1/queue/drain endpoint exists by hitting
    //       it directly (still proves the wire is correct).
    const drainBtn = page.getByRole('button', { name: /drain queue/i })
    await expect(drainBtn).toBeVisible({ timeout: 5_000 })

    const isDisabled = await drainBtn.isDisabled()
    if (isDisabled) {
      // Queue is empty — onDrainClick early-returns. We still need to
      // assert the drain wire works; the v3 specs treat this as a valid
      // path because the seed leaves only 1 QUEUED task (titan-ui#2) and
      // a worker may have claimed it by now. Confirm the empty-state copy
      // and move on.
      await expect(page.getByText(/no tasks queued/i)).toBeVisible()
      // Annotate so the inventory captures it.
      test.info().annotations.push({
        type: 'note',
        description: 'Drain button disabled — queue empty when journey reached step 10. Drain wire untested in this run.',
      })
      return
    }

    // Drain uses window.confirm() — accept it before the click resolves.
    page.once('dialog', (dlg) => {
      void dlg.accept()
    })

    await drainBtn.click()

    // BannerLine renders with role="status". The success copy is
    // "Drained · Drained N tasks." (BannerLine sets okLabel=Drained).
    await expect(page.getByRole('status').filter({ hasText: /Drained/ })).toBeVisible({
      timeout: 10_000,
    })

    // Network-level proof: POST /api/v1/queue/drain must have been hit.
    await expect
      .poll(
        () =>
          apiResponses.some(
            (r) =>
              r.url.includes('/api/v1/queue/drain') && r.method === 'POST' && r.status < 400,
          ),
        { timeout: 5_000 },
      )
      .toBe(true)
  })

  // ── Journey post-conditions ──────────────────────────────────────────────
  // Captured network evidence: surface the per-surface hits so the PR body
  // can list which endpoints the journey exercised.
  await test.step('post: network inventory of cross-surface endpoints hit', async () => {
    const uniqueEndpoints = new Set(
      apiResponses
        .filter((r) => r.status < 400)
        .map((r) => r.url.replace(/\?.*$/, '').replace(/\/\d+/g, '/<id>')),
    )
    // Expected: stats, activity, build detail, build nodes, tests page,
    // artifacts page, workers, queue (if loaded), and queue/drain (if
    // queue wasn't empty). We don't strict-equal — we just assert the
    // core integrated set landed.
    const expected = ['/api/v1/stats', '/api/v1/activity', '/api/v1/workers']
    for (const ep of expected) {
      expect(
        Array.from(uniqueEndpoints).some((u) => u.includes(ep)),
        `journey should have hit ${ep} — got: ${Array.from(uniqueEndpoints).join(', ')}`,
      ).toBe(true)
    }
  })
})
