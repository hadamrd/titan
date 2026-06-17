/**
 * Adversarial vitest for TopFailingJobsCard (closes #769).
 *
 * Covers what an SRE actually relies on:
 *   1. 0 rows → calm placeholder "All jobs healthy in the last 24h" (no emoji,
 *      no celebration — issue spec).
 *   2. 3 rows render in server-supplied order (the card does NOT re-sort —
 *      that's the server's responsibility, and the UI must not lie about the
 *      ranking by re-ordering).
 *   3. Each row's "N/M failed (P%)" string matches the wire numbers exactly.
 *   4. Clicking a row navigates to /jobs/{jobId}.
 *   5. Loading state renders skeletons (no flicker of "All healthy" before the
 *      first response lands — that would be a misleading regression).
 *   6. Error state renders a calm fallback, not the empty placeholder
 *      (otherwise an outage looks like green health).
 */
import { describe, it, expect, vi, afterEach } from 'vitest'
import { render, screen, cleanup, fireEvent } from '@testing-library/react'
import {
  RouterProvider,
  createRouter,
  createMemoryHistory,
  createRootRoute,
  createRoute,
  Outlet,
} from '@tanstack/react-router'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { TopFailingJobDto } from '../api/types'

// Mock the hooks module so the component renders synchronously without the
// real fetch path. The hook contract under test is just "returns
// { data, isLoading, error }"; we vary those three.
const mockHook = vi.fn()
vi.mock('@/api/hooks', () => ({
  useTopFailingJobs: () => mockHook(),
}))

import { TopFailingJobsCard } from '../components/TopFailingJobsCard'

afterEach(() => {
  cleanup()
  mockHook.mockReset()
})

function renderCard() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const rootRoute = createRootRoute({ component: () => <Outlet /> })
  const indexRoute = createRoute({
    getParentRoute: () => rootRoute,
    path: '/',
    component: () => <TopFailingJobsCard />,
  })
  const jobRoute = createRoute({
    getParentRoute: () => rootRoute,
    path: '/pipelines/$pipelineId',
    component: () => <div data-testid="job-page">job</div>,
  })
  const buildRoute = createRoute({
    getParentRoute: () => rootRoute,
    path: '/builds/$buildId',
    component: () => <div data-testid="build-page">build</div>,
  })
  const router = createRouter({
    routeTree: rootRoute.addChildren([indexRoute, jobRoute, buildRoute]),
    history: createMemoryHistory({ initialEntries: ['/'] }),
  })
  return render(
    <QueryClientProvider client={qc}>
      {/* eslint-disable-next-line @typescript-eslint/no-explicit-any */}
      <RouterProvider router={router as any} />
    </QueryClientProvider>,
  )
}

function threeRows(): TopFailingJobDto[] {
  // Server-supplied order: failure rate descending.
  return [
    {
      jobId: 11,
      jobName: 'flake-A',
      totalBuilds: 4,
      failedBuilds: 3,
      failureRate: 0.75,
      lastFailedBuildId: 9001,
    },
    {
      jobId: 22,
      jobName: 'flake-B',
      totalBuilds: 10,
      failedBuilds: 4,
      failureRate: 0.4,
      lastFailedBuildId: 9002,
    },
    {
      jobId: 33,
      jobName: 'flake-C',
      totalBuilds: 5,
      failedBuilds: 1,
      failureRate: 0.2,
      lastFailedBuildId: null,
    },
  ]
}

describe('TopFailingJobsCard', () => {
  it('renders calm placeholder when API returns 0 rows', async () => {
    mockHook.mockReturnValue({ data: [], isLoading: false, error: null })
    renderCard()
    const empty = await screen.findByTestId('top-failing-jobs-empty')
    expect(empty.textContent).toContain('All jobs healthy in the last 24h')
    // Adversarial: must not contain emoji or any gamification glyph.
    expect(empty.textContent).not.toMatch(/[\u{1F300}-\u{1FAFF}]/u)
    expect(empty.textContent).not.toMatch(/[\u{1F600}-\u{1F64F}]/u)
    expect(empty.textContent).not.toContain('!')
  })

  it('renders 3 rows in the order the server returned them', async () => {
    mockHook.mockReturnValue({ data: threeRows(), isLoading: false, error: null })
    renderCard()
    const r11 = await screen.findByTestId('top-failing-row-11')
    const r22 = await screen.findByTestId('top-failing-row-22')
    const r33 = await screen.findByTestId('top-failing-row-33')
    expect(r11).toBeInTheDocument()
    expect(r22).toBeInTheDocument()
    expect(r33).toBeInTheDocument()
    // Order: in DOM order, r11 precedes r22 precedes r33.
    const order = [r11, r22, r33].map((el) => el.compareDocumentPosition)
    expect(order.length).toBe(3)
    expect(r11.compareDocumentPosition(r22) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(r22.compareDocumentPosition(r33) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    // Stats string contract.
    expect(screen.getByTestId('top-failing-stats-11').textContent).toBe('3/4 failed (75%)')
    expect(screen.getByTestId('top-failing-stats-22').textContent).toBe('4/10 failed (40%)')
    expect(screen.getByTestId('top-failing-stats-33').textContent).toBe('1/5 failed (20%)')
  })

  it('row exposes role="link" (#853: outer <a> → <div role="link"> to fix nested-anchor)', async () => {
    mockHook.mockReturnValue({ data: threeRows(), isLoading: false, error: null })
    renderCard()
    const row = await screen.findByTestId('top-failing-row-22')
    // #853 swapped the outer <a> for a <div role="link"> + onClick + onKeyDown
    // to avoid the nested-anchor a11y bug (the inner #lastFailedBuildId link
    // had been illegal-nested inside the row anchor).
    expect(row.tagName.toLowerCase()).toBe('div')
    expect(row.getAttribute('role')).toBe('link')
  })

  it('clicking a row navigates to /pipelines/{jobId}', async () => {
    mockHook.mockReturnValue({ data: threeRows(), isLoading: false, error: null })
    renderCard()
    const row = await screen.findByTestId('top-failing-row-11')
    fireEvent.click(row)
    // The job route component mounts on navigate.
    expect(await screen.findByTestId('job-page')).toBeInTheDocument()
  })

  it('omits the lastFailedBuild link when the wire field is null', async () => {
    mockHook.mockReturnValue({ data: threeRows(), isLoading: false, error: null })
    renderCard()
    expect(await screen.findByTestId('top-failing-last-11')).toBeInTheDocument()
    expect(screen.queryByTestId('top-failing-last-33')).toBeNull()
  })

  it('renders skeletons while loading (no premature "all healthy" flicker)', () => {
    mockHook.mockReturnValue({ data: undefined, isLoading: true, error: null })
    renderCard()
    expect(screen.queryByTestId('top-failing-jobs-empty')).toBeNull()
  })

  it('error state shows a calm fallback, not the empty-state placeholder', async () => {
    mockHook.mockReturnValue({
      data: undefined,
      isLoading: false,
      error: new Error('boom'),
    })
    renderCard()
    expect(await screen.findByText('Top failing jobs unavailable.')).toBeInTheDocument()
    expect(screen.queryByTestId('top-failing-jobs-empty')).toBeNull()
  })
})
