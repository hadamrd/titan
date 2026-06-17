/**
 * Adversarial tests for the /audit page + sidebar visibility (#517).
 *
 * Covered:
 *  - GET /audit returns 3 mixed-action rows → table renders all 3.
 *  - GET /audit narrowed by action filter → handler echoes back the filter,
 *    asserting the hook actually passes it on the query string.
 *  - GET /audit returns 403 → "Admin role required" rendered (NOT a blank
 *    table, NOT a generic "failed to load" — the operator must know it's a
 *    role gate so they ask for ADMIN, not file a bug).
 *  - Sidebar admin gate: roles=[] hides the entry; roles=["ADMIN"] shows it.
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
import { Route as AuditRoute } from '../routes/audit'
import type { AuditPage as AuditPageDto } from '../api/types'
import { setupFetchMock, resetFetchMock } from './msw-handlers'

// ── Fake UserManager (copied from auth.test.tsx — same minimal shape) ───────
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

// ── Seed audit page ─────────────────────────────────────────────────────────
const SEED_AUDIT_3: AuditPageDto = {
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
    {
      id: 2,
      occurredAt: '2026-05-23T09:05:00Z',
      actor: 'bob',
      action: 'BUILD_TRIGGER',
      targetType: 'BUILD',
      targetId: '99',
      detailsJson: '{"jobId":42}',
    },
    {
      id: 3,
      occurredAt: '2026-05-23T09:10:00Z',
      actor: 'svc-cd',
      action: 'PAT_CREATE',
      targetType: 'PAT',
      targetId: '7',
      detailsJson: '{"name":"ci-deploy"}',
    },
  ],
  total: 3,
  offset: 0,
  limit: 50,
}

interface MatchResult { status: number; body: unknown }
type Handler = (url: URL, method: string) => MatchResult | null

function auditOkHandler(): Handler {
  return (url, method) => {
    if (method !== 'GET') return null
    if (url.pathname !== '/api/v1/audit') return null
    return { status: 200, body: SEED_AUDIT_3 }
  }
}

/**
 * Handler that ECHOES the action query filter: items with a non-matching
 * action are stripped out. Asserts the hook actually forwards the filter
 * rather than always sending the unfiltered query.
 */
function auditFilteredHandler(): Handler {
  return (url, method) => {
    if (method !== 'GET') return null
    if (url.pathname !== '/api/v1/audit') return null
    const action = url.searchParams.get('action')
    if (action === null) {
      return { status: 200, body: SEED_AUDIT_3 }
    }
    const items = SEED_AUDIT_3.items.filter((e) => e.action === action)
    return {
      status: 200,
      body: { ...SEED_AUDIT_3, items, total: items.length },
    }
  }
}

function audit403Handler(): Handler {
  return (url, method) => {
    if (method !== 'GET') return null
    if (url.pathname !== '/api/v1/audit') return null
    return {
      status: 403,
      body: {
        type: 'about:blank',
        title: 'Forbidden',
        status: 403,
        detail: 'ADMIN role required',
        instance: null,
      },
    }
  }
}

// ── Route harness for the /audit page ───────────────────────────────────────
function mountAudit(handlers: Handler[]) {
  setupFetchMock(handlers)
  setAccessToken('test-token')

  const rootRoute = createRootRoute({
    component: () => <Outlet />,
  })
  // Re-mount the file-route under the harness root — forward both component AND
  // validateSearch so `useSearch({from: '/audit'})` resolves to the typed shape.
  const auditRoute = createRoute({
    getParentRoute: () => rootRoute,
    path: '/audit',
    component: AuditRoute.options.component,
    validateSearch: AuditRoute.options.validateSearch,
  })
  const router = createRouter({
    routeTree: rootRoute.addChildren([auditRoute]),
    history: createMemoryHistory({ initialEntries: ['/audit'] }),
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

// ── Sidebar harness (for role-visibility tests) ─────────────────────────────
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

// ── /audit page ─────────────────────────────────────────────────────────────

describe('AuditPage', () => {
  it('renders one table row per server event (3 of 3)', async () => {
    mountAudit([auditOkHandler()])
    // Wait for the page header
    await waitFor(() =>
      expect(screen.getByRole('heading', { name: /audit log/i })).toBeInTheDocument(),
    )
    // 3 chips, one per event
    await waitFor(() => {
      expect(screen.getByTestId('audit-action-1')).toHaveTextContent('JOB_CREATE')
      expect(screen.getByTestId('audit-action-2')).toHaveTextContent('BUILD_TRIGGER')
      expect(screen.getByTestId('audit-action-3')).toHaveTextContent('PAT_CREATE')
    })
  })

  it('renders human summaries for transition-cap events, with a fallback on missing details (#1074)', async () => {
    const seed: AuditPageDto = {
      items: [
        {
          id: 11,
          occurredAt: '2026-05-23T09:00:00Z',
          actor: 'titan-controller',
          action: 'TRANSITION_CAP_WARN',
          targetType: 'BUILD',
          targetId: '500',
          detailsJson: '{"buildId":500,"kind":"ADVANCE","count":201,"softCap":200}',
        },
        {
          id: 12,
          occurredAt: '2026-05-23T09:01:00Z',
          actor: 'titan-controller',
          action: 'TRANSITION_CAP_HALT',
          targetType: 'BUILD',
          targetId: '500',
          detailsJson: '{"buildId":500,"kind":"ADVANCE","count":1001,"hardCap":1000}',
        },
        {
          // Adversarial: malformed/empty details must degrade to a generic summary, never crash.
          id: 13,
          occurredAt: '2026-05-23T09:02:00Z',
          actor: 'titan-controller',
          action: 'TRANSITION_CAP_HALT',
          targetType: 'BUILD',
          targetId: '501',
          detailsJson: null,
        },
      ],
      total: 3,
      offset: 0,
      limit: 50,
    }
    mountAudit([
      (url, method) => {
        if (method !== 'GET' || url.pathname !== '/api/v1/audit') return null
        return { status: 200, body: seed }
      },
    ])

    await waitFor(() => {
      expect(screen.getByTestId('audit-row-toggle-11')).toHaveTextContent(
        'transition soft-cap warning — ADVANCE re-entered 201×',
      )
      expect(screen.getByTestId('audit-row-toggle-12')).toHaveTextContent(
        'halted — ADVANCE hit the transition spam guard (1001)',
      )
      // No kind/count → generic fallback, not a thrown render.
      expect(screen.getByTestId('audit-row-toggle-13')).toHaveTextContent(
        'halted by transition spam guard',
      )
    })
  })

  it('action-filter chip narrows the displayed rows (#727 — chips replace single-select)', async () => {
    mountAudit([auditFilteredHandler()])

    // First render is unfiltered: 3 rows.
    await waitFor(() => {
      expect(screen.getByTestId('audit-action-1')).toBeInTheDocument()
      expect(screen.getByTestId('audit-action-2')).toBeInTheDocument()
      expect(screen.getByTestId('audit-action-3')).toBeInTheDocument()
    })

    // Click the BUILD_TRIGGER chip — the handler echoes the filter, so only row 2 stays.
    const chip = screen.getByTestId('filter-action-BUILD_TRIGGER')
    fireEvent.click(chip)

    await waitFor(() => {
      expect(screen.queryByTestId('audit-action-1')).toBeNull()
      expect(screen.getByTestId('audit-action-2')).toBeInTheDocument()
      expect(screen.queryByTestId('audit-action-3')).toBeNull()
    })
  })

  it('on 403, renders "Admin role required" instead of a blank table', async () => {
    mountAudit([audit403Handler()])

    const banner = await screen.findByTestId('audit-error-403')
    expect(banner.textContent).toMatch(/admin role required/i)
    // And NOT the generic empty / table.
    expect(screen.queryByTestId('audit-empty')).toBeNull()
    expect(screen.queryByTestId('audit-table-body')).toBeNull()
  })
})

// ── Sidebar role-gating ─────────────────────────────────────────────────────

describe('Sidebar — Audit entry visibility', () => {
  it('hides "Audit" when the bearer has no roles', async () => {
    mountSidebar([])
    // Wait for sidebar to render any link
    await waitFor(() =>
      expect(screen.getByText('Overview')).toBeInTheDocument(),
    )
    expect(screen.queryByText('Audit')).toBeNull()
  })

  it('shows "Audit" when the bearer has the ADMIN role', async () => {
    mountSidebar(['ADMIN'])
    await waitFor(() => expect(screen.getByText('Audit')).toBeInTheDocument())
  })
})
