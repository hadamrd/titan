/**
 * Adversarial tests for the DAG-collapse feature (#553).
 *
 * Pinned invariants:
 *   1. The full /builds/$id page mounts uncollapsed by default, with the
 *      `dag-canvas` region present.
 *   2. Clicking the toolbar collapse button replaces the DAG region with
 *      the 32px summary bar AND persists "1" under
 *      `titan.ui.buildDetail.dagCollapsed` in localStorage.
 *   3. Remounting with that key already set to "1" starts collapsed
 *      (no `dag-canvas`, summary bar visible).
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { render, screen, fireEvent, waitFor, cleanup } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { RouterProvider, createRouter, createMemoryHistory } from '@tanstack/react-router'
import { routeTree } from '../routeTree.gen'
import { AuthContext, type AuthState } from '../auth/AuthProvider'
import { setAccessToken } from '../auth/tokenStore'
import type { BuildDto, FlowNodeDto } from '../api/types'

const DAG_KEY = 'titan.ui.buildDetail.dagCollapsed'

const BUILD: BuildDto = {
  id: 553,
  jobId: 1,
  buildNumber: 1,
  status: 'SUCCESS',
  triggeredBy: 'tester',
  triggerType: 'manual',
  queuedAt: '2026-05-24T14:00:00Z',
  startedAt: '2026-05-24T14:00:01Z',
  finishedAt: '2026-05-24T14:00:42Z',
  durationMs: 41000,
  errorMessage: null,
  failureSummary: null,
}

function node(nodeId: string, displayName: string, parentIds: string | null = null): FlowNodeDto {
  return {
    buildId: BUILD.id,
    nodeId,
    parentIds,
    nodeType: 'STEP',
    displayName,
    stepDescriptor: null,
    status: 'SUCCESS',
    agentLabel: null,
    startedAt: null,
    completedAt: null,
    durationMs: 1000,
    attempt: 1,
    maxAttempts: 1,
    failureCategory: null,
    failureReason: null,
    logTaskId: null,
  }
}

const NODES: FlowNodeDto[] = [
  node('checkout', 'setup: checkout'),
  node('build', 'build: compile', 'checkout'),
  node('test', 'test: unit', 'build'),
]

const fakeAuth: AuthState = {
  user: { access_token: 'fake', expired: false } as unknown as AuthState['user'],
  isLoading: false,
  isAuthenticated: true,
  signinRedirect: async () => {},
  signinRedirectCallback: async () => ({} as never),
  signoutRedirect: async () => {},
}

function jsonResponse(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  })
}

function stubFetch(): typeof globalThis.fetch {
  return vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const rawUrl =
      typeof input === 'string'
        ? input
        : input instanceof URL
          ? input.href
          : (input as Request).url
    const url = new URL(rawUrl, 'http://localhost:8080')
    const method = (
      init?.method ?? (input instanceof Request ? input.method : 'GET')
    ).toUpperCase()

    if (url.pathname === `/api/v1/builds/${BUILD.id}/logs` && method === 'GET') {
      const encoder = new TextEncoder()
      const body = new ReadableStream<Uint8Array>({
        pull(c) {
          c.enqueue(encoder.encode('event: done\ndata: \n\n'))
          c.close()
        },
      })
      return new Response(body, {
        status: 200,
        headers: { 'Content-Type': 'text/event-stream' },
      })
    }
    if (url.pathname === `/api/v1/builds/${BUILD.id}` && method === 'GET') return jsonResponse(BUILD)
    if (url.pathname === `/api/v1/builds/${BUILD.id}/nodes` && method === 'GET') return jsonResponse(NODES)
    if (url.pathname === `/api/v1/builds/${BUILD.id}/gates` && method === 'GET') return jsonResponse([])
    if (url.pathname === `/api/v1/jobs/${BUILD.jobId}` && method === 'GET') {
      return jsonResponse({
        id: BUILD.jobId,
        fullName: 'demo',
        displayName: 'demo',
        folderPath: null,
        enabled: true,
        createdAt: '2026-05-20T08:00:00Z',
        updatedAt: '2026-05-20T08:00:00Z',
      })
    }
    return jsonResponse({})
  }) as typeof globalThis.fetch
}

function renderBuildPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const router = createRouter({
    routeTree,
    history: createMemoryHistory({ initialEntries: [`/builds/${BUILD.id}`] }),
  })
  return render(
    <AuthContext.Provider value={fakeAuth}>
      <QueryClientProvider client={qc}>
        <RouterProvider router={router} />
      </QueryClientProvider>
    </AuthContext.Provider>,
  )
}

const originalFetch = globalThis.fetch

beforeEach(() => {
  setAccessToken('fake-token')
  window.localStorage.removeItem(DAG_KEY)
  globalThis.fetch = stubFetch()
})

afterEach(() => {
  setAccessToken(null)
  globalThis.fetch = originalFetch
  window.localStorage.removeItem(DAG_KEY)
  vi.restoreAllMocks()
  cleanup()
})

describe('DAG collapse (#553)', () => {
  it('starts uncollapsed and renders the DAG canvas', async () => {
    renderBuildPage()
    await screen.findByTestId('build-detail-v3')
    await waitFor(() => {
      expect(screen.queryByTestId('dag-canvas')).not.toBeNull()
    })
    expect(screen.queryByTestId('dag-collapsed-bar')).toBeNull()
  })

  it('collapse button hides the canvas, swaps in the summary bar, persists to localStorage', async () => {
    renderBuildPage()
    await screen.findByTestId('build-detail-v3')
    const btn = await screen.findByTestId('dag-collapse-btn')
    fireEvent.click(btn)

    await waitFor(() => {
      expect(screen.queryByTestId('dag-canvas')).toBeNull()
    })
    expect(screen.getByTestId('dag-collapsed-bar')).toBeTruthy()
    expect(window.localStorage.getItem(DAG_KEY)).toBe('1')

    // Expand from the summary bar restores the canvas + persists "0".
    fireEvent.click(screen.getByTestId('dag-expand-btn'))
    await waitFor(() => {
      expect(screen.queryByTestId('dag-canvas')).not.toBeNull()
    })
    expect(window.localStorage.getItem(DAG_KEY)).toBe('0')
  })

  it('initial mount honors a pre-existing collapsed=1 in localStorage', async () => {
    window.localStorage.setItem(DAG_KEY, '1')
    renderBuildPage()
    await screen.findByTestId('build-detail-v3')
    await waitFor(() => {
      expect(screen.queryByTestId('dag-collapsed-bar')).not.toBeNull()
    })
    expect(screen.queryByTestId('dag-canvas')).toBeNull()
  })
})
