/**
 * Render smoke-tests for the components extracted from
 * `routes/pipelines/index.tsx` in issue #1070 (refactor — split the 736-line
 * route into `components/jobs/`).
 *
 * The behavioural invariants of the page (sort order, favorites pinning,
 * quick-trigger, star cap) are already covered by jobs-sort / jobs-favorites /
 * jobs-quick-trigger / jobs-list-status. These tests assert the extracted
 * presentational units mount and wire their callbacks — plus one adversarial
 * case per unit (empty groups, handler firing) so a regression in the split
 * hard-fails rather than silently rendering nothing.
 */
import { describe, it, expect, vi, afterEach } from 'vitest'
import { render, screen, fireEvent, cleanup, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import {
  RouterProvider,
  createRouter,
  createMemoryHistory,
  createRootRoute,
  createRoute,
  Outlet,
} from '@tanstack/react-router'
import type { JobDto } from '../api/types'
import { JobsTable } from '../components/jobs/JobsTable'
import { JobsEmptyState } from '../components/jobs/JobsEmptyState'
import { JobsSkeleton } from '../components/jobs/JobsTableSkeleton'
import { StarErrorToast, NewJobErrorToast } from '../components/jobs/JobsToasts'

afterEach(() => cleanup())

function job(id: number, displayName: string, over: Partial<JobDto> = {}): JobDto {
  return {
    id,
    fullName: `org/${displayName}`,
    displayName,
    folderPath: null,
    enabled: true,
    createdAt: '2026-05-20T08:00:00Z',
    updatedAt: '2026-05-20T08:00:00Z',
    lastBuild: {
      id: id * 10,
      buildNumber: 3,
      status: 'SUCCESS',
      durationMs: 60_000,
      finishedAt: '2026-05-20T09:01:00Z',
    },
    ...over,
  }
}

/** Wrap in the providers JobsTable needs: TanStack Query + a router that knows
 * the routes its <Link>s target (`/pipelines/$pipelineId`, `/onboarding`). */
function renderWithProviders(ui: React.ReactNode) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const rootRoute = createRootRoute({ component: () => <Outlet /> })
  const indexRoute = createRoute({
    getParentRoute: () => rootRoute,
    path: '/',
    component: () => <>{ui}</>,
  })
  const detailRoute = createRoute({
    getParentRoute: () => rootRoute,
    path: '/pipelines/$pipelineId',
    component: () => <div>detail</div>,
  })
  const onboardingRoute = createRoute({
    getParentRoute: () => rootRoute,
    path: '/onboarding',
    component: () => <div>onboarding</div>,
  })
  const router = createRouter({
    routeTree: rootRoute.addChildren([indexRoute, detailRoute, onboardingRoute]),
    history: createMemoryHistory({ initialEntries: ['/'] }),
  })
  return render(
    <QueryClientProvider client={qc}>
      {/* eslint-disable-next-line @typescript-eslint/no-explicit-any */}
      <RouterProvider router={router as any} />
    </QueryClientProvider>,
  )
}

describe('JobsSkeleton', () => {
  it('mounts the loading shimmer with the table column labels', () => {
    const { container } = render(<JobsSkeleton />)
    // Header labels render so the skeleton matches the real table's layout.
    expect(screen.getByText('Name')).toBeInTheDocument()
    expect(screen.getByText('Enabled')).toBeInTheDocument()
    // 4 placeholder rows × 9 cells — a non-empty shimmer, not a blank card.
    expect(container.querySelectorAll('tbody tr').length).toBe(4)
  })
})

describe('JobsEmptyState', () => {
  it('renders the zero-pipelines message + create CTA', async () => {
    renderWithProviders(<JobsEmptyState />)
    expect(await screen.findByTestId('jobs-empty')).toBeInTheDocument()
    expect(screen.getByText('No pipelines yet')).toBeInTheDocument()
    expect(
      screen.getByText('Create your first pipeline to start running builds.'),
    ).toBeInTheDocument()
  })
})

describe('JobsToasts', () => {
  it('StarErrorToast shows the message and fires onDismiss', () => {
    const onDismiss = vi.fn()
    render(<StarErrorToast message="Star limit reached (10)." onDismiss={onDismiss} />)
    expect(screen.getByTestId('star-job-toast')).toHaveTextContent(
      'Star limit reached (10).',
    )
    fireEvent.click(screen.getByText('Dismiss'))
    expect(onDismiss).toHaveBeenCalledTimes(1)
  })

  it('NewJobErrorToast prefixes the message and fires onRetry', () => {
    const onRetry = vi.fn()
    render(<NewJobErrorToast message="503 from server" onRetry={onRetry} />)
    expect(screen.getByTestId('new-job-toast')).toHaveTextContent(
      'Could not create job: 503 from server',
    )
    fireEvent.click(screen.getByText('Retry'))
    expect(onRetry).toHaveBeenCalledTimes(1)
  })
})

describe('JobsTable', () => {
  it('renders favorite + rest rows with a separator between the two groups', async () => {
    renderWithProviders(
      <JobsTable
        favorites={[job(1, 'alpha')]}
        rest={[job(2, 'bravo')]}
        recentBuildsMap={undefined}
        sort={{ key: 'status' }}
        onClickName={() => {}}
        onClickLastBuild={() => {}}
        onStarError={() => {}}
      />,
    )
    expect(await screen.findByTestId('job-row-1')).toBeInTheDocument()
    expect(screen.getByTestId('job-row-2')).toBeInTheDocument()
    // The separator only exists when BOTH groups are non-empty.
    expect(screen.getByTestId('jobs-favorites-separator')).toBeInTheDocument()
    // "View" link targets the renamed /pipelines detail route, not /jobs.
    const row = screen.getByTestId('job-row-1')
    expect(within(row).getByText('View').closest('a')).toHaveAttribute(
      'href',
      '/pipelines/1',
    )
  })

  it('omits the favorites separator when there are no favorites (adversarial)', async () => {
    renderWithProviders(
      <JobsTable
        favorites={[]}
        rest={[job(2, 'bravo'), job(3, 'charlie')]}
        recentBuildsMap={undefined}
        sort={{ key: 'status' }}
        onClickName={() => {}}
        onClickLastBuild={() => {}}
        onStarError={() => {}}
      />,
    )
    expect(await screen.findByTestId('job-row-2')).toBeInTheDocument()
    expect(screen.queryByTestId('jobs-favorites-separator')).toBeNull()
  })

  it('renders a never-run placeholder when a job has no lastBuild (adversarial)', async () => {
    renderWithProviders(
      <JobsTable
        favorites={[]}
        rest={[job(4, 'delta', { lastBuild: null })]}
        recentBuildsMap={undefined}
        sort={{ key: 'status' }}
        onClickName={() => {}}
        onClickLastBuild={() => {}}
        onStarError={() => {}}
      />,
    )
    const row = await screen.findByTestId('job-row-4')
    expect(within(row).getByText('never run')).toBeInTheDocument()
  })

  it('fires the header sort callbacks on click', async () => {
    const onClickName = vi.fn()
    const onClickLastBuild = vi.fn()
    renderWithProviders(
      <JobsTable
        favorites={[]}
        rest={[job(2, 'bravo')]}
        recentBuildsMap={undefined}
        sort={{ key: 'status' }}
        onClickName={onClickName}
        onClickLastBuild={onClickLastBuild}
        onStarError={() => {}}
      />,
    )
    fireEvent.click(await screen.findByTestId('jobs-sort-name'))
    fireEvent.click(screen.getByTestId('jobs-sort-status'))
    expect(onClickName).toHaveBeenCalledTimes(1)
    expect(onClickLastBuild).toHaveBeenCalledTimes(1)
  })
})
