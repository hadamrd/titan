/**
 * Adversarial tests for the /jobs per-row quick-trigger (issue #632).
 *
 * Covers the three failure modes that matter:
 *   1. Click on the trigger button MUST fire POST /jobs/:id/builds AND MUST NOT
 *      navigate the row (link click would land us on the job detail; the user
 *      asked for a quick trigger, not a context switch).
 *   2. Trigger button is DISABLED when lastBuild.status === 'RUNNING' — a
 *      panic-retry must not accidentally double-queue a build.
 *   3. On a 201 success, the row surfaces a confirmation whose text contains
 *      "queued" — that is the affordance the user is looking at the row for.
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { RouterProvider, createRouter, createMemoryHistory } from '@tanstack/react-router'
import { routeTree } from '../routeTree.gen'
import { AuthContext, type AuthState } from '../auth/AuthProvider'
import { setAccessToken } from '../auth/tokenStore'
import { setupFetchMock, resetFetchMock } from './msw-handlers'
import type { JobsPage } from '../api/types'

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
        id: 201,
        fullName: 'org/idle',
        displayName: 'idle-job',
        folderPath: null,
        enabled: true,
        createdAt: '2026-05-20T08:00:00Z',
        updatedAt: '2026-05-20T08:00:00Z',
        lastBuild: {
          id: 9501,
          buildNumber: 4,
          status: 'SUCCESS',
          durationMs: 60_000,
          finishedAt: '2026-05-20T09:01:00Z',
        },
      },
      {
        id: 202,
        fullName: 'org/inflight',
        displayName: 'inflight-job',
        folderPath: null,
        enabled: true,
        createdAt: '2026-05-20T08:00:00Z',
        updatedAt: '2026-05-20T08:00:00Z',
        lastBuild: {
          id: 9502,
          buildNumber: 8,
          status: 'RUNNING',
          durationMs: 0,
          finishedAt: '',
        },
      },
      {
        id: 203,
        fullName: 'org/parameterized',
        displayName: 'parameterized-job',
        folderPath: null,
        enabled: true,
        createdAt: '2026-05-20T08:00:00Z',
        updatedAt: '2026-05-20T08:00:00Z',
        lastBuild: {
          id: 9503,
          buildNumber: 11,
          status: 'SUCCESS',
          durationMs: 30_000,
          finishedAt: '2026-05-20T09:01:00Z',
        },
      },
    ],
    total: 3,
    offset: 0,
    limit: 50,
  }
}

let triggerCalls: Array<{ url: string; method: string }> = []

beforeEach(() => {
  triggerCalls = []
  const payload = jobsPayload()
  setupFetchMock([
    (url, method) => {
      if (method !== 'GET') return null
      if (url.pathname !== '/api/v1/jobs') return null
      return { status: 200, body: payload }
    },
    // GET /jobs/:id/parameters — the lazy fetch the quick-trigger now makes
    // before deciding modal-vs-direct. Job 203 declares one (non-required,
    // defaulted) parameter; every other job declares none.
    (url, method) => {
      if (method !== 'GET') return null
      const m = /^\/api\/v1\/jobs\/(\d+)\/parameters$/.exec(url.pathname)
      if (!m) return null
      if (Number(m[1]) === 203) {
        return {
          status: 200,
          body: [
            {
              name: 'ENV',
              type: 'STRING',
              defaultValue: 'staging',
              description: 'target environment',
              required: false,
              choices: [],
            },
          ],
        }
      }
      return { status: 200, body: [] }
    },
    (url, method) => {
      if (method !== 'POST') return null
      const m = /^\/api\/v1\/jobs\/(\d+)\/builds$/.exec(url.pathname)
      if (!m) return null
      triggerCalls.push({ url: url.pathname, method })
      const jobId = Number(m[1])
      return {
        status: 201,
        body: {
          buildId: 9999,
          buildNumber: 42,
          jobId,
          queuedPosition: 1,
        },
      }
    },
    (url, method) => {
      if (method !== 'GET') return null
      if (url.pathname === '/api/v1/workers') {
        return { status: 200, body: { items: [], total: 0, offset: 0, limit: 200 } }
      }
      if (url.pathname === '/api/v1/stats') {
        return {
          status: 200,
          body: { buildsToday: 0, successRate: 0, medianDurationMs: 0 },
        }
      }
      if (url.pathname === '/api/v1/activity') {
        return { status: 200, body: { items: [], nextCursor: null } }
      }
      return null
    },
  ])
  setAccessToken('fake')
})

afterEach(() => {
  resetFetchMock()
  setAccessToken(null)
  vi.restoreAllMocks()
})

describe('/jobs quick-trigger (issue #632)', () => {
  it('clicking the trigger fires POST /jobs/:id/builds without navigating', async () => {
    renderAt('/pipelines')
    await waitFor(() => expect(screen.getByText('idle-job')).toBeInTheDocument())

    const btn = screen.getByTestId('job-row-201-trigger')
    fireEvent.click(btn)

    await waitFor(() => expect(triggerCalls.length).toBe(1))
    expect(triggerCalls[0].url).toBe('/api/v1/jobs/201/builds')
    expect(triggerCalls[0].method).toBe('POST')

    // Still on /pipelines — the click did NOT propagate to the row's View
    // link. The page header "Pipelines" must still be visible (we did not
    // navigate away to the detail page).
    expect(screen.getByRole('heading', { name: 'Pipelines' })).toBeInTheDocument()
  })

  it('disables the trigger when lastBuild.status === RUNNING', async () => {
    renderAt('/pipelines')
    await waitFor(() => expect(screen.getByText('inflight-job')).toBeInTheDocument())

    const btn = screen.getByTestId('job-row-202-trigger') as HTMLButtonElement
    expect(btn.disabled).toBe(true)
    expect(btn.getAttribute('title')).toBe('Build already in flight')

    // Click is a no-op while disabled.
    fireEvent.click(btn)
    // Wait a tick to be safe; mutation must not fire.
    await new Promise((r) => setTimeout(r, 30))
    expect(triggerCalls.length).toBe(0)
  })

  it('shows inline "queued" confirmation on 201 response', async () => {
    renderAt('/pipelines')
    await waitFor(() => expect(screen.getByText('idle-job')).toBeInTheDocument())

    fireEvent.click(screen.getByTestId('job-row-201-trigger'))

    const status = await screen.findByTestId('job-row-201-trigger-status')
    expect(status.textContent?.toLowerCase()).toContain('queued')
    // Build number from the mocked 201 surfaces in the confirmation.
    expect(status.textContent).toContain('42')
  })

  // ── The gap the operator hit (params modal never opened from the LIST) ──────
  // The detail page (#779) opens TriggerParamsModal for a parameterized
  // pipeline, but the list-row quick-trigger fired a build directly with no
  // way to set parameters. These two tests pin the parity so it can't regress.

  it('a PARAMETERIZED pipeline opens the params modal instead of firing a build', async () => {
    renderAt('/pipelines')
    await waitFor(() =>
      expect(screen.getByText('parameterized-job')).toBeInTheDocument(),
    )

    fireEvent.click(screen.getByTestId('job-row-203-trigger'))

    // The set-parameters modal must appear …
    await screen.findByTestId('trigger-params-backdrop')
    expect(screen.getByText('ENV')).toBeInTheDocument()

    // … and NO build may have been queued yet — the user hasn't confirmed.
    await new Promise((r) => setTimeout(r, 30))
    expect(triggerCalls.length).toBe(0)
  })

  it('confirming the params modal then fires the build for that pipeline', async () => {
    renderAt('/pipelines')
    await waitFor(() =>
      expect(screen.getByText('parameterized-job')).toBeInTheDocument(),
    )

    fireEvent.click(screen.getByTestId('job-row-203-trigger'))
    await screen.findByTestId('trigger-params-backdrop')

    fireEvent.click(screen.getByTestId('trigger-params-confirm'))

    await waitFor(() => expect(triggerCalls.length).toBe(1))
    expect(triggerCalls[0].url).toBe('/api/v1/jobs/203/builds')
  })
})
