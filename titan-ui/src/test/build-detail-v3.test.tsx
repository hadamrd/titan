/**
 * Adversarial tests for the v3-stack build detail (#539).
 *
 * Pinned invariants:
 *   1. The DAG renders one xyflow node per FlowNodeDto AND tags each node
 *      with a data-status attribute so smoke tools can assert per-status
 *      color buckets without a visual diff.
 *   2. Clicking a tree-rail row drives the same selectedNodeId path as
 *      clicking the corresponding DAG node — and stamps the per-step log
 *      filter (post-#537 contract).
 *   3. The terminal console exposes line counts AND per-severity counts
 *      (err / warn / ok) so SREs see "3 err · 2 warn · 1217 ok" at a
 *      glance.
 *   4. The minimap renders one band per run of severity (not one per
 *      line) AND at least one band when lines exist.
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { RouterProvider, createRouter, createMemoryHistory } from '@tanstack/react-router'
import { routeTree } from '../routeTree.gen'
import { AuthContext, type AuthState } from '../auth/AuthProvider'
import { setAccessToken } from '../auth/tokenStore'
import { Minimap } from '../components/BuildDetail/Minimap'
import { TerminalConsole } from '../components/BuildDetail/TerminalConsole'
import type { BuildDto, FlowNodeDto } from '../api/types'

const STEP_TOKEN_A = '11111111-aaaa-4aaa-aaaa-111111111111'
const STEP_TOKEN_B = '22222222-bbbb-4bbb-bbbb-222222222222'

const BUILD: BuildDto = {
  id: 539,
  jobId: 1,
  buildNumber: 12,
  status: 'FAILED',
  triggeredBy: 'tester',
  triggerType: 'manual',
  queuedAt: '2026-05-24T14:00:00Z',
  startedAt: '2026-05-24T14:00:01Z',
  finishedAt: '2026-05-24T14:04:19Z',
  durationMs: 258000,
  errorMessage: null,
  failureSummary: null,
}

function node(
  nodeId: string,
  displayName: string,
  status: string,
  logTaskId: string | null,
  parentIds: string | null = null,
): FlowNodeDto {
  return {
    buildId: BUILD.id,
    nodeId,
    parentIds,
    nodeType: 'STEP',
    displayName,
    stepDescriptor: null,
    status,
    agentLabel: null,
    startedAt: null,
    completedAt: null,
    durationMs: 12000,
    attempt: 1,
    maxAttempts: 1,
    failureCategory: null,
    failureReason: null,
    logTaskId,
  }
}

const NODES: FlowNodeDto[] = [
  node('checkout', 'setup: checkout', 'SUCCESS', STEP_TOKEN_A),
  node('build', 'build: compile', 'SUCCESS', STEP_TOKEN_B, 'checkout'),
  node('test-it', 'test: it', 'FAILED', STEP_TOKEN_B, 'build'),
  node('package', 'deploy: package', 'NOT_BUILT', null, 'test-it'),
]

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

function recordingFetch(): {
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
    if (url.pathname === `/api/v1/builds/${BUILD.id}` && method === 'GET') {
      return jsonResponse(BUILD)
    }
    if (url.pathname === `/api/v1/builds/${BUILD.id}/nodes` && method === 'GET') {
      return jsonResponse(NODES)
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

describe('/builds/$id v3-stack — DAG + tree rail', () => {
  it('renders one DAG node per FlowNodeDto with data-status attributes', async () => {
    const { fetchSpy } = recordingFetch()
    globalThis.fetch = fetchSpy
    const { container } = renderBuildPage()

    // Wait for the v3 chrome to mount AND the build to load.
    await screen.findByTestId('build-detail-v3')
    // Each StackCardNode gets a stable testid that includes the nodeId.
    await waitFor(() => {
      expect(container.querySelector('[data-testid="dag-node-checkout"]')).not.toBeNull()
    })
    expect(container.querySelector('[data-testid="dag-node-build"]')).not.toBeNull()
    expect(container.querySelector('[data-testid="dag-node-test-it"]')).not.toBeNull()
    expect(container.querySelector('[data-testid="dag-node-package"]')).not.toBeNull()

    // Per-status data attributes — the bug we're guarding against is a
    // status palette that collapses everything to one color.
    const checkoutCard = container.querySelector('[data-testid="dag-node-checkout"]')!
    const failedCard = container.querySelector('[data-testid="dag-node-test-it"]')!
    expect(checkoutCard.getAttribute('data-status')).toBe('SUCCESS')
    expect(failedCard.getAttribute('data-status')).toBe('FAILED')
    // Variant class wins the color — FAIL card must carry .fail.
    expect(failedCard.className).toMatch(/\bfail\b/)
  })

  it('clicking a tree-rail row selects the node + stamps the per-step log filter', async () => {
    const { fetchSpy, requests } = recordingFetch()
    globalThis.fetch = fetchSpy
    renderBuildPage()

    // Wait for the tree to render — the 'build' row carries a stable testid.
    const treeRow = await screen.findByTestId('tree-row-build')
    fireEvent.click(treeRow)

    // After the click, the SSE log fetch must have been re-issued with the
    // selected node's logTaskId. The first request may have been the
    // unfiltered initial open; we assert the LAST request carries the token.
    await waitFor(() => {
      const last = requests[requests.length - 1]
      expect(last?.taskIdParam).toBe(STEP_TOKEN_B)
    })

    // The same row must now carry the 'selected' class so the design's
    // accent rail + accent text light up.
    await waitFor(() => {
      expect(treeRow.className).toMatch(/\bselected\b/)
    })
  })
})

describe('TerminalConsole — counts header', () => {
  const LINES = [
    '$ pnpm test',
    '> @titan/server@2.4.0 test',
    '  ✓ POST /v2/auth/login → 200',
    '  ✓ POST /v2/auth/refresh → 200',
    '  ⚠ deprecated API call',
    '  ✗ POST /v2/workers/:id/labels → expected 200, got 409',
    '  ✗ Worker labels modified concurrently',
    'FAIL test/it/workers.spec.ts',
  ]

  it('renders total line count and per-severity counts visible to SREs', () => {
    render(<TerminalConsole lines={LINES} sseState="done" />)
    const counts = screen.getByTestId('terminal-counts')
    expect(counts.textContent).toMatch(/8 lines/)
    // Two FAIL-pattern matches (`✗ ` + `FAIL `) → 3 err. The matcher in
    // TerminalConsole intentionally tags both `✗` lines AND the all-caps
    // `FAIL` summary line as err.
    expect(screen.getByTestId('count-err').textContent).toBe('3 err')
    expect(screen.getByTestId('count-warn').textContent).toBe('1 warn')
    // 2 lines with `✓ ` pass through the ok matcher.
    expect(screen.getByTestId('count-ok').textContent).toBe('2 ok')
  })
})

describe('Minimap — structure-banded log preview', () => {
  it('renders at least one band when lines exist', () => {
    render(
      <Minimap
        severities={['err', 'err', 'ok', 'ok', 'ok', 'warn', 'err']}
        totalLines={7}
        viewportRange={[1, 3]}
      />,
    )
    const bands = screen.getAllByTestId('minimap-band')
    // Three runs: [err err] [ok ok ok] [warn] [err] = 4 bands.
    expect(bands.length).toBe(4)
  })

  it('renders no bands but doesn’t crash when lines are empty', () => {
    const { container } = render(
      <Minimap severities={[]} totalLines={0} viewportRange={null} />,
    )
    expect(container.querySelectorAll('[data-testid="minimap-band"]').length).toBe(0)
    // The minimap shell stays mounted so its width is part of the layout.
    expect(container.querySelector('[data-testid="log-minimap"]')).not.toBeNull()
  })
})
