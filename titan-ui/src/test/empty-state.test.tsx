/**
 * Adversarial tests for EmptyState (closes #764).
 *
 * Covers:
 *   1. title + message render verbatim.
 *   2. action.to renders a TanStack Router <Link> with the correct href.
 *   3. action.onClick variant fires the handler on click.
 *   4. Omitting action mounts cleanly with no CTA in the tree.
 *   5. role="status" + aria-label set so SR users get a non-urgent state
 *      update with the title as the accessible name. Icon stays aria-hidden.
 */
import { describe, it, expect, vi, afterEach } from 'vitest'
import { render, screen, fireEvent, cleanup } from '@testing-library/react'
import {
  RouterProvider,
  createRouter,
  createMemoryHistory,
  createRootRoute,
  createRoute,
  Outlet,
} from '@tanstack/react-router'
import { EmptyState } from '../components/EmptyState'

afterEach(() => cleanup())

function renderInRouter(ui: React.ReactNode) {
  const rootRoute = createRootRoute({ component: () => <Outlet /> })
  const indexRoute = createRoute({
    getParentRoute: () => rootRoute,
    path: '/',
    component: () => <>{ui}</>,
  })
  // Register `/jobs` so a Link with to="/jobs" type-checks at runtime.
  const jobsRoute = createRoute({
    getParentRoute: () => rootRoute,
    path: '/jobs',
    component: () => <div>jobs</div>,
  })
  const router = createRouter({
    routeTree: rootRoute.addChildren([indexRoute, jobsRoute]),
    history: createMemoryHistory({ initialEntries: ['/'] }),
  })
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  return render(<RouterProvider router={router as any} />)
}

describe('EmptyState', () => {
  it('renders title and message', async () => {
    renderInRouter(
      <EmptyState title="No jobs yet" message="Create your first job to get started." />,
    )
    expect(await screen.findByText('No jobs yet')).toBeInTheDocument()
    expect(screen.getByText('Create your first job to get started.')).toBeInTheDocument()
  })

  it('renders a Link with the action.to href', async () => {
    renderInRouter(
      <EmptyState
        title="No jobs yet"
        message="Create one."
        action={{ label: 'Create your first job', to: '/jobs' }}
        data-testid="es"
      />,
    )
    const link = await screen.findByTestId('es-action')
    expect(link.tagName.toLowerCase()).toBe('a')
    expect(link.getAttribute('href')).toBe('/jobs')
    expect(link.textContent).toBe('Create your first job')
  })

  it('fires action.onClick when the button is clicked', async () => {
    const onClick = vi.fn()
    renderInRouter(
      <EmptyState
        title="Filtered"
        message="No matches."
        action={{ label: 'Clear filters', onClick }}
        data-testid="es"
      />,
    )
    const btn = await screen.findByTestId('es-action')
    expect(btn.tagName.toLowerCase()).toBe('button')
    fireEvent.click(btn)
    expect(onClick).toHaveBeenCalledTimes(1)
  })

  it('renders without an action without crashing and shows no CTA', async () => {
    renderInRouter(<EmptyState title="No approvals" message="None pending." />)
    expect(await screen.findByText('No approvals')).toBeInTheDocument()
    expect(screen.queryByRole('button')).toBeNull()
    expect(screen.queryByRole('link')).toBeNull()
  })

  it('exposes role="status" with the title as accessible name; icon is aria-hidden', async () => {
    renderInRouter(
      <EmptyState
        icon={<svg data-testid="icon" />}
        title="No workers connected"
        message="Start one."
      />,
    )
    const status = await screen.findByRole('status')
    expect(status.getAttribute('aria-label')).toBe('No workers connected')
    // The icon's wrapper carries aria-hidden, so the icon subtree is not
    // exposed to assistive tech.
    const icon = screen.getByTestId('icon')
    const wrapper = icon.parentElement
    expect(wrapper?.getAttribute('aria-hidden')).not.toBeNull()
  })
})
