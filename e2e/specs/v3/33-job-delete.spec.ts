/**
 * 33-job-delete — DELETE /api/v1/jobs/{id} actually deletes the row (closes #964).
 *
 * Before the fix the endpoint returned 405 Method Not Allowed and every e2e
 * spec that creates a job (#31 with-retry, #26-30 fixtures, #960 cancel)
 * leaked a row per run. This spec is a small dedicated guard so the
 * regression cannot creep back in unnoticed by the larger feature specs
 * (which catch it only via a console.warn in their finally{}).
 *
 * Flow (API-only, no UI — the delete button doesn't exist yet, follow-up
 * issue):
 *   1. Create a job via POST /api/v1/jobs with a minimal valid pipeline.
 *   2. Confirm it is visible via GET /api/v1/jobs/{id} → 200.
 *   3. DELETE /api/v1/jobs/{id} → 204.
 *   4. GET /api/v1/jobs/{id} → 404 (the row is gone).
 *   5. Listing /api/v1/jobs?search=<unique-name> → does not contain the id.
 *   6. A second DELETE → 404 (idempotent, never 500).
 */
import { test, expect } from '@playwright/test'
import { authEnv, fetchBearerToken } from '../../fixtures/auth-v3'

const ENV = authEnv()
const API_BASE = process.env.TITAN_API_URL ?? 'http://localhost:18080'

const MIN_YAML = 'stages:\n  - stage: build\n    steps:\n      - shell: echo hi\n'

test.describe('v3 job-delete @golden', () => {
  test('create → delete → 404 → list excludes the id', async ({ request }) => {
    test.setTimeout(60_000)

    const bearer = await fetchBearerToken(ENV)
    const fullName = `e2e/delete-${Date.now()}-${Math.floor(Math.random() * 1e6)}`

    // 1. Create.
    const created = await request.post(`${API_BASE}/api/v1/jobs`, {
      headers: { Authorization: `Bearer ${bearer}`, 'Content-Type': 'application/json' },
      data: {
        fullName,
        displayName: 'e2e-delete',
        pipelineScript: MIN_YAML,
        enabled: true,
      },
    })
    const createdBody = await created.text()
    expect(
      created.status(),
      `POST /api/v1/jobs HTTP ${created.status()} body=${createdBody.slice(0, 400)}`,
    ).toBe(201)
    const jobId = (JSON.parse(createdBody) as { id: number }).id
    expect(jobId, 'created job must carry a numeric id').toBeGreaterThan(0)

    let cleanupNeeded = true
    try {
      // 2. GET → 200.
      const before = await request.get(`${API_BASE}/api/v1/jobs/${jobId}`, {
        headers: { Authorization: `Bearer ${bearer}` },
      })
      expect(before.status(), 'GET before delete must return 200').toBe(200)

      // 3. DELETE → 204.
      const del = await request.delete(`${API_BASE}/api/v1/jobs/${jobId}`, {
        headers: { Authorization: `Bearer ${bearer}` },
      })
      expect(
        del.status(),
        `DELETE /api/v1/jobs/${jobId} expected 204 — pre-fix this was 405 (#964)`,
      ).toBe(204)
      cleanupNeeded = false

      // 4. GET after delete → 404.
      const after = await request.get(`${API_BASE}/api/v1/jobs/${jobId}`, {
        headers: { Authorization: `Bearer ${bearer}` },
      })
      expect(after.status(), 'GET after delete must return 404').toBe(404)

      // 5. The unique fullName must no longer surface in the list.
      const list = await request.get(
        `${API_BASE}/api/v1/jobs?search=${encodeURIComponent(fullName)}&limit=200`,
        { headers: { Authorization: `Bearer ${bearer}` } },
      )
      expect(list.status()).toBe(200)
      const listBody = (await list.json()) as { items: Array<{ id: number; fullName: string }> }
      const stillThere = listBody.items.find((j) => j.id === jobId)
      expect(
        stillThere,
        `deleted job ${jobId} (${fullName}) must not appear in /api/v1/jobs search results`,
      ).toBeUndefined()

      // 6. Second DELETE — must be a clean 404, never 500.
      const second = await request.delete(`${API_BASE}/api/v1/jobs/${jobId}`, {
        headers: { Authorization: `Bearer ${bearer}` },
      })
      expect(
        second.status(),
        'second DELETE on an already-deleted job must return 404, not 500',
      ).toBe(404)
    } finally {
      if (cleanupNeeded) {
        await request
          .delete(`${API_BASE}/api/v1/jobs/${jobId}`, {
            headers: { Authorization: `Bearer ${bearer}` },
          })
          .catch(() => null)
      }
    }
  })
})
