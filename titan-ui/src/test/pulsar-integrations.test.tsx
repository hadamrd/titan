/**
 * Tests for the Pulsar provider card on /integrations (#1283).
 *
 * The admin source-registration endpoint now exists on trunk
 * (GET|POST /api/v1/pulsar/sources + POST .../{id}/sync — PulsarSourcesApi,
 * #1293), so the card is LIVE: it flips to {@code available: true} and links to
 * the real {@code /integrations/pulsar} detail route. These tests pin that the
 * card is a working link — NOT a "Coming soon" placeholder — and that it points
 * at the registration route, so the card can't silently regress into a dead
 * link or back into a disabled placeholder.
 *
 * Harness mirrors integrations-ia.test.tsx: pull the page component through
 * `Route.options.component` and render it under a synthetic memory router.
 */
import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest'
import { render, screen, cleanup } from '@testing-library/react'
import {
  RouterProvider,
  createRouter,
  createMemoryHistory,
  createRootRoute,
  createRoute,
  Outlet,
} from '@tanstack/react-router'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'

// Mock BEFORE importing the route module — the index page calls these hooks.
vi.mock('@/api/githubApp', async () => {
  const actual = await vi.importActual<typeof import('@/api/githubApp')>('@/api/githubApp')
  return {
    ...actual,
    useGithubApp: vi.fn(),
    useGithubInstallations: vi.fn(),
  }
})

import { useGithubApp, useGithubInstallations } from '@/api/githubApp'
import { Route as IntegrationsIndexRoute } from '@/routes/integrations.index'

const mockedUseGithubApp = vi.mocked(useGithubApp)
const mockedUseInstalls = vi.mocked(useGithubInstallations)

function makeQuery(overrides: Record<string, unknown> = {}): any {
  return {
    data: undefined,
    isLoading: false,
    isError: false,
    error: null,
    isFetching: false,
    ...overrides,
  }
}

function renderIndex() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const Component = IntegrationsIndexRoute.options.component as () => React.JSX.Element
  const rootRoute = createRootRoute({ component: () => <Outlet /> })
  const integrationsIndexRoute = createRoute({
    getParentRoute: () => rootRoute,
    path: '/integrations',
    component: Component,
  })
  // Live targets the grid links to.
  const githubIndexRoute = createRoute({
    getParentRoute: () => rootRoute,
    path: '/integrations/github',
    component: () => <div>github</div>,
  })
  const pulsarIndexRoute = createRoute({
    getParentRoute: () => rootRoute,
    path: '/integrations/pulsar',
    component: () => <div>pulsar</div>,
  })
  const router = createRouter({
    routeTree: rootRoute.addChildren([
      integrationsIndexRoute,
      githubIndexRoute,
      pulsarIndexRoute,
    ]),
    history: createMemoryHistory({ initialEntries: ['/integrations'] }),
  })
  return render(
    <QueryClientProvider client={qc}>
      <RouterProvider router={router as any} />
    </QueryClientProvider>,
  )
}

beforeEach(() => {
  vi.clearAllMocks()
  mockedUseGithubApp.mockReturnValue(makeQuery({ data: null }))
  mockedUseInstalls.mockReturnValue(makeQuery({ data: [] }))
})
afterEach(() => cleanup())

describe('/integrations — Pulsar provider card', () => {
  it('renders a Pulsar card in the grid', async () => {
    renderIndex()
    expect(await screen.findByTestId('provider-card-pulsar')).toBeInTheDocument()
  })

  it('is a live link (not aria-disabled, not "Coming soon")', async () => {
    renderIndex()
    const card = await screen.findByTestId('provider-card-pulsar')
    // It is now a real anchor pointing at the registration detail route.
    expect(card.tagName.toLowerCase()).toBe('a')
    expect(card.getAttribute('aria-disabled')).toBeNull()
    expect(screen.getByTestId('provider-card-pulsar-status').textContent).not.toBe(
      'Coming soon',
    )
    // The idle affordance shows "Connect", not the disabled placeholder.
    expect(screen.getByTestId('provider-card-pulsar-status').textContent).toContain(
      'Connect',
    )
  })

  it('links to the real /integrations/pulsar registration route', async () => {
    renderIndex()
    const card = await screen.findByTestId('provider-card-pulsar')
    expect(card.getAttribute('href')).toBe('/integrations/pulsar')
  })

  it('does NOT link Pulsar at the GitHub route (regression guard)', async () => {
    renderIndex()
    const card = await screen.findByTestId('provider-card-pulsar')
    expect(card.getAttribute('href')).not.toBe('/integrations/github')
  })
})
