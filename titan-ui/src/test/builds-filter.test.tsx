/**
 * Adversarial vitest for the /builds calm-redesign toolbar.
 *
 * Replaces the chip-strip URL-sync spec from #686. The chip strip was deleted
 * with PR #865's calm redesign in favour of one search input + four tabs.
 * These cases pin the new contract:
 *
 *   1. Click the Failed tab → URL gains `?tab=failed`; aria-selected flips.
 *   2. Five keystrokes in the search input → debounced to a single URL
 *      write with `?q=abcde` (NOT five intermediate writes).
 *   3. Clicking the in-input clear glyph drops `?q=…` from the URL.
 *   4. Initial URL `?tab=failed` → that tab is rendered aria-selected and
 *      the wire request carries `status=FAILED&status=ABORTED`.
 *   5. Initial URL `?q=foo` → the wire request carries `search=foo`.
 *
 * Selectors used (reuse these in future tests):
 *   - data-testid="builds-filter-strip"
 *   - data-testid="filter-tab-{all|running|failed|mine}"
 *   - data-testid="filter-search"
 *   - data-testid="filter-clear"  (only present while the input is non-empty)
 *
 * Assertions hit the actual URL params + fetch call args — not text content —
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
import { Route as BuildsIndexRoute } from '../routes/builds/index'
import type { BuildsPage as BuildsPageDto } from '../api/types'
import {
  SEED_BUILD,
  SEED_JOBS_PAGE,
  setupFetchMock,
  resetFetchMock,
} from './msw-handlers'

// ── Fake user / auth ─────────────────────────────────────────────────────────
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

const SEED_BUILDS_FILTER_PAGE: BuildsPageDto = {
  items: [SEED_BUILD],
  total: 1,
  offset: 0,
  limit: 100,
}

interface MatchResult {
  status: number
  body: unknown
}
type Handler = (url: URL, method: string) => MatchResult | null

const buildsCalls: URL[] = []

function buildsRecordingHandler(): Handler {
  return (url, method) => {
    if (method !== 'GET') return null
    if (url.pathname !== '/api/v1/builds') return null
    buildsCalls.push(new URL(url.href))
    return { status: 200, body: SEED_BUILDS_FILTER_PAGE }
  }
}

function jobsHandler(): Handler {
  return (url, method) => {
    if (method !== 'GET') return null
    if (url.pathname !== '/api/v1/jobs') return null
    return { status: 200, body: SEED_JOBS_PAGE }
  }
}

function mountBuilds(initialUrl = '/builds') {
  buildsCalls.length = 0
  setupFetchMock([buildsRecordingHandler(), jobsHandler()])
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
  buildsCalls.length = 0
})

afterEach(() => {
  resetFetchMock()
  setAccessToken(null)
  vi.useRealTimers()
})

// ── 1. Tab click writes ?tab=… ───────────────────────────────────────────────

describe('Builds toolbar — tab click', () => {
  it('clicking the Failed tab writes ?tab=failed and flips aria-selected', async () => {
    const { router } = mountBuilds('/builds')

    const failedTab = await screen.findByTestId('filter-tab-failed')
    expect(failedTab.getAttribute('aria-selected')).toBe('false')

    fireEvent.click(failedTab)

    await waitFor(() => {
      const search = router.state.location.search as Record<string, unknown>
      expect(search.tab).toBe('failed')
    })

    await waitFor(() => {
      expect(
        screen.getByTestId('filter-tab-failed').getAttribute('aria-selected'),
      ).toBe('true')
    })
  })
})

// ── 2. Debounced search ──────────────────────────────────────────────────────

describe('Builds toolbar — debounced search', () => {
  it('typing 5 characters lands a single search fetch (not 5)', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    mountBuilds('/builds')

    await screen.findByTestId('filter-search')
    const initialCount = buildsCalls.length

    const input = screen.getByTestId('filter-search') as HTMLInputElement

    fireEvent.change(input, { target: { value: 'a' } })
    fireEvent.change(input, { target: { value: 'ab' } })
    fireEvent.change(input, { target: { value: 'abc' } })
    fireEvent.change(input, { target: { value: 'abcd' } })
    fireEvent.change(input, { target: { value: 'abcde' } })

    // Between keystrokes (< 300ms) no debounced effect should have fired.
    expect(buildsCalls.length).toBe(initialCount)

    await act(async () => {
      vi.advanceTimersByTime(350)
    })

    await waitFor(() => {
      const newCalls = buildsCalls.slice(initialCount)
      const withQ = newCalls.filter((u) => u.searchParams.get('search') === 'abcde')
      expect(withQ.length).toBeGreaterThanOrEqual(1)
      // No per-keystroke fan-out.
      const prefixCalls = newCalls.filter((u) => {
        const s = u.searchParams.get('search')
        return s !== null && s.length > 0 && s.length < 5
      })
      expect(prefixCalls.length).toBe(0)
    })
  })
})

// ── 3. Clear-button removes ?q= ──────────────────────────────────────────────

describe('Builds toolbar — clear search', () => {
  it('clicking the inline clear glyph drops ?q from the URL', async () => {
    const { router } = mountBuilds('/builds?q=hello')

    const clear = await screen.findByTestId('filter-clear')
    expect(clear).toBeInTheDocument()

    fireEvent.click(clear)

    await waitFor(() => {
      const search = router.state.location.search as Record<string, unknown>
      expect(search.q ?? '').toBe('')
    })

    // Glyph disappears once q is empty (only rendered while input is non-empty).
    await waitFor(() => {
      expect(screen.queryByTestId('filter-clear')).toBeNull()
    })
  })
})

// ── 4. Initial URL ?tab=failed renders selected + correct wire ───────────────

describe('Builds toolbar — initial URL state', () => {
  it('mounting with ?tab=failed renders that tab aria-selected=true and fetches FAILED+ABORTED', async () => {
    mountBuilds('/builds?tab=failed')

    const failed = await screen.findByTestId('filter-tab-failed')
    expect(failed.getAttribute('aria-selected')).toBe('true')

    expect(
      screen.getByTestId('filter-tab-all').getAttribute('aria-selected'),
    ).toBe('false')
    expect(
      screen.getByTestId('filter-tab-running').getAttribute('aria-selected'),
    ).toBe('false')

    await waitFor(() => {
      expect(buildsCalls.length).toBeGreaterThan(0)
      // The data-fetch for the active tab carries both FAILED and ABORTED.
      const failedCalls = buildsCalls.filter((u) => {
        const statuses = u.searchParams.getAll('status')
        return statuses.includes('FAILED') && statuses.includes('ABORTED')
      })
      expect(failedCalls.length).toBeGreaterThan(0)
    })
  })
})

// ── 5. Initial URL ?q=foo carries through to wire ────────────────────────────

describe('Builds toolbar — initial search in URL', () => {
  it('mounting with ?q=foo sends search=foo on the first builds request', async () => {
    mountBuilds('/builds?q=foo')

    await screen.findByTestId('filter-search')
    await waitFor(() => {
      const withQ = buildsCalls.filter((u) => u.searchParams.get('search') === 'foo')
      expect(withQ.length).toBeGreaterThan(0)
    })

    const input = screen.getByTestId('filter-search') as HTMLInputElement
    expect(input.value).toBe('foo')
  })
})
