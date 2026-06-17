/**
 * Adversarial tests for the NotificationsBell (#573).
 *
 * Covered:
 *  - Closed by default; click opens the dropdown with one row per server event.
 *  - Per-action icon dispatch: 3 events with mixed actions render 3 distinct
 *    icon elements keyed by AuditAction (assert the test-id encodes action).
 *  - 403 from /audit: bell stays inert — click does NOT open the dropdown.
 *  - Unread dot visibility: when localStorage lastSeenId < latest event id,
 *    the bell carries data-unread="true"; after open, it flips to "false".
 *  - "View all" link → href="/audit".
 */
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
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
import { NotificationsBell } from '../components/NotificationsBell'
import type { AuditPage } from '../api/types'
import { setupFetchMock, resetFetchMock } from './msw-handlers'

// ── Fake UserManager (shape lifted from audit.test.tsx) ─────────────────────
function makeFakeUser(): User {
  return {
    access_token: 'test-token',
    expired: false,
    profile: { sub: 'u1', preferred_username: 'alice', groups: ['ADMIN'] },
  } as unknown as User
}

function makeFakeUserManager(): UserManager {
  const u = makeFakeUser()
  return {
    getUser: async () => u,
    signinRedirect: async () => undefined,
    signinRedirectCallback: async () => u,
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

const SEED_PAGE: AuditPage = {
  items: [
    {
      id: 30,
      occurredAt: new Date(Date.now() - 60_000).toISOString(),
      actor: 'alice',
      action: 'JOB_CREATE',
      targetType: 'JOB',
      targetId: '42',
      detailsJson: null,
    },
    {
      id: 29,
      occurredAt: new Date(Date.now() - 5 * 60_000).toISOString(),
      actor: 'bob',
      action: 'BUILD_TRIGGER',
      targetType: 'BUILD',
      targetId: '99',
      detailsJson: null,
    },
    {
      id: 28,
      occurredAt: new Date(Date.now() - 10 * 60_000).toISOString(),
      actor: 'svc-cd',
      action: 'PAT_CREATE',
      targetType: 'PAT',
      targetId: '7',
      detailsJson: null,
    },
  ],
  total: 3,
  offset: 0,
  limit: 10,
}

interface MatchResult {
  status: number
  body: unknown
}
type Handler = (url: URL, method: string) => MatchResult | null

function okHandler(): Handler {
  return (url, method) => {
    if (method !== 'GET') return null
    if (url.pathname !== '/api/v1/audit') return null
    return { status: 200, body: SEED_PAGE }
  }
}

function forbiddenHandler(): Handler {
  return (url, method) => {
    if (method !== 'GET') return null
    if (url.pathname !== '/api/v1/audit') return null
    return {
      status: 403,
      body: {
        type: 'about:blank',
        title: 'Forbidden',
        status: 403,
        detail: 'READ_AUDIT or ADMIN required',
        instance: null,
      },
    }
  }
}

function mountBell(handlers: Handler[]) {
  setupFetchMock(handlers)
  setAccessToken('test-token')

  const rootRoute = createRootRoute({
    component: () => (
      <>
        <NotificationsBell />
        <Outlet />
      </>
    ),
  })
  const indexRoute = createRoute({
    getParentRoute: () => rootRoute,
    path: '/',
    component: () => null,
  })
  const auditRoute = createRoute({
    getParentRoute: () => rootRoute,
    path: '/audit',
    component: () => <div data-testid="audit-route-marker">audit</div>,
  })
  const router = createRouter({
    routeTree: rootRoute.addChildren([indexRoute, auditRoute]),
    history: createMemoryHistory({ initialEntries: ['/'] }),
  })

  const qc = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })

  return render(
    <AuthProvider userManager={makeFakeUserManager()}>
      <QueryClientProvider client={qc}>
        <RouterProvider router={router} />
      </QueryClientProvider>
    </AuthProvider>,
  )
}

beforeEach(() => {
  localStorage.clear()
  setAccessToken(null)
})

afterEach(() => {
  resetFetchMock()
  setAccessToken(null)
  localStorage.clear()
})

describe('NotificationsBell', () => {
  it('opens the dropdown on click and renders one row per server event', async () => {
    mountBell([okHandler()])

    // Wait until the bell's query has resolved (mounted bell exists).
    const bell = await screen.findByTestId('notifications-bell')
    // Wait until data arrives — the data-unread attribute flips to "true".
    await waitFor(() => expect(bell).toHaveAttribute('data-unread', 'true'))

    fireEvent.click(bell)
    const dropdown = await screen.findByTestId('notifications-dropdown')
    expect(dropdown).toBeInTheDocument()

    expect(screen.getByTestId('notification-row-30')).toBeInTheDocument()
    expect(screen.getByTestId('notification-row-29')).toBeInTheDocument()
    expect(screen.getByTestId('notification-row-28')).toBeInTheDocument()
  })

  it('dispatches a distinct icon per AuditAction (3 actions = 3 icon test-ids)', async () => {
    mountBell([okHandler()])

    const bell = await screen.findByTestId('notifications-bell')
    await waitFor(() => expect(bell).toHaveAttribute('data-unread', 'true'))
    fireEvent.click(bell)

    await screen.findByTestId('notifications-dropdown')
    // The icon test-id encodes the AuditAction code — proves the per-action
    // switch fired (not a generic fallback).
    expect(screen.getByTestId('notification-icon-JOB_CREATE-30')).toBeInTheDocument()
    expect(screen.getByTestId('notification-icon-BUILD_TRIGGER-29')).toBeInTheDocument()
    expect(screen.getByTestId('notification-icon-PAT_CREATE-28')).toBeInTheDocument()
  })

  it('stays inert on 403 — clicking the bell does NOT open the dropdown', async () => {
    mountBell([forbiddenHandler()])

    const bell = await screen.findByTestId('notifications-bell')
    await waitFor(() =>
      expect(bell).toHaveAttribute('data-forbidden', 'true'),
    )
    // No unread dot on forbidden either.
    expect(bell).toHaveAttribute('data-unread', 'false')

    fireEvent.click(bell)
    // Nothing pops.
    expect(screen.queryByTestId('notifications-dropdown')).toBeNull()
  })

  it('shows the unread dot when latest id > lastSeenId, clears it on open', async () => {
    // Stale lastSeenId = 20; newest event = id 30 → dot shows.
    localStorage.setItem('titan.notifications.lastSeenId.v1', '20')
    mountBell([okHandler()])

    const bell = await screen.findByTestId('notifications-bell')
    await waitFor(() => expect(bell).toHaveAttribute('data-unread', 'true'))

    fireEvent.click(bell)
    await screen.findByTestId('notifications-dropdown')

    // After open, lastSeenId updates to 30 and the dot clears.
    await waitFor(() => expect(bell).toHaveAttribute('data-unread', 'false'))
    expect(localStorage.getItem('titan.notifications.lastSeenId.v1')).toBe('30')
  })

  it('"View all" link points at /audit', async () => {
    mountBell([okHandler()])

    const bell = await screen.findByTestId('notifications-bell')
    await waitFor(() => expect(bell).toHaveAttribute('data-unread', 'true'))
    fireEvent.click(bell)

    const viewAll = await screen.findByTestId('notifications-view-all')
    // TanStack Link renders an <a href>.
    expect(viewAll.tagName.toLowerCase()).toBe('a')
    // /audit route requires search params; Link encodes the defaults.
    expect(viewAll.getAttribute('href')).toMatch(/^\/audit(\?|$)/)
  })
})
