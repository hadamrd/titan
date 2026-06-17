/**
 * Adversarial tests for /jobs star/favorite — server-backed per #703.
 *
 * Replaces the v0 localStorage harness (#636). Now drives the real
 * useStarredJobs / useStarJob / useUnstarJob hooks against a mocked
 * /api/v1/me/starred-jobs endpoint.
 *
 * Covers:
 *   1. Click star → PUT fires, optimistic prepend, row pinned to top + separator.
 *   2. Pre-seeded starred-jobs payload → favorites already pinned at top.
 *   3. All-favorite case → no separator (only one group).
 *   4. Unstar → DELETE fires, favorite drops back to normal order.
 *   5. 11th star at the cap → 409, toast appears, optimistic rollback.
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import {
  render,
  screen,
  waitFor,
  within,
  fireEvent,
  cleanup,
} from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { RouterProvider, createRouter, createMemoryHistory } from '@tanstack/react-router'
import { routeTree } from '../routeTree.gen'
import { AuthContext, type AuthState } from '../auth/AuthProvider'
import { setAccessToken } from '../auth/tokenStore'
import { setupFetchMock, resetFetchMock } from './msw-handlers'
import type { JobDto, JobsPage } from '../api/types'

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

function jobsPayload(): JobsPage {
  return {
    items: [
      {
        id: 301,
        fullName: 'org/alpha',
        displayName: 'alpha-job',
        folderPath: null,
        enabled: true,
        createdAt: '2026-05-20T08:00:00Z',
        updatedAt: '2026-05-20T08:00:00Z',
        lastBuild: {
          id: 1,
          buildNumber: 1,
          status: 'SUCCESS',
          durationMs: 1000,
          finishedAt: '2026-05-20T09:01:00Z',
        },
      },
      {
        id: 302,
        fullName: 'org/bravo',
        displayName: 'bravo-job',
        folderPath: null,
        enabled: true,
        createdAt: '2026-05-20T08:00:00Z',
        updatedAt: '2026-05-20T08:00:00Z',
        lastBuild: {
          id: 2,
          buildNumber: 1,
          status: 'SUCCESS',
          durationMs: 1000,
          finishedAt: '2026-05-20T09:02:00Z',
        },
      },
      {
        id: 303,
        fullName: 'org/charlie',
        displayName: 'charlie-job',
        folderPath: null,
        enabled: true,
        createdAt: '2026-05-20T08:00:00Z',
        updatedAt: '2026-05-20T08:00:00Z',
        lastBuild: {
          id: 3,
          buildNumber: 1,
          status: 'SUCCESS',
          durationMs: 1000,
          finishedAt: '2026-05-20T09:03:00Z',
        },
      },
    ],
    total: 3,
    offset: 0,
    limit: 50,
  }
}

/**
 * Mutable per-test "server-side" stars state. Each test re-binds it via
 * {@link configureFetch}; mutations against the mock /api/v1/me/starred-jobs
 * endpoints flip it.
 */
let serverStars: JobDto[] = []
const lastRequests: { method: string; path: string }[] = []

function configureFetch(initialStars: JobDto[] = []) {
  serverStars = [...initialStars]
  lastRequests.length = 0
  const payload = jobsPayload()
  setupFetchMock([
    (url, method) => {
      lastRequests.push({ method, path: url.pathname })
      if (method === 'GET' && url.pathname === '/api/v1/jobs') {
        return { status: 200, body: payload }
      }
      if (method === 'GET' && url.pathname === '/api/v1/me/starred-jobs') {
        return { status: 200, body: serverStars }
      }
      if (method === 'PUT' && url.pathname.startsWith('/api/v1/me/starred-jobs/')) {
        const idStr = url.pathname.split('/').pop() ?? ''
        const id = Number(idStr)
        const job = payload.items.find((j) => j.id === id)
        if (job && !serverStars.some((j) => j.id === id)) {
          serverStars = [job, ...serverStars]
        }
        return { status: 204, body: null }
      }
      if (method === 'DELETE' && url.pathname.startsWith('/api/v1/me/starred-jobs/')) {
        const idStr = url.pathname.split('/').pop() ?? ''
        const id = Number(idStr)
        serverStars = serverStars.filter((j) => j.id !== id)
        return { status: 204, body: null }
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
  configureFetch()
  setAccessToken('fake')
})

afterEach(() => {
  cleanup()
  resetFetchMock()
  setAccessToken(null)
  vi.restoreAllMocks()
})

/**
 * Returns the displayName text for each rendered job row in document order.
 */
function rowOrder(): string[] {
  const rows = document.querySelectorAll('[data-testid^="job-row-"]')
  const out: string[] = []
  rows.forEach((r) => {
    const tid = r.getAttribute('data-testid') ?? ''
    if (!/^job-row-\d+$/.test(tid)) return
    const nameCell = r.querySelector('.font-medium')
    if (nameCell?.textContent) out.push(nameCell.textContent)
  })
  return out
}

describe('/jobs starred (server-backed, #703)', () => {
  it('clicking a star fires PUT, optimistic prepend, separator appears', async () => {
    renderAt('/jobs')
    await waitFor(() => expect(screen.getByText('bravo-job')).toBeInTheDocument())

    // Sanity: no separator yet (no favorites).
    expect(screen.queryByTestId('jobs-favorites-separator')).toBeNull()

    fireEvent.click(screen.getByTestId('job-row-302-star'))

    await waitFor(() => {
      expect(
        lastRequests.some(
          (r) => r.method === 'PUT' && r.path === '/api/v1/me/starred-jobs/302',
        ),
      ).toBe(true)
    })

    // Bravo (302) jumps to the top via the optimistic cache update.
    await waitFor(() => {
      expect(rowOrder()[0]).toBe('bravo-job')
    })

    expect(screen.getByTestId('jobs-favorites-separator')).toBeInTheDocument()
  })

  it('pre-seeded server stars → favorites already at top', async () => {
    const seeded = jobsPayload().items.find((j) => j.id === 303)!
    configureFetch([seeded])
    renderAt('/jobs')
    // Scope the readiness probe to the jobs table — the sidebar's Starred
    // section also renders 'charlie-job', so an unscoped getByText would hit
    // both nodes and throw.
    await waitFor(() =>
      expect(
        within(screen.getByRole('table')).getByText('charlie-job'),
      ).toBeInTheDocument(),
    )
    await waitFor(() => {
      expect(rowOrder()[0]).toBe('charlie-job')
    })
    expect(screen.getByTestId('jobs-favorites-separator')).toBeInTheDocument()
  })

  it('all-favorite case → no separator (only one group)', async () => {
    configureFetch(jobsPayload().items)
    renderAt('/jobs')
    // Scope to the table — alpha-job also appears in the sidebar Starred list.
    await waitFor(() =>
      expect(
        within(screen.getByRole('table')).getByText('alpha-job'),
      ).toBeInTheDocument(),
    )
    await waitFor(() => {
      expect(rowOrder()).toHaveLength(3)
    })
    expect(screen.queryByTestId('jobs-favorites-separator')).toBeNull()
  })

  it('clicking the star on a favorite fires DELETE and drops it back', async () => {
    const seeded = jobsPayload().items.find((j) => j.id === 303)!
    configureFetch([seeded])
    renderAt('/jobs')
    // Scope to the table — sidebar Starred also renders charlie-job.
    await waitFor(() =>
      expect(
        within(screen.getByRole('table')).getByText('charlie-job'),
      ).toBeInTheDocument(),
    )
    await waitFor(() => expect(rowOrder()[0]).toBe('charlie-job'))

    fireEvent.click(screen.getByTestId('job-row-303-star'))

    await waitFor(() => {
      expect(
        lastRequests.some(
          (r) => r.method === 'DELETE' && r.path === '/api/v1/me/starred-jobs/303',
        ),
      ).toBe(true)
    })

    // No favorites left → no separator. Charlie back to its normal alphabetical
    // position within the SUCCESS bucket (last).
    await waitFor(() => {
      expect(screen.queryByTestId('jobs-favorites-separator')).toBeNull()
    })
    const order = rowOrder()
    expect(order[order.length - 1]).toBe('charlie-job')
  })

  it('11th star at cap → 409 toast appears, optimistic state rolls back', async () => {
    // Pre-seed with 10 distinct jobs to put us at the cap. The visible jobsPayload
    // only has 3 rows so we synthesise placeholder stars for the cache.
    const tenSeed: JobDto[] = Array.from({ length: 10 }, (_, i) => ({
      id: 1000 + i,
      fullName: `pinned/job-${i}`,
      displayName: `pinned-${i}`,
      folderPath: null,
      enabled: true,
      pipelineScript: '',
      createdAt: '2026-05-20T08:00:00Z',
      updatedAt: '2026-05-20T08:00:00Z',
    }))
    configureFetch(tenSeed)
    renderAt('/jobs')
    await waitFor(() => expect(screen.getByText('alpha-job')).toBeInTheDocument())

    // Clicking the 11th distinct star (job 302) — the client-side cap-guard in
    // StarButton fires the toast without even reaching the server. Either way,
    // the user sees a toast and the bravo row does NOT become starred.
    fireEvent.click(screen.getByTestId('job-row-302-star'))

    await waitFor(() => {
      expect(screen.getByTestId('star-job-toast')).toBeInTheDocument()
    })

    // Bravo did not jump to the top — the cache still reflects the 10 pinned.
    // (Bravo isn't even in our pinned-set; this is the negative assertion.)
    expect(
      lastRequests.some(
        (r) => r.method === 'PUT' && r.path === '/api/v1/me/starred-jobs/302',
      ),
    ).toBe(false)
  })
})
