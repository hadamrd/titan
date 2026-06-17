/**
 * Tests for the /integrations/pulsar detail page (#1283 / #1293).
 *
 * Renders the real route component with the api/pulsar hooks mocked, and
 * asserts the page actually drives the endpoint: it lists registered sources,
 * the connect form calls registerPulsarSource on submit, the per-source Sync
 * button calls syncPulsarSource, and a typed register error renders inline.
 *
 * Harness mirrors pulsar-integrations.test.tsx: component via
 * `Route.options.component` under a synthetic memory router.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, cleanup, fireEvent, waitFor } from '@testing-library/react'
import {
  RouterProvider,
  createRouter,
  createMemoryHistory,
  createRootRoute,
  createRoute,
  Outlet,
} from '@tanstack/react-router'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { ApiError } from '@/api/types'

const registerMutate = vi.fn()
const syncMutate = vi.fn()

// Mutable mock state the hooks read so each test can shape isPending/isError.
const registerState = {
  mutate: registerMutate,
  isPending: false,
  isError: false,
  error: null as ApiError | null,
}
const syncState = {
  mutate: syncMutate,
  isPending: false,
  isError: false,
  error: null as ApiError | null,
}
const sourcesState: { data: unknown; isLoading: boolean; isError: boolean; error: unknown } = {
  data: [],
  isLoading: false,
  isError: false,
  error: null,
}

vi.mock('@/api/pulsar', async () => {
  const actual = await vi.importActual<typeof import('@/api/pulsar')>('@/api/pulsar')
  return {
    ...actual,
    usePulsarSources: () => sourcesState,
    useRegisterPulsarSource: () => registerState,
    useSyncPulsarSource: () => syncState,
  }
})

import { Route as PulsarRoute } from '@/routes/integrations.pulsar.index'

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const Component = PulsarRoute.options.component as () => React.JSX.Element
  const rootRoute = createRootRoute({ component: () => <Outlet /> })
  const pulsarRoute = createRoute({
    getParentRoute: () => rootRoute,
    path: '/integrations/pulsar',
    component: Component,
  })
  const integrationsRoute = createRoute({
    getParentRoute: () => rootRoute,
    path: '/integrations',
    component: () => <div>integrations</div>,
  })
  const router = createRouter({
    routeTree: rootRoute.addChildren([pulsarRoute, integrationsRoute]),
    history: createMemoryHistory({ initialEntries: ['/integrations/pulsar'] }),
  })
  return render(
    <QueryClientProvider client={qc}>
      <RouterProvider router={router as any} />
    </QueryClientProvider>,
  )
}

beforeEach(() => {
  vi.clearAllMocks()
  registerState.isPending = false
  registerState.isError = false
  registerState.error = null
  syncState.isPending = false
  syncState.isError = false
  syncState.error = null
  sourcesState.data = []
  sourcesState.isLoading = false
  sourcesState.isError = false
  sourcesState.error = null
})
afterEach(() => cleanup())

describe('/integrations/pulsar', () => {
  it('shows the empty state when no sources are registered', async () => {
    renderPage()
    expect(await screen.findByTestId('pulsar-sources-empty')).toBeInTheDocument()
  })

  it('renders registered sources from the query (url, repoCount, lastPolledAt)', async () => {
    sourcesState.data = [
      {
        id: 7,
        nodeUrl: 'https://pulsar.example.com',
        nodeName: 'prod-east',
        repoCount: 4,
        lastPolledAt: '2026-06-15T10:00:00Z',
        createdAt: '2026-06-15T09:00:00Z',
      },
    ]
    renderPage()
    expect(await screen.findByTestId('pulsar-source-row-7')).toBeInTheDocument()
    expect(screen.getByTestId('pulsar-source-url-7').textContent).toBe(
      'https://pulsar.example.com',
    )
    expect(screen.getByTestId('pulsar-source-repos-7').textContent).toBe('4 repos')
    expect(screen.getByTestId('pulsar-source-polled-7').textContent).toBe('synced 2026-06-15')
  })

  it('renders "repos unknown" / "never synced" when the node was never probed', async () => {
    sourcesState.data = [
      { id: 8, nodeUrl: 'https://p2.example.com', createdAt: '2026-06-15T09:00:00Z' },
    ]
    renderPage()
    expect(await screen.findByTestId('pulsar-source-row-8')).toBeInTheDocument()
    expect(screen.getByTestId('pulsar-source-repos-8').textContent).toBe('repos unknown')
    expect(screen.getByTestId('pulsar-source-polled-8').textContent).toBe('never synced')
  })

  it('submitting the connect form calls registerPulsarSource with the entered values', async () => {
    renderPage()
    const urlInput = await screen.findByTestId('pulsar-node-url-input')
    const nameInput = screen.getByTestId('pulsar-node-name-input')
    fireEvent.change(urlInput, { target: { value: 'https://new.example.com' } })
    fireEvent.change(nameInput, { target: { value: 'edge-1' } })
    fireEvent.submit(screen.getByTestId('pulsar-connect-form'))
    await waitFor(() => expect(registerMutate).toHaveBeenCalledTimes(1))
    expect(registerMutate.mock.calls[0][0]).toEqual({
      nodeUrl: 'https://new.example.com',
      nodeName: 'edge-1',
    })
  })

  it('does not submit an empty node URL', async () => {
    renderPage()
    await screen.findByTestId('pulsar-connect-form')
    fireEvent.submit(screen.getByTestId('pulsar-connect-form'))
    expect(registerMutate).not.toHaveBeenCalled()
  })

  it('renders the typed register error inline (409 duplicate)', async () => {
    registerState.isError = true
    registerState.error = new ApiError(409, {
      type: 'about:blank',
      title: 'pulsar-source-exists',
      status: 409,
      detail: 'already registered',
      instance: null,
    })
    renderPage()
    const alert = await screen.findByTestId('pulsar-connect-error')
    expect(alert.textContent).toMatch(/already registered/i)
  })

  it('clicking Sync on a source calls syncPulsarSource with its id', async () => {
    sourcesState.data = [
      { id: 7, nodeUrl: 'https://pulsar.example.com', createdAt: '2026-06-15T09:00:00Z' },
    ]
    renderPage()
    const syncBtn = await screen.findByTestId('pulsar-source-sync-7')
    fireEvent.click(syncBtn)
    await waitFor(() => expect(syncMutate).toHaveBeenCalledTimes(1))
    expect(syncMutate.mock.calls[0][0]).toEqual({ id: 7 })
  })
})
