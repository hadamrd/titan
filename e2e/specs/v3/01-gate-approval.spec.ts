/**
 * 01-gate-approval — end-to-end approval flow against the v3 rig.
 *
 * Pre-req: `task dev:titan` is up (with seed-data.sh having run).
 *
 * Setup: insert a paused-gate flow_node (status=RUNNING) onto the seeded
 * RUNNING titan-ui build. The UI's /builds/$id route reads the gate via the
 * v3 GatesApi (GET /api/v1/builds/{id}/gates) — see PR #299.
 *
 * Assertions:
 *   - GateDecision panel renders (gate-decision class, gate name, node id,
 *     "Approve & resume" button, approver chips).
 *   - Clicking "Approve & resume" closes the panel.
 *   - The flow_node status flips to SUCCESS via the DB read-back.
 */
import { test, expect } from '@playwright/test'
import { authEnv, fetchBearerToken, loginViaKeycloak } from '../../fixtures/auth-v3'
import {
  cleanFlowNode,
  findRunningUiBuildId,
  readFlowNodeStatus,
  seedPendingGate,
} from '../../fixtures/seed-v3'
import { TitanApiV3 } from '../../fixtures/titan-api-v3'

const ENV = authEnv()
const GATE_NODE_ID = 'gate.deploy-prod-approve'
const GATE_NAME = 'Deploy to production'

let buildId: number

test.beforeAll(async () => {
  buildId = await findRunningUiBuildId()
})

test.beforeEach(async () => {
  await seedPendingGate(buildId, GATE_NODE_ID, GATE_NAME, ['dev'])
})

test.afterEach(async () => {
  await cleanFlowNode(buildId, GATE_NODE_ID)
})

test('approve flow flips gate flow_node to SUCCESS', async ({ page }) => {
  await loginViaKeycloak(page, ENV)
  await page.goto(`${ENV.uiBaseUrl}/builds/${buildId}`)

  // Wait for the gate panel — keyed off the `gate-decision` class in
  // titan-ui/src/components/GateDecision.tsx.
  const panel = page.locator('.gate-decision').first()
  await expect(panel).toBeVisible({ timeout: 10_000 })
  await expect(panel.locator('.gate-name')).toHaveText(GATE_NAME)
  await expect(panel.locator('.gate-meta')).toContainText(GATE_NODE_ID)
  // The dev user is the seeded approver — at least one chip rendered.
  await expect(panel.locator('.gate-approver')).toHaveCount(1)

  // Approve & resume. The button's accessible name is set explicitly on the
  // component.
  const approveBtn = panel.getByRole('button', { name: new RegExp(`Approve gate ${GATE_NAME}`) })
  await expect(approveBtn).toBeEnabled()

  // Wait for the POST /approve response in parallel with the click so we
  // don't race the panel's React state update.
  const [resp] = await Promise.all([
    page.waitForResponse(
      (r) => r.url().includes(`/api/v1/builds/${buildId}/gates/`) && r.request().method() === 'POST',
      { timeout: 10_000 },
    ),
    approveBtn.click(),
  ])
  expect(resp.ok()).toBeTruthy()

  // DB read-back: the gate flow_node must be SUCCESS now.
  await expect
    .poll(async () => readFlowNodeStatus(buildId, GATE_NODE_ID), { timeout: 10_000 })
    .toBe('SUCCESS')

  // API read-back: no longer in the pending-gates list.
  const token = await fetchBearerToken(ENV)
  const api = new TitanApiV3(token)
  const stillPending = (await api.listPendingGates(buildId)).find((g) => g.nodeId === GATE_NODE_ID)
  expect(stillPending).toBeUndefined()
})
