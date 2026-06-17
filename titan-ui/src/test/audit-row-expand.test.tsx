/**
 * Adversarial tests for /audit row-click expand (#614).
 *
 * Three invariants:
 *  1. Click row 1 → its details JSON is visible.
 *  2. Click row 1 again → it collapses.
 *  3. Click row 1 then row 2 → row 1 collapses, row 2 expands
 *     (the only-one-open invariant — a per-row useState would let both
 *     stay open, which silently regresses the spec).
 */
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import {
  Outlet,
  RouterProvider,
  createMemoryHistory,
  createRootRoute,
  createRoute,
  createRouter,
} from '@tanstack/react-router'
import type { User, UserManager } from 'oidc-client-ts'

import { AuthProvider } from '../auth/AuthProvider'
import { setAccessToken } from '../auth/tokenStore'
import { Route as AuditRoute } from '../routes/audit'
import type { AuditPage as AuditPageDto } from '../api/types'
import { resetFetchMock, setupFetchMock } from './msw-handlers'

function makeFakeUser(roles: string[] = ['ADMIN']): User {
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

const SEED: AuditPageDto = {
  items: [
    {
      id: 1,
      occurredAt: '2026-05-23T09:00:00Z',
      actor: 'alice',
      action: 'JOB_CREATE',
      targetType: 'JOB',
      targetId: '42',
      detailsJson: '{"foo":"bar"}',
    },
    {
      id: 2,
      occurredAt: '2026-05-23T09:05:00Z',
      actor: 'bob',
      action: 'BUILD_TRIGGER',
      targetType: 'BUILD',
      targetId: '99',
      detailsJson: '{"foo":"bar"}',
    },
    {
      id: 3,
      occurredAt: '2026-05-23T09:10:00Z',
      actor: 'svc-cd',
      action: 'PAT_CREATE',
      targetType: 'PAT',
      targetId: '7',
      detailsJson: '{"foo":"bar"}',
    },
  ],
  total: 3,
  offset: 0,
  limit: 50,
}

interface MatchResult { status: number; body: unknown }
type Handler = (url: URL, method: string) => MatchResult | null

function auditHandler(body: unknown = SEED): Handler {
  return (url, method) => {
    if (method !== 'GET') return null
    if (url.pathname !== '/api/v1/audit') return null
    return { status: 200, body }
  }
}

function mount(body: unknown = SEED) {
  setupFetchMock([auditHandler(body)])
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
    history: createMemoryHistory({ initialEntries: ['/audit'] }),
  })
  const qc = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })

  return render(
    <AuthProvider userManager={makeFakeUserManager(makeFakeUser())}>
      <QueryClientProvider client={qc}>
        <RouterProvider router={router} />
      </QueryClientProvider>
    </AuthProvider>,
  )
}

beforeEach(() => {
  setAccessToken(null)
})

afterEach(() => {
  resetFetchMock()
  setAccessToken(null)
})

describe('Audit row expand (#614)', () => {
  // Regression: Quarkus @JsonInclude(NON_NULL) strips a null detailsJson, so it
  // arrives as `undefined`. The old `detailsJson !== null` guard let undefined
  // through → `.trim()` threw "Cannot read properties of undefined" and crashed
  // the whole /audit page. A row with no details must render + collapse cleanly.
  it('renders a row whose detailsJson is absent (undefined) without crashing', async () => {
    const bodyNoDetails = {
      items: [
        {
          id: 9,
          occurredAt: '2026-05-23T09:00:00Z',
          actor: 'svc-cd',
          action: 'JOB_CREATE',
          targetType: 'JOB',
          targetId: '6',
          // detailsJson intentionally OMITTED → arrives as undefined
        },
      ],
      total: 1,
      offset: 0,
      limit: 50,
    }
    mount(bodyNoDetails)
    const row = await screen.findByTestId('audit-row-9')
    expect(row).toBeInTheDocument()
    fireEvent.click(row)
    const details = await screen.findByTestId('audit-row-details-9')
    expect(details.textContent?.toLowerCase()).toContain('no details')
  })

  it('click row → details JSON visible; click again → collapsed', async () => {
    mount()
    const row1 = await screen.findByTestId('audit-row-1')

    expect(screen.queryByTestId('audit-row-details-1')).toBeNull()

    fireEvent.click(row1)
    const details1 = await screen.findByTestId('audit-row-details-1')
    expect(details1.textContent).toContain('"foo"')
    expect(details1.textContent).toContain('"bar"')

    fireEvent.click(row1)
    await waitFor(() =>
      expect(screen.queryByTestId('audit-row-details-1')).toBeNull(),
    )
  })

  it('clicking row 2 collapses row 1 (only-one-open invariant)', async () => {
    mount()
    const row1 = await screen.findByTestId('audit-row-1')
    const row2 = await screen.findByTestId('audit-row-2')

    fireEvent.click(row1)
    await screen.findByTestId('audit-row-details-1')

    fireEvent.click(row2)
    await screen.findByTestId('audit-row-details-2')
    expect(screen.queryByTestId('audit-row-details-1')).toBeNull()
  })
})
