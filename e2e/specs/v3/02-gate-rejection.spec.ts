/**
 * 02-gate-rejection — reject-with-reason flow against the v3 rig.
 *
 * Pre-req: `task dev:titan` is up. Mirrors 01-gate-approval but exercises the
 * reject path (modal with optional reason → POST /reject → flow_node FAILED).
 */
import { test, expect } from '@playwright/test'
import { authEnv, loginViaKeycloak } from '../../fixtures/auth-v3'
import {
  cleanFlowNode,
  findRunningUiBuildId,
  readFlowNodeStatus,
  seedPendingGate,
} from '../../fixtures/seed-v3'

const ENV = authEnv()
const GATE_NODE_ID = 'gate.deploy-prod-reject'
const GATE_NAME = 'Deploy to production'
const REJECT_REASON = 'waiting for QA signoff'

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

test('reject flow flips gate flow_node to FAILED', async ({ page }) => {
  await loginViaKeycloak(page, ENV)
  await page.goto(`${ENV.uiBaseUrl}/builds/${buildId}`)

  const panel = page.locator('.gate-decision').first()
  await expect(panel).toBeVisible({ timeout: 10_000 })

  // Open the reject modal.
  await panel.getByRole('button', { name: new RegExp(`Reject gate ${GATE_NAME}`) }).click()

  // Modal — aria-labelledby="gate-reject-title", focus-trapped, with the
  // reason textarea.
  const modal = page.getByRole('dialog')
  await expect(modal).toBeVisible()
  await modal.locator('#gate-reject-reason').fill(REJECT_REASON)

  const [resp] = await Promise.all([
    page.waitForResponse(
      (r) =>
        r.url().includes(`/api/v1/builds/${buildId}/gates/`) &&
        r.url().endsWith('/reject') &&
        r.request().method() === 'POST',
      { timeout: 10_000 },
    ),
    modal.getByRole('button', { name: /reject deploy/i }).click(),
  ])
  expect(resp.ok()).toBeTruthy()

  // DB read-back — engine writes FAILED on rejection.
  await expect
    .poll(async () => readFlowNodeStatus(buildId, GATE_NODE_ID), { timeout: 10_000 })
    .toBe('FAILED')
})
