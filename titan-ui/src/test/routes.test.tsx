/**
 * Route smoke tests against real API hooks + hand-rolled fetch mock.
 *
 * Strategy: use the real generated routeTree with createMemoryHistory so each
 * test navigates to a specific URL. Global fetch is replaced with the mock
 * before each test and restored after.
 *
 * EventSource is not available in jsdom — we stub it so SSE-using routes
 * don't crash on mount. The stub never fires events (log streaming is not
 * tested at route level; see api.test.ts for hook-level coverage).
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import {
  RouterProvider,
  createRouter,
  createMemoryHistory,
  createRootRoute,
  createRoute,
  Outlet,
} from '@tanstack/react-router'
import { routeTree } from '../routeTree.gen'
import { Route as BuildsIndexRoute } from '../routes/builds/index'
import { AuthContext, type AuthState } from '../auth/AuthProvider'
import { setAccessToken } from '../auth/tokenStore'
import {
  setupFetchMock,
  resetFetchMock,
  defaultHandlers,
  SEED_JOB,
  SEED_BUILD,
  SEED_RUNNING_BUILD,
  SEED_JOBS_PAGE,
  SEED_QUEUE_ENTRY,
  SEED_WORKER_NULL_METRICS,
} from './msw-handlers'
import type { BuildsPage as BuildsPageDto } from '../api/types'

// A fake AuthState that the test harness installs above the router. Routes that
// guard on `isAuthenticated` (everything except /login + /login/callback) see a
// signed-in user and render. The real OIDC flow is exercised in auth.test.tsx.
const fakeAuth: AuthState = {
  user: { access_token: 'fake', expired: false } as unknown as AuthState['user'],
  isLoading: false,
  isAuthenticated: true,
  signinRedirect: async () => {},
  signinRedirectCallback: async () => ({} as never),
  signoutRedirect: async () => {},
}

// ── EventSource stub ─────────────────────────────────────────────────────────
// jsdom does not implement EventSource; stub it so the SSE hook in $buildId
// doesn't throw on mount.
class EventSourceStub {
  static readonly CONNECTING = 0
  static readonly OPEN = 1
  static readonly CLOSED = 2
  readyState = EventSourceStub.CONNECTING
  onmessage: ((e: MessageEvent) => void) | null = null
  onerror: ((e: Event) => void) | null = null
  addEventListener() { /* no-op */ }
  removeEventListener() { /* no-op */ }
  close() { this.readyState = EventSourceStub.CLOSED }
}

// Install before all tests in this file
vi.stubGlobal('EventSource', EventSourceStub)

// ── Helpers ──────────────────────────────────────────────────────────────────

function makeRouter(initialPath: string) {
  return createRouter({
    routeTree,
    history: createMemoryHistory({ initialEntries: [initialPath] }),
  })
}

function renderAt(path: string, authOverride?: Partial<AuthState>) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const router = makeRouter(path)
  const auth: AuthState = { ...fakeAuth, ...authOverride }
  return render(
    <AuthContext.Provider value={auth}>
      <QueryClientProvider client={qc}>
        <RouterProvider router={router} />
      </QueryClientProvider>
    </AuthContext.Provider>,
  )
}

// ── /builds mounter (#699) ────────────────────────────────────────────────────
// PR #685 added validateSearch + a BuildsSearch shape to the file route, and
// the page reads it via `useSearch({ from: '/builds/' })`. Mounting through the
// generated routeTree skips that wiring (no validateSearch on the parent), so
// the page sees raw strings and never renders the filter strip. Re-mount the
// route under a two-level memory tree (layout `/builds` → index `/`) and
// forward BOTH `component` and `validateSearch` from the real route — same
// pattern as builds-filter.test.tsx (#689). The harness still installs the
// real AuthContext.Provider above so the page's auth-gated hooks survive.
function renderBuildsAt(path: string, authOverride?: Partial<AuthState>) {
  // The default fetch mock has no GET /api/v1/builds handler (the page calls
  // useFilteredBuilds against that endpoint). Override beforeEach's default
  // mock with one that responds to /api/v1/builds (and /jobs for the per-row
  // job-name display). Mirrors the handler shape in builds-filter.test.tsx.
  const buildsPage: BuildsPageDto = {
    items: [SEED_BUILD],
    total: 1,
    offset: 0,
    limit: 100,
  }
  resetFetchMock()
  setupFetchMock([
    (url, method) => {
      if (method !== 'GET') return null
      if (url.pathname !== '/api/v1/builds') return null
      return { status: 200, body: buildsPage }
    },
    (url, method) => {
      if (method !== 'GET') return null
      if (url.pathname !== '/api/v1/jobs') return null
      return { status: 200, body: SEED_JOBS_PAGE }
    },
  ])
  setAccessToken('fake')
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
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
    history: createMemoryHistory({ initialEntries: [path] }),
  })
  const auth: AuthState = { ...fakeAuth, ...authOverride }
  return render(
    <AuthContext.Provider value={auth}>
      <QueryClientProvider client={qc}>
        <RouterProvider router={router} />
      </QueryClientProvider>
    </AuthContext.Provider>,
  )
}

beforeEach(() => {
  setupFetchMock()
  // The TanStack Router `beforeLoad` guard in __root.tsx reads tokenStore (not
  // React context), so the AuthContext.Provider above doesn't help on its own.
  // Prime the in-memory token slot so non-public routes survive the guard.
  setAccessToken('fake')
})
afterEach(() => {
  resetFetchMock()
  setAccessToken(null)
})

// ── Login ─────────────────────────────────────────────────────────────────────

describe('Login route /login', () => {
  it('renders sign-in heading and the OIDC redirect button', async () => {
    // The LoginPage useEffect redirects authenticated users to '/'. Override
    // the AuthContext to unauthenticated so the login card actually renders.
    // tokenStore is still primed by beforeEach so the __root.tsx beforeLoad
    // guard does not redirect us back to /login (no-op here) — what matters
    // is the React component's `isAuthenticated === false` path.
    renderAt('/login', { isAuthenticated: false, user: null as unknown as AuthState['user'] })
    await waitFor(() =>
      expect(screen.getByRole('heading', { name: /sign in to titan/i })).toBeInTheDocument(),
    )
    expect(screen.getByRole('button', { name: /sign in/i })).toBeInTheDocument()
  })
})

// ── Login callback ───────────────────────────────────────────────────────────
// Guards the bug class from PR #343 where /login/callback was routed to the
// LoginPage card instead of the LoginCallbackPage that runs the OIDC code
// exchange. The route MUST resolve to the callback component — visible signal
// is the "Completing sign-in…" line, NOT the "Sign in to Titan" heading.

describe('Login callback route /login/callback', () => {
  it('renders the LoginCallbackPage (not the LoginPage card) on ?code=', async () => {
    // Make signinRedirectCallback hang so the "Completing sign-in…" text
    // stays observable for the assertion (a resolving stub would race the
    // post-callback navigate({to:'/'}) and replace the visible text).
    const pending: Promise<never> = new Promise(() => {})
    renderAt('/login/callback?code=invalid', {
      signinRedirectCallback: () => pending,
    })
    await waitFor(() =>
      expect(screen.getByText(/completing sign-in/i)).toBeInTheDocument(),
    )
    // Defensive: assert the LoginPage card is NOT what got rendered. Catches a
    // regression where the route tree collapses /login/callback onto /login.
    expect(
      screen.queryByRole('heading', { name: /sign in to titan/i }),
    ).not.toBeInTheDocument()
  })
})

// ── Jobs list ─────────────────────────────────────────────────────────────────

describe('Jobs route /jobs', () => {
  it('renders job list with seeded data', async () => {
    renderAt('/jobs')
    await waitFor(() =>
      expect(screen.getAllByText(SEED_JOB.displayName).length).toBeGreaterThan(0),
      { timeout: 10000 },
    )
    // The v3 sidebar adds an "Overview" nav link which also matches /view/i;
    // restrict to the row-level "View" link by exact name.
    expect(screen.getAllByRole('link', { name: /^view$/i }).length).toBeGreaterThan(0)
    expect(screen.getByText(/1 pipeline/i)).toBeInTheDocument()
  })

  it('shows enabled badge', async () => {
    renderAt('/jobs')
    await waitFor(() =>
      expect(screen.getAllByText('enabled').length).toBeGreaterThan(0),
      { timeout: 10000 },
    )
  })

  // Ticket #437 (original): /jobs MUST surface a "New job" CTA in the header.
  // Ticket #512 (PR #515) superseded the link-to-/onboarding affordance with an
  // inline NewJobDialog opened by a button — same product intent (create a job
  // without leaving the page), better moment-of-action locality. The test now
  // asserts the CTA still exists, is labeled "New job", carries the documented
  // test hook, and — critically — opens the dialog. The dialog-open assertion
  // is what keeps this non-tautological: if the CTA loses its handler or the
  // dialog wiring breaks, the test fails. Ticket #678 tracked the test drift.
  it('renders a "New job" CTA that opens the new-job dialog', async () => {
    const user = userEvent.setup()
    renderAt('/jobs')
    await waitFor(() =>
      expect(screen.getByRole('button', { name: /new job/i })).toBeInTheDocument(),
    )
    const cta = screen.getByRole('button', { name: /new job/i })
    expect(cta.getAttribute('data-testid')).toBe('new-job-open')
    // No dialog before click — guards against an always-open dialog accidentally
    // satisfying the post-click assertion.
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    await user.click(cta)
    await waitFor(() =>
      expect(screen.getByRole('dialog')).toBeInTheDocument(),
    )
  })
})

// ── Job detail ────────────────────────────────────────────────────────────────

describe('Job detail route /jobs/:jobId', () => {
  it('renders job summary and recent builds', async () => {
    renderAt(`/jobs/${SEED_JOB.id}`)
    await waitFor(() =>
      expect(screen.getAllByText(SEED_JOB.displayName).length).toBeGreaterThan(0),
    )
    // Exactly one "Run pipeline" button — the page header CTA. The TopBar
    // icon stub was removed in #791 (was a no-op duplicate that broke
    // Playwright strict-mode selectors).
    expect(screen.getAllByRole('button', { name: /run pipeline/i })).toHaveLength(1)
    // Builds list should appear
    await waitFor(() =>
      expect(screen.getByText('Recent builds')).toBeInTheDocument(),
    )
    // Build numbers — rendered as "#N" in the v3 row shape. At least one match
    // (the seeded builds page may render more than one row).
    expect(
      screen.getAllByText((_, el) => el?.textContent === `#${SEED_BUILD.buildNumber}`).length,
    ).toBeGreaterThan(0)
  })

  // #853 UX-cleanup: the 4-card KPI strip (TOTAL BUILDS / SUCCESS RATE /
  // AVG DURATION / LAST TRIGGERED) on /jobs/:jobId was removed. The header
  // still surfaces "last run <relative>" inline but the dedicated KPI card
  // strip is gone — assertion deleted.

  it('sets document.title to "<Pipeline> — Pipeline"', async () => {
    renderAt(`/pipelines/${SEED_JOB.id}`)
    await waitFor(() =>
      expect(document.title).toMatch(new RegExp(`${SEED_JOB.displayName}.*Pipeline`)),
    )
  })

  it('shows 404 message for unknown pipeline', async () => {
    renderAt('/pipelines/9999')
    await waitFor(() =>
      expect(screen.getByText(/pipeline not found/i)).toBeInTheDocument(),
    )
  })
})

// ── Builds list ───────────────────────────────────────────────────────────────

describe('Builds route /builds', () => {
  it('renders builds list with seeded data', async () => {
    renderBuildsAt('/builds')
    // Shows first job name in header / row
    await waitFor(() =>
      expect(screen.getAllByText(SEED_JOB.displayName).length).toBeGreaterThan(0),
    )
    // Build number is rendered as "#7" in the row body. The calm-editorial
    // redesign drops DataTable on /builds and renders hand-rolled rows; the
    // per-row label testid is preserved (BuildRow.tsx).
    await waitFor(() =>
      expect(
        screen.getByTestId(`builds-row-label-${SEED_BUILD.id}`),
      ).toHaveTextContent(`#${SEED_BUILD.buildNumber}`),
    )
    // Row exists with the standard testid.
    expect(screen.getByTestId(`builds-row-${SEED_BUILD.id}`)).toBeInTheDocument()
    // Status indicator: the row-redesign (ui/builds-list-rework) replaced
    // the standalone status dot with a 4px colored rail at the row's left
    // edge. Each rail carries `data-status` matching the BuildStatus —
    // assert at least one row's rail reflects SUCCESS.
    const rail = document.querySelector(
      `[data-testid="row-rail-${SEED_BUILD.id}"]`,
    )
    expect(rail).not.toBeNull()
    expect(rail?.getAttribute('data-status')).toBe('SUCCESS')
  })
})

// Regression for #442: the page MUST NOT pin to a single hidden job's builds.
// Subtitle ("Showing builds for <X>") and missing global-jobs context were the
// user-visible symptom; assert the global subtitle wording instead.
describe('Builds route /builds — global view regression (#442)', () => {
  it('renders the global-view subtitle, not a job-pinned one', async () => {
    renderBuildsAt('/builds')
    // #77 (defect-6 no-rot): the subtitle now leads with the BUILDS total —
    // "<n> builds across <m> jobs" — never a bare jobs count masquerading as
    // the page's numeral. Still global (spans jobs), still not job-pinned.
    await waitFor(() =>
      expect(screen.getByTestId('page-description')).toHaveTextContent(
        /\d+\s+builds?\s+across\s+\d+\s+jobs?/i,
      ),
    )
    // The old bug rendered "Showing builds for Main Pipeline · 1 jobs total".
    // Specifically guard the "for <name>" phrasing being gone — the global page
    // does not name a single job in its subtitle.
    expect(screen.queryByText(/showing builds for /i)).not.toBeInTheDocument()
  })

  it('exposes status filter as four tab buttons (not static spans)', async () => {
    renderBuildsAt('/builds')
    // The calm-editorial redesign replaces the per-status toggle chip strip
    // with a 4-tab filter: All / Running / Failed / Mine. Each tab is a real
    // <button role="tab"> with a data-testid and the URL syncs via ?tab=.
    await waitFor(() =>
      expect(screen.getByTestId('filter-tab-failed')).toBeInTheDocument(),
    )
    expect(screen.getByTestId('filter-tab-all')).toBeInTheDocument()
    expect(screen.getByTestId('filter-tab-running')).toBeInTheDocument()
    expect(screen.getByTestId('filter-tab-mine')).toBeInTheDocument()
    // All four MUST be <button> elements (regression guard for the original
    // "static span" failure mode in #442 — clicking the tab must dispatch).
    for (const k of ['all', 'running', 'failed', 'mine'] as const) {
      const el = screen.getByTestId(`filter-tab-${k}`)
      expect(el.tagName).toBe('BUTTON')
    }
  })
})

// Regression for #443: the Overview MUST NOT leak the "pending /metrics
// endpoint" developer placeholder copy. The Cluster health card was removed
// pending a real metrics endpoint — assert the literal string is gone.
describe('Overview route / — placeholder gone (#443)', () => {
  it('does not render "pending /metrics endpoint" anywhere', async () => {
    renderAt('/')
    // Wait for any page content to settle (the metric grid is one anchor).
    await waitFor(() =>
      expect(screen.getByText(/builds.*24h/i)).toBeInTheDocument(),
    )
    expect(screen.queryByText(/pending \/metrics endpoint/i)).not.toBeInTheDocument()
    expect(screen.queryByText(/cluster cpu/i)).not.toBeInTheDocument()
    expect(screen.queryByText(/cluster mem/i)).not.toBeInTheDocument()
    expect(screen.queryByText(/queue latency · pending/i)).not.toBeInTheDocument()
  })
})

// ── Sidebar em-dash regression (#448) ───────────────────────────────────────
// PR #452 demo screenshot caught a bare "—" in the sidebar's "Build minutes"
// usage tile. Billing/quota isn't a Titan feature in 0.1.0, so the whole tile
// was removed rather than leaving a deceptive placeholder. Guard against
// regression by asserting neither the label nor any bare em-dash row appears
// in the sidebar (<aside>) on the Overview route with a seeded fleet.
describe('Sidebar — em-dash placeholder cleanup (#448)', () => {
  it('does not render "Build minutes" nor a bare em-dash in the sidebar', async () => {
    const { container } = renderAt('/')
    await waitFor(() =>
      expect(screen.getByText(/builds.*24h/i)).toBeInTheDocument(),
    )
    const sidebar = container.querySelector('aside.sidebar')
    expect(sidebar).not.toBeNull()
    const text = sidebar!.textContent ?? ''
    expect(text).not.toMatch(/build minutes/i)
    // No bare em-dash (any U+2014) anywhere in sidebar text.
    expect(text.includes('—')).toBe(false)
  })
})

// ── Build detail ──────────────────────────────────────────────────────────────

describe('Build detail route /builds/:buildId', () => {
  it('renders summary card and log pane for a terminal build', async () => {
    renderAt(`/builds/${SEED_BUILD.id}`)
    // v3 detail page no longer has a literal "Build Summary" heading; it has a
    // metric-grid with "Status", "Duration", "Started", "Job" tiles and a tab
    // labelled "Logs". Assert on those user-visible anchors instead.
    await waitFor(() =>
      expect(screen.getByText('Status')).toBeInTheDocument(),
    )
    // "Duration" — appears in the metric grid AND, since #450, in the
    // auto-selected Step detail panel's <dt>. At least one.
    expect(screen.getAllByText('Duration').length).toBeGreaterThan(0)
    expect(screen.getByRole('tab', { name: /^logs$/i })).toBeInTheDocument()
    // Status badge — appears in page-actions, metric, and (since #450) the
    // auto-selected Step detail panel. At least one.
    expect(screen.getAllByText('SUCCESS').length).toBeGreaterThan(0)
    // Duration: 179000 ms = 2m 59s — the build-level duration tile.
    expect(screen.getByText('2m 59s')).toBeInTheDocument()
  })

  it('shows Cancel button for a running build', async () => {
    renderAt(`/builds/${SEED_BUILD.id + 1}`) // id 43 = RUNNING
    // For the running build, the page-actions slot renders a "Cancel" button
    // (v3 dropped the "Build Summary" heading). Use the button as the anchor.
    await waitFor(() =>
      expect(screen.getByRole('button', { name: /^cancel$/i })).toBeInTheDocument(),
    )
  })

  it('shows build not found for unknown buildId', async () => {
    renderAt('/builds/9999')
    await waitFor(() =>
      expect(screen.getByText(/build not found/i)).toBeInTheDocument(),
    )
  })
})

// ── Build compare (path form) ────────────────────────────────────────────────
// Regression guard for GH #51: builds/$buildId.compare.$other was a NESTED
// child of builds/$buildId.tsx, which never renders an <Outlet/> — so the
// compare route matched but rendered nothing (build-detail-or-blank instead
// of the comparison / 404 message). The route file is now un-nested
// ($buildId_.compare.$other.tsx). These tests mount the REAL generated
// routeTree, so a future re-nesting regression fails here, not only on the
// live rig. Same bug class as the /login/callback guard above (PR #343).

describe('Build compare route /builds/$buildId/compare/$other (#51)', () => {
  /** Default handlers + an empty artifacts page (the compare page fetches
   *  /builds/:id/artifacts, which the default mock set does not cover). */
  function withArtifactsHandlers() {
    resetFetchMock()
    setupFetchMock([
      (url, method) => {
        if (method !== 'GET') return null
        if (!url.pathname.match(/^\/api\/v1\/builds\/\d+\/artifacts$/)) return null
        return { status: 200, body: { items: [], total: 0 } }
      },
      ...defaultHandlers(),
    ])
    setAccessToken('fake')
  }

  it('renders the side-by-side comparison view, not the build-detail page', async () => {
    withArtifactsHandlers()
    renderAt(`/builds/${SEED_BUILD.id}/compare/${SEED_RUNNING_BUILD.id}`)
    await waitFor(() =>
      expect(screen.getByTestId('build-compare-view')).toBeInTheDocument(),
    )
    expect(screen.getByTestId('compare-summary-a')).toBeInTheDocument()
    expect(screen.getByTestId('compare-summary-b')).toBeInTheDocument()
    // The artifacts section renders (empty-state flavour with the mock above).
    expect(screen.getByTestId('compare-artifacts-disclosure')).toBeInTheDocument()
  })

  it('surfaces the "comparing with itself" notice on the same-build URL', async () => {
    withArtifactsHandlers()
    renderAt(`/builds/${SEED_BUILD.id}/compare/${SEED_BUILD.id}`)
    await waitFor(() =>
      expect(screen.getByTestId('compare-same-build-notice')).toBeInTheDocument(),
    )
    expect(screen.getByTestId('compare-same-build-notice').textContent).toMatch(/itself/i)
  })

  it('renders the 404 message (never a blank page) for garbage ids', async () => {
    renderAt('/builds/not-a-number/compare/also-not')
    await waitFor(() =>
      expect(screen.getByTestId('compare-invalid-id')).toBeInTheDocument(),
    )
    expect(screen.getByTestId('compare-invalid-id').textContent).toMatch(/build not found/i)
  })

  it('renders the 404 message for negative / non-integer ids too', async () => {
    renderAt('/builds/-1/compare/2.5')
    await waitFor(() =>
      expect(screen.getByTestId('compare-invalid-id')).toBeInTheDocument(),
    )
  })
})

// ── /jobs → /pipelines vocabulary rename (design 66) ─────────────────────────
// Inverted from the prior #538 setup. /pipelines is now the canonical surface
// (one row per discovered pipeline; the YAML file IS the pipeline). The /jobs
// URLs are kept as redirect shims so bookmarks survive. The sidebar carries a
// single "Pipelines" entry and no "Jobs" / "Repositories" rows. The deleted
// /repositories surface has its YAML pane folded into /pipelines/$id.

describe('Pipelines vocabulary rename (design 66)', () => {
  it('redirects /jobs to /pipelines', async () => {
    renderAt('/jobs')
    // Landing page is /pipelines — the canonical heading.
    await waitFor(() =>
      expect(
        screen.getByRole('heading', { level: 1, name: /^pipelines$/i }),
      ).toBeInTheDocument(),
    )
  })

  it('redirects /jobs/$jobId to /pipelines/$pipelineId', async () => {
    renderAt(`/jobs/${SEED_JOB.id}`)
    // Landing page is the pipeline detail. The "Run pipeline" CTA is the
    // unique anchor (the index page has a "New job" CTA instead).
    await waitFor(() =>
      expect(
        screen.getByTestId('pipeline-detail-trigger-btn'),
      ).toBeInTheDocument(),
    )
  })

  it('does NOT render the old /repositories surface (folded into /pipelines/$id)', async () => {
    // The /repositories surface was deleted; its YAML preview now lives on
    // /pipelines/$id (toggleable "Source" panel). The router emits its
    // default not-found page for the URL — we assert the deleted surface's
    // unique anchors (h1 + filter input testid) are gone.
    renderAt('/repositories')
    expect(
      screen.queryByRole('heading', { level: 1, name: /^repositories$/i }),
    ).not.toBeInTheDocument()
    expect(screen.queryByTestId('repositories-filter')).toBeNull()
  })

  it('renders a "Pipelines" link in the sidebar pointing at /pipelines', async () => {
    const { container } = renderAt('/')
    await waitFor(() =>
      expect(screen.getByText(/builds.*24h/i)).toBeInTheDocument(),
    )
    const sidebar = container.querySelector('aside.sidebar')
    expect(sidebar).not.toBeNull()
    const pipelinesLink = Array.from(sidebar!.querySelectorAll('a')).find(
      (a) => a.getAttribute('href') === '/pipelines',
    )
    expect(pipelinesLink).toBeDefined()
    // No standalone /jobs nav row anymore.
    const jobsLink = Array.from(sidebar!.querySelectorAll('a')).find(
      (a) => a.getAttribute('href') === '/jobs',
    )
    expect(jobsLink).toBeUndefined()
    // No /repositories nav row anymore either.
    const reposLink = Array.from(sidebar!.querySelectorAll('a')).find(
      (a) => a.getAttribute('href') === '/repositories',
    )
    expect(reposLink).toBeUndefined()
  })

  it('renders a Source pane on /pipelines/$id with the YAML', async () => {
    renderAt(`/pipelines/${SEED_JOB.id}`)
    await waitFor(() =>
      expect(screen.getByTestId('pipeline-source')).toBeInTheDocument(),
    )
    // Default collapsed — toggle to reveal.
    const toggle = screen.getByTestId('pipeline-source-toggle')
    toggle.click()
    await waitFor(() => {
      // Either the YAML pre OR the empty-state placeholder is rendered,
      // depending on the seeded job's pipelineScript. Both are valid Source
      // states; the test asserts the surface exists, not the seed shape.
      const yaml = screen.queryByTestId('pipeline-source-yaml')
      const empty = screen.queryByTestId('pipeline-source-empty')
      expect(yaml ?? empty).not.toBeNull()
    })
  })
})

// ── Queue ─────────────────────────────────────────────────────────────────────
// Regression guard: the /queue page was reported broken on the live local rig
// after PR #396 (dnd-kit reorder UX). This test renders the route end-to-end
// — including the DndContext + SortableContext + useSortable per row — so any
// hook-order violation, missing dep, or dnd-kit mis-wrapping shows up as a
// render failure here rather than only on the rig.

describe('Queue route /queue', () => {
  it('renders the queue header and a draggable row for a seeded queued task', async () => {
    renderAt('/queue')
    await waitFor(() =>
      expect(screen.getByRole('heading', { name: /build queue/i })).toBeInTheDocument(),
    )
    // Subtitle reflects the seeded total
    await waitFor(() =>
      expect(screen.getByText(/1 task waiting for a worker/i)).toBeInTheDocument(),
    )
    // Row priority chip + drag handle accessible label. Await the row: the
    // page header renders straight from the query `data`, but rows render
    // from the `localOrder` useState mirror that syncs one effect-flush
    // later (queue.tsx) — asserting synchronously after the subtitle races
    // that commit under load.
    expect(
      await screen.findByText(`P${SEED_QUEUE_ENTRY.priority}`),
    ).toBeInTheDocument()
    expect(
      screen.getByRole('button', {
        name: new RegExp(`drag to reorder task ${SEED_QUEUE_ENTRY.taskId}`, 'i'),
      }),
    ).toBeInTheDocument()
    // Drain action is present (and enabled because the queue is non-empty)
    const drainBtn = screen.getByRole('button', { name: /^drain queue$/i })
    expect(drainBtn).toBeInTheDocument()
    expect(drainBtn).not.toBeDisabled()
  })

  // Brand-polish (tick #47) regression: every route sets document.title via the
  // router's meta wiring. The Queue page is the smoke probe — if the title
  // isn't "Queue — Titan" something's wrong with the meta plumbing.
  it('sets document.title to "Queue — Titan"', async () => {
    renderAt('/queue')
    await waitFor(() => expect(document.title).toMatch(/Queue.*Titan/i))
  })
})

// ── Workers ───────────────────────────────────────────────────────────────────
// #580 v3 restyle: card grid replaced by a 6-column table grouped by pool.
// Columns are hostname/id | pool | status pill | last heartbeat | running
// tasks | actions. No CPU/MEM/DISK bars — backend doesn't expose those metrics
// (the old card-era contract is superseded). The "lastSeenAt = null" cell
// still renders an em-dash; the rest of the dash-vs-zero contract no longer
// applies because there are no bars to render zero into.

describe('Workers route /workers', () => {
  it('renders the workers grid header for the seeded fleet', async () => {
    renderAt('/workers')
    await waitFor(() =>
      expect(screen.getByRole('heading', { name: /^workers$/i })).toBeInTheDocument(),
    )
    // The seeded worker name appears in the per-pool table.
    await waitFor(() =>
      expect(screen.getByText(SEED_WORKER_NULL_METRICS.name)).toBeInTheDocument(),
    )
  })

  it('renders "—" (NOT "0%") for the null lastSeen cell', async () => {
    renderAt('/workers')
    await waitFor(() =>
      expect(screen.getByText(SEED_WORKER_NULL_METRICS.name)).toBeInTheDocument(),
    )
    // lastSeenAt is null on the seeded worker → em-dash in the "Last heartbeat"
    // cell. At least one dash must render.
    const dashes = screen.getAllByText('—')
    expect(dashes.length).toBeGreaterThanOrEqual(1)
    // Hard regression: "0%" must NOT appear anywhere — the v3 table dropped
    // the CPU/MEM/DISK bars precisely because the backend lacks metrics, so a
    // "0%" leak would mean we're surfacing a wrong number from somewhere.
    expect(screen.queryByText('0%')).not.toBeInTheDocument()
  })
})

// ── Settings ──────────────────────────────────────────────────────────────────

describe('Settings route /settings', () => {
  it('renders workspace, worker pool, tweaks and About cards', async () => {
    renderAt('/settings')
    await waitFor(() =>
      expect(screen.getByRole('heading', { name: /^settings$/i })).toBeInTheDocument(),
    )
    // The 0.1.0 polish (forge-loop tick #45) replaced the placeholder
    // profile/token/preferences nav with read-only cards. Assert the
    // canonical card titles instead.
    expect(screen.getByRole('heading', { name: /^general$/i })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: /worker pools/i })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: /visual preferences/i })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: /about this titan/i })).toBeInTheDocument()
    // Worker count comes from the mocked /api/v1/workers seed (1 worker).
    await waitFor(() =>
      expect(screen.getByText(/registered workers/i)).toBeInTheDocument(),
    )
    // Version literal is surfaced as v0.1.0 in the About card.
    expect(screen.getByText(/^v0\.1\.0$/)).toBeInTheDocument()
  })

  // Closes #446. PR #424 wired GET /api/v1/info → useServerInfo() → the About
  // card's Commit + Built-at rows. Without an assertion on those exact literals
  // a regression that breaks the binding (or quietly reverts to the hardcoded
  // TITAN_UI_VERSION fallback) would slip through — the demo found the empty
  // em-dashes only at CTO eyeballing time. Pin both fields here.
  it('renders the commit + builtAt from /api/v1/info in the About card', async () => {
    renderAt('/settings')
    await waitFor(() =>
      expect(screen.getByRole('heading', { name: /about this titan/i })).toBeInTheDocument(),
    )
    // Commit short-SHA from SEED_SERVER_INFO.commit — must replace the '—' fallback.
    await waitFor(() =>
      expect(screen.getByText('abc1234')).toBeInTheDocument(),
    )
    // Built-at ISO-8601 string from SEED_SERVER_INFO.builtAt — surfaced verbatim.
    expect(
      screen.getByText('2026-05-23T13:19:33.171073921Z'),
    ).toBeInTheDocument()
  })
})

// ── Profile ───────────────────────────────────────────────────────────────────

describe('Profile route /profile', () => {
  it('renders the signed-in user name and email from OIDC claims', async () => {
    renderAt('/profile', {
      user: {
        access_token: 'fake',
        expired: false,
        profile: {
          preferred_username: 'alice',
          email: 'alice@titan-ci.local',
          iss: 'https://kc.example.com/realms/titan-dev',
          iat: 1_700_000_000,
        },
      } as unknown as AuthState['user'],
    })
    // Post-#587 redesign: page-level h1 is "Profile & settings" (the v3
    // settings-grid layout). Anchor on level:1 so we assert the page heading
    // specifically — not a section sub-heading, not a sidebar nav button.
    // Guards against the "heading never rendered" regression bug class.
    await waitFor(() =>
      expect(
        screen.getByRole('heading', { level: 1, name: /^profile & settings$/i }),
      ).toBeInTheDocument(),
    )
    // The sidebar/header also surfaces the signed-in user's name, so
    // multiple matches are expected — assert "at least one".
    expect(screen.getAllByText('alice').length).toBeGreaterThan(0)
    expect(screen.getAllByText('alice@titan-ci.local').length).toBeGreaterThan(0)
    // Issuer pill — Keycloak issuers collapse to "keycloak / <realm>".
    expect(screen.getByText(/keycloak \/ titan-dev/i)).toBeInTheDocument()
    // Sign-out CTA is present (the header has one too, but the page-level
    // button is the primary one).
    expect(
      screen.getAllByRole('button', { name: /sign out/i }).length,
    ).toBeGreaterThan(0)
  })

  it('shows "Not signed in" when AuthContext has no user', async () => {
    renderAt('/profile', {
      user: null as unknown as AuthState['user'],
      isAuthenticated: false,
    })
    // The __root.tsx beforeLoad guard reads tokenStore (still primed in
    // beforeEach), so we DO mount the route — and the component itself
    // takes the unauthenticated branch. Assert on the subtitle copy.
    await waitFor(() =>
      expect(screen.getByText(/not signed in/i)).toBeInTheDocument(),
    )
  })
})

// ── Onboarding (Forge loop tick #42 — §5.6 "first 90 seconds") ───────────────
// Asserts the wizard renders, step 1 → step 2 navigation works, and that the
// Skip / dismiss action persists the localStorage flag the Welcome card reads.

describe('Onboarding manual route /onboarding/manual', () => {
  beforeEach(() => {
    window.localStorage.removeItem('titan.onboarding.dismissed')
  })

  it('renders the welcome heading and step 1', async () => {
    renderAt('/onboarding/manual')
    await waitFor(() =>
      expect(
        screen.getByRole('heading', { name: /run your first build/i }),
      ).toBeInTheDocument(),
    )
    expect(screen.getByText(/step 1 — connect a repository/i)).toBeInTheDocument()
    expect(screen.getByLabelText(/git url/i)).toBeInTheDocument()
  })

  it('advances from step 1 to step 2 after entering a repo URL', async () => {
    renderAt('/onboarding/manual')
    await waitFor(() =>
      expect(screen.getByLabelText(/git url/i)).toBeInTheDocument(),
    )
    const input = screen.getByLabelText(/git url/i) as HTMLInputElement
    // fireEvent.change drives React's controlled-input setState path; a raw
    // input.dispatchEvent leaves the React state out of sync.
    fireEvent.change(input, { target: { value: 'https://github.com/hadamrd/titan' } })
    const next = screen.getByRole('button', { name: /continue/i })
    fireEvent.click(next)
    await waitFor(() =>
      expect(screen.getByText(/step 2 — when should it run/i)).toBeInTheDocument(),
    )
    expect(screen.getByRole('radiogroup')).toBeInTheDocument()
  })

  it('Skip button persists the dismissed flag in localStorage', async () => {
    renderAt('/onboarding/manual')
    await waitFor(() =>
      expect(screen.getByRole('button', { name: /skip/i })).toBeInTheDocument(),
    )
    screen.getByRole('button', { name: /skip/i }).click()
    await waitFor(() =>
      expect(window.localStorage.getItem('titan.onboarding.dismissed')).toBe('1'),
    )
  })
})
