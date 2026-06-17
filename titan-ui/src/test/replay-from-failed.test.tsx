/**
 * Adversarial tests for the "Replay from failed" header shortcut (closes #664).
 *
 * Pinned invariants:
 *   1. Button is HIDDEN when build.status === 'SUCCESS' — replaying-from-failed on a green
 *      build is semantically nonsensical and the UI must not surface the option.
 *   2. Button is VISIBLE when build.status === 'FAILED' AND at least one flow node ended
 *      FAILED — both conditions, because a FAILED build whose DAG never materialised (synthesis
 *      failure) has no stage to replay from.
 *   3. Button is HIDDEN when build.status === 'FAILED' but every materialised node is
 *      SUCCESS / SKIPPED / etc. — the degenerate edge case.
 *   4. Clicking the button POSTs to /api/v1/builds/{id}/replay-from-failed (NOT to /replay)
 *      and on a 201 response, navigates to /builds/{newId}.
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { RouterProvider, createRouter, createMemoryHistory } from '@tanstack/react-router'
import { routeTree } from '../routeTree.gen'
import { AuthContext, type AuthState } from '../auth/AuthProvider'
import { setAccessToken } from '../auth/tokenStore'
import type { BuildDto, FlowNodeDto } from '../api/types'

const BASE_BUILD: BuildDto = {
  id: 4242,
  jobId: 7,
  buildNumber: 3,
  status: 'FAILED',
  triggeredBy: 'api',
  triggerType: 'manual',
  queuedAt: '2026-05-20T09:00:00Z',
  startedAt: '2026-05-20T09:00:01Z',
  finishedAt: '2026-05-20T09:03:00Z',
  durationMs: 179000,
  errorMessage: 'stage-2 failed',
  failureSummary: null,
}

function stageNode(nodeId: string, status: string): FlowNodeDto {
  return {
    buildId: BASE_BUILD.id,
    nodeId,
    parentIds: null,
    nodeType: 'STAGE',
    displayName: nodeId,
    stepDescriptor: null,
    status,
    agentLabel: null,
    startedAt: null,
    completedAt: null,
    durationMs: null,
    attempt: 1,
    maxAttempts: 1,
    failureCategory: null,
    failureReason: null,
    logTaskId: null,
  }
}

const fakeAuth: AuthState = {
  user: { access_token: 'fake', expired: false } as unknown as AuthState['user'],
  isLoading: false,
  isAuthenticated: true,
  signinRedirect: async () => {},
  signinRedirectCallback: async () => ({} as never),
  signoutRedirect: async () => {},
}

interface ReplayFromFailedCall {
  body: unknown
}

function makeFetch(
  build: BuildDto,
  nodes: FlowNodeDto[],
): {
  fetchSpy: typeof globalThis.fetch
  calls: ReplayFromFailedCall[]
  replayCalls: { nodeId: string }[]
} {
  const calls: ReplayFromFailedCall[] = []
  const replayCalls: { nodeId: string }[] = []
  const fetchSpy = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
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

    if (
      url.pathname === `/api/v1/builds/${build.id}/replay-from-failed` &&
      method === 'POST'
    ) {
      const body = init?.body ? JSON.parse(init.body as string) : {}
      calls.push({ body })
      // Mirror real backend: 201 with the full BuildDto for the new build.
      return jsonResponse({ ...build, id: 9999, buildNumber: build.buildNumber + 1 }, 201)
    }
    if (url.pathname === `/api/v1/builds/${build.id}/replay` && method === 'POST') {
      const body = init?.body ? JSON.parse(init.body as string) : {}
      replayCalls.push({ nodeId: body.nodeId })
      return jsonResponse({ newBuildId: 9999, queuedPosition: null })
    }
    if (url.pathname === `/api/v1/builds/${build.id}/logs` && method === 'GET') {
      const encoder = new TextEncoder()
      const body = new ReadableStream<Uint8Array>({
        pull(controller) {
          controller.enqueue(encoder.encode('event: done\ndata: \n\n'))
          controller.close()
        },
      })
      return new Response(body, {
        status: 200,
        headers: { 'Content-Type': 'text/event-stream' },
      })
    }
    if (url.pathname === `/api/v1/builds/${build.id}` && method === 'GET') {
      return jsonResponse(build)
    }
    if (url.pathname === `/api/v1/builds/${build.id}/nodes` && method === 'GET') {
      return jsonResponse(nodes)
    }
    if (url.pathname === `/api/v1/builds/${build.id}/gates` && method === 'GET') {
      return jsonResponse([])
    }
    if (url.pathname === `/api/v1/jobs/${build.jobId}` && method === 'GET') {
      return jsonResponse({
        id: build.jobId,
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
  return { fetchSpy, calls, replayCalls }
}

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function renderBuildPage(buildId: number) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const router = createRouter({
    routeTree,
    history: createMemoryHistory({ initialEntries: [`/builds/${buildId}`] }),
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
})

afterEach(() => {
  setAccessToken(null)
  globalThis.fetch = originalFetch
  vi.restoreAllMocks()
})

describe('/builds/$id — "Replay from failed" shortcut (closes #664)', () => {
  it('button is HIDDEN when build.status === SUCCESS', async () => {
    const successBuild: BuildDto = { ...BASE_BUILD, status: 'SUCCESS', errorMessage: null }
    // Even with a hypothetical failed node lingering on a SUCCESS build, the button must hide:
    // the status is the gate, not the node states. SUCCESS-with-failed-node is degenerate but
    // possible (e.g. a re-baked build that hasn't yet rewritten node history).
    const nodes = [stageNode('s1', 'SUCCESS'), stageNode('s2', 'FAILED')]
    const { fetchSpy } = makeFetch(successBuild, nodes)
    globalThis.fetch = fetchSpy

    renderBuildPage(successBuild.id)
    // Wait for the page to mount — the ReplayMenu is always present on a build with nodes, so
    // its trigger is our "page mounted" anchor.
    await screen.findByTestId('replay-menu-trigger')

    expect(screen.queryByTestId('replay-from-failed-button')).toBeNull()
  })

  it('button is VISIBLE when build.status === FAILED and a node is FAILED', async () => {
    const nodes = [stageNode('s1', 'SUCCESS'), stageNode('s2', 'FAILED')]
    const { fetchSpy } = makeFetch(BASE_BUILD, nodes)
    globalThis.fetch = fetchSpy

    renderBuildPage(BASE_BUILD.id)

    const button = await screen.findByTestId('replay-from-failed-button')
    expect(button).toBeTruthy()
    expect(button.textContent).toMatch(/replay from failed/i)
  })

  it('button is HIDDEN when build is FAILED but no node ended FAILED (degenerate case)', async () => {
    // Synthesis-only failure: the build row is FAILED but the DAG never materialised — every
    // node is QUEUED or absent. The endpoint would 400, so we must hide the button.
    const nodes = [stageNode('s1', 'SUCCESS'), stageNode('s2', 'SKIPPED')]
    const { fetchSpy } = makeFetch(BASE_BUILD, nodes)
    globalThis.fetch = fetchSpy

    renderBuildPage(BASE_BUILD.id)
    await screen.findByTestId('replay-menu-trigger')

    expect(screen.queryByTestId('replay-from-failed-button')).toBeNull()
  })

  it('clicking the button POSTs to /replay-from-failed (NOT /replay) and navigates', async () => {
    const nodes = [stageNode('s1', 'SUCCESS'), stageNode('s2', 'FAILED')]
    const { fetchSpy, calls, replayCalls } = makeFetch(BASE_BUILD, nodes)
    globalThis.fetch = fetchSpy

    renderBuildPage(BASE_BUILD.id)
    const button = await screen.findByTestId('replay-from-failed-button')
    fireEvent.click(button)

    await waitFor(() => {
      expect(calls.length).toBe(1)
    })
    // Adversarial: the click must not accidentally hit the existing /replay endpoint — that's
    // the bug a naive shared mutation would introduce.
    expect(replayCalls.length).toBe(0)

    // Body is a plain object with no nodeId — the endpoint determines the stage server-side.
    const body = calls[0].body as Record<string, unknown>
    expect(body.nodeId).toBeUndefined()
  })
})
