/**
 * Adversarial tests for the /overview redesign (#1189, was #580).
 *
 * Covered:
 *   - Page mounts inside the shared <PageContainer> frame (default variant,
 *     max-w-screen-xl — the token AC §1 names) — H1.
 *   - 4 KPI tiles render with mocked counts (Pipelines / Builds 24h / Fail rate
 *     / Workers).
 *   - Recent activity card renders rows for terminal builds with status pills.
 *   - Region four-state matrix (H4): loading → Skeleton; empty → "No builds yet"
 *     copy + NO bare table rows; error → a retry control; populated → rows.
 *   - Hooks are unconditional (PR #343): one query in `error` while others have
 *     `data` still renders the healthy regions and shows a retry ONLY on the
 *     failed region — no whole-page crash/blank.
 *   - Idle controller: zero terminal builds renders "—" / "no data", NEVER a
 *     "0% failure" or "100% success" claim.
 */
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
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

import { setAccessToken } from '../auth/tokenStore'
import { Route as OverviewRoute, RecentActivityCard } from '../routes/index'
import type {
  ActivityItemDto,
  ActivityPage,
  JobsPage,
  StatsDto,
  WorkersPage,
} from '../api/types'
import { setupFetchMock, resetFetchMock } from './msw-handlers'

// ── Fixtures ────────────────────────────────────────────────────────────────

const STATS_LIVE: StatsDto = {
  buildsToday: 42,
  successRate: 0.9, // → fail rate 10%
  medianDurationMs: 180_000,
}

const STATS_IDLE: StatsDto = {
  buildsToday: 0,
  successRate: 0,
  medianDurationMs: 0,
}

const JOBS_PAGE: JobsPage = {
  items: [
    {
      id: 1,
      fullName: 'org/green',
      displayName: 'green-job',
      folderPath: null,
      enabled: true,
      createdAt: '2026-05-20T08:00:00Z',
      updatedAt: '2026-05-20T08:00:00Z',
      lastBuild: {
        id: 9001,
        buildNumber: 7,
        status: 'SUCCESS',
        durationMs: 60_000,
        finishedAt: '2026-05-20T09:01:00Z',
      },
    },
    {
      id: 2,
      fullName: 'org/red',
      displayName: 'red-job',
      folderPath: null,
      enabled: true,
      createdAt: '2026-05-20T08:00:00Z',
      updatedAt: '2026-05-20T08:00:00Z',
      lastBuild: {
        id: 9002,
        buildNumber: 3,
        status: 'FAILED',
        durationMs: 120_000,
        finishedAt: '2026-05-20T09:02:00Z',
      },
    },
    {
      id: 3,
      fullName: 'org/never',
      displayName: 'never-run-job',
      folderPath: null,
      enabled: true,
      createdAt: '2026-05-20T08:00:00Z',
      updatedAt: '2026-05-20T08:00:00Z',
    },
  ],
  total: 3,
  offset: 0,
  limit: 50,
}

const WORKERS_PAGE: WorkersPage = {
  items: [
    {
      id: 'a',
      name: 'alpha',
      state: 'ONLINE',
      pool: 'default',
      labels: [],
      currentTasks: 0,
      maxConcurrent: 4,
      lastSeenAt: '2026-05-24T09:59:30Z',
      registeredAt: '2026-05-20T08:00:00Z',
    },
    {
      id: 'b',
      name: 'beta',
      state: 'OFFLINE',
      pool: 'default',
      labels: [],
      currentTasks: 0,
      maxConcurrent: 4,
      lastSeenAt: '2026-05-24T09:00:00Z',
      registeredAt: '2026-05-20T08:00:00Z',
    },
  ],
  total: 2,
  offset: 0,
  limit: 200,
}

function activityPage(items: ActivityItemDto[]): ActivityPage {
  return { items, nextCursor: null }
}

const TERMINAL_ACTIVITY: ActivityItemDto[] = [
  {
    id: 'a-1',
    type: 'build.terminal',
    ts: '2026-05-24T09:55:00Z',
    jobName: 'org/red',
    buildId: 9002,
    status: 'FAILED',
    durationMs: 120_000,
  },
  {
    id: 'a-2',
    type: 'build.terminal',
    ts: '2026-05-24T09:50:00Z',
    jobName: 'org/green',
    buildId: 9001,
    status: 'SUCCESS',
    durationMs: 60_000,
  },
]

// ── Handlers ────────────────────────────────────────────────────────────────

interface MatchResult { status: number; body: unknown }
type Handler = (url: URL, method: string) => MatchResult | null

function overviewHandlers(opts: {
  jobs?: JobsPage
  stats?: StatsDto
  workers?: WorkersPage
  activity?: ActivityPage
}): Handler[] {
  return [
    (url, method) => {
      if (method !== 'GET') return null
      if (url.pathname !== '/api/v1/jobs') return null
      return { status: 200, body: opts.jobs ?? JOBS_PAGE }
    },
    (url, method) => {
      if (method !== 'GET') return null
      if (url.pathname !== '/api/v1/stats') return null
      return { status: 200, body: opts.stats ?? STATS_LIVE }
    },
    (url, method) => {
      if (method !== 'GET') return null
      if (url.pathname !== '/api/v1/workers') return null
      return { status: 200, body: opts.workers ?? WORKERS_PAGE }
    },
    (url, method) => {
      if (method !== 'GET') return null
      if (url.pathname !== '/api/v1/activity') return null
      return { status: 200, body: opts.activity ?? activityPage(TERMINAL_ACTIVITY) }
    },
    // /api/v1/builds — fed to the RecentActivityTimeline (#710). Defaults to
    // empty; overview-v3 tests don't assert on the timeline (separate spec).
    (url, method) => {
      if (method !== 'GET') return null
      if (url.pathname !== '/api/v1/builds') return null
      return {
        status: 200,
        body: { items: [], total: 0, offset: 0, limit: 20 },
      }
    },
  ]
}

/** A handler that fails one endpoint with a 500 so its query enters `error`. */
function failingHandler(pathname: string): Handler {
  return (url, method) => {
    if (method !== 'GET') return null
    if (url.pathname !== pathname) return null
    return {
      status: 500,
      body: {
        type: 'about:blank',
        title: 'Internal Server Error',
        status: 500,
        detail: 'boom',
        instance: null,
      },
    }
  }
}

/** Build the router + QueryClient and render — assumes fetch is already mocked. */
function renderOverview() {
  setAccessToken('test-token')
  const rootRoute = createRootRoute({ component: () => <Outlet /> })
  const overviewRoute = createRoute({
    getParentRoute: () => rootRoute,
    path: '/',
    component: OverviewRoute.options.component,
  })
  const router = createRouter({
    routeTree: rootRoute.addChildren([overviewRoute]),
    history: createMemoryHistory({ initialEntries: ['/'] }),
  })
  const qc = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return render(
    <QueryClientProvider client={qc}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
}

function mountOverview(handlers: Handler[]) {
  setupFetchMock(handlers)
  return renderOverview()
}

// ── Lifecycle ───────────────────────────────────────────────────────────────

beforeEach(() => {
  setAccessToken(null)
})

afterEach(() => {
  resetFetchMock()
  setAccessToken(null)
})

// ── Tests ───────────────────────────────────────────────────────────────────

describe('/overview redesign (#1189)', () => {
  it('mounts inside the shared PageContainer frame at the AC width (H1)', async () => {
    mountOverview(overviewHandlers({}))
    await waitFor(() => expect(screen.getByTestId('overview-page')).toBeInTheDocument())
    // The framed wrapper centres + caps width — NOT a bare div hugging the
    // top-left of the canvas. AC §1 names max-w-screen-xl (the `default`
    // variant); `wide`/2xl is reserved for data-dense tables (Page.tsx).
    const frame = screen.getByTestId('overview-page')
    expect(frame.className).toContain('mx-auto')
    expect(frame.className).toContain('max-w-screen-xl')
  })

  it('renders 4 KPI tiles with mocked metric values', async () => {
    mountOverview(overviewHandlers({}))

    await waitFor(() =>
      expect(screen.getByTestId('kpi-jobs').textContent).toContain('3'),
    )
    expect(screen.getByTestId('kpi-builds').textContent).toContain('42')
    // 1 - 0.9 = 0.1 → 10%
    expect(screen.getByTestId('kpi-fail-rate').textContent).toContain('10%')
    // 2 workers, 1 OFFLINE → active = 1
    expect(screen.getByTestId('kpi-workers').textContent).toContain('1')
  })

  it('exactly one dominant primary action when populated (H5)', async () => {
    const { container } = mountOverview(overviewHandlers({}))
    await waitFor(() =>
      expect(screen.getByTestId('overview-primary-action')).toBeInTheDocument(),
    )
    // The OnboardingWelcomeCard (which carries its own primary) is suppressed
    // once builds exist, so the header CTA is the *only* .btn-primary.
    expect(container.querySelectorAll('.btn-primary')).toHaveLength(1)
  })

  it('Recent activity renders one row per terminal build (populated state)', async () => {
    mountOverview(overviewHandlers({}))

    await waitFor(() =>
      expect(screen.getByTestId('activity-row-a-1')).toBeInTheDocument(),
    )
    expect(screen.getByTestId('activity-row-a-2')).toBeInTheDocument()

    // Status pill (StatusBadge) shows the literal status text.
    const failRow = screen.getByTestId('activity-row-a-1')
    expect(failRow.textContent).toContain('FAILED')
    const okRow = screen.getByTestId('activity-row-a-2')
    expect(okRow.textContent).toContain('SUCCESS')
  })

  it('layout uses the class system, not inline style attributes (H3)', async () => {
    // H3 hard constraint: spacing/layout comes from the Tailwind scale / CSS
    // classes, NEVER ad-hoc `style={{…}}` with raw pixel values — the recurring
    // critic finding on this PR. Lock it so it can't regress: the activity rows
    // carry the semantic `.ov-activity-row` class and expose no inline style,
    // and neither do the four metric tiles.
    mountOverview(overviewHandlers({}))
    await waitFor(() =>
      expect(screen.getByTestId('activity-row-a-1')).toBeInTheDocument(),
    )
    const row = screen.getByTestId('activity-row-a-1')
    expect(row).toHaveClass('ov-activity-row')
    expect(row.getAttribute('style')).toBeNull()
    for (const id of ['kpi-jobs', 'kpi-builds', 'kpi-fail-rate', 'kpi-workers']) {
      expect(screen.getByTestId(id).getAttribute('style')).toBeNull()
    }
  })

  it('Recent activity loading state shows a Skeleton, not a bare header (H4)', () => {
    // Component-level test: the loading branch renders only Skeletons (no
    // router-dependent Link), so it asserts deterministically without a mount.
    const { container } = render(
      <RecentActivityCard items={[]} isLoading hasError={false} onRetry={() => {}} />,
    )
    const card = screen.getByTestId('overview-recent-activity')
    // Implementation-agnostic: assert via the Skeleton's stable role/testid
    // contract, NOT the `.skeleton` CSS class (which is free to change).
    expect(card.querySelectorAll('[data-testid="skeleton"]').length).toBeGreaterThan(0)
    // No data rows, no empty copy — purely loading.
    expect(container.querySelector('[data-testid^="activity-row-"]')).toBeNull()
    expect(screen.queryByTestId('overview-recent-activity-empty')).toBeNull()
  })

  it('empty activity feed renders "No builds yet" copy + no data rows (H4)', async () => {
    const { container } = mountOverview(overviewHandlers({ activity: activityPage([]) }))

    await waitFor(() =>
      expect(screen.getByTestId('overview-recent-activity-empty')).toBeInTheDocument(),
    )
    const card = screen.getByTestId('overview-recent-activity')
    expect(card.textContent).toContain('No builds yet')
    // NOT a bare header with phantom rows.
    expect(container.querySelector('[data-testid^="activity-row-"]')).toBeNull()
  })

  it('Recent activity error state renders a retry affordance (H4)', async () => {
    mountOverview([
      failingHandler('/api/v1/activity'),
      ...overviewHandlers({}),
    ])
    await waitFor(() =>
      expect(
        screen.getByTestId('overview-recent-activity-retry'),
      ).toBeInTheDocument(),
    )
  })

  it('one failed hook does not blank the page — healthy regions render, only the failed region shows retry (PR #343)', async () => {
    // Workers query 500s; jobs/stats/activity are healthy.
    mountOverview([failingHandler('/api/v1/workers'), ...overviewHandlers({})])

    // Page frame is intact (no whole-page crash/blank).
    await waitFor(() => expect(screen.getByTestId('overview-page')).toBeInTheDocument())
    // Healthy regions still render their data.
    await waitFor(() =>
      expect(screen.getByTestId('kpi-jobs').textContent).toContain('3'),
    )
    expect(screen.getByTestId('activity-row-a-1')).toBeInTheDocument()
    // The failed Workers tile shows a retry; healthy tiles do NOT.
    await waitFor(() =>
      expect(screen.getByTestId('kpi-workers-retry')).toBeInTheDocument(),
    )
    expect(screen.queryByTestId('kpi-jobs-retry')).toBeNull()
  })

  it('idle controller renders "—"/"no data" and NEVER "0% failure" or "100% success"', async () => {
    mountOverview(overviewHandlers({ stats: STATS_IDLE }))
    await waitFor(() =>
      expect(screen.getByTestId('kpi-fail-rate').textContent).toContain('—'),
    )
    const failTile = screen.getByTestId('kpi-fail-rate')
    // The explicit no-signal caption, not a fabricated percentage.
    expect(failTile.textContent).toContain('no data')
    expect(failTile.textContent).not.toContain('0%')
    expect(failTile.textContent).not.toContain('100%')
    // The zero-builds tile shows 0 (a real count), not "—".
    expect(screen.getByTestId('kpi-builds').textContent).toContain('0')
  })

  it('fresh instance: header CTA is suppressed and the sole primary belongs to OnboardingWelcomeCard (H5)', async () => {
    // Fresh = empty activity + zero builds + no query errors → isFreshInstance.
    window.localStorage.clear()
    const { container } = mountOverview(
      overviewHandlers({ activity: activityPage([]), stats: STATS_IDLE }),
    )

    // The OnboardingWelcomeCard surfaces once it confirms a fresh controller.
    await waitFor(() =>
      expect(screen.getByRole('region', { name: /onboarding/i })).toBeInTheDocument(),
    )
    // (a) Header CTA is suppressed — no competing primary.
    expect(screen.queryByTestId('overview-primary-action')).toBeNull()
    // (b) Exactly one .btn-primary on the page, and it lives in the onboarding card.
    const primaries = container.querySelectorAll('.btn-primary')
    expect(primaries).toHaveLength(1)
    const onboarding = screen.getByRole('region', { name: /onboarding/i })
    expect(onboarding.querySelector('.btn-primary')).toBe(primaries[0])
  })

  it('errored stats on an established controller is NOT treated as fresh — header CTA stays, no double primary (H5 + sev2/correctness)', async () => {
    // stats 500s and the activity feed is empty. `stats.data` is undefined so a
    // naive `buildsToday ?? 0 === 0` would read as "fresh" and suppress the
    // header CTA — the regression this guards. With the !isError gate the page
    // treats this as an established controller in a degraded state.
    window.localStorage.clear()
    const { container } = mountOverview([
      failingHandler('/api/v1/stats'),
      ...overviewHandlers({ activity: activityPage([]) }),
    ])

    // Header CTA is the single dominant primary (NOT suppressed).
    await waitFor(() =>
      expect(screen.getByTestId('overview-primary-action')).toBeInTheDocument(),
    )
    // OnboardingWelcomeCard must NOT appear on an errored controller…
    expect(screen.queryByRole('region', { name: /onboarding/i })).toBeNull()
    // …so there is exactly one primary, and it is the header CTA.
    const primaries = container.querySelectorAll('.btn-primary')
    expect(primaries).toHaveLength(1)
    expect(primaries[0]).toBe(screen.getByTestId('overview-primary-action'))
  })

  it('slow stats (still loading) on an established controller is NOT treated as fresh — onboarding hidden, header CTA stays (sev2/correctness: !stats.isLoading)', async () => {
    // Regression for the transient false-positive: the activity feed can settle
    // empty BEFORE a slow /stats request resolves. During that window
    // `stats.data` is undefined so a `buildsToday ?? 0 === 0` gate (without a
    // !stats.isLoading guard) reads as "fresh", flickers the header CTA off and
    // surfaces the OnboardingWelcomeCard primary on an established controller.
    window.localStorage.clear()
    // Baseline mock (also captures the real fetch so afterEach restores it)…
    setupFetchMock(overviewHandlers({ activity: activityPage([]) }))
    const baseline = globalThis.fetch
    // …then wrap it so ONLY /api/v1/stats hangs forever → its query stays in
    // `isLoading`; jobs/activity/workers resolve normally.
    globalThis.fetch = ((input: RequestInfo | URL, init?: RequestInit) => {
      const raw =
        typeof input === 'string'
          ? input
          : input instanceof URL
            ? input.href
            : (input as Request).url
      if (new URL(raw, 'http://localhost:8080').pathname === '/api/v1/stats') {
        return new Promise<Response>(() => {}) // never resolves
      }
      return (baseline as typeof fetch)(input, init)
    }) as typeof globalThis.fetch

    const { container } = renderOverview()

    // Wait until the (resolved) jobs tile proves the page rendered while stats
    // is still in-flight (the exact "activity settled, stats pending" window).
    await waitFor(() =>
      expect(screen.getByTestId('kpi-jobs').textContent).toContain('3'),
    )
    // The header CTA is the single dominant primary — NOT suppressed.
    expect(screen.getByTestId('overview-primary-action')).toBeInTheDocument()
    // The onboarding card must NOT appear while stats is still loading…
    expect(screen.queryByRole('region', { name: /onboarding/i })).toBeNull()
    // …so exactly one primary survives (H5), and it is the header CTA.
    const primaries = container.querySelectorAll('.btn-primary')
    expect(primaries).toHaveLength(1)
    expect(primaries[0]).toBe(screen.getByTestId('overview-primary-action'))
  })
})
