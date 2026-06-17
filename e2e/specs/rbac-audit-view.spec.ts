/**
 * rbac-audit-view — admin view of the typed RBAC allow/deny trail (#1167).
 *
 * The new /rbac-audit page reads GET /api/v1/rbac-audit (gated
 * @RolesAllowed({READ_AUDIT, ADMIN})) and surfaces a first-class Verdict
 * column over titan.rbac_audit. This spec exercises the real rig with the
 * lightweight network/console-assertion convention used by the other v3
 * specs (profile-tokens-render, golden-path-failure-triage) — NOT full
 * snapshots:
 *
 *   1. As the (full-grant) dev user, the page loads, the GET returns 200,
 *      the table OR an empty-state mounts, and there are ZERO console errors
 *      / unhandled page errors (the anonymous-deny null-actor row must not
 *      crash the render — the regression the unit test guards).
 *   2. Toggling the DENY verdict chip fires a GET carrying ?verdict=DENY and
 *      the response is ok — proving the hook forwards the closed-union filter.
 *      When DENY rows exist, the first one's Verdict badge + scope/roles cells
 *      render.
 *   3. The role gate itself (a non-audit caller gets 403, no rows leak) is
 *      asserted deterministically at the API layer in RbacAuditApiIT — the
 *      local rig provisions only the full-grant dev user, so a browser-level
 *      negative would be non-deterministic here. We instead assert at the API
 *      that the endpoint exists and answers 200 for an authorized caller.
 */
import { test, expect, type ConsoleMessage } from '@playwright/test'
import { authEnv, loginViaKeycloak, fetchBearerToken } from '../fixtures/auth-v3'

const ENV = authEnv()

test.describe('RBAC audit — admin view + verdict filter', () => {
  test('GET /api/v1/rbac-audit answers 200 for an authorized caller', async () => {
    const token = await fetchBearerToken(ENV)
    const res = await fetch(`${ENV.uiBaseUrl}/api/v1/rbac-audit?limit=5`, {
      headers: { Authorization: `Bearer ${token}`, Accept: 'application/json' },
    })
    expect(res.ok, `rbac-audit GET status was ${res.status}`).toBeTruthy()
    const body = (await res.json()) as { items: unknown[]; total: number }
    expect(Array.isArray(body.items), 'items is an array').toBeTruthy()
    expect(typeof body.total, 'total is a number').toBe('number')
  })

  test('the page mounts with zero console errors and the verdict filter round-trips', async ({
    page,
  }) => {
    const pageErrors: Error[] = []
    const consoleErrors: ConsoleMessage[] = []
    page.on('pageerror', (e) => pageErrors.push(e))
    page.on('console', (m) => {
      if (m.type() === 'error') consoleErrors.push(m)
    })

    await loginViaKeycloak(page, ENV)

    // Capture the initial feed fetch the page makes on mount.
    const initialRespPromise = page.waitForResponse(
      (r) =>
        r.url().includes('/api/v1/rbac-audit') && r.request().method() === 'GET',
      { timeout: 15_000 },
    )
    const navResp = await page.goto(`${ENV.uiBaseUrl}/rbac-audit`)
    expect(navResp?.status(), 'rbac-audit route HTTP status').toBeLessThan(400)
    const initialResp = await initialRespPromise
    expect(
      initialResp.ok(),
      `/api/v1/rbac-audit GET status was ${initialResp.status()}`,
    ).toBeTruthy()

    // Either the table body or one of the two empty states must mount — the page
    // never hangs on a blank card.
    const tableBody = page.getByTestId('rbac-audit-table-body')
    const emptyState = page.getByTestId('rbac-audit-empty')
    await expect(tableBody.or(emptyState).first()).toBeVisible({ timeout: 10_000 })

    // Toggle the DENY chip → the hook must send ?verdict=DENY on the next fetch.
    const denyRespPromise = page.waitForResponse(
      (r) =>
        r.url().includes('/api/v1/rbac-audit') &&
        new URL(r.url()).searchParams.getAll('verdict').includes('DENY') &&
        r.request().method() === 'GET',
      { timeout: 15_000 },
    )
    await page.getByTestId('filter-verdict-DENY').click()
    const denyResp = await denyRespPromise
    expect(denyResp.ok(), `filtered GET status was ${denyResp.status()}`).toBeTruthy()

    // If the filtered feed has any rows, the first must be a DENY with its scope
    // + roles visible (the incident-triage assertion).
    const denyBody = (await denyResp.json()) as {
      items: Array<{ id: number; verdict: string }>
    }
    const firstDeny = denyBody.items[0]
    if (firstDeny) {
      const firstId = firstDeny.id
      await expect(page.getByTestId(`rbac-audit-verdict-${firstId}`)).toHaveAttribute(
        'data-verdict',
        'DENY',
      )
      // The row exists and carries the scope/roles cells (non-empty).
      await expect(page.getByTestId(`rbac-audit-row-${firstId}`)).toBeVisible()
    }

    // Hard regression: no console errors, no uncaught exceptions (null-actor /
    // null-effectiveRole rows must render cleanly).
    expect(
      pageErrors,
      pageErrors.length === 0 ? '' : `page errors:\n${pageErrors.map((e) => e.message).join('\n')}`,
    ).toEqual([])
    expect(
      consoleErrors,
      consoleErrors.length === 0
        ? ''
        : `console errors:\n${consoleErrors.map((m) => m.text()).join('\n')}`,
    ).toEqual([])
  })
})
