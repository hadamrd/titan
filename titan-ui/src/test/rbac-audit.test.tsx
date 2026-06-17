/**
 * Adversarial tests for the /rbac-audit page + sidebar visibility (#1167).
 *
 * Covered:
 *  - GET /rbac-audit returns mixed ALLOW/DENY rows → table renders each with a
 *    visually-distinct Verdict badge.
 *  - Verdict filter chip narrows the rows → handler echoes the verdict, proving
 *    the hook forwards ?verdict= on the query string.
 *  - GET /rbac-audit returns 403 → "Admin / READ_AUDIT role required" rendered
 *    (NOT a blank table, NOT the generic error — the operator must know it's a
 *    role gate).
 *  - Two DISTINCT empty states: system-quiet (no filters) vs filter-too-narrow
 *    (after toggling a verdict chip).
 *  - Anonymous deny row (null actor + null effectiveRole) renders without an
 *    NPE/blank-crash — shows "(anonymous)" and "—".
 *  - Sidebar gate: roles=[] hides the entry; roles=["READ_AUDIT"] and
 *    roles=["ADMIN"] both show it (matches the endpoint's @RolesAllowed).
 */
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
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
import { Sidebar } from '../components/Sidebar'
import { Route as RbacAuditRoute } from '../routes/rbac-audit'
import type { RbacAuditPage as RbacAuditPageDto } from '../api/types'
import { setupFetchMock, resetFetchMock } from './msw-handlers'

// ── Fake UserManager (copied from audit.test.tsx — same minimal shape) ──────
function makeFakeUser(roles: string[] = []): User {
  return {
    access_token: 'test-token',
    expired: false,
    profile: { sub: 'u1', preferred_username: 'alice', groups: roles },
  } as unknown as User
}

function makeFakeUserManager(initialUser: User | null): UserManager {
  return {
    getUser: async () => initialUser,
    signinRedirect: async () => undefined,
    signinRedirectCallback: async () => initialUser ?? makeFakeUser(),
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

// ── Seed rbac-audit page ────────────────────────────────────────────────────
const SEED: RbacAuditPageDto = {
  items: [
    {
      id: 1,
      occurredAt: '2026-05-23T09:00:00Z',
      actor: 'alice',
      endpoint: 'JobsApi.list',
      scopeKind: 'ORG',
      scopeId: 'acme',
      requiredRole: 'READ_JOB',
      effectiveRole: 'READ_JOB',
      verdict: 'ALLOW',
    },
    {
      id: 2,
      occurredAt: '2026-05-23T09:05:00Z',
      actor: 'alice',
      endpoint: 'JobsApi.create',
      scopeKind: 'ORG',
      scopeId: 'acme',
      requiredRole: 'MAINTAINER',
      effectiveRole: 'DEVELOPER',
      verdict: 'DENY',
    },
    // Anonymous deny — null actor + null effectiveRole. Must render, not crash.
    {
      id: 3,
      occurredAt: '2026-05-23T09:10:00Z',
      actor: null,
      endpoint: 'BuildDetailApi.cancel',
      scopeKind: 'REPO',
      scopeId: 'acme/web',
      requiredRole: 'ABORT_BUILD',
      effectiveRole: null,
      verdict: 'DENY',
    },
  ],
  total: 3,
  offset: 0,
  limit: 50,
}

interface MatchResult {
  status: number
  body: unknown
}
type Handler = (url: URL, method: string) => MatchResult | null

function okHandler(): Handler {
  return (url, method) => {
    if (method !== 'GET') return null
    if (url.pathname !== '/api/v1/rbac-audit') return null
    return { status: 200, body: SEED }
  }
}

/** Echoes the verdict filter: rows whose verdict differs are stripped. */
function filteredHandler(): Handler {
  return (url, method) => {
    if (method !== 'GET') return null
    if (url.pathname !== '/api/v1/rbac-audit') return null
    const verdicts = url.searchParams.getAll('verdict')
    if (verdicts.length === 0) {
      return { status: 200, body: SEED }
    }
    const items = SEED.items.filter((e) => verdicts.includes(e.verdict))
    return { status: 200, body: { ...SEED, items, total: items.length } }
  }
}

function emptyHandler(): Handler {
  return (url, method) => {
    if (method !== 'GET') return null
    if (url.pathname !== '/api/v1/rbac-audit') return null
    return { status: 200, body: { items: [], total: 0, offset: 0, limit: 50 } }
  }
}

function forbiddenHandler(): Handler {
  return (url, method) => {
    if (method !== 'GET') return null
    if (url.pathname !== '/api/v1/rbac-audit') return null
    return {
      status: 403,
      body: {
        type: 'about:blank',
        title: 'Forbidden',
        status: 403,
        detail: 'READ_AUDIT or ADMIN role required',
        instance: null,
      },
    }
  }
}

// ── Route harness for the /rbac-audit page ──────────────────────────────────
function mountPage(handlers: Handler[]) {
  setupFetchMock(handlers)
  setAccessToken('test-token')

  const rootRoute = createRootRoute({ component: () => <Outlet /> })
  const route = createRoute({
    getParentRoute: () => rootRoute,
    path: '/rbac-audit',
    component: RbacAuditRoute.options.component,
    validateSearch: RbacAuditRoute.options.validateSearch,
  })
  const router = createRouter({
    routeTree: rootRoute.addChildren([route]),
    history: createMemoryHistory({ initialEntries: ['/rbac-audit'] }),
  })
  const qc = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return render(
    <AuthProvider userManager={makeFakeUserManager(makeFakeUser(['ADMIN']))}>
      <QueryClientProvider client={qc}>
        <RouterProvider router={router} />
      </QueryClientProvider>
    </AuthProvider>,
  )
}

// ── Sidebar harness ─────────────────────────────────────────────────────────
function mountSidebar(roles: string[]) {
  const rootRoute = createRootRoute({
    component: () => (
      <>
        <Sidebar />
        <Outlet />
      </>
    ),
  })
  const indexRoute = createRoute({
    getParentRoute: () => rootRoute,
    path: '/',
    component: () => null,
  })
  const router = createRouter({
    routeTree: rootRoute.addChildren([indexRoute]),
    history: createMemoryHistory({ initialEntries: ['/'] }),
  })
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const wrapper = ({ children }: { children: ReactNode }) => (
    <AuthProvider userManager={makeFakeUserManager(makeFakeUser(roles))}>
      <QueryClientProvider client={qc}>{children}</QueryClientProvider>
    </AuthProvider>
  )
  return render(<RouterProvider router={router} />, { wrapper })
}

beforeEach(() => {
  setAccessToken(null)
})
afterEach(() => {
  resetFetchMock()
  setAccessToken(null)
})

// ── /rbac-audit page ────────────────────────────────────────────────────────

describe('RbacAuditPage', () => {
  it('renders one row per event with a distinct Verdict badge', async () => {
    mountPage([okHandler()])
    await waitFor(() =>
      expect(screen.getByRole('heading', { name: /rbac audit/i })).toBeInTheDocument(),
    )
    await waitFor(() => {
      expect(screen.getByTestId('rbac-audit-verdict-1')).toHaveTextContent('ALLOW')
      expect(screen.getByTestId('rbac-audit-verdict-2')).toHaveTextContent('DENY')
      expect(screen.getByTestId('rbac-audit-verdict-3')).toHaveTextContent('DENY')
    })
    // ALLOW and DENY carry different data-verdict markers (visually distinct).
    expect(screen.getByTestId('rbac-audit-verdict-1')).toHaveAttribute('data-verdict', 'ALLOW')
    expect(screen.getByTestId('rbac-audit-verdict-2')).toHaveAttribute('data-verdict', 'DENY')
  })

  it('verdict chip narrows displayed rows to DENY (hook forwards ?verdict=)', async () => {
    mountPage([filteredHandler()])
    await waitFor(() => {
      expect(screen.getByTestId('rbac-audit-verdict-1')).toBeInTheDocument()
      expect(screen.getByTestId('rbac-audit-verdict-2')).toBeInTheDocument()
    })
    fireEvent.click(screen.getByTestId('filter-verdict-DENY'))
    await waitFor(() => {
      expect(screen.queryByTestId('rbac-audit-verdict-1')).toBeNull() // ALLOW row gone
      expect(screen.getByTestId('rbac-audit-verdict-2')).toBeInTheDocument()
      expect(screen.getByTestId('rbac-audit-verdict-3')).toBeInTheDocument()
    })
  })

  it('renders an anonymous deny row (null actor + null role) without crashing', async () => {
    mountPage([okHandler()])
    const row = await screen.findByTestId('rbac-audit-row-3')
    expect(row).toBeInTheDocument()
    // Null actor renders the explicit "(anonymous)" marker, not a blank/NPE.
    expect(screen.getByTestId('rbac-audit-actor-3')).toHaveTextContent(/anonymous/i)
  })

  it('on 403, shows the role-gate message, not a blank table or generic error', async () => {
    mountPage([forbiddenHandler()])
    const banner = await screen.findByTestId('rbac-audit-error-403')
    expect(banner.textContent).toMatch(/read_audit|admin/i)
    expect(screen.queryByTestId('rbac-audit-empty')).toBeNull()
    expect(screen.queryByTestId('rbac-audit-table-body')).toBeNull()
  })

  it('distinguishes the system-quiet empty from the filter-too-narrow empty', async () => {
    mountPage([emptyHandler()])
    // No filters active initially (default window) → system-quiet copy.
    const quiet = await screen.findByTestId('rbac-audit-empty')
    expect(quiet.textContent).toMatch(/no rbac checks recorded yet/i)

    // Toggle a verdict chip → filters active → filter-too-narrow copy.
    fireEvent.click(screen.getByTestId('filter-verdict-DENY'))
    await waitFor(() => {
      expect(screen.getByTestId('rbac-audit-empty').textContent).toMatch(
        /no rbac events match these filters/i,
      )
    })
  })
})

// ── Sidebar role-gating ─────────────────────────────────────────────────────

describe('Sidebar — RBAC Audit entry visibility', () => {
  it('hides "RBAC Audit" when the bearer has no roles', async () => {
    mountSidebar([])
    await waitFor(() => expect(screen.getByText('Overview')).toBeInTheDocument())
    expect(screen.queryByText('RBAC Audit')).toBeNull()
  })

  it('shows "RBAC Audit" for a READ_AUDIT-only bearer', async () => {
    mountSidebar(['READ_AUDIT'])
    await waitFor(() => expect(screen.getByText('RBAC Audit')).toBeInTheDocument())
  })

  it('shows "RBAC Audit" for an ADMIN bearer', async () => {
    mountSidebar(['ADMIN'])
    await waitFor(() => expect(screen.getByText('RBAC Audit')).toBeInTheDocument())
  })
})
