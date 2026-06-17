/**
 * Adversarial tests for /jobs default sort = FAILED-first (issue #544).
 *
 * The SRE workflow: when the fleet goes red the operator lands on /jobs and
 * MUST see red rows first without scrolling past a wall of green. The
 * pre-#544 default (alphabetical) buried red jobs whose names started with
 * 'z'. These tests pin the corrected behaviour and the user-pref persistence.
 */
import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, fireEvent, within, cleanup } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { RouterProvider, createRouter, createMemoryHistory } from '@tanstack/react-router'
import { routeTree } from '../routeTree.gen'
import { AuthContext, type AuthState } from '../auth/AuthProvider'
import { setAccessToken } from '../auth/tokenStore'
import { setupFetchMock, resetFetchMock } from './msw-handlers'
import type { JobDto, JobsPage } from '../api/types'
import { sortJobs, type SortKey } from '../routes/pipelines/index'

const fakeAuth: AuthState = {
  user: { access_token: 'fake', expired: false } as unknown as AuthState['user'],
  isLoading: false,
  isAuthenticated: true,
  signinRedirect: async () => {},
  signinRedirectCallback: async () => ({} as never),
  signoutRedirect: async () => {},
}

const STORAGE_KEY = 'titan.ui.jobsSort'

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

/**
 * Three jobs whose NAMES are deliberately ordered so that alphabetical and
 * status sorts produce DIFFERENT first rows. If we accidentally still
 * default to alphabetical, "a-good" sorts to row 0 and the test fails. With
 * the correct FAILED-first default, "z-broken" wins row 0.
 *
 *   id 101 — a-good       — SUCCESS  (alpha-first; status-last bucket)
 *   id 102 — z-broken     — FAILED   (alpha-last;  status-first bucket)
 *   id 103 — m-never      — no build (alpha-mid;   status-middle bucket)
 */
function adversarialPayload(): JobsPage {
  return {
    items: [
      {
        id: 101,
        fullName: 'org/a-good',
        displayName: 'a-good',
        folderPath: null,
        enabled: true,
        createdAt: '2026-05-20T08:00:00Z',
        updatedAt: '2026-05-20T08:00:00Z',
        lastBuild: {
          id: 9001,
          buildNumber: 7,
          status: 'SUCCESS',
          durationMs: 60_000,
          finishedAt: '2026-05-20T09:01:00Z',
        },
      },
      {
        id: 102,
        fullName: 'org/z-broken',
        displayName: 'z-broken',
        folderPath: null,
        enabled: true,
        createdAt: '2026-05-20T08:00:00Z',
        updatedAt: '2026-05-20T08:00:00Z',
        lastBuild: {
          id: 9002,
          buildNumber: 3,
          status: 'FAILED',
          durationMs: 120_000,
          finishedAt: '2026-05-20T09:02:00Z',
        },
      },
      {
        id: 103,
        fullName: 'org/m-never',
        displayName: 'm-never',
        folderPath: null,
        enabled: true,
        createdAt: '2026-05-20T08:00:00Z',
        updatedAt: '2026-05-20T08:00:00Z',
        // lastBuild omitted — server strips NON_NULL.
      },
    ],
    total: 3,
    offset: 0,
    limit: 50,
  }
}

function allSuccessPayload(): JobsPage {
  const mk = (id: number, name: string): JobDto => ({
    id,
    fullName: `org/${name}`,
    displayName: name,
    folderPath: null,
    enabled: true,
    createdAt: '2026-05-20T08:00:00Z',
    updatedAt: '2026-05-20T08:00:00Z',
    lastBuild: {
      id: 9000 + id,
      buildNumber: 1,
      status: 'SUCCESS',
      durationMs: 1_000,
      finishedAt: `2026-05-20T09:0${id}:00Z`,
    },
  })
  return {
    items: [mk(1, 'charlie'), mk(2, 'alpha'), mk(3, 'bravo')],
    total: 3,
    offset: 0,
    limit: 50,
  }
}

function installMock(payload: JobsPage) {
  setupFetchMock([
    (url, method) => {
      if (method !== 'GET') return null
      if (url.pathname !== '/api/v1/jobs') return null
      return { status: 200, body: payload }
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
}

beforeEach(() => {
  localStorage.removeItem(STORAGE_KEY)
  setAccessToken('fake')
})

afterEach(() => {
  cleanup()
  resetFetchMock()
  setAccessToken(null)
  localStorage.removeItem(STORAGE_KEY)
})

describe('/jobs default sort — FAILED first (issue #544)', () => {
  it('default sort places the FAILED job at row 0, even when its name sorts LAST alphabetically', async () => {
    installMock(adversarialPayload())
    renderAt('/jobs')
    await waitFor(() => expect(screen.getByText('z-broken')).toBeInTheDocument())
    const rows = document.querySelectorAll('tbody tr[data-testid^="job-row-"]')
    expect(rows.length).toBe(3)
    // Row 0 = the red job (z-broken), even though "a-good" would win
    // alphabetically. This is the load-bearing assertion of #544.
    expect(within(rows[0] as HTMLElement).getByText('z-broken')).toBeInTheDocument()
  })

  it('clicking the Name header switches to alphabetical order', async () => {
    installMock(adversarialPayload())
    renderAt('/jobs')
    await waitFor(() => expect(screen.getByTestId('jobs-sort-name')).toBeInTheDocument())
    fireEvent.click(screen.getByTestId('jobs-sort-name'))
    await waitFor(() => {
      const rows = document.querySelectorAll('tbody tr[data-testid^="job-row-"]')
      expect(rows.length).toBe(3)
      expect(within(rows[0] as HTMLElement).getByText('a-good')).toBeInTheDocument()
      expect(within(rows[1] as HTMLElement).getByText('m-never')).toBeInTheDocument()
      expect(within(rows[2] as HTMLElement).getByText('z-broken')).toBeInTheDocument()
    })
  })

  it('Name header toggles asc → desc on a second click', async () => {
    installMock(adversarialPayload())
    renderAt('/jobs')
    await waitFor(() => expect(screen.getByTestId('jobs-sort-name')).toBeInTheDocument())
    const btn = screen.getByTestId('jobs-sort-name')
    fireEvent.click(btn) // → alpha asc
    fireEvent.click(btn) // → alpha desc
    await waitFor(() => {
      const rows = document.querySelectorAll('tbody tr[data-testid^="job-row-"]')
      expect(within(rows[0] as HTMLElement).getByText('z-broken')).toBeInTheDocument()
      expect(within(rows[2] as HTMLElement).getByText('a-good')).toBeInTheDocument()
    })
    // aria-sort on the header reflects the descending state.
    const th = btn.closest('th')
    expect(th?.getAttribute('aria-sort')).toBe('descending')
  })

  it('persists the user sort to localStorage and restores it on re-render', async () => {
    installMock(adversarialPayload())
    const first = renderAt('/jobs')
    await waitFor(() => expect(screen.getByTestId('jobs-sort-name')).toBeInTheDocument())
    fireEvent.click(screen.getByTestId('jobs-sort-name')) // alpha asc

    // The persisted value should be the alpha sort.
    await waitFor(() => {
      const raw = localStorage.getItem(STORAGE_KEY)
      expect(raw).not.toBeNull()
      const parsed = JSON.parse(raw!) as SortKey
      expect(parsed.key).toBe('alpha')
    })

    // Tear down and re-render — simulates a page reload. The mock is still
    // installed; the route mounts fresh and re-reads localStorage.
    first.unmount()
    const second = renderAt('/jobs')
    await waitFor(() => expect(second.getByText('a-good')).toBeInTheDocument())
    const rows = second.container.querySelectorAll('tbody tr[data-testid^="job-row-"]')
    expect(rows.length).toBe(3)
    // Alphabetical preserved across the simulated reload.
    expect(within(rows[0] as HTMLElement).getByText('a-good')).toBeInTheDocument()
    expect(within(rows[2] as HTMLElement).getByText('z-broken')).toBeInTheDocument()
  })

  it('all-SUCCESS fleet — default sort falls back to alphabetical (no false red signal)', async () => {
    installMock(allSuccessPayload())
    renderAt('/jobs')
    await waitFor(() => expect(screen.getByText('alpha')).toBeInTheDocument())
    const rows = document.querySelectorAll('tbody tr[data-testid^="job-row-"]')
    expect(rows.length).toBe(3)
    // When every row is in the same bucket, the tie-break MUST be
    // alphabetical — alpha, bravo, charlie — not insertion order.
    expect(within(rows[0] as HTMLElement).getByText('alpha')).toBeInTheDocument()
    expect(within(rows[1] as HTMLElement).getByText('bravo')).toBeInTheDocument()
    expect(within(rows[2] as HTMLElement).getByText('charlie')).toBeInTheDocument()
  })

  it('clicking Last build header switches to recency (newest finishedAt first)', async () => {
    installMock(adversarialPayload())
    renderAt('/jobs')
    await waitFor(() => expect(screen.getByTestId('jobs-sort-status')).toBeInTheDocument())
    fireEvent.click(screen.getByTestId('jobs-sort-status')) // → recency
    await waitFor(() => {
      const rows = document.querySelectorAll('tbody tr[data-testid^="job-row-"]')
      // z-broken (09:02) > a-good (09:01) > m-never (no build → last).
      expect(within(rows[0] as HTMLElement).getByText('z-broken')).toBeInTheDocument()
      expect(within(rows[1] as HTMLElement).getByText('a-good')).toBeInTheDocument()
      expect(within(rows[2] as HTMLElement).getByText('m-never')).toBeInTheDocument()
    })
  })

  it('header aria-sort reflects active sort mode', async () => {
    installMock(adversarialPayload())
    renderAt('/jobs')
    await waitFor(() => expect(screen.getByTestId('jobs-sort-status')).toBeInTheDocument())
    // Default = status; Last-build header reports descending; Name = none.
    const nameTh = screen.getByTestId('jobs-sort-name').closest('th')
    const lastTh = screen.getByTestId('jobs-sort-status').closest('th')
    expect(nameTh?.getAttribute('aria-sort')).toBe('none')
    expect(lastTh?.getAttribute('aria-sort')).toBe('descending')
  })
})

describe('sortJobs() pure function — direct invariants', () => {
  const items: JobDto[] = [
    {
      id: 1,
      fullName: 'a',
      displayName: 'a',
      folderPath: null,
      enabled: true,
      createdAt: '',
      updatedAt: '',
      lastBuild: {
        id: 1,
        buildNumber: 1,
        status: 'SUCCESS',
        durationMs: 0,
        finishedAt: '2026-01-01T00:00:00Z',
      },
    },
    {
      id: 2,
      fullName: 'b',
      displayName: 'b',
      folderPath: null,
      enabled: true,
      createdAt: '',
      updatedAt: '',
      lastBuild: {
        id: 2,
        buildNumber: 1,
        status: 'FAILED',
        durationMs: 0,
        finishedAt: '2026-01-02T00:00:00Z',
      },
    },
    {
      id: 3,
      fullName: 'c',
      displayName: 'c',
      folderPath: null,
      enabled: true,
      createdAt: '',
      updatedAt: '',
      lastBuild: {
        id: 3,
        buildNumber: 1,
        status: 'FAILED',
        durationMs: 0,
        finishedAt: '2026-01-03T00:00:00Z',
      },
    },
  ]

  it('status sort orders FAILED-bucket by finishedAt DESC', () => {
    const out = sortJobs(items, { key: 'status' })
    // Two FAILED rows first, newest (c, 01-03) before older (b, 01-02);
    // then the SUCCESS row last.
    expect(out.map((j) => j.displayName)).toEqual(['c', 'b', 'a'])
  })

  it('does not mutate the input array', () => {
    const before = items.map((j) => j.id)
    sortJobs(items, { key: 'status' })
    expect(items.map((j) => j.id)).toEqual(before)
  })
})
