/**
 * Adversarial tests for the /queue "Recent activity" empty-state card (#523).
 *
 * Covered:
 *  - Live queue empty + 3 archive rows → "No tasks currently queued" renders
 *    AND all 3 archive rows render in the activity table.
 *  - Live queue non-empty (1 task) + 5 archive rows → the activity card is NOT
 *    rendered (current behaviour preserved; only the live queue shows).
 *  - Live queue empty + archive empty → DUAL empty state: both the live-queue
 *    empty line ("No tasks currently queued") and the recent-activity empty
 *    line ("No recent activity yet") render — the operator can tell the
 *    controller is genuinely idle, not broken.
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
import { Route as QueueRoute } from '../routes/queue'
import type {
  QueueEntryDto,
  QueuePage,
  RecentTaskDto,
} from '../api/types'
import { setupFetchMock, resetFetchMock } from './msw-handlers'

// ── Seeds ────────────────────────────────────────────────────────────────────

const EMPTY_QUEUE: QueuePage = { items: [], total: 0, offset: 0, limit: 200 }

const ONE_TASK_QUEUE_ENTRY: QueueEntryDto = {
  taskId: 9001,
  buildId: 42,
  jobId: 1,
  jobName: 'main-pipeline',
  queuedAt: '2026-05-23T10:00:00Z',
  waitingMs: 4_000,
  priority: 5,
  requestedLabels: 'linux',
}

const ONE_TASK_QUEUE: QueuePage = {
  items: [ONE_TASK_QUEUE_ENTRY],
  total: 1,
  offset: 0,
  limit: 200,
}

function mkArchive(n: number): RecentTaskDto[] {
  return Array.from({ length: n }, (_, i) => ({
    taskId: 1000 + i,
    buildId: 42 + i,
    jobId: 1,
    jobName: `pipeline-${i}`,
    nodeId: `node-${i}`,
    type: 'EXECUTE_COMMAND',
    status: i % 2 === 0 ? 'COMPLETED' : 'FAILED',
    queuedAt: '2026-05-23T09:00:00Z',
    completedAt: `2026-05-23T09:0${i}:30Z`,
    priority: 5,
    requestedLabels: 'default',
  }))
}

// ── Handlers ─────────────────────────────────────────────────────────────────

interface MatchResult { status: number; body: unknown }
type Handler = (url: URL, method: string) => MatchResult | null

function queueHandler(page: QueuePage): Handler {
  return (url, method) => {
    if (method !== 'GET') return null
    if (url.pathname !== '/api/v1/queue') return null
    return { status: 200, body: page }
  }
}

function recentHandler(rows: RecentTaskDto[]): Handler {
  return (url, method) => {
    if (method !== 'GET') return null
    if (url.pathname !== '/api/v1/queue/recent') return null
    return { status: 200, body: rows }
  }
}

// Swallow unrelated reads (the queue page's polling has no other deps, but
// adding a permissive catch-all keeps unrelated fetches from blowing up the
// test if the route ever sprouts a new query).

// ── Mount harness ────────────────────────────────────────────────────────────

function mountQueue(handlers: Handler[]) {
  setupFetchMock(handlers)
  setAccessToken('test-token')

  const rootRoute = createRootRoute({
    component: () => <Outlet />,
  })
  const queueRoute = createRoute({
    getParentRoute: () => rootRoute,
    path: '/queue',
    component: QueueRoute.options.component,
  })
  const router = createRouter({
    routeTree: rootRoute.addChildren([queueRoute]),
    history: createMemoryHistory({ initialEntries: ['/queue'] }),
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

// ── Lifecycle ────────────────────────────────────────────────────────────────

beforeEach(() => {
  setAccessToken(null)
})

afterEach(() => {
  resetFetchMock()
  setAccessToken(null)
})

// ── Tests ────────────────────────────────────────────────────────────────────

describe('Queue page — Recent activity (#523)', () => {
  it('empty queue + 3 archive rows → renders "No tasks currently queued" + 3 archive rows', async () => {
    const archive = mkArchive(3)
    mountQueue([queueHandler(EMPTY_QUEUE), recentHandler(archive)])

    // Live-queue empty line.
    await waitFor(() =>
      expect(screen.getByTestId('queue-empty')).toHaveTextContent(
        /no tasks currently queued/i,
      ),
    )

    // Recent-activity card present.
    await waitFor(() =>
      expect(screen.getByTestId('recent-activity-card')).toBeInTheDocument(),
    )

    // Each archive row is rendered (3 of 3).
    await waitFor(() => {
      expect(screen.getByTestId('recent-row-1000')).toBeInTheDocument()
      expect(screen.getByTestId('recent-row-1001')).toBeInTheDocument()
      expect(screen.getByTestId('recent-row-1002')).toBeInTheDocument()
    })

    // And the archive empty-state did NOT render (we have rows).
    expect(screen.queryByTestId('recent-activity-empty')).toBeNull()
  })

  it('non-empty queue + 5 archive rows → activity card is NOT rendered', async () => {
    const archive = mkArchive(5)
    mountQueue([queueHandler(ONE_TASK_QUEUE), recentHandler(archive)])

    // Wait for the live queue row to settle so we are past the loading state.
    await waitFor(() =>
      expect(
        screen.getByRole('heading', { name: /build queue/i }),
      ).toBeInTheDocument(),
    )

    // Card MUST NOT render — current behaviour preserved.
    await waitFor(() => {
      expect(screen.queryByTestId('queue-empty')).toBeNull()
    })
    await waitFor(() => {
      expect(screen.queryByTestId('recent-activity-card')).toBeNull()
    })
    expect(screen.queryByTestId('recent-row-1000')).toBeNull()
  })

  it('empty queue + empty archive → dual empty state (queue empty + activity empty)', async () => {
    mountQueue([queueHandler(EMPTY_QUEUE), recentHandler([])])

    // Live-queue empty line.
    await waitFor(() =>
      expect(screen.getByTestId('queue-empty')).toHaveTextContent(
        /no tasks currently queued/i,
      ),
    )

    // Recent-activity card present AND its empty body rendered.
    await waitFor(() =>
      expect(screen.getByTestId('recent-activity-card')).toBeInTheDocument(),
    )
    await waitFor(() =>
      expect(screen.getByTestId('recent-activity-empty')).toHaveTextContent(
        /no recent activity yet/i,
      ),
    )
  })
})
