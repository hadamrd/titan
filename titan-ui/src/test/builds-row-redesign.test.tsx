/**
 * Vitest pinning the /builds row redesign (ui/builds-list-rework).
 *
 * The old 5-column row (status dot · GA icon · job name · #N · branch ·
 * sha · …vast whitespace… · duration · time-ago) is replaced by a 4-column
 * denser shape: [status rail 4px][primary][duration][time-ago]. These
 * tests pin the new contract — including the bug fix where a FAILED row
 * used to render `—` for duration even though BuildDto.durationMs was set.
 *
 *   1. Renders 5 builds with rail · primary · duration · time-ago each.
 *   2. Failed row's duration cell renders the actual duration, NOT `—`.
 *   3. Status rail data-status attribute matches the build status.
 *   4. "Failed" tab filters to FAILED/ABORTED rows only.
 *   5. Empty state for the Failed tab renders the calm "nice." copy.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
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
import type { BuildDto, BuildsPage as BuildsPageDto } from '../api/types'
import {
  SEED_BUILD,
  SEED_JOBS_PAGE,
  setupFetchMock,
  resetFetchMock,
} from './msw-handlers'

// ── Auth fixture ────────────────────────────────────────────────────────────
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

// ── Seed: 5 builds covering every status the rail must handle ────────────────
const SEEDS: BuildDto[] = [
  {
    ...SEED_BUILD,
    id: 101,
    buildNumber: 11,
    status: 'SUCCESS',
    durationMs: 31_000,
  },
  {
    ...SEED_BUILD,
    id: 102,
    buildNumber: 12,
    status: 'FAILED',
    durationMs: 47_500,
    finishedAt: '2026-05-20T09:01:00Z',
    triggerMeta: { branch: 'main', commitSha: '58b7da6f1c2', actor: 'hadamrd' },
  },
  {
    ...SEED_BUILD,
    id: 103,
    buildNumber: 13,
    status: 'RUNNING',
    durationMs: null,
    finishedAt: null,
  },
  {
    ...SEED_BUILD,
    id: 104,
    buildNumber: 14,
    status: 'ABORTED',
    durationMs: 9_000,
  },
  {
    ...SEED_BUILD,
    id: 105,
    buildNumber: 15,
    status: 'QUEUED',
    durationMs: null,
    startedAt: null,
    finishedAt: null,
  },
]

const SEED_PAGE: BuildsPageDto = {
  items: SEEDS,
  total: SEEDS.length,
  offset: 0,
  limit: 100,
}

const EMPTY_PAGE: BuildsPageDto = { items: [], total: 0, offset: 0, limit: 100 }

interface MatchResult {
  status: number
  body: unknown
}
type Handler = (url: URL, method: string) => MatchResult | null

function buildsHandlerWith(seedSelector: (url: URL) => BuildsPageDto): Handler {
  return (url, method) => {
    if (method !== 'GET') return null
    if (url.pathname !== '/api/v1/builds') return null
    return { status: 200, body: seedSelector(url) }
  }
}

function jobsHandler(): Handler {
  return (url, method) => {
    if (method !== 'GET') return null
    if (url.pathname !== '/api/v1/jobs') return null
    return { status: 200, body: SEED_JOBS_PAGE }
  }
}

function mount(initialUrl: string, seedSelector: (url: URL) => BuildsPageDto) {
  setupFetchMock([buildsHandlerWith(seedSelector), jobsHandler()])
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
  const router = createRouter({
    routeTree: rootRoute.addChildren([buildsLayout.addChildren([buildsIndex])]),
    history: createMemoryHistory({ initialEntries: [initialUrl] }),
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
  setAccessToken(null)
})

afterEach(() => {
  resetFetchMock()
  setAccessToken(null)
  vi.useRealTimers()
})

// ── 1. Five rows, each with the four new structural cells ────────────────────

describe('Builds row redesign — shape', () => {
  it('renders 5 rows each with rail + primary + duration + time-ago cells', async () => {
    mount('/builds', () => SEED_PAGE)

    for (const b of SEEDS) {
      const row = await screen.findByTestId(`builds-row-${b.id}`)
      // Status rail present and tagged with the row's status.
      const rail = row.querySelector(`[data-testid="row-rail-${b.id}"]`)
      expect(rail).not.toBeNull()
      expect(rail?.getAttribute('data-status')).toBe(b.status)
      // Status pill carries the lowercase verbal status.
      const pill = row.querySelector(`[data-testid="builds-row-pill-${b.id}"]`)
      expect(pill).not.toBeNull()
      // Duration cell exists.
      const dur = row.querySelector(`[data-testid="builds-row-duration-${b.id}"]`)
      expect(dur).not.toBeNull()
      // Time-ago cell exists (the .cl-started slot, with native title for abs ts).
      expect(row.querySelector('.cl-started')).not.toBeNull()
    }
  })
})

// ── 2. Failed row's duration shows actual time, NOT em-dash ─────────────────

describe('Builds row redesign — failed duration', () => {
  it("renders the actual durationMs on a FAILED row (not '—')", async () => {
    mount('/builds', () => SEED_PAGE)

    const dur = await screen.findByTestId('builds-row-duration-102')
    // 47_500 ms → "47s" via formatDuration.
    expect(dur.textContent).toBe('47s')
    expect(dur.textContent).not.toBe('—')
  })

  it("renders 'queued' on a QUEUED row instead of '—'", async () => {
    mount('/builds', () => SEED_PAGE)
    const dur = await screen.findByTestId('builds-row-duration-105')
    expect(dur.textContent?.toLowerCase()).toContain('queued')
  })
})

// ── 3. Rail data-status matches each status ─────────────────────────────────

describe('Builds row redesign — rail status', () => {
  it('rail data-status reflects every BuildStatus seeded', async () => {
    mount('/builds', () => SEED_PAGE)
    await screen.findByTestId('builds-row-101')

    const expected: Array<[number, string]> = [
      [101, 'SUCCESS'],
      [102, 'FAILED'],
      [103, 'RUNNING'],
      [104, 'ABORTED'],
      [105, 'QUEUED'],
    ]
    for (const [id, status] of expected) {
      const rail = screen.getByTestId(`row-rail-${id}`)
      expect(rail.getAttribute('data-status')).toBe(status)
    }
  })
})

// ── 4. Failed tab filters out non-failed ────────────────────────────────────

describe('Builds row redesign — failed tab filter', () => {
  it('clicking Failed tab requests status=FAILED&status=ABORTED', async () => {
    let lastQuery: URLSearchParams | null = null
    mount('/builds', (url) => {
      lastQuery = url.searchParams
      const wantsFailed =
        url.searchParams.getAll('status').includes('FAILED') &&
        url.searchParams.getAll('status').includes('ABORTED')
      if (wantsFailed) {
        return {
          ...SEED_PAGE,
          items: SEEDS.filter((b) => b.status === 'FAILED' || b.status === 'ABORTED'),
          total: 2,
        }
      }
      return SEED_PAGE
    })

    const failedTab = await screen.findByTestId('filter-tab-failed')
    fireEvent.click(failedTab)

    await waitFor(() => {
      expect(lastQuery?.getAll('status')).toEqual(
        expect.arrayContaining(['FAILED', 'ABORTED']),
      )
    })

    // Only FAILED + ABORTED rows survive in the rendered list.
    await waitFor(() => {
      expect(screen.queryByTestId('builds-row-101')).toBeNull() // SUCCESS
      expect(screen.queryByTestId('builds-row-103')).toBeNull() // RUNNING
      expect(screen.queryByTestId('builds-row-105')).toBeNull() // QUEUED
      expect(screen.getByTestId('builds-row-102')).toBeInTheDocument() // FAILED
      expect(screen.getByTestId('builds-row-104')).toBeInTheDocument() // ABORTED
    })
  })
})

// ── 5. Empty state on Failed tab uses the calm copy ─────────────────────────

describe('Builds row redesign — empty state on Failed tab', () => {
  it("renders the 'No failed builds — nice.' copy when the failed tab is empty", async () => {
    mount('/builds?tab=failed', () => EMPTY_PAGE)

    const empty = await screen.findByTestId('builds-empty-filtered')
    expect(empty.textContent ?? '').toMatch(/no failed builds/i)
    expect(empty.textContent ?? '').toMatch(/nice/i)
  })
})
