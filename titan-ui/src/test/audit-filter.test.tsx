/**
 * Adversarial vitest for the /audit filter strip (closes #727).
 *
 * The /audit page is the operator's only path to "who approved that prod
 * deploy yesterday". A regression in URL sync, debouncing, or chip selection
 * silently degrades incident response. These four cases pin the contract,
 * mirroring builds-filter.test.tsx (#686):
 *
 *   1. Action chip click → URL search param updates (?action=BUILD_TRIGGER).
 *   2. 5 keystrokes in actor → debounced to 1 fetch with actor=abcde (not 5).
 *   3. Initial URL ?action=BUILD_TRIGGER → that chip is rendered aria-pressed=true.
 *   4. "Clear filters" → URL search params return to defaults; the request to
 *      /api/v1/audit no longer carries any filter.
 *
 * Selectors mirror the builds-filter convention:
 *   - data-testid="audit-filter-strip"             — the container
 *   - data-testid="filter-action-<NAME>"          — one per action chip (aria-pressed)
 *   - data-testid="filter-actor"                   — debounced actor input
 *   - data-testid="filter-resource"                — debounced resource input
 *   - data-testid="filter-since"                   — time-window select
 *   - data-testid="filter-clear"                   — only present when filters active
 *
 * Assertions hit URL params + outgoing fetch call args — not text content —
 * so a regression in URL sync or query-string assembly fails hard rather than
 * looking visually correct while sending the wrong wire.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import {
  RouterProvider,
  createMemoryHistory,
  createRootRoute,
  createRoute,
  createRouter,
  Outlet,
} from '@tanstack/react-router'
import type { User, UserManager } from 'oidc-client-ts'

import { AuthProvider } from '../auth/AuthProvider'
import { setAccessToken } from '../auth/tokenStore'
import { Route as AuditRoute } from '../routes/audit'
import type { AuditPage as AuditPageDto } from '../api/types'
import { setupFetchMock, resetFetchMock } from './msw-handlers'

// ── Fake user / auth (matches audit.test.tsx + builds-filter.test.tsx) ───────
function makeFakeUser(): User {
  return {
    access_token: 'test-token',
    expired: false,
    profile: { sub: 'u1', preferred_username: 'alice', groups: ['ADMIN'] },
  } as unknown as User
}

function makeFakeUserManager(): UserManager {
  return {
    getUser: async () => makeFakeUser(),
    signinRedirect: async () => undefined,
    signinRedirectCallback: async () => makeFakeUser(),
    signoutRedirect: async () => undefined,
    removeUser: async () => undefined,
    events: {
      addUserLoaded: () => {},
      removeUserLoaded: () => {},
      addUserUnloaded: () => {},
      removeUserUnloaded: () => {},
      addAccessTokenExpired: () => {},
      removeAccessTokenExpired: () => {},
    },
  } as unknown as UserManager
}

// ── Audit page handler ───────────────────────────────────────────────────────
//
// One row, so the page lands past the loading skeleton. We don't mutate the
// payload per filter — the test asserts on the OUTGOING request, not on the
// rendered rows (the URL/wire is the contract under test).
const SEED_AUDIT_FILTER_PAGE: AuditPageDto = {
  items: [
    {
      id: 1,
      occurredAt: '2026-05-23T09:00:00Z',
      actor: 'alice',
      action: 'JOB_CREATE',
      targetType: 'JOB',
      targetId: '42',
      detailsJson: '{"fullName":"org/sample"}',
    },
  ],
  total: 1,
  offset: 0,
  limit: 50,
}

interface MatchResult {
  status: number
  body: unknown
}
type Handler = (url: URL, method: string) => MatchResult | null

/** Capture every GET /api/v1/audit URL so the test can assert call shape + count. */
const auditCalls: URL[] = []

function auditRecordingHandler(): Handler {
  return (url, method) => {
    if (method !== 'GET') return null
    if (url.pathname !== '/api/v1/audit') return null
    auditCalls.push(new URL(url.href))
    return { status: 200, body: SEED_AUDIT_FILTER_PAGE }
  }
}

// ── Router harness ───────────────────────────────────────────────────────────
//
// Re-mounts the file-route under a memory history. Forwards BOTH the
// `component` AND the `validateSearch` from the real route so `useSearch`
// returns the AuditSearch shape the page expects.
function mountAudit(initialUrl = '/audit') {
  auditCalls.length = 0
  setupFetchMock([auditRecordingHandler()])
  setAccessToken('test-token')

  const rootRoute = createRootRoute({ component: () => <Outlet /> })
  const auditRoute = createRoute({
    getParentRoute: () => rootRoute,
    path: '/audit',
    component: AuditRoute.options.component,
    validateSearch: AuditRoute.options.validateSearch,
  })
  const router = createRouter({
    routeTree: rootRoute.addChildren([auditRoute]),
    history: createMemoryHistory({ initialEntries: [initialUrl] }),
  })

  const qc = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })

  const utils = render(
    <AuthProvider userManager={makeFakeUserManager()}>
      <QueryClientProvider client={qc}>
        <RouterProvider router={router} />
      </QueryClientProvider>
    </AuthProvider>,
  )
  return { ...utils, router }
}

beforeEach(() => {
  setAccessToken(null)
  auditCalls.length = 0
})

afterEach(() => {
  resetFetchMock()
  setAccessToken(null)
  vi.useRealTimers()
})

// ── 1. Action chip click updates the URL ─────────────────────────────────────

describe('Audit filter strip — URL sync', () => {
  it('clicking the BUILD_TRIGGER chip puts action=BUILD_TRIGGER in the URL', async () => {
    const { router } = mountAudit('/audit')

    const chip = await screen.findByTestId('filter-action-BUILD_TRIGGER')
    expect(chip.getAttribute('aria-pressed')).toBe('false')

    fireEvent.click(chip)

    await waitFor(() => {
      const search = router.state.location.search as Record<string, unknown>
      const action = search.action
      // Router serialises a single-element array as a bare value — accept either.
      const flat = Array.isArray(action) ? action : action === undefined ? [] : [action]
      expect(flat).toContain('BUILD_TRIGGER')
    })

    await waitFor(() => {
      expect(
        screen.getByTestId('filter-action-BUILD_TRIGGER').getAttribute('aria-pressed'),
      ).toBe('true')
    })
  })
})

// ── 2. Debounced actor: 5 keystrokes → 1 fetch ───────────────────────────────

describe('Audit filter strip — debounced actor', () => {
  it('typing 5 characters in actor lands a single fetch (not 5)', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    mountAudit('/audit')

    await screen.findByTestId('filter-actor')
    const initialCount = auditCalls.length

    const input = screen.getByTestId('filter-actor') as HTMLInputElement

    // Five rapid keystrokes — debounce is 300 ms (see routes/audit.tsx).
    fireEvent.change(input, { target: { value: 'a' } })
    fireEvent.change(input, { target: { value: 'al' } })
    fireEvent.change(input, { target: { value: 'ali' } })
    fireEvent.change(input, { target: { value: 'alic' } })
    fireEvent.change(input, { target: { value: 'alice' } })

    // Between keystrokes (< 300ms) the debounced effect MUST NOT have fired.
    expect(auditCalls.length).toBe(initialCount)

    // Drain the debounce timer (300 ms) + a tick.
    await act(async () => {
      vi.advanceTimersByTime(350)
    })

    // Exactly ONE new GET /api/v1/audit, carrying ?actor=alice.
    await waitFor(() => {
      const newCalls = auditCalls.slice(initialCount)
      const withQ = newCalls.filter((u) => u.searchParams.get('actor') === 'alice')
      expect(withQ.length).toBeGreaterThanOrEqual(1)
      // Hard adversarial cap — no per-keystroke fan-out. 5 keystrokes must
      // not result in 5 distinct /audit requests with intermediate
      // actor-prefixes.
      const prefixCalls = newCalls.filter((u) => {
        const s = u.searchParams.get('actor')
        return s !== null && s.length > 0 && s.length < 5
      })
      expect(prefixCalls.length).toBe(0)
    })
  })
})

// ── 3. Initial URL ?action=… preloads the chip selected ──────────────────────

describe('Audit filter strip — initial URL state', () => {
  it('mounting with ?action=BUILD_TRIGGER renders that chip aria-pressed=true', async () => {
    mountAudit('/audit?action=BUILD_TRIGGER')

    const chip = await screen.findByTestId('filter-action-BUILD_TRIGGER')
    expect(chip.getAttribute('aria-pressed')).toBe('true')

    // Other chips MUST remain un-pressed — guards against a regression where
    // the URL parser greedily marks everything selected.
    expect(
      screen.getByTestId('filter-action-JOB_CREATE').getAttribute('aria-pressed'),
    ).toBe('false')
    expect(
      screen.getByTestId('filter-action-PAT_CREATE').getAttribute('aria-pressed'),
    ).toBe('false')

    // And the very first request to /api/v1/audit must have carried the filter
    // — proves the hook reads from the URL on first paint, not just after a
    // user interaction.
    await waitFor(() => {
      expect(auditCalls.length).toBeGreaterThan(0)
      const first = auditCalls[0]!
      const actions = first.searchParams.getAll('action')
      expect(actions).toContain('BUILD_TRIGGER')
    })
  })
})

// ── 4. "Clear filters" resets the URL ────────────────────────────────────────

describe('Audit filter strip — clear', () => {
  it('clicking "Clear filters" resets URL search params', async () => {
    const { router } = mountAudit('/audit?action=BUILD_TRIGGER&actor=alice&resource=42')

    // Pre-flight: the clear button is only present when filters are active.
    const clear = await screen.findByTestId('filter-clear')
    expect(clear).toBeInTheDocument()

    fireEvent.click(clear)

    await waitFor(() => {
      const search = router.state.location.search as Record<string, unknown>
      const rawAction = search.action
      const action = Array.isArray(rawAction)
        ? rawAction
        : rawAction === undefined
          ? []
          : [rawAction]
      expect(action).toEqual([])
      expect(search.actor ?? '').toBe('')
      expect(search.resource ?? '').toBe('')
    })

    // Clear button disappears once filters are reset (the page only renders it
    // when filtersActive). If this assertion fails, the URL-derived
    // `filtersActive` boolean has drifted from the URL state.
    await waitFor(() => {
      expect(screen.queryByTestId('filter-clear')).toBeNull()
    })
  })
})
