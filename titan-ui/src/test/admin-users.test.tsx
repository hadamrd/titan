/**
 * Adversarial tests for /admin/users + sidebar visibility (#609).
 *
 * Covered:
 *  - GET /admin/users returns 3 mixed-role users → table renders 3 rows + a
 *    role chip per realmRole entry. Defends against the "iterate first user
 *    only" / "collapse roles to a single chip" regressions.
 *  - GET /admin/users returns 403 → "Admin role required" placeholder, NOT
 *    a blank table. The placeholder must also link out to Keycloak so a
 *    locked-out operator has a path forward.
 *  - Sidebar admin gate: roles=[] hides "Admin · Users"; roles=["ADMIN"]
 *    shows it. Defends against the always-on entry that would leak the page
 *    to USER bearers (defence in depth — server enforces 403 already).
 */
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
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
import { Route as AdminUsersRoute } from '../routes/users'
import type { UserDto } from '../api/types'
import { setupFetchMock, resetFetchMock } from './msw-handlers'

// ── Fake UserManager (same shape as audit.test.tsx) ─────────────────────────
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

// ── Seed users (mixed roles across categories) ──────────────────────────────
const SEED_USERS: UserDto[] = [
  {
    username: 'alice',
    email: 'alice@titan-ci.local',
    displayName: 'Alice Admin',
    realmRoles: ['ADMIN', 'USER', 'offline_access'],
    titanRoles: [],
  },
  {
    username: 'bob',
    email: 'bob@titan-ci.local',
    displayName: 'Bob Builder',
    realmRoles: ['DEVELOPER', 'USER'],
    titanRoles: [],
  },
  {
    // No ladder role: exercises the "none" badge floor.
    username: 'carol',
    email: null,
    displayName: null,
    realmRoles: ['default-roles-titan-dev', 'uma_authorization'],
    titanRoles: [],
  },
]

interface MatchResult { status: number; body: unknown }
type Handler = (
  url: URL,
  method: string,
  body?: string,
) => MatchResult | null

function adminUsersOkHandler(): Handler {
  return (url, method) => {
    if (method !== 'GET') return null
    if (url.pathname !== '/api/v1/admin/users') return null
    return { status: 200, body: SEED_USERS }
  }
}

function adminUsers403Handler(): Handler {
  return (url, method) => {
    if (method !== 'GET') return null
    if (url.pathname !== '/api/v1/admin/users') return null
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

// ── Route harness ───────────────────────────────────────────────────────────
function mountAdminUsers(handlers: Handler[], roles: string[] = ['ADMIN']) {
  setupFetchMock(handlers)
  setAccessToken('test-token')

  const rootRoute = createRootRoute({
    component: () => <Outlet />,
  })
  const usersRoute = createRoute({
    getParentRoute: () => rootRoute,
    path: '/users',
    component: AdminUsersRoute.options.component,
  })
  const router = createRouter({
    routeTree: rootRoute.addChildren([usersRoute]),
    history: createMemoryHistory({ initialEntries: ['/users'] }),
  })

  const qc = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })

  return render(
    <AuthProvider userManager={makeFakeUserManager(makeFakeUser(roles))}>
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

// ── /admin/users page ──────────────────────────────────────────────────────

describe('AdminUsersPage', () => {
  it('renders one table row per server user (3 of 3) with a chip per realmRole', async () => {
    mountAdminUsers([adminUsersOkHandler()])

    await waitFor(() =>
      expect(
        screen.getByRole('heading', { name: /admin · users/i }),
      ).toBeInTheDocument(),
    )

    // 3 rows
    await waitFor(() => {
      expect(screen.getByTestId('admin-users-row-alice')).toBeInTheDocument()
      expect(screen.getByTestId('admin-users-row-bob')).toBeInTheDocument()
      expect(screen.getByTestId('admin-users-row-carol')).toBeInTheDocument()
    })

    // alice: 3 chips, one per role
    expect(
      screen.getByTestId('admin-users-role-alice-ADMIN'),
    ).toBeInTheDocument()
    expect(
      screen.getByTestId('admin-users-role-alice-USER'),
    ).toBeInTheDocument()
    expect(
      screen.getByTestId('admin-users-role-alice-offline_access'),
    ).toBeInTheDocument()

    // bob: 1 chip
    expect(screen.getByTestId('admin-users-role-bob-USER')).toBeInTheDocument()

    // carol: 2 chips
    expect(
      screen.getByTestId('admin-users-role-carol-default-roles-titan-dev'),
    ).toBeInTheDocument()
    expect(
      screen.getByTestId('admin-users-role-carol-uma_authorization'),
    ).toBeInTheDocument()

    // Category encoding survives onto the data attribute (smoke check —
    // admin chip is the 'admin' bucket; offline_access is the 'system' bucket).
    expect(
      screen.getByTestId('admin-users-role-alice-ADMIN').getAttribute('data-role-category'),
    ).toBe('admin')
    expect(
      screen
        .getByTestId('admin-users-role-alice-offline_access')
        .getAttribute('data-role-category'),
    ).toBe('system')
  })

  it('on 403, renders "Admin role required" + Keycloak link (NOT a blank table)', async () => {
    mountAdminUsers([adminUsers403Handler()])

    const banner = await screen.findByTestId('admin-users-error-403')
    expect(banner.textContent).toMatch(/admin role required/i)

    // No table body, no empty state — only the 403 panel.
    expect(screen.queryByTestId('admin-users-table-body')).toBeNull()
    expect(screen.queryByTestId('admin-users-empty')).toBeNull()

    // Keycloak fallback link surfaces inside the panel.
    const kcLink = screen.getByTestId('admin-users-error-kc-link')
    expect(kcLink.getAttribute('href')).toMatch(/\/admin\/master\/console\/#\/.*\/users$/)
    expect(kcLink.getAttribute('target')).toBe('_blank')
  })

  // ── #1237: effective Titan role badge (read-only survival slice) ───────────

  it('renders the effective Titan role badge per user (ladder pick + none)', async () => {
    mountAdminUsers([adminUsersOkHandler()])
    await screen.findByTestId('admin-users-row-alice')

    // Highest-privileged ladder role wins (alice holds ADMIN+USER → ADMIN).
    expect(screen.getByTestId('admin-users-titanrole-alice').textContent).toBe(
      'ADMIN',
    )
    // bob holds DEVELOPER (USER is not a ladder role).
    expect(screen.getByTestId('admin-users-titanrole-bob').textContent).toBe(
      'DEVELOPER',
    )
    // carol holds no ladder role → "none" floor, not a crash.
    const carol = screen.getByTestId('admin-users-titanrole-carol')
    expect(carol.textContent).toBe('none')
    expect(carol.getAttribute('data-titan-role')).toBe('none')
  })

  it('renders the role badge read-only — no grant control for admin or non-admin', async () => {
    // The survival slice is read-only: the inline grant control (AC #2) is
    // deferred to a follow-up pending the role-grant write API + userId
    // projection. No row should expose a grant control to anyone.
    const { unmount } = mountAdminUsers([adminUsersOkHandler()])
    await screen.findByTestId('admin-users-row-bob')
    expect(screen.queryByTestId('admin-users-rolegrant-bob')).toBeNull()
    expect(screen.getByTestId('admin-users-titanrole-bob').textContent).toBe(
      'DEVELOPER',
    )
    unmount()
    resetFetchMock()

    // A non-admin bearer also sees roles read-only — no control, no console error.
    mountAdminUsers([adminUsersOkHandler()], ['USER'])
    await screen.findByTestId('admin-users-row-bob')
    expect(screen.queryByTestId('admin-users-rolegrant-bob')).toBeNull()
    expect(screen.getByTestId('admin-users-titanrole-bob').textContent).toBe(
      'DEVELOPER',
    )
  })
})

// ── Sidebar role-gating ─────────────────────────────────────────────────────

describe('Sidebar — Admin · Users entry visibility', () => {
  it('hides "Admin · Users" when the bearer has no roles', async () => {
    mountSidebar([])
    await waitFor(() =>
      expect(screen.getByText('Overview')).toBeInTheDocument(),
    )
    expect(screen.queryByText(/admin · users/i)).toBeNull()
  })

  it('shows "Admin · Users" when the bearer has the ADMIN role', async () => {
    mountSidebar(['ADMIN'])
    await waitFor(() =>
      expect(screen.getByText(/admin · users/i)).toBeInTheDocument(),
    )
  })
})
