/**
 * Frame / states tests for /builds (#1187, UX chart H1/H4/H5).
 *
 * The H1 sweep (#1192) wrapped the route in <PageContainer>; #1187 takes it
 * further: a WIDE frame, a single dominant primary action, an at-a-glance
 * summary strip, and an error state that degrades to a framed, readable
 * message instead of a bare <p> in a dead canvas.
 *
 * These assertions are adversarial-first — they pin the regressions a sweep
 * actually breaks:
 *   - the wide max-width frame (H1) + the <header> (H6),
 *   - EXACTLY ONE primary action (H5),
 *   - loading → skeleton rows (not a spinner-in-void) (H4),
 *   - error → a readable message + retry, NOT a blank page or an infinite
 *     skeleton (H4 + adversarial sad path),
 *   - empty-but-query-filtered → the "filtered" empty branch with a working
 *     Clear-filters affordance (NOT the bare "No builds yet").
 *
 * The URL-sync / wire-param contract is covered by builds-filter.test.tsx;
 * this file owns the framing + state regions.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
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
import { SEED_BUILD, SEED_JOBS_PAGE, setupFetchMock, resetFetchMock } from './msw-handlers'

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

const SEED_BUILDS_PAGE: BuildsPageDto = {
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

function buildsHandler(status = 200, body: unknown = SEED_BUILDS_PAGE): Handler {
  return (url, method) =>
    method === 'GET' && url.pathname === '/api/v1/builds'
      ? {
          status,
          body:
            status >= 400
              ? { type: 'about:blank', title: 'Boom', status, detail: 'backend exploded', instance: null }
              : body,
        }
      : null
}

/**
 * Fails ONLY the cheap failed-count query (limit=1 + status=FAILED) with a 500,
 * leaving the main builds/jobs fetch healthy. Used to pin the adversarial case
 * where a count query dies independently: the summary tile must degrade to a
 * neutral dash, NOT a permanent skeleton (critic #1197 sev2 / H4).
 */
function failedCountErrorHandler(): Handler {
  return (url, method) => {
    if (method !== 'GET' || url.pathname !== '/api/v1/builds') return null
    const isCount = url.searchParams.get('limit') === '1'
    const isFailed = url.searchParams.getAll('status').includes('FAILED')
    if (!isCount || !isFailed) return null
    return {
      status: 500,
      body: { type: 'about:blank', title: 'Boom', status: 500, detail: 'count exploded', instance: null },
    }
  }
}

function jobsHandler(status = 200): Handler {
  return (url, method) =>
    method === 'GET' && url.pathname === '/api/v1/jobs'
      ? status >= 400
        ? { status, body: { type: 'about:blank', title: 'Boom', status, detail: 'jobs exploded', instance: null } }
        : { status: 200, body: SEED_JOBS_PAGE }
      : null
}

function mountBuilds(initialUrl: string, handlers: Handler[] | null) {
  // handlers === null → the caller has already installed a custom fetch (used
  // by the loading test, which needs the builds request to hang).
  if (handlers) setupFetchMock(handlers)
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
  // Register /jobs so the header's "New build" <Link> resolves cleanly.
  const jobsRoute = createRoute({
    getParentRoute: () => rootRoute,
    path: '/jobs',
    component: () => <div>jobs</div>,
  })
  const router = createRouter({
    routeTree: rootRoute.addChildren([buildsLayout.addChildren([buildsIndex]), jobsRoute]),
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

beforeEach(() => setAccessToken(null))
afterEach(() => {
  resetFetchMock()
  setAccessToken(null)
})

// ── Frame: wide container + header + exactly one primary action ──────────────

describe('/builds — page frame (H1/H5/H6)', () => {
  it('wraps content in the WIDE PageContainer + a single <header> + one primary action', async () => {
    const { container } = mountBuilds('/builds', [buildsHandler(), jobsHandler()])

    // Wide frame (H1) — content is not marooned at default width.
    await waitFor(() =>
      expect(container.querySelector('.max-w-screen-2xl')).not.toBeNull(),
    )
    // Exactly one <header> (PageHeader) + one dominant <h1> "Builds" (H6).
    expect(container.querySelectorAll('header')).toHaveLength(1)
    const h1s = screen.getAllByRole('heading', { level: 1 })
    expect(h1s).toHaveLength(1)
    expect(h1s[0].textContent).toBe('Builds')

    // EXACTLY ONE primary action (H5): one .btn-primary, the "New build" CTA.
    const primaries = container.querySelectorAll('.btn-primary')
    expect(primaries).toHaveLength(1)
    expect(screen.getByTestId('builds-new-build').textContent).toBe('New build')
  })

  it('header subtitle leads with the BUILDS total, never a bare jobs count (#825 defect 6 / #77)', async () => {
    mountBuilds('/builds', [buildsHandler(), jobsHandler()])
    const desc = await screen.findByTestId('page-description')
    // Both counts, both labeled: "<builds> build(s) across <jobs> job(s)".
    await waitFor(() =>
      expect(desc.textContent).toMatch(/^\s*\d+\s+builds?\s+across\s+\d+\s+jobs?\s*$/),
    )
    const m = desc.textContent!.match(/(\d+)\s+builds?\s+across\s+(\d+)\s+jobs?/)!
    // The count labeled "build(s)" is the builds total; "job(s)" the jobs total.
    expect(Number(m[1])).toBe(SEED_BUILDS_PAGE.total)
    expect(Number(m[2])).toBe(SEED_JOBS_PAGE.total)
  })

  it('renders the at-a-glance summary strip (passing / failed / running)', async () => {
    mountBuilds('/builds', [buildsHandler(), jobsHandler()])
    await waitFor(() => expect(screen.getByTestId('builds-summary')).toBeInTheDocument())
    expect(screen.getByTestId('builds-summary-passing')).toBeInTheDocument()
    expect(screen.getByTestId('builds-summary-failed')).toBeInTheDocument()
    expect(screen.getByTestId('builds-summary-running')).toBeInTheDocument()
  })
})

// ── Four states ──────────────────────────────────────────────────────────────

describe('/builds — view states (H4)', () => {
  it('loading: renders skeleton rows, not a spinner-in-void', async () => {
    // Auth resolves async, so the loading window is real but transient. To pin
    // it deterministically we let /jobs resolve but hang /builds forever — the
    // page must hold the skeleton (NOT flash a spinner or a blank canvas).
    const saved = globalThis.fetch
    globalThis.fetch = vi.fn(async (input: RequestInfo | URL) => {
      const raw = typeof input === 'string' ? input : input instanceof URL ? input.href : (input as Request).url
      const url = new URL(raw, 'http://localhost:8080')
      if (url.pathname === '/api/v1/jobs') {
        return new Response(JSON.stringify(SEED_JOBS_PAGE), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        })
      }
      // /api/v1/builds (+ count queries) never resolve → stay in loading.
      return new Promise<Response>(() => {})
    }) as typeof globalThis.fetch
    try {
      mountBuilds('/builds', null)
      await screen.findByTestId('builds-list-loading')
      expect(screen.queryByTestId('builds-list')).toBeNull()
    } finally {
      globalThis.fetch = saved
    }
  })

  it('populated: renders the builds list once data resolves', async () => {
    mountBuilds('/builds', [buildsHandler(), jobsHandler()])
    await waitFor(() => expect(screen.getByTestId('builds-list')).toBeInTheDocument())
    expect(screen.getByTestId(`builds-row-${SEED_BUILD.id}`)).toBeInTheDocument()
  })

  it('error (builds 500): shows a framed, readable message + retry — NOT a blank page or infinite skeleton', async () => {
    const { container } = mountBuilds('/builds', [buildsHandler(500), jobsHandler()])
    const panel = await screen.findByTestId('builds-error')
    expect(panel.textContent).toContain('Server error')
    expect(screen.getByTestId('builds-error-retry')).toBeInTheDocument()
    // The frame survives (header still present) and there is NO endless skeleton.
    expect(container.querySelectorAll('header')).toHaveLength(1)
    expect(screen.queryByTestId('builds-list-loading')).toBeNull()
  })

  it('error (jobs 500): degrades the same way (readable, not blank)', async () => {
    mountBuilds('/builds', [buildsHandler(), jobsHandler(500)])
    const panel = await screen.findByTestId('builds-error')
    expect(panel.textContent).toContain('Server error')
    expect(screen.queryByTestId('builds-list-loading')).toBeNull()
  })
})

// ── Adversarial: a count query dies independently of the main fetch ──────────

describe('/builds — count-query failure (adversarial, H4)', () => {
  it('failed-count 500 → the Failed tile shows a neutral dash, NOT an infinite skeleton; the list still renders', async () => {
    // The failed-count handler 500s; the main builds + jobs fetches are healthy.
    mountBuilds('/builds', [failedCountErrorHandler(), buildsHandler(), jobsHandler()])

    // The page is fully usable — the table renders from the healthy main fetch.
    await waitFor(() => expect(screen.getByTestId('builds-list')).toBeInTheDocument())

    // The Failed tile degrades to a neutral dash (terminal), never a skeleton.
    const dash = await screen.findByTestId('builds-summary-failed-error')
    expect(dash.textContent).toBe('—')
    // No misleading "0" and no value cell for the failed tile.
    expect(screen.queryByTestId('builds-summary-failed-value')).toBeNull()
    // The infinite-skeleton anti-pattern is gone: the Failed tile holds no
    // skeleton. (Passing/Running resolved fine from their own healthy queries.)
    const failedTile = screen.getByTestId('builds-summary-failed')
    expect(failedTile.querySelector('[data-testid="skeleton"]')).toBeNull()
  })
})

// ── Adversarial: empty-but-query-filtered ────────────────────────────────────

describe('/builds — empty-but-filtered (adversarial)', () => {
  it('?q=zzz with zero client matches → the filtered empty branch + Clear filters', async () => {
    mountBuilds('/builds?q=zzz', [buildsHandler(), jobsHandler()])
    // The lone seed build does not match "zzz", so the client filter empties
    // the list → the FILTERED empty branch (NOT the bare "No builds yet").
    const empty = await screen.findByTestId('builds-empty-filtered')
    expect(empty.textContent).toContain('zzz')
    expect(screen.getByRole('button', { name: /clear filters/i })).toBeInTheDocument()
    expect(screen.queryByTestId('builds-empty')).toBeNull()
  })
})
