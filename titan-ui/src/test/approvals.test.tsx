/**
 * /approvals inbox + inline approval banner (#721).
 *
 * Adversarial coverage:
 *  - empty state: muted "No pending approvals" + topbar badge hidden
 *  - populated: rows render; Approve click POSTs + invalidates the inbox
 *  - non-approver: Approve/Reject disabled with tooltip
 *  - badge: hidden at count 0; visible (with count) when > 0
 *  - inline banner only on a build with PENDING approval; reject click works;
 *    TIMED_OUT row renders the auto-rejected hint.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor, cleanup, fireEvent } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { RouterProvider, createMemoryHistory, createRouter } from '@tanstack/react-router'
import { routeTree } from '../routeTree.gen'
import { AuthContext, type AuthState } from '../auth/AuthProvider'
import { setAccessToken } from '../auth/tokenStore'
import { setupFetchMock, resetFetchMock, defaultHandlers, SEED_BUILD } from './msw-handlers'
import type { ApprovalDto } from '../api/types'

function makeAuth(groups: string[], username = 'alice'): AuthState {
  return {
    user: {
      access_token: 'fake',
      expired: false,
      profile: { preferred_username: username, groups },
    } as unknown as AuthState['user'],
    isLoading: false,
    isAuthenticated: true,
    signinRedirect: async () => {},
    signinRedirectCallback: async () => ({} as never),
    signoutRedirect: async () => {},
  }
}

function renderAt(path: string, auth: AuthState) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const router = createRouter({
    routeTree,
    history: createMemoryHistory({ initialEntries: [path] }),
  })
  return render(
    <AuthContext.Provider value={auth}>
      <QueryClientProvider client={qc}>
        <RouterProvider router={router} />
      </QueryClientProvider>
    </AuthContext.Provider>,
  )
}

const SAMPLE_APPROVAL: ApprovalDto = {
  id: 501,
  buildId: SEED_BUILD.id,
  flowNodeId: 'gate-prod',
  prompt: 'Deploy to prod?',
  approvers: ['alice', 'bob'],
  status: 'PENDING',
  expiresAt: new Date(Date.now() + 60 * 60_000).toISOString(),
  createdAt: new Date().toISOString(),
}

const TIMED_OUT_APPROVAL: ApprovalDto = {
  ...SAMPLE_APPROVAL,
  id: 502,
  status: 'TIMED_OUT',
  decidedBy: 'system',
  decidedAt: new Date().toISOString(),
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

describe('/approvals inbox (#721)', () => {
  it('renders empty state when no pending approvals', async () => {
    setupFetchMock(defaultHandlers())
    renderAt('/approvals', makeAuth(['APPROVE_BUILD']))
    await waitFor(() =>
      expect(screen.getByTestId('approvals-empty')).toBeInTheDocument(),
    )
    expect(screen.getByText('No pending approvals')).toBeInTheDocument()
  })

  it('renders rows and POSTs on approve click', async () => {
    let approvePosted = false
    let inboxFetches = 0
    setupFetchMock([
      (url, method) => {
        if (method === 'GET' && url.pathname === '/api/v1/approvals') {
          inboxFetches++
          if (approvePosted) {
            return { status: 200, body: { items: [], total: 0, offset: 0, limit: 50 } }
          }
          return {
            status: 200,
            body: { items: [SAMPLE_APPROVAL], total: 1, offset: 0, limit: 50 },
          }
        }
        if (method === 'POST' && url.pathname === `/api/v1/approvals/${SAMPLE_APPROVAL.id}/approve`) {
          approvePosted = true
          return { status: 200, body: { applied: true, status: 'APPROVED', message: 'ok' } }
        }
        return null
      },
      ...defaultHandlers(),
    ])
    renderAt('/approvals', makeAuth(['APPROVE_BUILD'], 'alice'))
    const approveBtn = await screen.findByTestId(`approval-approve-${SAMPLE_APPROVAL.id}`)
    expect(approveBtn).not.toBeDisabled()
    expect(screen.getByText('Deploy to prod?')).toBeInTheDocument()
    fireEvent.click(approveBtn)
    await waitFor(() => expect(approvePosted).toBe(true))
    // After mutation success the inbox query is invalidated → refetched.
    await waitFor(() => expect(inboxFetches).toBeGreaterThan(1))
  })

  it('disables Approve/Reject for a non-approver with tooltip', async () => {
    setupFetchMock([
      (url, method) => {
        if (method === 'GET' && url.pathname === '/api/v1/approvals') {
          return {
            status: 200,
            body: { items: [SAMPLE_APPROVAL], total: 1, offset: 0, limit: 50 },
          }
        }
        return null
      },
      ...defaultHandlers(),
    ])
    // 'carol' is NOT in approvers ['alice','bob'] and has no APPROVE_BUILD role.
    renderAt('/approvals', makeAuth(['READ_JOB'], 'carol'))
    const approveBtn = await screen.findByTestId(`approval-approve-${SAMPLE_APPROVAL.id}`)
    const rejectBtn = screen.getByTestId(`approval-reject-${SAMPLE_APPROVAL.id}`)
    expect(approveBtn).toBeDisabled()
    expect(rejectBtn).toBeDisabled()
    expect(approveBtn.getAttribute('title')).toMatch(/APPROVE_BUILD/)
  })
})

describe('/approvals bulk actions (#734)', () => {
  const SAMPLE_A: ApprovalDto = { ...SAMPLE_APPROVAL, id: 601 }
  const SAMPLE_B: ApprovalDto = { ...SAMPLE_APPROVAL, id: 602 }
  const SAMPLE_C: ApprovalDto = { ...SAMPLE_APPROVAL, id: 603 }

  it('selects 3 rows and POSTs once to /bulk/approve with [id,id,id]', async () => {
    let posted: { ids: number[] } | null = null
    let inboxFetches = 0
    setupFetchMock([
      (url, method, body) => {
        if (method === 'GET' && url.pathname === '/api/v1/approvals') {
          inboxFetches++
          return {
            status: 200,
            body: {
              items: posted ? [] : [SAMPLE_A, SAMPLE_B, SAMPLE_C],
              total: posted ? 0 : 3,
              offset: 0,
              limit: 50,
            },
          }
        }
        if (
          method === 'POST' &&
          url.pathname === '/api/v1/approvals/bulk/approve'
        ) {
          posted = JSON.parse(body ?? '{}') as { ids: number[] }
          return {
            status: 200,
            body: {
              outcomes: posted.ids.map((id) => ({
                id,
                applied: true,
                status: 'APPROVED',
              })),
            },
          }
        }
        return null
      },
      ...defaultHandlers(),
    ])
    renderAt('/approvals', makeAuth(['APPROVE_BUILD'], 'alice'))

    // Wait for rows to render, then check 3 checkboxes.
    const cbA = await screen.findByTestId(`approval-select-${SAMPLE_A.id}`)
    const cbB = screen.getByTestId(`approval-select-${SAMPLE_B.id}`)
    const cbC = screen.getByTestId(`approval-select-${SAMPLE_C.id}`)
    fireEvent.click(cbA)
    fireEvent.click(cbB)
    fireEvent.click(cbC)

    const bulkBtn = await screen.findByTestId('bulk-approve-btn')
    fireEvent.click(bulkBtn)

    await waitFor(() => expect(posted).not.toBeNull())
    // Single network call carrying exactly the 3 selected ids in order.
    expect(posted!.ids).toEqual([SAMPLE_A.id, SAMPLE_B.id, SAMPLE_C.id])

    // Inbox query invalidated → at least one refetch after the mutation.
    await waitFor(() => expect(inboxFetches).toBeGreaterThan(1))

    // Success banner appeared with the summary.
    const banner = await screen.findByTestId('approvals-bulk-banner')
    expect(banner.textContent).toContain('3 approved')
  })

  it('mixed-outcome response shows "2 approved, 1 skipped (1 already decided)"', async () => {
    setupFetchMock([
      (url, method) => {
        if (method === 'GET' && url.pathname === '/api/v1/approvals') {
          return {
            status: 200,
            body: {
              items: [SAMPLE_A, SAMPLE_B, SAMPLE_C],
              total: 3,
              offset: 0,
              limit: 50,
            },
          }
        }
        if (
          method === 'POST' &&
          url.pathname === '/api/v1/approvals/bulk/approve'
        ) {
          return {
            status: 200,
            body: {
              outcomes: [
                { id: SAMPLE_A.id, applied: true, status: 'APPROVED' },
                { id: SAMPLE_B.id, applied: true, status: 'APPROVED' },
                {
                  id: SAMPLE_C.id,
                  applied: false,
                  status: 'APPROVED',
                  reason: 'already_decided',
                },
              ],
            },
          }
        }
        return null
      },
      ...defaultHandlers(),
    ])
    renderAt('/approvals', makeAuth(['APPROVE_BUILD'], 'alice'))

    fireEvent.click(await screen.findByTestId('approval-select-all'))
    fireEvent.click(await screen.findByTestId('bulk-approve-btn'))

    const banner = await screen.findByTestId('approvals-bulk-banner')
    expect(banner.getAttribute('data-kind')).toBe('mixed')
    expect(banner.textContent).toContain('2 approved')
    expect(banner.textContent).toContain('1 skipped')
    expect(banner.textContent).toContain('already decided')
  })

  it('hides the action bar when no rows are selected', async () => {
    setupFetchMock([
      (url, method) => {
        if (method === 'GET' && url.pathname === '/api/v1/approvals') {
          return {
            status: 200,
            body: { items: [SAMPLE_A], total: 1, offset: 0, limit: 50 },
          }
        }
        return null
      },
      ...defaultHandlers(),
    ])
    renderAt('/approvals', makeAuth(['APPROVE_BUILD'], 'alice'))
    await screen.findByTestId(`approval-select-${SAMPLE_A.id}`)
    expect(screen.queryByTestId('approvals-bulk-actionbar')).not.toBeInTheDocument()
    expect(screen.queryByTestId('bulk-approve-btn')).not.toBeInTheDocument()
  })

  it('disables the checkbox for non-approvers (carol on alice/bob row)', async () => {
    setupFetchMock([
      (url, method) => {
        if (method === 'GET' && url.pathname === '/api/v1/approvals') {
          return {
            status: 200,
            body: { items: [SAMPLE_A], total: 1, offset: 0, limit: 50 },
          }
        }
        return null
      },
      ...defaultHandlers(),
    ])
    renderAt('/approvals', makeAuth(['READ_JOB'], 'carol'))
    const cb = (await screen.findByTestId(
      `approval-select-${SAMPLE_A.id}`,
    )) as HTMLInputElement
    expect(cb.disabled).toBe(true)
  })
})

describe('Topbar approvals badge (#721)', () => {
  it('is hidden when pending count is 0', async () => {
    setupFetchMock(defaultHandlers())
    renderAt('/', makeAuth(['APPROVE_BUILD']))
    // Give the inbox query a tick to settle.
    await waitFor(() => {
      expect(screen.queryByTestId('approvals-badge')).not.toBeInTheDocument()
    })
  })

  it('is hidden for users without APPROVE_BUILD even if count > 0', async () => {
    setupFetchMock([
      (url, method) => {
        if (method === 'GET' && url.pathname === '/api/v1/approvals') {
          return {
            status: 200,
            body: { items: [SAMPLE_APPROVAL], total: 1, offset: 0, limit: 50 },
          }
        }
        return null
      },
      ...defaultHandlers(),
    ])
    renderAt('/', makeAuth(['READ_JOB']))
    await waitFor(() => {
      expect(screen.queryByTestId('approvals-badge')).not.toBeInTheDocument()
    })
  })

  it('renders the chip with the count for an approver when count > 0', async () => {
    setupFetchMock([
      (url, method) => {
        if (method === 'GET' && url.pathname === '/api/v1/approvals') {
          return {
            status: 200,
            body: { items: [SAMPLE_APPROVAL], total: 3, offset: 0, limit: 1 },
          }
        }
        return null
      },
      ...defaultHandlers(),
    ])
    renderAt('/', makeAuth(['APPROVE_BUILD']))
    const badge = await screen.findByTestId('approvals-badge')
    expect(badge.getAttribute('data-count')).toBe('3')
    expect(badge.textContent).toContain('3')
  })
})

describe('Inline approval banner on /builds/$buildId (#721)', () => {
  it('does NOT render when build has no PENDING approval', async () => {
    setupFetchMock(defaultHandlers())
    renderAt(`/builds/${SEED_BUILD.id}`, makeAuth(['APPROVE_BUILD']))
    // Wait for the build header to settle; banner container must be absent.
    await waitFor(() => {
      expect(screen.queryByTestId('approval-banners')).not.toBeInTheDocument()
    })
  })

  it('renders the banner and POSTs on reject click', async () => {
    // A PENDING approval on a terminal build is an orphan and the banner
    // hides — pin the approval to the RUNNING seed build (id 43).
    const RUNNING_APPROVAL = { ...SAMPLE_APPROVAL, buildId: 43 }
    let rejectPosted = false
    setupFetchMock([
      (url, method) => {
        if (method === 'GET' && url.pathname === '/api/v1/approvals') {
          return {
            status: 200,
            body: {
              items: rejectPosted ? [] : [RUNNING_APPROVAL],
              total: rejectPosted ? 0 : 1,
              offset: 0,
              limit: 200,
            },
          }
        }
        if (method === 'POST' && url.pathname === `/api/v1/approvals/${RUNNING_APPROVAL.id}/reject`) {
          rejectPosted = true
          return { status: 200, body: { applied: true, status: 'REJECTED', message: 'ok' } }
        }
        return null
      },
      ...defaultHandlers(),
    ])
    renderAt(`/builds/43`, makeAuth(['APPROVE_BUILD'], 'alice'))
    const rejectBtn = await screen.findByTestId(`approval-reject-${RUNNING_APPROVAL.id}`)
    fireEvent.click(rejectBtn)
    await waitFor(() => expect(rejectPosted).toBe(true))
  })

  it('shows "auto-rejected: timeout" hint for a TIMED_OUT approval surfaced on the build', async () => {
    // The hook filters by status=PENDING, so TIMED_OUT rows never reach the
    // banner via the inbox path. We test the component's display of the
    // status directly by injecting a TIMED_OUT row through the same response
    // shape — the ApprovalBanner renders the hint when status !== PENDING.
    setupFetchMock([
      (url, method) => {
        if (method === 'GET' && url.pathname === '/api/v1/approvals') {
          // We send a PENDING row that the UI WILL render; the test then
          // asserts the timeout state is reachable from the component via a
          // separate render pass.
          return {
            status: 200,
            body: { items: [], total: 0, offset: 0, limit: 200 },
          }
        }
        return null
      },
      ...defaultHandlers(),
    ])
    // Render the banner component in isolation to assert the TIMED_OUT branch
    // without depending on the build-detail route's plumbing.
    const { ApprovalBanner } = await import('../components/ApprovalBanner')
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    cleanup()
    render(
      <AuthContext.Provider value={makeAuth(['APPROVE_BUILD'])}>
        <QueryClientProvider client={qc}>
          <ApprovalBanner approval={TIMED_OUT_APPROVAL} />
        </QueryClientProvider>
      </AuthContext.Provider>,
    )
    expect(screen.getByTestId(`approval-timed-out-${TIMED_OUT_APPROVAL.id}`)).toBeInTheDocument()
    expect(screen.getByText(/auto-rejected: timeout/)).toBeInTheDocument()
  })
})
