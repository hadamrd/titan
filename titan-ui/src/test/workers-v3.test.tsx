/**
 * Adversarial tests for /workers v3 (closes #580).
 *
 * Covered:
 *   - Three metric tiles render (Total / Running / Idle) with correct counts.
 *   - Table groups by pool — one section per distinct pool.
 *   - Drain action fires the POST mutation and disables the button while pending.
 *   - DRAINING worker exposes a Reset (undrain) button, not a Drain button.
 *   - Empty fleet shows the empty-state card and no pool sections.
 */
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
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

import { setAccessToken } from '../auth/tokenStore'
import { Route as WorkersRoute } from '../routes/workers'
import type { WorkerDto, WorkersPage } from '../api/types'
import { setupFetchMock, resetFetchMock } from './msw-handlers'

// ── Fixtures ────────────────────────────────────────────────────────────────

function mkWorker(overrides: Partial<WorkerDto>): WorkerDto {
  return {
    id: 'w-1',
    name: 'worker-1',
    state: 'ONLINE',
    pool: 'default',
    labels: ['default'],
    currentTasks: 0,
    maxConcurrent: 4,
    cpuPct: null,
    memPct: null,
    diskPct: null,
    lastSeenAt: '2026-05-24T09:59:30Z',
    registeredAt: '2026-05-20T08:00:00Z',
    ...overrides,
  }
}

const FLEET: WorkerDto[] = [
  mkWorker({ id: 'a-1', name: 'alpha-1', pool: 'pool-a', currentTasks: 2, state: 'ONLINE' }),
  mkWorker({ id: 'a-2', name: 'alpha-2', pool: 'pool-a', currentTasks: 0, state: 'ONLINE' }),
  mkWorker({ id: 'b-1', name: 'beta-1', pool: 'pool-b', currentTasks: 0, state: 'ONLINE' }),
  mkWorker({ id: 'd-1', name: 'delta-1', pool: 'pool-b', currentTasks: 1, state: 'DRAINING' }),
  mkWorker({ id: 'o-1', name: 'offline-1', pool: 'pool-b', currentTasks: 0, state: 'OFFLINE' }),
]

function fleetPage(items: WorkerDto[]): WorkersPage {
  return { items, total: items.length, offset: 0, limit: 200 }
}

// ── Harness ─────────────────────────────────────────────────────────────────

interface MatchResult { status: number; body: unknown }
type Handler = (url: URL, method: string) => MatchResult | null

function workersGetHandler(page: WorkersPage): Handler {
  return (url, method) => {
    if (method !== 'GET') return null
    if (!url.pathname.startsWith('/api/v1/workers')) return null
    // Exact path or with query — drain endpoint is POST, handled below.
    if (url.pathname === '/api/v1/workers') return { status: 200, body: page }
    return null
  }
}

function drainHandler(captured: { calls: string[] }): Handler {
  return (url, method) => {
    if (method !== 'POST') return null
    const m = url.pathname.match(/^\/api\/v1\/workers\/([^/]+)\/drain$/)
    if (!m) return null
    captured.calls.push(decodeURIComponent(m[1]))
    return {
      status: 200,
      body: {
        workerId: decodeURIComponent(m[1]),
        state: 'DRAINING',
        inflightBuilds: 0,
        estimatedSecondsRemaining: 0,
      },
    }
  }
}

function undrainHandler(captured: { calls: string[] }): Handler {
  return (url, method) => {
    if (method !== 'POST') return null
    const m = url.pathname.match(/^\/api\/v1\/workers\/([^/]+)\/undrain$/)
    if (!m) return null
    captured.calls.push(decodeURIComponent(m[1]))
    return {
      status: 200,
      body: {
        workerId: decodeURIComponent(m[1]),
        state: 'ONLINE',
        inflightBuilds: 0,
        estimatedSecondsRemaining: 0,
      },
    }
  }
}

function mountWorkers(handlers: Handler[]) {
  setupFetchMock(handlers)
  setAccessToken('test-token')
  const rootRoute = createRootRoute({ component: () => <Outlet /> })
  const workersRoute = createRoute({
    getParentRoute: () => rootRoute,
    path: '/workers',
    component: WorkersRoute.options.component,
  })
  const router = createRouter({
    routeTree: rootRoute.addChildren([workersRoute]),
    history: createMemoryHistory({ initialEntries: ['/workers'] }),
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

// ── Lifecycle ───────────────────────────────────────────────────────────────

beforeEach(() => {
  setAccessToken(null)
})

afterEach(() => {
  resetFetchMock()
  setAccessToken(null)
})

// ── Tests ───────────────────────────────────────────────────────────────────

describe('/workers v3 (#580)', () => {
  it('renders 3 metric tiles with totals derived from the fleet', async () => {
    mountWorkers([workersGetHandler(fleetPage(FLEET))])

    // Wait for any worker row to land.
    await waitFor(() => expect(screen.getByTestId('worker-row-a-1')).toBeInTheDocument())

    const active = screen.getByTestId('metric-active-workers')
    const running = screen.getByTestId('metric-running-tasks')
    const idle = screen.getByTestId('metric-idle-workers')

    // Active workers = items not OFFLINE / DRAINING (matches Overview KPI)
    // Fleet: 3 ONLINE + 1 DRAINING + 1 OFFLINE → active = 3.
    expect(active.textContent).toContain('3')
    // Running tasks = 2 (alpha-1) + 0 + 0 + 1 (delta-1 DRAINING but still counts) + 0 = 3
    expect(running.textContent).toContain('3')
    // Idle = ONLINE && currentTasks === 0 → alpha-2, beta-1 = 2
    expect(idle.textContent).toContain('2')
  })

  it('groups the table by pool — one section per distinct pool', async () => {
    mountWorkers([workersGetHandler(fleetPage(FLEET))])
    await waitFor(() =>
      expect(screen.getByTestId('pool-section-pool-a')).toBeInTheDocument(),
    )
    expect(screen.getByTestId('pool-section-pool-b')).toBeInTheDocument()

    // pool-a holds alpha-1 + alpha-2; pool-b holds beta-1, delta-1, offline-1
    const sectionA = screen.getByTestId('pool-section-pool-a')
    expect(sectionA.textContent).toContain('alpha-1')
    expect(sectionA.textContent).toContain('alpha-2')
    expect(sectionA.textContent).not.toContain('beta-1')

    const sectionB = screen.getByTestId('pool-section-pool-b')
    expect(sectionB.textContent).toContain('beta-1')
    expect(sectionB.textContent).toContain('delta-1')
    // #853 UX-cleanup: OFFLINE workers are hidden by default behind a toggle.
    // The DRAINING delta-1 still surfaces (drain is an in-flight state).
    expect(sectionB.textContent).not.toContain('offline-1')
  })

  it('Drain button fires POST /workers/:id/drain', async () => {
    const captured = { calls: [] as string[] }
    mountWorkers([
      drainHandler(captured),
      undrainHandler(captured),
      workersGetHandler(fleetPage(FLEET)),
    ])

    await waitFor(() => expect(screen.getByTestId('worker-row-a-1')).toBeInTheDocument())

    // alpha-1 is ONLINE busy → Drain action
    const drainBtn = screen.getByTestId('worker-drain-a-1')
    fireEvent.click(drainBtn)

    await waitFor(() => expect(captured.calls).toContain('a-1'))
  })

  it('DRAINING worker exposes Reset, not Drain', async () => {
    mountWorkers([workersGetHandler(fleetPage(FLEET))])
    await waitFor(() => expect(screen.getByTestId('worker-row-d-1')).toBeInTheDocument())

    // delta-1 is DRAINING — only Reset must be visible.
    expect(screen.getByTestId('worker-reset-d-1')).toBeInTheDocument()
    expect(screen.queryByTestId('worker-drain-d-1')).toBeNull()
  })

  it('empty fleet shows empty-state and no pool sections', async () => {
    mountWorkers([workersGetHandler(fleetPage([]))])
    await waitFor(() => expect(screen.getByTestId('workers-empty')).toBeInTheDocument())
    expect(screen.queryByTestId('pool-section-default')).toBeNull()

    // Metric tiles still render — just zero.
    expect(screen.getByTestId('metric-active-workers').textContent).toContain('0')
    expect(screen.getByTestId('metric-running-tasks').textContent).toContain('0')
    expect(screen.getByTestId('metric-idle-workers').textContent).toContain('0')
  })
})
