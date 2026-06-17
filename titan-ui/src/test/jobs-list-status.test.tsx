/**
 * Adversarial tests for the /jobs page lastBuild pill (issue #529).
 *
 * Covers the failure modes an SRE actually hits:
 *   - SUCCESS / FAILED rows render the StatusBadge with the right status text
 *     AND the right v3 status-dot variant class.
 *   - A row with no lastBuild (never-run job) renders the muted "never run"
 *     placeholder — not a status pill, not a missing cell, not a JS error.
 *   - The lastBuild field can be absent entirely on the wire (server's
 *     JsonInclude.NON_NULL strips nulls): renderer must treat that the same
 *     as `null` — no .status crash.
 *   - Sorting by status puts FAILED above SUCCESS above never-run.
 */
import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, fireEvent, within } from '@testing-library/react'
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

/**
 * Three-job fixture matching the spec adversaries:
 *   id 101 — SUCCESS (most-recent build is green)
 *   id 102 — FAILED (red — fleet-health signal)
 *   id 103 — never run (lastBuild OMITTED entirely on the wire, NOT just null)
 *
 * The omission is the load-bearing detail: JsonInclude.NON_NULL on the server
 * strips null fields, so the property is `undefined` in practice. The renderer
 * must treat undefined identically to null — no .status access on either.
 */
function threeJobsPayload(): JobsPage {
  return {
    items: [
      {
        id: 101,
        fullName: 'org/green',
        displayName: 'green-job',
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
        fullName: 'org/red',
        displayName: 'red-job',
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
        fullName: 'org/never',
        displayName: 'never-run-job',
        folderPath: null,
        enabled: true,
        createdAt: '2026-05-20T08:00:00Z',
        updatedAt: '2026-05-20T08:00:00Z',
        // lastBuild intentionally OMITTED — server strips NON_NULL.
      },
    ],
    total: 3,
    offset: 0,
    limit: 50,
  }
}

beforeEach(() => {
  const payload = threeJobsPayload()
  setupFetchMock([
    (url, method) => {
      if (method !== 'GET') return null
      if (url.pathname !== '/api/v1/jobs') return null
      return { status: 200, body: payload }
    },
    // Workers + stats + activity called by the chrome around the page — return empty.
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
})

describe('/jobs page — lastBuild pill (issue #529)', () => {
  it('renders SUCCESS pill on the green job row', async () => {
    renderAt('/jobs')
    await waitFor(() => expect(screen.getByText('green-job')).toBeInTheDocument())
    const row = screen.getByTestId('job-row-101-last-build')
    expect(row.textContent).toContain('SUCCESS')
    // v3 dot variant class — StatusBadge maps SUCCESS → status-dot.success.
    const dot = row.querySelector('.status-dot')
    expect(dot).not.toBeNull()
    expect(dot!.classList.contains('success')).toBe(true)
  })

  it('renders FAILED pill on the red job row', async () => {
    renderAt('/jobs')
    await waitFor(() => expect(screen.getByText('red-job')).toBeInTheDocument())
    const row = screen.getByTestId('job-row-102-last-build')
    expect(row.textContent).toContain('FAILED')
    const dot = row.querySelector('.status-dot')
    expect(dot).not.toBeNull()
    // StatusBadge maps FAILED → 'fail' variant.
    expect(dot!.classList.contains('fail')).toBe(true)
  })

  it('renders muted "never run" — NOT a status pill, NOT a JS error — when lastBuild is absent', async () => {
    renderAt('/jobs')
    await waitFor(() => expect(screen.getByText('never-run-job')).toBeInTheDocument())
    const row = screen.getByTestId('job-row-103-last-build')
    // The placeholder text the spec asks for.
    expect(row.textContent?.toLowerCase()).toContain('never run')
    // No status-dot — that would falsely imply a coloured status exists.
    expect(row.querySelector('.status-dot')).toBeNull()
    // None of the status strings leak into this row.
    expect(row.textContent).not.toContain('SUCCESS')
    expect(row.textContent).not.toContain('FAILED')
  })

  it('sorting by Last build puts FAILED first, then SUCCESS, then never-run', async () => {
    renderAt('/jobs')
    await waitFor(() => expect(screen.getByTestId('jobs-sort-status')).toBeInTheDocument())
    fireEvent.click(screen.getByTestId('jobs-sort-status'))
    // After re-sort, the row order in the table body must be: red → green → never-run.
    await waitFor(() => {
      const rows = document.querySelectorAll('tbody tr[data-testid^="job-row-"]')
      expect(rows.length).toBe(3)
      // Index 0 must be the FAILED row; check its name cell.
      expect(within(rows[0] as HTMLElement).getByText('red-job')).toBeInTheDocument()
      expect(within(rows[1] as HTMLElement).getByText('green-job')).toBeInTheDocument()
      expect(within(rows[2] as HTMLElement).getByText('never-run-job')).toBeInTheDocument()
    })
  })
})
