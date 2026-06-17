/**
 * Adversarial tests for RecentActivityTimeline (closes #710).
 *
 * Covers:
 *   1. 0 events → muted "No recent activity yet" placeholder, no crash.
 *   2. Mixed started/finished builds → rows in newest-first chronological order.
 *   3. Click a row → navigates to /builds/<id> (asserted on URL location).
 *   4. Terminal build with null durationMs → row renders "0s" gracefully, no NaN.
 *   5. Relative-time formatting: "2m ago" / "1h ago" / "yesterday".
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { render, screen, fireEvent, cleanup, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import {
  RouterProvider,
  createRootRoute,
  createRoute,
  createRouter,
  createMemoryHistory,
  Outlet,
} from '@tanstack/react-router'

import {
  RecentActivityTimeline,
  formatRelative,
} from '../components/RecentActivityTimeline'
import type { AgentEventDto, BuildDto } from '../api/types'

// ── Fixtures ────────────────────────────────────────────────────────────────

function makeBuild(overrides: Partial<BuildDto>): BuildDto {
  return {
    id: 1,
    jobId: 1,
    buildNumber: 1,
    status: 'SUCCESS',
    triggeredBy: null,
    triggerType: null,
    queuedAt: null,
    startedAt: null,
    finishedAt: null,
    durationMs: null,
    errorMessage: null,
    failureSummary: null,
    ...overrides,
  }
}

// ── Harness ─────────────────────────────────────────────────────────────────

/**
 * Mount the timeline inside a memory-history router so {@code <Link>} routing
 * is real — a click resolves through the same code path production uses,
 * which is the only way to validate the "navigate to /builds/<id>" contract
 * without leaking on a Link href string mismatch.
 */
function mountTimeline(builds: BuildDto[], events: AgentEventDto[] = []) {
  const rootRoute = createRootRoute({
    component: () => <Outlet />,
  })
  const homeRoute = createRoute({
    getParentRoute: () => rootRoute,
    path: '/',
    component: () => (
      <RecentActivityTimeline builds={builds} events={events} />
    ),
  })
  const buildRoute = createRoute({
    getParentRoute: () => rootRoute,
    path: '/builds/$buildId',
    component: function BuildStub() {
      const params = buildRoute.useParams()
      return <div data-testid="build-stub">build-{params.buildId}</div>
    },
  })
  const history = createMemoryHistory({ initialEntries: ['/'] })
  const router = createRouter({
    routeTree: rootRoute.addChildren([homeRoute, buildRoute]),
    history,
  })
  const qc = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  const utils = render(
    <QueryClientProvider client={qc}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  return { ...utils, router, history }
}

// ── Lifecycle ───────────────────────────────────────────────────────────────

beforeEach(() => {
  vi.useFakeTimers({ shouldAdvanceTime: true })
  // Pin "now" so relative-time assertions are deterministic.
  vi.setSystemTime(new Date('2026-05-24T10:00:00Z'))
})

afterEach(() => {
  vi.useRealTimers()
  cleanup()
})

// ── Tests ───────────────────────────────────────────────────────────────────

describe('RecentActivityTimeline (#710)', () => {
  it('renders the muted empty-state placeholder for zero events', async () => {
    mountTimeline([])
    await waitFor(() =>
      expect(screen.getByTestId('recent-activity-timeline')).toBeInTheDocument(),
    )
    expect(
      screen.getByTestId('recent-activity-timeline-empty').textContent,
    ).toContain('No recent activity yet')
  })

  it('renders mixed started/finished rows in newest-first order', async () => {
    const builds: BuildDto[] = [
      // Finished 9:50 (10m ago)
      makeBuild({
        id: 11,
        jobId: 1,
        buildNumber: 1,
        status: 'SUCCESS',
        startedAt: '2026-05-24T09:47:00Z',
        finishedAt: '2026-05-24T09:50:00Z',
        durationMs: 180_000,
      }),
      // Running, started 9:58 (2m ago) — should come BEFORE the 9:50 finish.
      makeBuild({
        id: 12,
        jobId: 1,
        buildNumber: 2,
        status: 'RUNNING',
        startedAt: '2026-05-24T09:58:00Z',
        finishedAt: null,
        durationMs: null,
      }),
      // Failed 9:55 (5m ago)
      makeBuild({
        id: 13,
        jobId: 2,
        buildNumber: 1,
        status: 'FAILED',
        startedAt: '2026-05-24T09:51:00Z',
        finishedAt: '2026-05-24T09:55:00Z',
        durationMs: 240_000,
      }),
      // Cancelled 9:40 (20m ago)
      makeBuild({
        id: 14,
        jobId: 2,
        buildNumber: 2,
        status: 'ABORTED',
        startedAt: '2026-05-24T09:35:00Z',
        finishedAt: '2026-05-24T09:40:00Z',
        durationMs: 300_000,
      }),
      // Finished 8:50 (1h 10m ago)
      makeBuild({
        id: 15,
        jobId: 3,
        buildNumber: 1,
        status: 'SUCCESS',
        startedAt: '2026-05-24T08:45:00Z',
        finishedAt: '2026-05-24T08:50:00Z',
        durationMs: 300_000,
      }),
    ]
    mountTimeline(builds)
    await waitFor(() =>
      expect(
        screen.getByTestId('recent-activity-timeline'),
      ).toBeInTheDocument(),
    )

    // 5 rows render.
    const rows = screen.getAllByTestId(/^timeline-row-/)
    expect(rows).toHaveLength(5)

    // Order: newest first by event ts → 12 (9:58 start), 13 (9:55 finish),
    // 11 (9:50 finish), 14 (9:40 finish), 15 (8:50 finish).
    const orderedIds = rows.map((r) =>
      r.getAttribute('data-testid')!.replace('timeline-row-', ''),
    )
    expect(orderedIds).toEqual([
      'b-12-start',
      'b-13-fin',
      'b-11-fin',
      'b-14-fin',
      'b-15-fin',
    ])

    // Verb formatting on terminal rows.
    expect(screen.getByTestId('timeline-row-b-13-fin').textContent).toContain(
      'failed',
    )
    expect(screen.getByTestId('timeline-row-b-11-fin').textContent).toContain(
      'succeeded',
    )
    expect(screen.getByTestId('timeline-row-b-14-fin').textContent).toContain(
      'cancelled',
    )
    // Running build renders "started" + 2m ago.
    const runRow = screen.getByTestId('timeline-row-b-12-start')
    expect(runRow.textContent).toContain('started')
    expect(runRow.textContent).toContain('2m ago')
  })

  it('clicking a row navigates to /builds/<id>', async () => {
    const builds: BuildDto[] = [
      makeBuild({
        id: 77,
        jobId: 1,
        buildNumber: 4,
        status: 'SUCCESS',
        startedAt: '2026-05-24T09:50:00Z',
        finishedAt: '2026-05-24T09:55:00Z',
        durationMs: 60_000,
      }),
    ]
    const { history } = mountTimeline(builds)
    const row = await screen.findByTestId('timeline-row-b-77-fin')
    fireEvent.click(row)
    // Router updates synchronously for memory history navigation.
    await screen.findByTestId('build-stub')
    expect(history.location.pathname).toBe('/builds/77')
  })

  it('renders gracefully when finished build has null durationMs (no NaN)', async () => {
    const builds: BuildDto[] = [
      makeBuild({
        id: 88,
        jobId: 1,
        buildNumber: 9,
        status: 'SUCCESS',
        startedAt: '2026-05-24T09:50:00Z',
        finishedAt: '2026-05-24T09:55:00Z',
        durationMs: null,
      }),
    ]
    mountTimeline(builds)
    const row = await screen.findByTestId('timeline-row-b-88-fin')
    // Spec says: render gracefully — use 0s.
    expect(row.textContent).toContain('0s')
    expect(row.textContent).not.toContain('NaN')
    expect(row.textContent).toContain('succeeded')
  })

  it('interleaves agent join/leave events with builds in newest-first order (#714)', async () => {
    // Two builds plus two agent events; assertions pin the merged ordering so
    // a regression in the merge comparator surfaces immediately.
    const builds: BuildDto[] = [
      // Finished 9:55 (5m ago)
      makeBuild({
        id: 21,
        jobId: 1,
        buildNumber: 1,
        status: 'SUCCESS',
        startedAt: '2026-05-24T09:50:00Z',
        finishedAt: '2026-05-24T09:55:00Z',
        durationMs: 300_000,
      }),
      // Finished 9:45 (15m ago)
      makeBuild({
        id: 22,
        jobId: 1,
        buildNumber: 2,
        status: 'FAILED',
        startedAt: '2026-05-24T09:40:00Z',
        finishedAt: '2026-05-24T09:45:00Z',
        durationMs: 300_000,
      }),
    ]
    const events: AgentEventDto[] = [
      // worker-1 joined 9:58 (2m ago) — newest
      {
        id: 101,
        agentId: 'worker-1',
        agentName: 'worker-1',
        type: 'JOINED',
        occurredAt: '2026-05-24T09:58:00Z',
      },
      // worker-2 left 9:50 (10m ago) — sits between the two builds
      {
        id: 102,
        agentId: 'worker-2',
        agentName: 'worker-2',
        type: 'LEFT',
        occurredAt: '2026-05-24T09:50:00Z',
      },
    ]
    mountTimeline(builds, events)
    await waitFor(() =>
      expect(
        screen.getByTestId('recent-activity-timeline'),
      ).toBeInTheDocument(),
    )

    const rows = screen.getAllByTestId(/^timeline-row-/)
    const orderedIds = rows.map((r) =>
      r.getAttribute('data-testid')!.replace('timeline-row-', ''),
    )
    // Expected order by ts desc:
    //   9:58 worker-1 joined  → a-101-join
    //   9:55 build 21 fin     → b-21-fin
    //   9:50 worker-2 left    → a-102-left
    //   9:45 build 22 fin     → b-22-fin
    expect(orderedIds).toEqual([
      'a-101-join',
      'b-21-fin',
      'a-102-left',
      'b-22-fin',
    ])
    // Sanity-check phrasing on a join row.
    expect(screen.getByTestId('timeline-row-a-101-join').textContent).toContain(
      'worker-1 joined',
    )
    expect(screen.getByTestId('timeline-row-a-102-left').textContent).toContain(
      'worker-2 left',
    )
  })

  it('formatRelative covers minutes, hours, and yesterday buckets', () => {
    // System time pinned to 2026-05-24T10:00:00Z above.
    expect(formatRelative('2026-05-24T09:58:00Z')).toBe('2m ago')
    expect(formatRelative('2026-05-24T09:00:00Z')).toBe('1h ago')
    // 26h ago → "yesterday".
    expect(formatRelative('2026-05-23T08:00:00Z')).toBe('yesterday')
    // 3 days ago → "3d ago".
    expect(formatRelative('2026-05-21T10:00:00Z')).toBe('3d ago')
    // Sub-5s clamps to "just now".
    expect(formatRelative('2026-05-24T09:59:58Z')).toBe('just now')
    // Null / unparseable → em-dash.
    expect(formatRelative(null)).toBe('—')
    expect(formatRelative('not-a-date')).toBe('—')
  })
})
