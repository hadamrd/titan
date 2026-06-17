/**
 * Adversarial tests for the per-FAILED-stage "Retry" button (#748).
 *
 * Pinned invariants:
 *   1. Button is VISIBLE on stage rows whose status === 'FAILED' AND the
 *      caller has REPLAY_BUILD (or ADMIN).
 *   2. Button is HIDDEN on SUCCEEDED / RUNNING stage rows — retrying a
 *      green stage is semantically nonsensical; the server would 409 anyway.
 *   3. A caller without REPLAY_BUILD/ADMIN sees the button DISABLED with a
 *      tooltip explaining the missing role. The UI pre-disables (don't lean
 *      on the 403 round-trip as the primary feedback channel).
 *   4. Clicking the button POSTs to
 *      /api/v1/builds/{buildId}/stages/{stageId}/retry and on success the
 *      build / nodes queries are invalidated so the freshly-QUEUED
 *      descendants render without a manual refresh.
 *   5. A 409 response surfaces a clear error notice ("no longer in FAILED
 *      state") and does NOT crash the panel.
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { StageTimingPanel } from '../components/StageTimingPanel'
import type { FlowNodeDto } from '../api/types'
import { AuthContext, type AuthState } from '../auth/AuthProvider'
import { setAccessToken } from '../auth/tokenStore'
import type { User } from 'oidc-client-ts'

function stage(opts: {
  id: string
  name: string
  status: string
  startedAt?: string | null
  completedAt?: string | null
}): FlowNodeDto {
  return {
    buildId: 4242,
    nodeId: opts.id,
    parentIds: null,
    nodeType: 'STAGE',
    displayName: opts.name,
    stepDescriptor: null,
    status: opts.status,
    agentLabel: null,
    startedAt: opts.startedAt ?? '2026-05-24T11:00:00Z',
    completedAt: opts.completedAt ?? '2026-05-24T11:00:05Z',
    durationMs: null,
    attempt: 1,
    maxAttempts: 1,
    failureCategory: null,
    failureReason: null,
    logTaskId: null,
  }
}

function authStateWithRoles(roles: readonly string[]): AuthState {
  const user = {
    access_token: 'fake',
    expired: false,
    profile: { groups: [...roles] },
  } as unknown as User
  return {
    user,
    isLoading: false,
    isAuthenticated: true,
    signinRedirect: async () => {},
    signinRedirectCallback: async () => ({}) as never,
    signoutRedirect: async () => {},
  }
}

interface RetryCall {
  url: string
  method: string
  body: unknown
}

function makeFetch(
  status: number,
  responseBody: unknown,
): {
  fetchSpy: typeof globalThis.fetch
  calls: RetryCall[]
} {
  const calls: RetryCall[] = []
  const fetchSpy = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const rawUrl =
      typeof input === 'string'
        ? input
        : input instanceof URL
          ? input.href
          : (input as Request).url
    const method = (init?.method ?? 'GET').toUpperCase()
    const body = init?.body ? JSON.parse(init.body as string) : null
    calls.push({ url: rawUrl, method, body })
    return new Response(JSON.stringify(responseBody), {
      status,
      headers: { 'Content-Type': 'application/json' },
    })
  }) as typeof globalThis.fetch
  return { fetchSpy, calls }
}

function renderPanel(opts: {
  nodes: FlowNodeDto[]
  buildId?: number
  roles: readonly string[]
}) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const invalidateSpy = vi.spyOn(qc, 'invalidateQueries')
  const auth = authStateWithRoles(opts.roles)
  const utils = render(
    <AuthContext.Provider value={auth}>
      <QueryClientProvider client={qc}>
        <StageTimingPanel nodes={opts.nodes} buildId={opts.buildId} />
      </QueryClientProvider>
    </AuthContext.Provider>,
  )
  return { ...utils, qc, invalidateSpy }
}

const originalFetch = globalThis.fetch

beforeEach(() => {
  setAccessToken('fake-token')
})

afterEach(() => {
  setAccessToken(null)
  globalThis.fetch = originalFetch
  vi.restoreAllMocks()
})

describe('StageTimingPanel — per-FAILED-stage Retry button (#748)', () => {
  it('renders the Retry button on FAILED rows and HIDES it on non-FAILED rows', () => {
    const nodes: FlowNodeDto[] = [
      stage({ id: 's-ok', name: 'build', status: 'SUCCESS' }),
      stage({ id: 's-run', name: 'test', status: 'RUNNING', completedAt: null }),
      stage({ id: 's-fail', name: 'deploy', status: 'FAILED' }),
    ]
    renderPanel({ nodes, buildId: 4242, roles: ['REPLAY_BUILD'] })

    // Visible only on the FAILED row.
    expect(screen.getByTestId('stage-retry-s-fail')).toBeInTheDocument()

    // NOT on SUCCESS / RUNNING rows — degenerate to-not-render, not just disabled.
    expect(screen.queryByTestId('stage-retry-s-ok')).toBeNull()
    expect(screen.queryByTestId('stage-retry-s-run')).toBeNull()
  })

  it('disables the button (with tooltip) when the caller lacks REPLAY_BUILD/ADMIN', () => {
    const nodes: FlowNodeDto[] = [stage({ id: 's-fail', name: 'deploy', status: 'FAILED' })]
    renderPanel({ nodes, buildId: 4242, roles: ['USER', 'READ_JOB'] })

    const btn = screen.getByTestId('stage-retry-s-fail') as HTMLButtonElement
    expect(btn.disabled).toBe(true)
    expect(btn.getAttribute('aria-disabled')).toBe('true')
    expect(btn.title).toMatch(/REPLAY_BUILD/i)
  })

  it('also enables for ADMIN role (RBAC parity with the server gate)', () => {
    const nodes: FlowNodeDto[] = [stage({ id: 's-fail', name: 'deploy', status: 'FAILED' })]
    renderPanel({ nodes, buildId: 4242, roles: ['ADMIN'] })

    const btn = screen.getByTestId('stage-retry-s-fail') as HTMLButtonElement
    expect(btn.disabled).toBe(false)
  })

  it('clicking POSTs to /api/v1/builds/{id}/stages/{stageId}/retry and invalidates the build query', async () => {
    const { fetchSpy, calls } = makeFetch(200, {
      type: 'applied',
      buildId: 4242,
      stageId: 's-fail',
      resetNodeIds: ['s-fail', 's-downstream'],
      taskId: 99,
    })
    globalThis.fetch = fetchSpy

    const nodes: FlowNodeDto[] = [stage({ id: 's-fail', name: 'deploy', status: 'FAILED' })]
    const { invalidateSpy } = renderPanel({
      nodes,
      buildId: 4242,
      roles: ['REPLAY_BUILD'],
    })

    fireEvent.click(screen.getByTestId('stage-retry-s-fail'))

    await waitFor(() => {
      expect(calls.length).toBe(1)
    })
    const call = calls[0]!
    expect(call.method).toBe('POST')
    expect(call.url).toContain('/api/v1/builds/4242/stages/s-fail/retry')

    // Success notice surfaces with the reset-node count.
    await waitFor(() => {
      const notice = screen.getByTestId('stage-retry-notice-s-fail')
      expect(notice.getAttribute('data-kind')).toBe('ok')
      expect(notice.textContent).toMatch(/2 nodes reset/)
    })

    // Build + nodes queries invalidated so the freshly-QUEUED descendants render.
    const keys = invalidateSpy.mock.calls.map((c) =>
      JSON.stringify((c[0] as { queryKey: unknown }).queryKey),
    )
    expect(keys).toContain(JSON.stringify(['builds', 4242]))
    expect(keys).toContain(JSON.stringify(['builds', 4242, 'nodes']))
  })

  it('surfaces a 409 response as an inline error notice without crashing', async () => {
    const { fetchSpy } = makeFetch(409, {
      type: 'about:blank',
      title: 'Conflict',
      status: 409,
      detail: 'stage is not in FAILED state',
      instance: null,
    })
    globalThis.fetch = fetchSpy

    const nodes: FlowNodeDto[] = [stage({ id: 's-fail', name: 'deploy', status: 'FAILED' })]
    renderPanel({ nodes, buildId: 4242, roles: ['REPLAY_BUILD'] })

    fireEvent.click(screen.getByTestId('stage-retry-s-fail'))

    const notice = await screen.findByTestId('stage-retry-notice-s-fail')
    await waitFor(() => {
      expect(notice.getAttribute('data-kind')).toBe('err')
    })
    expect(notice.textContent).toMatch(/no longer in FAILED state/i)

    // The panel itself is still rendered — the 409 must not unmount stage rows.
    expect(screen.getByTestId('stage-timing-row-s-fail')).toBeInTheDocument()
  })
})
