/**
 * 13-reorder-queue-dnd — Admin reorders the queue via drag-drop.
 *
 * Pre-req: `task dev:titan` is up; dev user has ADMIN (POST
 * /api/v1/queue/reorder is gated to Roles.ADMIN in AdminQueueApi).
 *
 * Affordance under test: the SortableQueueRow + DndContext in
 * titan-ui/src/routes/queue.tsx (uses @dnd-kit/sortable). The drag handle
 * has aria-label `Drag to reorder task ${taskId}`. Dragging causes
 * `Save order` to appear → clicking it posts /api/v1/queue/reorder with
 * the new taskIds.
 *
 * Why KeyboardSensor instead of mouse drag (closes #435):
 *   dnd-kit's PointerSensor has an `activationConstraint: { distance: 4 }`
 *   that requires a deliberate pointer-movement sequence before activation.
 *   Playwright's synthetic `mouse.down` / `mouse.move` chain is timing-
 *   sensitive against that threshold — the drag would silently fail to
 *   register a non-trivial fraction of runs, and the previous version of
 *   this spec fell back to `test.fixme` (forbidden anti-pattern: tests
 *   that mask their own flakiness instead of reporting a real defect).
 *
 *   dnd-kit ships a first-class KeyboardSensor wired in the production
 *   `useSensors` block of queue.tsx with `sortableKeyboardCoordinates` —
 *   the canonical accessible-drag path. Focusing the grip <button> and
 *   pressing Space picks it up, ArrowDown moves it, Space drops it. No
 *   activation threshold, no pointer-event simulation, fully deterministic,
 *   and we get accessibility coverage for free.
 */
import { test, expect } from '@playwright/test'
import { authEnv, loginViaKeycloak } from '../../fixtures/auth-v3'
import {
  clearQueuedTasksByPrefix,
  seedQueuedTask,
} from '../../fixtures/seed-v3'

const ENV = authEnv()
const PREFIX = 'e2e-reorder-'

let seededIds: number[] = []

test.beforeEach(async () => {
  await clearQueuedTasksByPrefix(PREFIX)
  // Insert two QUEUED tasks with equal priorities so order is by created_at
  // ASC — i.e. `${PREFIX}a` comes first, `${PREFIX}b` second.
  const a = await seedQueuedTask(`${PREFIX}a`, 5)
  const b = await seedQueuedTask(`${PREFIX}b`, 5)
  seededIds = [a.taskId, b.taskId]
})

test.afterEach(async () => {
  await clearQueuedTasksByPrefix(PREFIX)
})

test('Keyboard reorder posts /api/v1/queue/reorder with flipped ids', async ({
  page,
}) => {
  await loginViaKeycloak(page, ENV)
  await page.goto(`${ENV.uiBaseUrl}/queue`)

  // Wait for our two seeded rows to be present.
  const gripA = page.getByRole('button', {
    name: `Drag to reorder task ${seededIds[0]}`,
  })
  const gripB = page.getByRole('button', {
    name: `Drag to reorder task ${seededIds[1]}`,
  })
  await expect(gripA).toBeVisible({ timeout: 10_000 })
  await expect(gripB).toBeVisible({ timeout: 10_000 })

  // Keyboard-driven drag via dnd-kit's KeyboardSensor:
  //   focus → Space (pickup) → ArrowDown (move) → Space (drop)
  // sortableKeyboardCoordinates translates ArrowDown into a position swap
  // with the next sortable item — moving seededIds[0] one slot down past
  // seededIds[1]. The order becomes [b, a].
  await gripA.focus()
  await page.keyboard.press('Space')
  // Small wait so dnd-kit's keyboard-coordinate computation re-reads the
  // node measurements after the pickup before we move. Without this, the
  // ArrowDown is processed while the drag overlay still has the source
  // position and the move resolves as "over self".
  await page.waitForTimeout(100)
  await page.keyboard.press('ArrowDown')
  await page.waitForTimeout(100)
  await page.keyboard.press('Space')

  // The "Save order" button only renders once localOrder diverges from the
  // server (`isDirty`). Hard assertion — no fallback.
  const saveBtn = page.getByRole('button', { name: /^Save order$/ })
  await expect(saveBtn).toBeVisible({ timeout: 5_000 })

  const [resp] = await Promise.all([
    page.waitForResponse(
      (r) =>
        r.url().includes('/api/v1/queue/reorder') &&
        r.request().method() === 'POST',
      { timeout: 10_000 },
    ),
    saveBtn.click(),
  ])
  expect(resp.ok()).toBeTruthy()

  // The POST body must carry both of our seeded ids in flipped order.
  const req = resp.request()
  const postedRaw = req.postData() ?? '{}'
  const posted = JSON.parse(postedRaw) as { taskIds: number[] }
  expect(Array.isArray(posted.taskIds)).toBeTruthy()
  // The posted list mirrors the page's full localOrder, which may include
  // queue rows we didn't seed. We only assert on OUR two ids:
  const ours = posted.taskIds.filter((id) => seededIds.includes(id))
  expect(ours.length).toBe(2)
  // The relative order of our two seeded ids must be flipped vs the baseline.
  expect(ours).toEqual([seededIds[1], seededIds[0]])

  // Success banner — uses role="status" with "Saved"/"Reordered" prefix.
  const banner = page.getByRole('status').filter({ hasText: /Reordered|Saved/ })
  await expect(banner).toBeVisible({ timeout: 5_000 })
})
