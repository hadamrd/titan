/**
 * Unit coverage for the /builds error states (#1200, sev2/tests).
 *
 * The auth golden-path e2e spec (e2e/specs/v3/56-golden-path-auth.spec.ts)
 * asserts the build view never collapses to a blank page: it distinguishes a
 * real DATA view from an ERROR boundary via the stable marker this route emits
 * when its queries reject — `[data-builds-error="jobs|builds"]` + `role="alert"`
 * (the spec reads the `[data-builds-error]` attribute, NOT a per-query testid).
 *
 * #1187 reframed the error state: instead of a bare `<p>` per failing query, the
 * route now renders ONE framed `builds-error` panel (header stays, readable
 * message + Retry — never a marooned string in a dead canvas). The e2e contract
 * is preserved on that panel: the `data-builds-error` marker still names WHICH
 * query failed ("jobs" wins when both reject) and `role="alert"` still fires.
 *
 * That marker is production contract the e2e spec depends on, but nothing else
 * in the unit suite exercises it — a refactor could silently drop the attribute
 * and only surface as a red e2e run on a live rig. These cases pin the contract
 * at the unit layer so the regression fails fast, locally, with no rig.
 *
 * Cases:
 *   1. jobs query rejects   → framed panel + data-builds-error="jobs"   + role="alert".
 *   2. builds query rejects → framed panel + data-builds-error="builds" + role="alert".
 *   3. both reject          → jobs is checked first, so the marker reads "jobs".
 *   4. happy path           → NO `[data-builds-error]` element exists (the guard the e2e
 *                             spec relies on to rule out a false error state).
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
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
import { Route as BuildsIndexRoute } from '../routes/builds/index'
import {
  SEED_BUILDS_PAGE,
  SEED_JOBS_PAGE,
  setupFetchMock,
  resetFetchMock,
} from './msw-handlers'

// ── Fake user / auth (mirrors builds-filter.test.tsx) ────────────────────────
function makeFakeUser(): User {
  return {
    access_token: 'test-token',
    expired: false,
    profile: { sub: 'u1', preferred_username: 'alice', groups: [] },
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

interface MatchResult {
  status: number
  body: unknown
}
type Handler = (url: URL, method: string) => MatchResult | null

const PROBLEM_500 = {
  type: 'about:blank',
  title: 'Internal Server Error',
  status: 500,
  detail: 'boom',
  instance: null,
}

function jobsHandler(status: number): Handler {
  return (url, method) => {
    if (method !== 'GET' || url.pathname !== '/api/v1/jobs') return null
    return status === 200
      ? { status: 200, body: SEED_JOBS_PAGE }
      : { status, body: PROBLEM_500 }
  }
}

function buildsHandler(status: number): Handler {
  return (url, method) => {
    if (method !== 'GET' || url.pathname !== '/api/v1/builds') return null
    return status === 200
      ? { status: 200, body: SEED_BUILDS_PAGE }
      : { status, body: PROBLEM_500 }
  }
}

function mountBuilds(jobsStatus: number, buildsStatus: number) {
  setupFetchMock([jobsHandler(jobsStatus), buildsHandler(buildsStatus)])
  setAccessToken('test-token')

  const rootRoute = createRootRoute({ component: () => <Outlet /> })
  const buildsLayout = createRoute({
    getParentRoute: () => rootRoute,
    path: '/builds',
    component: () => <Outlet />,
  })
  const buildsIndex = createRoute({
    getParentRoute: () => buildsLayout,
    path: '/',
    component: BuildsIndexRoute.options.component,
    validateSearch: BuildsIndexRoute.options.validateSearch,
  })
  // Register /jobs so the framed error header's "New build" <Link> resolves.
  const jobsRoute = createRoute({
    getParentRoute: () => rootRoute,
    path: '/jobs',
    component: () => <div>jobs</div>,
  })
  const router = createRouter({
    routeTree: rootRoute.addChildren([buildsLayout.addChildren([buildsIndex]), jobsRoute]),
    history: createMemoryHistory({ initialEntries: ['/builds'] }),
  })

  // retry:false so a rejected query surfaces isError immediately (no backoff).
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
  setAccessToken(null)
})

afterEach(() => {
  resetFetchMock()
  setAccessToken(null)
  vi.useRealTimers()
})

describe('/builds error states — stable markers the auth e2e spec depends on', () => {
  it('jobs query rejects → framed panel + data-builds-error="jobs" + role="alert"', async () => {
    mountBuilds(500, 200)

    const el = await screen.findByTestId('builds-error')
    expect(el.getAttribute('data-builds-error')).toBe('jobs')
    expect(el.getAttribute('role')).toBe('alert')
    // Exactly one error marker — the framed panel, not a per-query duplicate.
    expect(document.querySelectorAll('[data-builds-error]')).toHaveLength(1)
  })

  it('builds query rejects → framed panel + data-builds-error="builds" + role="alert"', async () => {
    mountBuilds(200, 500)

    const el = await screen.findByTestId('builds-error')
    expect(el.getAttribute('data-builds-error')).toBe('builds')
    expect(el.getAttribute('role')).toBe('alert')
    expect(document.querySelectorAll('[data-builds-error]')).toHaveLength(1)
  })

  it('both queries reject → jobs is checked first, so the marker reads "jobs"', async () => {
    mountBuilds(500, 500)

    const el = await screen.findByTestId('builds-error')
    expect(el.getAttribute('data-builds-error')).toBe('jobs')
    expect(document.querySelectorAll('[data-builds-error]')).toHaveLength(1)
  })

  it('happy path → no [data-builds-error] element exists (the e2e error-guard read)', async () => {
    mountBuilds(200, 200)

    // Wait for the real data view (the Builds heading) so we assert AFTER the
    // queries settle, not on a loading frame.
    await screen.findByRole('heading', { name: /^builds$/i })
    expect(document.querySelector('[data-builds-error]')).toBeNull()
  })
})
