/**
 * Adversarial tests for #768 — surface BuildDto.displayName across the UI.
 *
 * Three load-bearing surfaces:
 *   1. {@link buildDisplayLabel} — single source of truth for the
 *      `displayName ?? '#' + buildNumber` fallback chain. Covers
 *      undefined / null / empty / whitespace-only / set.
 *   2. {@link CommandPalette} — build results render the friendly label;
 *      the legacy `#N` flow still works.
 *   3. Conceptual: header + /builds list label come from the same helper,
 *      so the helper-level tests guard those surfaces by composition.
 *
 * Empty-string MUST be treated as absent (defensive: a worker that set an
 * empty templated value would otherwise produce a blank header).
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
import { buildDisplayLabel } from '../api/types'
import type { BuildsPage, JobsPage } from '../api/types'
import { setupFetchMock, resetFetchMock } from './msw-handlers'

// ── Helper unit tests ────────────────────────────────────────────────────────

describe('buildDisplayLabel (#768 fallback chain)', () => {
  it('renders the displayName override when present', () => {
    expect(
      buildDisplayLabel({ displayName: 'deploy-prod-v2.3.1', buildNumber: 42 }),
    ).toBe('deploy-prod-v2.3.1')
  })

  it('falls back to "#N" when displayName is undefined', () => {
    expect(buildDisplayLabel({ buildNumber: 42 })).toBe('#42')
  })

  it('falls back to "#N" when displayName is explicit null', () => {
    expect(buildDisplayLabel({ displayName: null, buildNumber: 42 })).toBe('#42')
  })

  it('treats empty-string displayName as absent (no blank header)', () => {
    expect(buildDisplayLabel({ displayName: '', buildNumber: 42 })).toBe('#42')
  })

  it('treats whitespace-only displayName as absent', () => {
    expect(buildDisplayLabel({ displayName: '   ', buildNumber: 42 })).toBe('#42')
  })

  it('does NOT prepend "#" when a displayName happens to look numeric', () => {
    // Regression guard: a user-set name of "42" should render verbatim, NOT
    // be confused with the buildNumber chip — they are semantically different.
    expect(buildDisplayLabel({ displayName: '42', buildNumber: 7 })).toBe('42')
  })
})

// ── CommandPalette integration ──────────────────────────────────────────────

const navigateMock = vi.fn()
vi.mock('@tanstack/react-router', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@tanstack/react-router')>()
  return {
    ...actual,
    useNavigate: () => navigateMock,
  }
})

function mountPalette() {
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

  return render(
    <QueryClientProvider client={qc}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
}

async function pressMetaK() {
  await act(async () => {
    await Promise.resolve()
    fireEvent.keyDown(window, { key: 'k', metaKey: true })
  })
}

function seedHandlers(builds: BuildsPage['items']) {
  const emptyJobs: JobsPage = { items: [], total: 0, offset: 0, limit: 8 }
  setupFetchMock([
    (url, method) => {
      if (method !== 'GET') return null
      if (url.pathname !== '/api/v1/jobs') return null
      return { status: 200, body: emptyJobs }
    },
    (url, method) => {
      if (method !== 'GET') return null
      if (url.pathname !== '/api/v1/builds') return null
      const page: BuildsPage = {
        items: builds,
        total: builds.length,
        offset: 0,
        limit: 8,
      }
      return { status: 200, body: page }
    },
  ])
}

describe('CommandPalette — #768 displayName surfacing', () => {
  beforeEach(() => {
    navigateMock.mockReset()
  })
  afterEach(() => {
    resetFetchMock()
    vi.restoreAllMocks()
  })

  it('renders the displayName override in the build result row', async () => {
    seedHandlers([
      {
        id: 101,
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
        displayName: 'deploy-prod-v2.3.1',
      },
    ])

    mountPalette()
    await waitFor(() => expect(screen.getByTestId('route-index')).toBeInTheDocument())
    await pressMetaK()
    await waitFor(() => expect(screen.getByTestId('cmdk-input')).toBeInTheDocument())

    const input = screen.getByTestId('cmdk-input') as HTMLInputElement
    fireEvent.change(input, { target: { value: 'deploy-prod' } })

    const row = await screen.findByTestId('cmdk-build-101', {}, { timeout: 2000 })
    // The override label is what the user sees, NOT "Build #12".
    expect(row.textContent).toContain('deploy-prod-v2.3.1')
    expect(row.textContent).not.toMatch(/Build #12\b/)
  })

  it('falls back to "#N" when the build has no displayName (legacy)', async () => {
    seedHandlers([
      {
        id: 102,
        jobId: 1,
        buildNumber: 42,
        status: 'SUCCESS',
        triggeredBy: 'api',
        triggerType: 'manual',
        queuedAt: '2026-05-20T09:00:00Z',
        startedAt: '2026-05-20T09:00:01Z',
        finishedAt: '2026-05-20T09:01:00Z',
        durationMs: 59000,
        errorMessage: null,
        failureSummary: null,
        // displayName intentionally omitted
      },
    ])

    mountPalette()
    await waitFor(() => expect(screen.getByTestId('route-index')).toBeInTheDocument())
    await pressMetaK()
    await waitFor(() => expect(screen.getByTestId('cmdk-input')).toBeInTheDocument())

    const input = screen.getByTestId('cmdk-input') as HTMLInputElement
    fireEvent.change(input, { target: { value: '42' } })

    const row = await screen.findByTestId('cmdk-build-102', {}, { timeout: 2000 })
    expect(row.textContent).toContain('#42')
  })

  it('treats empty-string displayName as absent in the build row', async () => {
    seedHandlers([
      {
        id: 103,
        jobId: 1,
        buildNumber: 7,
        status: 'SUCCESS',
        triggeredBy: 'api',
        triggerType: 'manual',
        queuedAt: '2026-05-20T09:00:00Z',
        startedAt: '2026-05-20T09:00:01Z',
        finishedAt: '2026-05-20T09:01:00Z',
        durationMs: 59000,
        errorMessage: null,
        failureSummary: null,
        displayName: '',
      },
    ])

    mountPalette()
    await waitFor(() => expect(screen.getByTestId('route-index')).toBeInTheDocument())
    await pressMetaK()
    await waitFor(() => expect(screen.getByTestId('cmdk-input')).toBeInTheDocument())

    const input = screen.getByTestId('cmdk-input') as HTMLInputElement
    fireEvent.change(input, { target: { value: '7' } })

    const row = await screen.findByTestId('cmdk-build-103', {}, { timeout: 2000 })
    // No empty span before the `#7` tag — the label IS `#7`.
    expect(row.textContent).toContain('#7')
  })
})
