/**
 * Adversarial tests for the /builds/$id per-step log filter (closes #537).
 *
 * SREs scrubbing a long log to find one step's failure can't afford "all
 * logs interleaved" any more — they need to click a step on the Pipeline
 * tab and have the Logs tab restrict to just that step's task_token.
 *
 * Pinned invariants:
 *   1. buildLogsUrl + streamBuildLogs carry the `taskId` query param when
 *      filtering, and DON'T carry it (or any empty `?taskId=`) when not —
 *      the backend distinguishes the two.
 *   2. Clicking a STEP card with a non-null `logTaskId` stamps the filter;
 *      a follow-up click on the "All logs" affordance clears it.
 *   3. A STEP card whose `logTaskId` is null (legacy / pre-#541 data) is
 *      still selectable for the right-rail "Step" panel BUT must NOT
 *      stamp the log filter — otherwise the Logs tab silently goes blank
 *      (filter=null token → zero rows server-side).
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { RouterProvider, createRouter, createMemoryHistory } from '@tanstack/react-router'
import { routeTree } from '../routeTree.gen'
import { AuthContext, type AuthState } from '../auth/AuthProvider'
import { setAccessToken } from '../auth/tokenStore'
import { buildLogsUrl } from '../api/client'
import type { BuildDto, FlowNodeDto } from '../api/types'

// ── Wire-level: buildLogsUrl ────────────────────────────────────────────────
// The URL is the actual contract with the server — a regression here would
// silently break the filter or send `?taskId=` with an empty value.

describe('buildLogsUrl — wire contract', () => {
  it('omits the taskId param entirely when no filter is set', () => {
    expect(buildLogsUrl(42)).toBe('/api/v1/builds/42/logs')
    expect(buildLogsUrl(42, null)).toBe('/api/v1/builds/42/logs')
    expect(buildLogsUrl(42, undefined)).toBe('/api/v1/builds/42/logs')
  })

  it('appends ?taskId=<UUID> exactly when a filter is set', () => {
    const tok = 'a3f4c1b0-1234-4567-89ab-cdef01234567'
    expect(buildLogsUrl(42, tok)).toBe(
      `/api/v1/builds/42/logs?taskId=${tok}`,
    )
  })

  it('URL-encodes the taskId to defend against future non-UUID tokens', () => {
    expect(buildLogsUrl(42, 'has space&weird')).toBe(
      '/api/v1/builds/42/logs?taskId=has%20space%26weird',
    )
  })
})

// ── Route-level: click a step → SSE request URL has the right taskId ───────

const STEP_A_TOKEN = '11111111-aaaa-4aaa-aaaa-111111111111'
const STEP_B_TOKEN = '22222222-bbbb-4bbb-bbbb-222222222222'

const BUILD: BuildDto = {
  id: 77,
  jobId: 1,
  buildNumber: 3,
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

function step(nodeId: string, displayName: string, logTaskId: string | null): FlowNodeDto {
  return {
    buildId: 77,
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
    logTaskId,
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

interface RecordedRequest {
  url: string
  taskIdParam: string | null
}

function recordingFetch(nodes: FlowNodeDto[]): {
  fetchSpy: typeof globalThis.fetch
  requests: RecordedRequest[]
} {
  const requests: RecordedRequest[] = []
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

    // SSE log stream — record + return an immediately-closing stream so the
    // log hook resolves without hanging the test.
    if (url.pathname === `/api/v1/builds/${BUILD.id}/logs` && method === 'GET') {
      requests.push({
        url: url.href,
        taskIdParam: url.searchParams.get('taskId'),
      })
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

    // Build, job, nodes, gates, etc.
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
    // Anything else — return empty 200 to keep the page rendering even if
    // some hook polls something we haven't enumerated. Hard-failing here
    // makes the test brittle to unrelated additions.
    return jsonResponse({})
  }) as typeof globalThis.fetch
  return { fetchSpy, requests }
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

describe('/builds/$id — per-step log filter (closes #537)', () => {
  it('clicking step B issues a log fetch with taskId=B token (NOT step A)', async () => {
    const nodes = [
      step('n-a', 'build: step-A', STEP_A_TOKEN),
      step('n-b', 'build: step-B', STEP_B_TOKEN),
    ]
    const { fetchSpy, requests } = recordingFetch(nodes)
    globalThis.fetch = fetchSpy
    renderBuildPage()

    // Wait until the pipeline cards are on screen. v3-stack renders the
    // name in both the DAG card AND the tree rail — pick the first match.
    await screen.findAllByText('build: step-B')
    // Initial unfiltered request should have already happened.
    await waitFor(() => {
      expect(requests.length).toBeGreaterThanOrEqual(1)
    })
    expect(requests[0].taskIdParam).toBeNull()

    // Click step B's pipeline card — drives setSelectedNodeId + setFilterTaskId.
    fireEvent.click(screen.getAllByText('build: step-B')[0])

    // A NEW SSE request must fire with taskId=step-B token. Adversarial: it
    // must not be step-A's token (the bug we're guarding against).
    await waitFor(() => {
      const last = requests[requests.length - 1]
      expect(last.taskIdParam).toBe(STEP_B_TOKEN)
    })
    const last = requests[requests.length - 1]
    expect(last.taskIdParam).not.toBe(STEP_A_TOKEN)
  })

  it('clicking "All logs" clears the filter (next SSE request has no taskId)', async () => {
    const nodes = [step('n-a', 'build: step-A', STEP_A_TOKEN)]
    const { fetchSpy, requests } = recordingFetch(nodes)
    globalThis.fetch = fetchSpy
    renderBuildPage()

    // v3-stack renders the step name in both the DAG card AND the tree
    // rail. Either click path selects the node; pick the DAG card (first
    // match) so the selection event mirrors the user's primary surface.
    await screen.findAllByText('build: step-A')
    const stepACards = screen.getAllByText('build: step-A')
    fireEvent.click(stepACards[0])

    // Switch to Logs tab so the clear button mounts.
    const logsTab = screen.getByRole('tab', { name: /logs/i })
    fireEvent.click(logsTab)

    const clearBtn = await screen.findByTestId('logs-clear-filter')
    await waitFor(() => {
      const last = requests[requests.length - 1]
      expect(last.taskIdParam).toBe(STEP_A_TOKEN)
    })

    fireEvent.click(clearBtn)

    await waitFor(() => {
      const last = requests[requests.length - 1]
      expect(last.taskIdParam).toBeNull()
    })
    // After #830 AC6: the filter bar is suppressed when no filter is
    // active (the "Logs · all steps" line was a tautology of the active
    // tab). Assert the bar — not just the label — is gone.
    expect(screen.queryByTestId('logs-filter-bar')).toBeNull()
    expect(screen.queryByTestId('logs-filter-label')).toBeNull()
  })

  it('clicking a step with null logTaskId does NOT stamp a filter (legacy guard)', async () => {
    // step-A has a token; step-LEGACY does not. Selecting LEGACY must leave
    // the filter unchanged — otherwise the Logs tab silently goes blank
    // (no token → zero log rows server-side).
    const nodes = [
      step('n-a', 'build: step-A', STEP_A_TOKEN),
      step('n-legacy', 'build: step-LEGACY', null),
    ]
    const { fetchSpy, requests } = recordingFetch(nodes)
    globalThis.fetch = fetchSpy
    renderBuildPage()

    // v3-stack renders each step name in both the DAG card AND the tree
    // rail — both click paths must select the node. We pick the first
    // match (the DAG card) to mirror the user's primary surface.
    await screen.findAllByText('build: step-A')
    fireEvent.click(screen.getAllByText('build: step-A')[0])
    await waitFor(() => {
      expect(requests[requests.length - 1].taskIdParam).toBe(STEP_A_TOKEN)
    })
    const reqsAfterA = requests.length

    fireEvent.click(screen.getAllByText('build: step-LEGACY')[0])

    // Give any potential extra request a tick to land.
    await new Promise((r) => setTimeout(r, 50))

    // If a new SSE request DID fire, it must still carry step-A's token (the
    // filter remained sticky). It must NEVER carry null after a legacy click.
    for (let i = reqsAfterA; i < requests.length; i++) {
      expect(requests[i].taskIdParam).toBe(STEP_A_TOKEN)
    }
  })
})
