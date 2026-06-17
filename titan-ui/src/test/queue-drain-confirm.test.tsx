/**
 * Adversarial tests for the /queue "Drain queue" themed-confirm migration
 * (#1040 — replaces the legacy `window.confirm()` with `ConfirmDialog`).
 *
 * Covered:
 *  - Clicking "Drain queue" with a non-empty queue opens the themed
 *    ConfirmDialog (NOT a native browser dialog) — proven by querying the
 *    `data-testid` and verifying the dialog title + destructive confirm label.
 *  - Clicking Cancel closes the dialog WITHOUT calling POST /api/v1/queue/drain.
 *  - Clicking Confirm calls POST /api/v1/queue/drain exactly once and renders
 *    the "Drained N tasks" success banner.
 *  - The "Drain queue" button is disabled on an empty queue (the dialog never
 *    opens — preserves existing UX).
 *
 * Why these tests: the legacy `window.confirm()` path was untestable in
 * vitest (jsdom returns false unconditionally), which let it ship to prod.
 * The themed dialog gives us deterministic DOM hooks; the lint rule in
 * eslint.config.js prevents regression at author time.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
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
import type { QueueEntryDto, QueuePage } from '../api/types'
import { setupFetchMock, resetFetchMock } from './msw-handlers'

// ── Seeds ────────────────────────────────────────────────────────────────────

const EMPTY_QUEUE: QueuePage = { items: [], total: 0, offset: 0, limit: 200 }

const TWO_TASKS: QueuePage = {
  items: [
    {
      taskId: 9001,
      buildId: 42,
      jobId: 1,
      jobName: 'main-pipeline',
      queuedAt: '2026-05-23T10:00:00Z',
      waitingMs: 4_000,
      priority: 5,
      requestedLabels: 'linux',
    } satisfies QueueEntryDto,
    {
      taskId: 9002,
      buildId: 43,
      jobId: 1,
      jobName: 'main-pipeline',
      queuedAt: '2026-05-23T10:01:00Z',
      waitingMs: 2_000,
      priority: 3,
      requestedLabels: 'linux',
    } satisfies QueueEntryDto,
  ],
  total: 2,
  offset: 0,
  limit: 200,
}

interface MatchResult {
  status: number
  body: unknown
}
type Handler = (url: URL, method: string) => MatchResult | null

function queueHandler(page: QueuePage): Handler {
  return (url, method) => {
    if (method !== 'GET') return null
    if (url.pathname !== '/api/v1/queue') return null
    return { status: 200, body: page }
  }
}

function recentHandler(): Handler {
  return (url, method) => {
    if (method !== 'GET') return null
    if (url.pathname !== '/api/v1/queue/recent') return null
    return { status: 200, body: [] }
  }
}

function drainHandler(spy: ReturnType<typeof vi.fn>, drained: number): Handler {
  return (url, method) => {
    if (method !== 'POST') return null
    if (url.pathname !== '/api/v1/queue/drain') return null
    spy()
    return { status: 200, body: { drained } }
  }
}

function mountQueue(handlers: Handler[]) {
  setupFetchMock(handlers)
  setAccessToken('test-token')

  const rootRoute = createRootRoute({ component: () => <Outlet /> })
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

beforeEach(() => {
  setAccessToken(null)
})

afterEach(() => {
  resetFetchMock()
  setAccessToken(null)
})

describe('Queue page — themed Drain confirm dialog (#1040)', () => {
  it('clicking "Drain queue" opens the themed ConfirmDialog (not native)', async () => {
    const drainSpy = vi.fn()
    mountQueue([
      queueHandler(TWO_TASKS),
      recentHandler(),
      drainHandler(drainSpy, 2),
    ])

    const user = userEvent.setup()

    // Wait for the queue to load (button stays disabled with "Queue is empty"
    // until the fetch resolves).
    const drainBtn = await waitFor(() => {
      const btn = screen.getByRole('button', { name: /drain queue/i })
      expect(btn).not.toBeDisabled()
      return btn
    })

    // Dialog NOT in DOM yet.
    expect(screen.queryByTestId('drain-queue-dialog')).toBeNull()

    await user.click(drainBtn)

    // Themed dialog renders, with destructive confirm label and the 2-task
    // message (pluralised correctly).
    const dialog = await screen.findByTestId('drain-queue-dialog')
    expect(dialog).toBeInTheDocument()
    expect(dialog).toHaveAttribute('role', 'alertdialog')
    expect(
      screen.getByTestId('drain-queue-dialog-confirm'),
    ).toHaveTextContent(/drain queue/i)
    expect(dialog.textContent).toMatch(/cancels 2 queued tasks/i)

    // Backend has NOT been called yet (user hasn't confirmed).
    expect(drainSpy).not.toHaveBeenCalled()
  })

  it('clicking Cancel closes the dialog without draining', async () => {
    const drainSpy = vi.fn()
    mountQueue([
      queueHandler(TWO_TASKS),
      recentHandler(),
      drainHandler(drainSpy, 2),
    ])
    const user = userEvent.setup()

    const drainBtn = await waitFor(() => {
      const btn = screen.getByRole('button', { name: /drain queue/i })
      expect(btn).not.toBeDisabled()
      return btn
    })
    await user.click(drainBtn)
    await screen.findByTestId('drain-queue-dialog')

    await user.click(screen.getByTestId('drain-queue-dialog-cancel'))

    await waitFor(() =>
      expect(screen.queryByTestId('drain-queue-dialog')).toBeNull(),
    )
    expect(drainSpy).not.toHaveBeenCalled()
  })

  it('clicking Confirm POSTs /drain once and renders the success banner', async () => {
    const drainSpy = vi.fn()
    mountQueue([
      queueHandler(TWO_TASKS),
      recentHandler(),
      drainHandler(drainSpy, 2),
    ])
    const user = userEvent.setup()

    const drainBtn = await waitFor(() => {
      const btn = screen.getByRole('button', { name: /drain queue/i })
      expect(btn).not.toBeDisabled()
      return btn
    })
    await user.click(drainBtn)
    await user.click(await screen.findByTestId('drain-queue-dialog-confirm'))

    await waitFor(() => expect(drainSpy).toHaveBeenCalledTimes(1))
    // Success banner — match on the text directly (dnd-kit also publishes a
    // role="status" live region, so getByRole('status') is ambiguous).
    expect(await screen.findByText(/drained 2 tasks/i)).toBeInTheDocument()
    // And the dialog closed.
    await waitFor(() =>
      expect(screen.queryByTestId('drain-queue-dialog')).toBeNull(),
    )
  })

  it('empty queue → Drain button disabled, dialog never opens', async () => {
    const drainSpy = vi.fn()
    mountQueue([
      queueHandler(EMPTY_QUEUE),
      recentHandler(),
      drainHandler(drainSpy, 0),
    ])
    const user = userEvent.setup()

    const drainBtn = await screen.findByRole('button', { name: /drain queue/i })
    expect(drainBtn).toBeDisabled()

    // Even attempting the click should be a no-op (button is disabled).
    await user.click(drainBtn).catch(() => undefined)

    expect(screen.queryByTestId('drain-queue-dialog')).toBeNull()
    expect(drainSpy).not.toHaveBeenCalled()
  })
})
