/**
 * Sidebar 'Starred' section — renders only when the user has at least one
 * server-backed star (#703). Adversarial: empty payload must NOT render the
 * section header (an empty 'Starred' label is noise on a fresh install).
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { render, screen, waitFor, within, cleanup } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { RouterProvider, createRouter, createMemoryHistory } from '@tanstack/react-router'
import { routeTree } from '../routeTree.gen'
import { AuthContext, type AuthState } from '../auth/AuthProvider'
import { setAccessToken } from '../auth/tokenStore'
import { setupFetchMock, resetFetchMock } from './msw-handlers'
import type { JobDto } from '../api/types'

const fakeAuth: AuthState = {
  user: { access_token: 'fake', expired: false } as unknown as AuthState['user'],
  isLoading: false,
  isAuthenticated: true,
  signinRedirect: async () => {},
  signinRedirectCallback: async () => ({} as never),
  signoutRedirect: async () => {},
}

function renderAt(path: string) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const router = createRouter({
    routeTree,
    history: createMemoryHistory({ initialEntries: [path] }),
  })
  return render(
    <AuthContext.Provider value={fakeAuth}>
      <QueryClientProvider client={qc}>
        <RouterProvider router={router} />
      </QueryClientProvider>
    </AuthContext.Provider>,
  )
}

function bindFetch(stars: JobDto[]) {
  setupFetchMock([
    (url, method) => {
      if (method === 'GET' && url.pathname === '/api/v1/me/starred-jobs') {
        return { status: 200, body: stars }
      }
      if (method === 'GET' && url.pathname === '/api/v1/jobs') {
        return { status: 200, body: { items: [], total: 0, offset: 0, limit: 50 } }
      }
      if (method === 'GET' && url.pathname === '/api/v1/workers') {
        return { status: 200, body: { items: [], total: 0, offset: 0, limit: 200 } }
      }
      if (method === 'GET' && url.pathname === '/api/v1/stats') {
        return {
          status: 200,
          body: { buildsToday: 0, successRate: 0, medianDurationMs: 0 },
        }
      }
      if (method === 'GET' && url.pathname === '/api/v1/activity') {
        return { status: 200, body: { items: [], nextCursor: null } }
      }
      return null
    },
  ])
}

beforeEach(() => {
  setAccessToken('fake')
})

afterEach(() => {
  cleanup()
  resetFetchMock()
  setAccessToken(null)
  vi.restoreAllMocks()
})

describe('Sidebar Starred section (#703)', () => {
  it('renders nothing when the user has zero stars', async () => {
    bindFetch([])
    renderAt('/jobs')
    // Wait for the sidebar to render before asserting the starred section is
    // absent — scoped to the sidebar so we don't false-match the jobs page
    // header's own "Workspace" string.
    await waitFor(() => {
      const sidebar = screen.getByTestId('sidebar')
      // "Workspace" appears twice in the sidebar (brand subtitle + nav-label),
      // so a single getByText would throw. We only care that the sidebar has
      // rendered before asserting the starred section is absent.
      expect(within(sidebar).getAllByText('Workspace').length).toBeGreaterThan(0)
    })
    expect(screen.queryByTestId('sidebar-starred-section')).toBeNull()
  })

  it('renders one entry per pinned job, capped at the server-side return', async () => {
    const stars: JobDto[] = [
      {
        id: 401,
        fullName: 'org/pinned-alpha',
        displayName: 'pinned-alpha',
        folderPath: null,
        enabled: true,
        pipelineScript: '',
        createdAt: '2026-05-20T08:00:00Z',
        updatedAt: '2026-05-20T08:00:00Z',
      },
      {
        id: 402,
        fullName: 'org/pinned-bravo',
        displayName: 'pinned-bravo',
        folderPath: null,
        enabled: true,
        pipelineScript: '',
        createdAt: '2026-05-20T08:00:00Z',
        updatedAt: '2026-05-20T08:00:00Z',
      },
    ]
    bindFetch(stars)
    renderAt('/jobs')

    await waitFor(() => {
      expect(screen.getByTestId('sidebar-starred-section')).toBeInTheDocument()
    })
    expect(screen.getByTestId('sidebar-starred-401')).toBeInTheDocument()
    expect(screen.getByTestId('sidebar-starred-402')).toBeInTheDocument()
    expect(screen.getByText('pinned-alpha')).toBeInTheDocument()
    expect(screen.getByText('pinned-bravo')).toBeInTheDocument()
  })
})
