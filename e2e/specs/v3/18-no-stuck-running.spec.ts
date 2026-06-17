/**
 * 18-no-stuck-running — assert no RUNNING build is "stuck".
 *
 * Bug class (PR #70): a fake-seeded build had status=RUNNING with a
 * started_at hours in the past but no task_queue row, so it would never
 * progress. The UI cheerfully showed "Running 1h" forever.
 *
 * This spec hits /builds, finds every RUNNING build, and asserts ONE of:
 *   (a) started_at is within the last 5 minutes — fresh, legitimately running
 *   (b) the build progresses within 30s: status changes OR log/node count
 *       grows on the detail page
 *
 * Otherwise FAIL with a clear "build N is stuck-RUNNING for X minutes —
 * fake seed?" message. Would have caught PR #70 on day zero.
 *
 * Lightweight per memory `feedback_playwright_lightweight_checks` — direct
 * API fetches via the page's session cookies + a small detail-page poll.
 */
import { test, expect, type APIRequestContext, type Page } from '@playwright/test'
import { authEnv, loginViaKeycloak } from '../../fixtures/auth-v3'

const ENV = authEnv()
const STUCK_THRESHOLD_MS = 5 * 60 * 1000

type Build = {
  id: number
  jobId: number
  buildNumber: number
  status: string
  startedAt: string | null
}

type BuildsPage = { items: Build[]; total: number }

// Pull builds via API (deterministic ordering, full visibility) rather than
// scraping the /builds DOM — same data source the UI uses, but we read it
// directly to avoid filter chips hiding RUNNING ones from us.
async function fetchAllRunning(api: APIRequestContext, token: string): Promise<Build[]> {
  const running: Build[] = []
  // Iterate jobs, fetch their builds. Cheaper than hammering one giant
  // /builds endpoint that may not exist on every rig configuration.
  const jobsResp = await api.get(`/api/v1/jobs?offset=0&limit=100`, {
    headers: { Authorization: `Bearer ${token}` },
  })
  expect(jobsResp.ok(), `GET /api/v1/jobs failed: ${jobsResp.status()}`).toBeTruthy()
  const jobs = (await jobsResp.json()) as { items: Array<{ id: number }> }
  for (const job of jobs.items) {
    const r = await api.get(`/api/v1/jobs/${job.id}/builds?offset=0&limit=50`, {
      headers: { Authorization: `Bearer ${token}` },
    })
    if (!r.ok()) continue
    const page = (await r.json()) as BuildsPage
    for (const b of page.items) {
      if (b.status === 'RUNNING') running.push(b)
    }
  }
  return running
}

test.describe('v3 no-stuck-running', () => {
  test('every RUNNING build is either fresh (<5min) or progressing within 30s', async ({
    page,
    request,
  }) => {
    // Catches: PR #70 fake-seed where status=RUNNING but task_queue empty
    // → "Running 1h" forever and no logs ever stream.
    await loginViaKeycloak(page, ENV)
    const token = await extractAccessToken(page)

    // Build a request context targeted at the rig (request fixture uses the
    // playwright base URL — we explicitly point at the UI host which
    // reverse-proxies /api → titan-server).
    const api = request
    const running = await fetchAllRunning(api, token)
    if (running.length === 0) {
      test.info().annotations.push({
        type: 'note',
        description: 'no RUNNING builds on the rig — spec vacuously passes',
      })
      return
    }

    const now = Date.now()
    const suspects = running.filter((b) => {
      if (!b.startedAt) return true // started_at null while RUNNING is itself suspicious
      const age = now - new Date(b.startedAt).getTime()
      return age > STUCK_THRESHOLD_MS
    })

    if (suspects.length === 0) {
      // All RUNNING builds are fresh — clean state.
      return
    }

    // For each suspect, poll for progress: status change OR node count grow.
    const stillStuck: string[] = []
    for (const b of suspects) {
      const nodesUrl = `/api/v1/builds/${b.id}/nodes`
      const initialNodes = await api
        .get(nodesUrl, { headers: { Authorization: `Bearer ${token}` } })
        .then((r) => (r.ok() ? (r.json() as Promise<unknown[]>) : Promise.resolve([])))
      const initialCount = Array.isArray(initialNodes) ? initialNodes.length : 0

      let progressed = false
      const deadline = Date.now() + 30_000
      while (Date.now() < deadline) {
        await page.waitForTimeout(2_000)
        // Refetch the build to see if status flipped.
        const br = await api.get(`/api/v1/builds/${b.id}`, {
          headers: { Authorization: `Bearer ${token}` },
        })
        if (br.ok()) {
          const cur = (await br.json()) as Build
          if (cur.status !== 'RUNNING') {
            progressed = true
            break
          }
        }
        const nr = await api.get(nodesUrl, {
          headers: { Authorization: `Bearer ${token}` },
        })
        if (nr.ok()) {
          const nodes = (await nr.json()) as unknown[]
          if (Array.isArray(nodes) && nodes.length > initialCount) {
            progressed = true
            break
          }
        }
      }

      if (!progressed) {
        const ageMin = Math.round((now - new Date(b.startedAt ?? now).getTime()) / 60_000)
        stillStuck.push(
          `build #${b.buildNumber} (id=${b.id}, jobId=${b.jobId}) RUNNING for ${ageMin}min, no status change + no new nodes in 30s — likely fake seed (PR #70 bug class)`,
        )
      }
    }

    expect(stillStuck, stillStuck.join('\n')).toEqual([])
  })
})

/**
 * Pull the OIDC access token out of the SPA's sessionStorage. The SPA stores
 * the User under `oidc.user:<authority>:<client_id>` per oidc-client-ts
 * defaults (see titan-ui/src/auth/oidcConfig.ts — userStore is sessionStorage).
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
