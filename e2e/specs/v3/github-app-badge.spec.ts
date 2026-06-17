/**
 * github-app-badge — build-detail GitHub-App provenance badge (issue #892).
 *
 * Finishing the UX half of #835: the engine (PR #891) reports commit status
 * back to GitHub for App-triggered builds; this badge gives an operator the
 * matching visual confirmation on /builds/$id —
 *
 *     via GitHub App · {org}/{repo} · view on github.com
 *
 * with "view on github.com" deep-linking the commit on github.com.
 *
 * Plan:
 *   - Seed directly into Postgres (no live GitHub round-trip): an installation,
 *     a repository row, a job linked to that (install, repo), and a build whose
 *     trigger_type is `github-app:push` carrying a commitSha in trigger_meta_json.
 *   - Navigate to /builds/<seededId> and assert the badge renders with the
 *     expected text + an <a href> pointing at github.com/<owner>/<repo>/commit/<sha>
 *     opening in a new tab.
 *   - Negative control: a manually-triggered build shows NO badge.
 */
import { test, expect } from '@playwright/test'
import { authEnv, loginViaKeycloak } from '../../fixtures/auth-v3'
import { pgClient } from '../../fixtures/seed-v3'

const ENV = authEnv()

const OWNER = 'hadamrd'
const REPO = 'titan'
const SHA = 'abc123def456abc123def456abc123def456abcd'
// Use high, test-private ids to avoid colliding with any seed-data.sh rows.
const INSTALL_ID = 880_001
const REPO_ID = 880_002

let appBuildId: number
let manualBuildId: number

/**
 * The id of the single row a `RETURNING id::text` insert produced. `pg`'s
 * `QueryResult.rows[0]` is `T | undefined`; this guards that explicitly (an
 * empty result means the insert silently did nothing — a test-setup bug we
 * want to surface loudly, not paper over with `!`).
 */
function insertedId(result: { rows: Array<{ id: string }> }, label: string): number {
  const row = result.rows[0]
  if (!row) throw new Error(`${label}: expected an inserted row, got none`)
  return Number(row.id)
}

test.beforeAll(async () => {
  const client = pgClient()
  await client.connect()
  try {
    await client.query(
      `INSERT INTO titan.github_installations (install_id, account_login, account_type, target_type)
       VALUES ($1, $2, 'User', 'User')
       ON CONFLICT (install_id) DO NOTHING`,
      [INSTALL_ID, OWNER],
    )
    await client.query(
      `INSERT INTO titan.github_repositories (install_id, repo_id, owner, name)
       VALUES ($1, $2, $3, $4)
       ON CONFLICT (repo_id) DO UPDATE SET owner = EXCLUDED.owner, name = EXCLUDED.name`,
      [INSTALL_ID, REPO_ID, OWNER, REPO],
    )

    const jobName = `e2e-gh-app/${Date.now()}`
    const job = await client.query<{ id: string }>(
      `INSERT INTO titan.jobs (full_name, pipeline_script, config_json, enabled,
                               github_installation_id, github_repo_id)
       VALUES ($1, '', '{}', true, $2, $3)
       RETURNING id::text AS id`,
      [jobName, INSTALL_ID, REPO_ID],
    )
    const jobId = insertedId(job, 'job insert')

    const meta = JSON.stringify({ branch: 'main', commitSha: SHA, actor: OWNER })
    const appBuild = await client.query<{ id: string }>(
      `INSERT INTO titan.builds (job_id, build_number, status, queued_at,
                                 trigger_meta_json, triggered_by, trigger_type)
       VALUES ($1, 1, 'SUCCESS', NOW(), $2, $3, 'github-app:push')
       RETURNING id::text AS id`,
      [jobId, meta, OWNER],
    )
    appBuildId = insertedId(appBuild, 'app build insert')

    const manualBuild = await client.query<{ id: string }>(
      `INSERT INTO titan.builds (job_id, build_number, status, queued_at,
                                 trigger_meta_json, triggered_by, trigger_type)
       VALUES ($1, 2, 'SUCCESS', NOW(), $2, 'alice', 'manual')
       RETURNING id::text AS id`,
      [jobId, meta],
    )
    manualBuildId = insertedId(manualBuild, 'manual build insert')
  } finally {
    await client.end()
  }
})

test.describe('v3 github-app provenance badge', () => {
  test.beforeEach(async ({ page }) => {
    await loginViaKeycloak(page, ENV)
  })

  test('App-triggered build shows the provenance badge with a github.com commit link', async ({
    page,
  }) => {
    await page.goto(`${ENV.uiBaseUrl}/builds/${appBuildId}`)

    const badge = page.getByTestId('github-provenance-badge')
    await expect(badge).toBeVisible({ timeout: 10_000 })
    await expect(badge).toContainText('via GitHub App')
    await expect(page.getByTestId('github-provenance-repo')).toHaveText(
      `${OWNER}/${REPO}`,
    )

    const link = page.getByTestId('github-provenance-link')
    await expect(link).toHaveText('view on github.com')
    await expect(link).toHaveAttribute(
      'href',
      `https://github.com/${OWNER}/${REPO}/commit/${SHA}`,
    )
    await expect(link).toHaveAttribute('target', '_blank')
    await expect(link).toHaveAttribute('rel', 'noopener noreferrer')
  })

  test('manually-triggered build shows NO provenance badge (negative control)', async ({
    page,
  }) => {
    await page.goto(`${ENV.uiBaseUrl}/builds/${manualBuildId}`)
    // Header must render so we know the page loaded, then assert badge absence.
    await expect(page.getByRole('heading', { name: /builds.*#/i })).toBeVisible({
      timeout: 10_000,
    })
    await expect(page.getByTestId('github-provenance-badge')).toHaveCount(0)
  })
})
