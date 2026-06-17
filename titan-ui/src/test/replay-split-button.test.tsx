/**
 * Adversarial tests for the /builds/$id header Replay split-button
 * (closes #618).
 *
 * The legacy header Replay button silently replayed from the FIRST node and
 * the per-step "Replay from here" sat in a different surface. That hid the
 * choice and shipped accidental whole-build replays when SREs meant to
 * resume mid-pipeline.
 *
 * Pinned invariants:
 *   1. With no step selected, the menu's "from selected step" item is
 *      disabled and surfaces a hint to select one.
 *   2. With a step selected, that item is enabled.
 *   3. "Replay entire build" posts to /replay with the FIRST node's id —
 *      the wire contract for a whole-build replay (see useReplayBuild).
 *   4. "Replay from selected step" posts to /replay with the SELECTED
 *      node's id, NOT the first node's id (this is the bug being guarded:
 *      a wired-up split that fires the wrong payload).
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { RouterProvider, createRouter, createMemoryHistory } from '@tanstack/react-router'
import { routeTree } from '../routeTree.gen'
import { AuthContext, type AuthState } from '../auth/AuthProvider'
import { setAccessToken } from '../auth/tokenStore'
import type { BuildDto, FlowNodeDto } from '../api/types'

const BUILD: BuildDto = {
  id: 1234,
  jobId: 1,
  buildNumber: 9,
  status: 'SUCCESS',
  triggeredBy: 'api',
  triggerType: 'manual',
  queuedAt: '2026-05-20T09:00:00Z',
  startedAt: '2026-05-20T09:00:01Z',
  finishedAt: '2026-05-20T09:03:00Z',
  durationMs: 179000,
  errorMessage: null,
  failureSummary: null,
}

const FIRST_NODE = 'n-first'
const SECOND_NODE = 'n-second'

function step(nodeId: string, displayName: string): FlowNodeDto {
  return {
    buildId: BUILD.id,
    nodeId,
    parentIds: null,
    nodeType: 'STEP',
    displayName,
    stepDescriptor: null,
    status: 'SUCCESS',
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

interface ReplayCall {
  nodeId: string
}

function recordingFetch(nodes: FlowNodeDto[]): {
  fetchSpy: typeof globalThis.fetch
  replays: ReplayCall[]
} {
  const replays: ReplayCall[] = []
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
      url.pathname === `/api/v1/builds/${BUILD.id}/replay` &&
      method === 'POST'
    ) {
      const body = init?.body ? JSON.parse(init.body as string) : {}
      replays.push({ nodeId: body.nodeId })
      return jsonResponse({ newBuildId: 9999, queuedPosition: null })
    }

    // SSE log stream: return immediately-closing stream.
    if (url.pathname === `/api/v1/builds/${BUILD.id}/logs` && method === 'GET') {
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

    if (url.pathname === `/api/v1/builds/${BUILD.id}` && method === 'GET') {
      return jsonResponse(BUILD)
    }
    if (url.pathname === `/api/v1/builds/${BUILD.id}/nodes` && method === 'GET') {
      return jsonResponse(nodes)
    }
    if (url.pathname === `/api/v1/builds/${BUILD.id}/gates` && method === 'GET') {
      return jsonResponse([])
    }
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
  return { fetchSpy, replays }
}

function jsonResponse(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  })
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
})

afterEach(() => {
  setAccessToken(null)
  globalThis.fetch = originalFetch
  vi.restoreAllMocks()
})

describe('/builds/$id — header Replay split-button (closes #618)', () => {
  it('with no selection, "from selected step" is disabled in the popover', async () => {
    // BUILD.status = SUCCESS would normally auto-preselect the last node.
    // Override to a status that doesn't auto-preselect, so we get a true
    // no-selection state.
    const nodesWithUnknownStatus: FlowNodeDto[] = [
      { ...step(FIRST_NODE, 'compile'), status: 'NOT_BUILT' },
      { ...step(SECOND_NODE, 'test'), status: 'NOT_BUILT' },
    ]
    // We also need build.status to NOT match SUCCESS/FAILED/RUNNING.
    const noPreselectBuild: BuildDto = { ...BUILD, status: 'QUEUED' }
    const built: { fetchSpy: typeof globalThis.fetch; replays: ReplayCall[] } = (() => {
      // Reuse recordingFetch but with our overridden BUILD.
      const _replays: ReplayCall[] = []
      const spy = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
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
          url.pathname === `/api/v1/builds/${BUILD.id}/replay` &&
          method === 'POST'
        ) {
          const body = init?.body ? JSON.parse(init.body as string) : {}
          _replays.push({ nodeId: body.nodeId })
          return jsonResponse({ newBuildId: 9999, queuedPosition: null })
        }
        if (url.pathname === `/api/v1/builds/${BUILD.id}/logs` && method === 'GET') {
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
        if (url.pathname === `/api/v1/builds/${BUILD.id}` && method === 'GET') {
          return jsonResponse(noPreselectBuild)
        }
        if (url.pathname === `/api/v1/builds/${BUILD.id}/nodes` && method === 'GET') {
          return jsonResponse(nodesWithUnknownStatus)
        }
        if (url.pathname === `/api/v1/builds/${BUILD.id}/gates` && method === 'GET') {
          return jsonResponse([])
        }
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
      return { fetchSpy: spy, replays: _replays }
    })()
    globalThis.fetch = built.fetchSpy
    renderBuildPage()

    const trigger = await screen.findByTestId('replay-menu-trigger')
    fireEvent.click(trigger)

    const menu = await screen.findByTestId('replay-menu')
    expect(menu).toBeTruthy()
    const whole = screen.getByTestId('replay-menu-whole')
    const fromSelected = screen.getByTestId('replay-menu-from-selected')
    expect(whole.hasAttribute('disabled')).toBe(false)
    expect(fromSelected.hasAttribute('disabled')).toBe(true)
    expect(fromSelected.getAttribute('title')).toMatch(/select a step/i)
  })

  it('with a selection, "from selected step" becomes enabled', async () => {
    // BUILD.status SUCCESS → auto-preselect picks the LAST node (SECOND_NODE).
    const nodes = [step(FIRST_NODE, 'compile'), step(SECOND_NODE, 'test')]
    const { fetchSpy } = recordingFetch(nodes)
    globalThis.fetch = fetchSpy
    renderBuildPage()

    // Wait for the page to mount and auto-preselect to kick in.
    await screen.findByTestId('replay-menu-trigger')
    // Give the auto-preselect effect a tick to set selectedNodeId.
    await waitFor(() => {
      // After auto-preselect, the per-step "Replay from here" button mounts
      // next to the selected node's header (proves selection is non-null).
      expect(screen.getByText('Replay from here')).toBeTruthy()
    })

    fireEvent.click(screen.getByTestId('replay-menu-trigger'))
    const fromSelected = await screen.findByTestId('replay-menu-from-selected')
    expect(fromSelected.hasAttribute('disabled')).toBe(false)
  })

  it('clicking "Replay entire build" POSTs /replay with the FIRST node id', async () => {
    const nodes = [step(FIRST_NODE, 'compile'), step(SECOND_NODE, 'test')]
    const { fetchSpy, replays } = recordingFetch(nodes)
    globalThis.fetch = fetchSpy
    renderBuildPage()

    fireEvent.click(await screen.findByTestId('replay-menu-trigger'))
    fireEvent.click(await screen.findByTestId('replay-menu-whole'))

    await waitFor(() => {
      expect(replays.length).toBe(1)
    })
    expect(replays[0].nodeId).toBe(FIRST_NODE)
    expect(replays[0].nodeId).not.toBe(SECOND_NODE)
  })

  it('clicking "Replay from selected step" POSTs /replay with the SELECTED node id', async () => {
    // SUCCESS build → auto-preselect = LAST node = SECOND_NODE.
    const nodes = [step(FIRST_NODE, 'compile'), step(SECOND_NODE, 'test')]
    const { fetchSpy, replays } = recordingFetch(nodes)
    globalThis.fetch = fetchSpy
    renderBuildPage()

    // Confirm selection landed on SECOND_NODE before opening the menu.
    await screen.findByTestId('replay-menu-trigger')
    await waitFor(() => {
      expect(screen.getByText('Replay from here')).toBeTruthy()
    })

    fireEvent.click(screen.getByTestId('replay-menu-trigger'))
    const fromSelected = await screen.findByTestId('replay-menu-from-selected')
    fireEvent.click(fromSelected)

    await waitFor(() => {
      expect(replays.length).toBe(1)
    })
    // Adversarial: must NOT be the first node — that's the bug a naive
    // split-button wires up if the click handler reuses the whole-build
    // path.
    expect(replays[0].nodeId).toBe(SECOND_NODE)
    expect(replays[0].nodeId).not.toBe(FIRST_NODE)
  })
})
