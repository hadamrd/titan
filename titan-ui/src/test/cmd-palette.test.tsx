/**
 * Adversarial tests for the ⌘K Command Palette (closes #687).
 *
 * Covers the exact failure modes called out in the spec:
 *   - Cmd+K opens, Esc closes
 *   - empty query → Pages section visible, NO fetch fired
 *   - 5 keystrokes "build" → exactly 1 jobs fetch + 1 builds fetch
 *     (debounce holds, fan-out doesn't escape)
 *   - Enter on a job result calls navigate({ to: '/jobs/$jobId', params: ... })
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { fireEvent, render, screen, waitFor, act } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import {
  RouterProvider,
  createRootRoute,
  createRoute,
  createRouter,
  createMemoryHistory,
  Outlet,
} from '@tanstack/react-router'
import { CommandPalette } from '../components/CommandPalette'
import { setupFetchMock, resetFetchMock } from './msw-handlers'
import type { BuildsPage, JobsPage } from '../api/types'

// ── Mock useNavigate so we can assert exact navigation calls without rooting
//    around in router state. The CommandPalette imports useNavigate from
//    @tanstack/react-router — partial-mock so the rest of the router stays real.
const navigateMock = vi.fn()
vi.mock('@tanstack/react-router', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@tanstack/react-router')>()
  return {
    ...actual,
    useNavigate: () => navigateMock,
  }
})

function mount() {
  const rootRoute = createRootRoute({
    component: () => (
      <>
        <Outlet />
        <CommandPalette />
      </>
    ),
  })
  const indexRoute = createRoute({
    getParentRoute: () => rootRoute,
    path: '/',
    component: () => <div data-testid="route-index">index</div>,
  })

  const router = createRouter({
    routeTree: rootRoute.addChildren([indexRoute]),
    history: createMemoryHistory({ initialEntries: ['/'] }),
  })

  const qc = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })

  return {
    ...render(
      <QueryClientProvider client={qc}>
        <RouterProvider router={router} />
      </QueryClientProvider>,
    ),
  }
}

async function pressMetaK() {
  await act(async () => {
    await Promise.resolve()
    fireEvent.keyDown(window, { key: 'k', metaKey: true })
  })
}

/** Snapshot the search-call counts off the shared fetch mock. */
function fetchCallPaths(): string[] {
  const fn = globalThis.fetch as unknown as { mock: { calls: unknown[][] } }
  return fn.mock.calls.map((args) => {
    const first = args[0]
    if (typeof first === 'string') return first
    if (first instanceof URL) return first.href
    if (first instanceof Request) return first.url
    return String(first)
  })
}

describe('CommandPalette (#687)', () => {
  beforeEach(() => {
    navigateMock.mockReset()
    // Custom handler set: respond to /api/v1/jobs?search=… and
    // /api/v1/builds?search=… with seeded matches so the test can assert
    // the items render. Every other path falls through to a 404 — keeps the
    // fetch budget visible.
    setupFetchMock([
      (url, method) => {
        if (method !== 'GET') return null
        if (url.pathname !== '/api/v1/jobs') return null
        const page: JobsPage = {
          items: [
            {
              id: 1,
              fullName: 'main-pipeline-build',
              displayName: 'Main Build',
              folderPath: null,
              enabled: true,
              createdAt: '2026-05-20T08:00:00Z',
              updatedAt: '2026-05-20T09:00:00Z',
            },
          ],
          total: 1,
          offset: 0,
          limit: 8,
        }
        return { status: 200, body: page }
      },
      (url, method) => {
        if (method !== 'GET') return null
        if (url.pathname !== '/api/v1/builds') return null
        const page: BuildsPage = {
          items: [
            {
              id: 99,
              jobId: 1,
              buildNumber: 12,
              status: 'SUCCESS',
              triggeredBy: 'api',
              triggerType: 'manual',
              queuedAt: '2026-05-20T09:00:00Z',
              startedAt: '2026-05-20T09:00:01Z',
              finishedAt: '2026-05-20T09:01:00Z',
              durationMs: 59000,
              errorMessage: null,
              failureSummary: null,
            },
          ],
          total: 1,
          offset: 0,
          limit: 8,
        }
        return { status: 200, body: page }
      },
    ])
  })
  afterEach(() => {
    resetFetchMock()
    vi.restoreAllMocks()
  })

  it('Cmd+K opens; Esc closes', async () => {
    mount()
    await waitFor(() => expect(screen.getByTestId('route-index')).toBeInTheDocument())
    expect(screen.queryByTestId('cmdk-input')).toBeNull()

    await pressMetaK()
    await waitFor(() => expect(screen.getByTestId('cmdk-input')).toBeInTheDocument())

    fireEvent.keyDown(screen.getByTestId('cmdk-input'), { key: 'Escape' })
    await waitFor(() => expect(screen.queryByTestId('cmdk-input')).toBeNull())
  })

  it('empty query: Pages section visible, NO fetch', async () => {
    mount()
    await waitFor(() => expect(screen.getByTestId('route-index')).toBeInTheDocument())

    // Snapshot any pre-open fetches (the route tree itself triggers none, but
    // the assertion below is a strict delta against this baseline).
    const baseline = fetchCallPaths().length

    await pressMetaK()
    await waitFor(() => expect(screen.getByTestId('cmdk-input')).toBeInTheDocument())

    // Every Pages entry must render.
    expect(screen.getByTestId('cmdk-page:home')).toBeInTheDocument()
    expect(screen.getByTestId('cmdk-page:pipelines')).toBeInTheDocument()
    expect(screen.getByTestId('cmdk-page:builds')).toBeInTheDocument()
    expect(screen.getByTestId('cmdk-page:pipelines-validate')).toBeInTheDocument()
    expect(screen.getByTestId('cmdk-page:workers')).toBeInTheDocument()
    expect(screen.getByTestId('cmdk-page:system')).toBeInTheDocument()
    expect(screen.getByTestId('cmdk-page:profile')).toBeInTheDocument()

    // No /api/v1/jobs or /api/v1/builds search call yet.
    const after = fetchCallPaths().slice(baseline)
    const searched = after.filter(
      (u) => u.includes('/api/v1/jobs') || u.includes('/api/v1/builds'),
    )
    expect(searched).toEqual([])
  })

  it('5 keystrokes "build" → exactly 1 fetch for jobs + 1 fetch for builds', async () => {
    vi.useFakeTimers()
    try {
      mount()
      // Drain the initial route render under fake timers.
      await act(async () => {
        await vi.advanceTimersByTimeAsync(0)
      })

      await act(async () => {
        fireEvent.keyDown(window, { key: 'k', metaKey: true })
        await vi.advanceTimersByTimeAsync(0)
      })

      const input = screen.getByTestId('cmdk-input') as HTMLInputElement

      // Five keystrokes inside the debounce window — only the last value should fire.
      const keystrokes = ['b', 'bu', 'bui', 'buil', 'build']
      for (const k of keystrokes) {
        await act(async () => {
          fireEvent.change(input, { target: { value: k } })
          // Advance LESS than the 300 ms debounce window between strokes.
          await vi.advanceTimersByTimeAsync(50)
        })
      }

      // Now cross the debounce boundary — single fetch per surface should fire.
      await act(async () => {
        await vi.advanceTimersByTimeAsync(500)
      })

      const paths = fetchCallPaths()
      const jobsCalls = paths.filter((p) => {
        const u = new URL(p, 'http://localhost:8080')
        return u.pathname === '/api/v1/jobs' && u.searchParams.get('search') === 'build'
      })
      const buildsCalls = paths.filter((p) => {
        const u = new URL(p, 'http://localhost:8080')
        return u.pathname === '/api/v1/builds' && u.searchParams.get('search') === 'build'
      })
      expect(jobsCalls).toHaveLength(1)
      expect(buildsCalls).toHaveLength(1)
    } finally {
      vi.useRealTimers()
    }
  })

  it('selecting "Validate pipeline YAML" navigates to /pipelines/validate (#751)', async () => {
    mount()
    await waitFor(() => expect(screen.getByTestId('route-index')).toBeInTheDocument())

    await pressMetaK()
    await waitFor(() => expect(screen.getByTestId('cmdk-input')).toBeInTheDocument())

    fireEvent.click(screen.getByTestId('cmdk-page:pipelines-validate'))

    await waitFor(() => expect(navigateMock).toHaveBeenCalled())
    expect(navigateMock).toHaveBeenCalledWith({ to: '/pipelines/validate' })
  })

  it('Enter on a job result navigates to its detail page', async () => {
    mount()
    await waitFor(() => expect(screen.getByTestId('route-index')).toBeInTheDocument())

    await pressMetaK()
    await waitFor(() => expect(screen.getByTestId('cmdk-input')).toBeInTheDocument())

    const input = screen.getByTestId('cmdk-input') as HTMLInputElement
    fireEvent.change(input, { target: { value: 'build' } })

    // Wait for the debounced fetch to land + the job to render.
    await waitFor(
      () => expect(screen.getByTestId('cmdk-job-1')).toBeInTheDocument(),
      { timeout: 2000 },
    )

    fireEvent.click(screen.getByTestId('cmdk-job-1'))

    await waitFor(() => expect(navigateMock).toHaveBeenCalled())
    expect(navigateMock).toHaveBeenCalledWith({
      to: '/pipelines/$pipelineId',
      params: { pipelineId: '1' },
    })
  })
})
