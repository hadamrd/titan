/**
 * 12-drain-queue — Admin drains the queue.
 *
 * Pre-req: `task dev:titan` is up, dev user has ADMIN role (per PR #430's
 * seed) — required because POST /api/v1/queue/drain is gated to
 * @RolesAllowed({Roles.ADMIN}) in AdminQueueApi.
 *
 * Affordance under test: the "Drain queue" button on /queue (PR #378,
 * styled by PR #410 — see titan-ui/src/routes/queue.tsx, ~line 200).
 *
 * Flow:
 *   - Seed a handful of QUEUED tasks (queue_name prefix `e2e-drain-`) so
 *     the test is deterministic regardless of whatever the rig has.
 *   - Navigate to /queue → click "Drain queue".
 *   - The page uses `window.confirm` (NOT a modal) — accept it via a
 *     dialog handler before the click.
 *   - Watch the POST /api/v1/queue/drain network call → expect 2xx.
 *   - DB read-back: our seeded queue_name prefix should have zero QUEUED
 *     rows left (they're moved to CANCELLED — TaskQueueDao.drainAllQueued).
 *   - "Drained N tasks." banner is rendered.
 *
 * Teardown: hard-delete the seeded rows by prefix so the queue table is
 * clean for downstream specs.
 */
import { test, expect } from '@playwright/test'
import { authEnv, loginViaKeycloak } from '../../fixtures/auth-v3'
import {
  clearQueuedTasksByPrefix,
  countQueuedTasks,
  seedQueuedTask,
} from '../../fixtures/seed-v3'

const ENV = authEnv()
const PREFIX = 'e2e-drain-'

test.beforeEach(async () => {
  await clearQueuedTasksByPrefix(PREFIX)
  await seedQueuedTask(`${PREFIX}a`, 0)
  await seedQueuedTask(`${PREFIX}b`, 0)
  await seedQueuedTask(`${PREFIX}c`, 0)
})

test.afterEach(async () => {
  await clearQueuedTasksByPrefix(PREFIX)
})

test('Drain queue posts /drain and clears QUEUED tasks', async ({ page }) => {
  await loginViaKeycloak(page, ENV)

  // Accept the window.confirm dialog the page fires before the mutation.
  page.on('dialog', (d) => {
    void d.accept()
  })

  await page.goto(`${ENV.uiBaseUrl}/queue`)

  // The button text is "Drain queue" while idle, "Draining…" while pending.
  const drainBtn = page.getByRole('button', { name: /^Drain queue$/ })
  await expect(drainBtn).toBeVisible({ timeout: 10_000 })
  await expect(drainBtn).toBeEnabled()

  const before = await countQueuedTasks()
  expect(before).toBeGreaterThanOrEqual(3)

  const [resp] = await Promise.all([
    page.waitForResponse(
      (r) =>
        r.url().includes('/api/v1/queue/drain') &&
        r.request().method() === 'POST',
      { timeout: 10_000 },
    ),
    drainBtn.click(),
  ])
  expect(resp.ok()).toBeTruthy()

  // Success banner — uses role="status" with "Drained" prefix (see BannerLine).
  const banner = page.getByRole('status').filter({ hasText: /Drained/ })
  await expect(banner).toBeVisible({ timeout: 5_000 })

  // DB read-back: our seeded rows are no longer QUEUED.
  await expect
    .poll(
      async () => {
        // Total QUEUED count must have dropped by at least our seed (3).
        const now = await countQueuedTasks()
        return before - now
      },
      { timeout: 5_000 },
    )
    .toBeGreaterThanOrEqual(3)
})
