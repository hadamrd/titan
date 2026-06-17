/**
 * Adversarial tests for the /system engine-health dashboard (#676).
 *
 * Covered:
 *  - Happy path: typed UP payload → 4 cards render (version, db, queue, workers),
 *    DB tile shows the "UP" label and the success-variant StatusDot.
 *  - Sad path: DB DOWN typed payload → page renders without crashing AND surfaces
 *    the red fail-variant indicator + "DOWN" label. This is the load-bearing
 *    case (the dashboard's primary purpose is telling the SRE the DB is down).
 *  - Counters honest: workersOnline=0 renders the literal "0", never an em-dash —
 *    "all workers dead" must read as a real number, not as "no data".
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
import { Route as SystemRoute } from '../routes/system/index'
import type { SystemInfoDto } from '../api/types'
import { setupFetchMock, resetFetchMock } from './msw-handlers'

// ── Seeds ────────────────────────────────────────────────────────────────────

const UP_PAYLOAD: SystemInfoDto = {
  version: '0.1.0',
  buildSha: 'abc1234',
  dbStatus: 'UP',
  queueDepth: 3,
  workersOnline: 2,
  serverTime: '2026-05-24T12:00:00Z',
}

const DOWN_PAYLOAD: SystemInfoDto = {
  version: '0.1.0',
  buildSha: 'abc1234',
  dbStatus: 'DOWN',
  queueDepth: 0,
  workersOnline: 0,
  serverTime: '2026-05-24T12:00:00Z',
}

// ── Handlers ─────────────────────────────────────────────────────────────────

interface MatchResult {
  status: number
  body: unknown
}
type Handler = (url: URL, method: string) => MatchResult | null

function systemHandler(payload: SystemInfoDto): Handler {
  return (url, method) => {
    if (method !== 'GET') return null
    if (url.pathname !== '/api/v1/system/info') return null
    return { status: 200, body: payload }
  }
}

// ── Mount harness ────────────────────────────────────────────────────────────

function mountSystem(handlers: Handler[]) {
  setupFetchMock(handlers)
  setAccessToken('test-token')

  const rootRoute = createRootRoute({ component: () => <Outlet /> })
  const systemRoute = createRoute({
    getParentRoute: () => rootRoute,
    path: '/system',
    component: SystemRoute.options.component,
  })
  const router = createRouter({
    routeTree: rootRoute.addChildren([systemRoute]),
    history: createMemoryHistory({ initialEntries: ['/system'] }),
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

describe('System page (#676)', () => {
  it('UP payload → all four tiles render with live values', async () => {
    mountSystem([systemHandler(UP_PAYLOAD)])

    await waitFor(() =>
      expect(screen.getByTestId('system-cards')).toBeInTheDocument(),
    )

    await waitFor(() => {
      expect(screen.getByTestId('system-card-version')).toHaveTextContent('0.1.0')
      expect(screen.getByTestId('system-card-version')).toHaveTextContent('abc1234')
      expect(screen.getByTestId('system-db-status')).toHaveTextContent('UP')
      expect(screen.getByTestId('system-card-queue')).toHaveTextContent('3')
      expect(screen.getByTestId('system-card-workers')).toHaveTextContent('2')
    })

    // success dot (oklch token-driven) — class is "status-dot success".
    const dbCard = screen.getByTestId('system-card-db')
    const dot = dbCard.querySelector('.status-dot')
    expect(dot).not.toBeNull()
    expect(dot?.className).toContain('success')
    expect(dot?.className).not.toContain('fail')
  })

  it('DOWN payload → red dot + "DOWN" label, no crash, counters honest at 0', async () => {
    mountSystem([systemHandler(DOWN_PAYLOAD)])

    await waitFor(() =>
      expect(screen.getByTestId('system-cards')).toBeInTheDocument(),
    )

    await waitFor(() => {
      expect(screen.getByTestId('system-db-status')).toHaveTextContent('DOWN')
    })

    // Fail-variant dot — the load-bearing visual signal of the page.
    const dbCard = screen.getByTestId('system-card-db')
    const dot = dbCard.querySelector('.status-dot')
    expect(dot).not.toBeNull()
    expect(dot?.className).toContain('fail')
    expect(dot?.className).not.toContain('success')

    // workersOnline=0 must render as literal "0", not "—". The sidebar polish
    // PR (#494) explicitly nuked em-dash placeholders for the same reason:
    // "no data" and "honest zero" are very different states.
    expect(screen.getByTestId('system-card-workers')).toHaveTextContent('0')
    expect(screen.getByTestId('system-card-workers').textContent).not.toContain('—')
    expect(screen.getByTestId('system-card-queue')).toHaveTextContent('0')
  })

  it('renders server time line after data lands', async () => {
    mountSystem([systemHandler(UP_PAYLOAD)])
    await waitFor(() =>
      expect(screen.getByTestId('system-server-time')).toHaveTextContent(
        '2026-05-24T12:00:00Z',
      ),
    )
  })
})
