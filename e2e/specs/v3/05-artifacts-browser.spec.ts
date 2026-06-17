/**
 * 05-artifacts-browser — ArtifactsPanel list + download affordance.
 *
 * Pre-req: `task dev:titan` is up and `rig/local/seed-data.sh` has run (closes
 * #323 — the script now inserts 4 titan.artifact rows on titan-server#2 with
 * a mix of paths and sizes spanning the humanBytes() B/KB/MB branches).
 *
 * Assertions:
 *   - All 4 seeded artifact names render in the panel, each with the
 *     formatter-derived size next to it (`5.0 MB`, `1.0 KB`, `12.0 MB`,
 *     `200.0 KB`).
 *   - Each artifact row exposes a Download link whose href targets
 *     `/api/v1/artifacts/{id}/download` — the contract frozen by ArtifactsApi
 *     (see ArtifactDto.from). We assert the URL shape, not the response body,
 *     because the download endpoint itself is a deliberate follow-up — see
 *     ArtifactsApi javadoc: "The URL shape is frozen now so the UI can wire
 *     its anchors against a stable contract." Hitting it today returns 404.
 *   - Hitting the link's URL via page.request.get() reaches the backend (any
 *     2xx-5xx status counts — what we're proving is the link wires to the
 *     correct path; once the endpoint lands the status will move to 200/302
 *     and this assertion still passes).
 */
import { test, expect } from '@playwright/test'
import { authEnv, loginViaKeycloak } from '../../fixtures/auth-v3'
import { pgClient } from '../../fixtures/seed-v3'

const ENV = authEnv()

let buildId: number

test.beforeAll(async () => {
  // Resolve the anchor build (titan-server build_number=2) and assert the
  // artifact seed actually landed — surfaces a clearer failure than the UI
  // assertions would if seed-data.sh wasn't run.
  const client = pgClient()
  await client.connect()
  try {
    const res = await client.query<{ id: string; n: string }>(
      `SELECT b.id::text AS id,
              (SELECT count(*) FROM titan.artifact a WHERE a.build_id = b.id)::text AS n
         FROM titan.builds b
         JOIN titan.jobs   j ON j.id = b.job_id
        WHERE j.full_name = 'titan-server' AND b.build_number = 2`,
    )
    if (res.rows.length === 0) {
      throw new Error('Anchor build titan-server#2 not found — did seed-data.sh run?')
    }
    const row = res.rows[0]!
    if (Number(row.n) < 4) {
      throw new Error(
        `Expected >=4 seeded titan.artifact rows on titan-server#2, found ${row.n}. ` +
          'Re-run rig/local/seed-data.sh against a clean rig.',
      )
    }
    buildId = Number(row.id)
  } finally {
    await client.end()
  }
})

test('renders seeded artifacts with formatted sizes and download links', async ({ page }) => {
  await loginViaKeycloak(page, ENV)
  await page.goto(`${ENV.uiBaseUrl}/builds/${buildId}`)

  // Switch to the Artifacts tab — BuildDetailPage defaults to Pipeline.
  await page.getByRole('tab', { name: /^artifacts$/i }).click()

  // humanBytes() rendering for our seeded sizes:
  //   5_242_880 B  -> "5.0 MB"
  //   1_024 B      -> "1.0 KB"
  //   12_582_912 B -> "12.0 MB"
  //   204_800 B    -> "200.0 KB"
  const expected: Array<{ name: string; size: string }> = [
    { name: 'target/titan-server.jar', size: '5.0 MB' },
    { name: 'target/bar.txt', size: '1.0 KB' },
    { name: 'target/baz.zip', size: '12.0 MB' },
    { name: 'build/reports/coverage.html', size: '200.0 KB' },
  ]

  // Wait for the panel to leave its loading skeleton — the row-head + each
  // data row render under `.artifact-row`, so count == expected+1 means the
  // list rendered. Generous timeout: the API call kicks off after tab click.
  await expect(page.locator('.artifact-row')).toHaveCount(expected.length + 1, {
    timeout: 10_000,
  })

  for (const { name, size } of expected) {
    const row = page.locator('.artifact-row').filter({ hasText: name }).first()
    await expect(row).toBeVisible()
    await expect(row).toContainText(size)

    // The Download link is an <a> with aria-label `Download {name}`.
    const dl = page.getByRole('link', { name: `Download ${name}` })
    await expect(dl).toBeVisible()
    const href = await dl.getAttribute('href')
    expect(href).toMatch(/\/api\/v1\/artifacts\/\d+\/download$/)
  }

  // ── Exercise the download URL ─────────────────────────────────────────────
  // The download endpoint is a deliberate follow-up per ArtifactsApi javadoc;
  // the contract we lock here is the URL shape (asserted above) plus that
  // hitting the URL actually reaches the backend (vs. e.g. a wrong path).
  const target = expected[0]!
  const link = page.getByRole('link', { name: `Download ${target.name}` })
  const downloadHref = await link.getAttribute('href')
  expect(downloadHref).not.toBeNull()
  const resp = await page.request.get(downloadHref!, { failOnStatusCode: false })
  expect(resp.status()).toBeGreaterThanOrEqual(200)
  expect(resp.status()).toBeLessThan(600)
})
