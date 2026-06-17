/**
 * Hand-rolled fetch mock for Vitest tests.
 *
 * Each handler is a function that returns a result OR returns null to skip.
 * The first non-null result wins. Unmatched requests throw so tests fail loudly.
 */
import { vi } from 'vitest'
import type {
  ActivityPage,
  BuildDto,
  BuildsPage,
  FlowNodeDto,
  JobDto,
  JobsPage,
  QueueEntryDto,
  QueuePage,
  StatsDto,
  TriggerBuildResponse,
  WorkerDto,
  WorkersPage,
} from '../api/types'

// ── Seed data ────────────────────────────────────────────────────────────────

export const SEED_JOB: JobDto = {
  id: 1,
  fullName: 'main-pipeline',
  displayName: 'Main Pipeline',
  folderPath: null,
  enabled: true,
  createdAt: '2026-05-20T08:00:00Z',
  updatedAt: '2026-05-20T09:00:00Z',
}

export const SEED_JOBS_PAGE: JobsPage = {
  items: [SEED_JOB],
  total: 1,
  offset: 0,
  limit: 50,
}

export const SEED_BUILD: BuildDto = {
  id: 42,
  jobId: 1,
  buildNumber: 7,
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

export const SEED_RUNNING_BUILD: BuildDto = {
  ...SEED_BUILD,
  id: 43,
  buildNumber: 8,
  status: 'RUNNING',
  finishedAt: null,
  durationMs: null,
}

export const SEED_BUILDS_PAGE: BuildsPage = {
  items: [SEED_BUILD, SEED_RUNNING_BUILD],
  total: 2,
  offset: 0,
  limit: 50,
}

export const SEED_FLOW_NODES: FlowNodeDto[] = [
  {
    buildId: 42,
    nodeId: 'n-1',
    parentIds: null,
    nodeType: 'STAGE',
    displayName: 'Checkout',
    stepDescriptor: null,
    status: 'SUCCESS',
    agentLabel: null,
    startedAt: '2026-05-20T09:00:01Z',
    completedAt: '2026-05-20T09:00:04Z',
    durationMs: 3000,
    attempt: 1,
    maxAttempts: 3,
    failureCategory: null,
    failureReason: null,
    logTaskId: null,
  },
  {
    buildId: 42,
    nodeId: 'n-2',
    parentIds: 'n-1',
    nodeType: 'STAGE',
    displayName: 'Build',
    stepDescriptor: null,
    status: 'SUCCESS',
    agentLabel: null,
    startedAt: '2026-05-20T09:00:04Z',
    completedAt: '2026-05-20T09:03:00Z',
    durationMs: 176000,
    attempt: 1,
    maxAttempts: 3,
    failureCategory: null,
    failureReason: null,
    logTaskId: null,
  },
]

export const SEED_QUEUE_ENTRY: QueueEntryDto = {
  taskId: 1001,
  buildId: 42,
  jobId: 1,
  jobName: 'Main Pipeline',
  queuedAt: '2026-05-20T09:00:00Z',
  waitingMs: 12_000,
  priority: 5,
  requestedLabels: 'linux,docker',
}

export const SEED_QUEUE_PAGE: QueuePage = {
  items: [SEED_QUEUE_ENTRY],
  total: 1,
  offset: 0,
  limit: 200,
}

// A worker with NO metrics signal — exercises the null-bar code path that
// PR #378 wired and that this design-pass tick (#41) tests as a regression.
export const SEED_WORKER_NULL_METRICS: WorkerDto = {
  id: 'worker-null-metrics',
  name: 'no-metrics-worker',
  state: 'ONLINE',
  pool: 'default',
  labels: ['default'],
  currentTasks: 0,
  maxConcurrent: 4,
  cpuPct: null,
  memPct: null,
  diskPct: null,
  lastSeenAt: null,
  registeredAt: '2026-05-20T08:00:00Z',
}

export const SEED_WORKERS_PAGE: WorkersPage = {
  items: [SEED_WORKER_NULL_METRICS],
  total: 1,
  offset: 0,
  limit: 100,
}

export const SEED_TRIGGER_RESPONSE: TriggerBuildResponse = {
  buildId: 99,
  buildNumber: 9,
  status: 'QUEUED',
}

export const SEED_ACTIVITY_EMPTY: ActivityPage = {
  items: [],
  nextCursor: null,
}

export const SEED_STATS_EMPTY: StatsDto = {
  buildsToday: 0,
  successRate: 0,
  medianDurationMs: 0,
}

// GET /api/v1/info seed — exercises the Settings "About this Titan" wiring
// (closes #446). The Settings page renders the commit + builtAt fields straight
// out of this payload; the test asserts those literals show up on screen.
export const SEED_SERVER_INFO = {
  version: '0.1.0',
  commit: 'abc1234',
  builtAt: '2026-05-23T13:19:33.171073921Z',
  uptimeSeconds: 12345,
}

// ── Handler table ────────────────────────────────────────────────────────────

interface MatchResult {
  status: number
  body: unknown
}

type Handler = (
  url: URL,
  method: string,
  body?: string,
) => MatchResult | null

/** Default handler set wired to the seed data above. */
export function defaultHandlers(): Handler[] {
  return [
    // GET /api/v1/jobs  (exact, no trailing segment)
    (url, method) => {
      if (method !== 'GET') return null
      if (url.pathname !== '/api/v1/jobs') return null
      return { status: 200, body: SEED_JOBS_PAGE }
    },

    // GET /api/v1/jobs/:id  (single job, no /builds suffix)
    (url, method) => {
      if (method !== 'GET') return null
      const m = url.pathname.match(/^\/api\/v1\/jobs\/(\d+)$/)
      if (!m) return null
      const id = Number(m[1])
      if (id === SEED_JOB.id) return { status: 200, body: SEED_JOB }
      return {
        status: 404,
        body: { type: 'about:blank', title: 'Not Found', status: 404, detail: 'job not found', instance: null },
      }
    },

    // GET /api/v1/jobs/:id/builds
    (url, method) => {
      if (method !== 'GET') return null
      if (!url.pathname.match(/^\/api\/v1\/jobs\/\d+\/builds$/)) return null
      return { status: 200, body: SEED_BUILDS_PAGE }
    },

    // POST /api/v1/jobs/:id/builds  (trigger)
    (url, method) => {
      if (method !== 'POST') return null
      if (!url.pathname.match(/^\/api\/v1\/jobs\/\d+\/builds$/)) return null
      return { status: 201, body: SEED_TRIGGER_RESPONSE }
    },

    // GET /api/v1/queue
    (url, method) => {
      if (method !== 'GET') return null
      if (url.pathname !== '/api/v1/queue') return null
      return { status: 200, body: SEED_QUEUE_PAGE }
    },

    // GET /api/v1/stats
    (url, method) => {
      if (method !== 'GET') return null
      if (url.pathname !== '/api/v1/stats') return null
      return { status: 200, body: SEED_STATS_EMPTY }
    },

    // GET /api/v1/activity — empty by default so onboarding can render
    (url, method) => {
      if (method !== 'GET') return null
      if (url.pathname !== '/api/v1/activity') return null
      return { status: 200, body: SEED_ACTIVITY_EMPTY }
    },

    // GET /api/v1/workers
    (url, method) => {
      if (method !== 'GET') return null
      if (url.pathname !== '/api/v1/workers') return null
      return { status: 200, body: SEED_WORKERS_PAGE }
    },

    // GET /api/v1/info — public server-identity tile, consumed by Settings'
    // "About this Titan" card via useServerInfo (PR #424; closes #446).
    (url, method) => {
      if (method !== 'GET') return null
      if (url.pathname !== '/api/v1/info') return null
      return { status: 200, body: SEED_SERVER_INFO }
    },

    // GET /api/v1/approvals — empty inbox by default; per-test overrides for
    // populated rows (the inbox test passes its own handler).
    (url, method) => {
      if (method !== 'GET') return null
      if (url.pathname !== '/api/v1/approvals') return null
      return { status: 200, body: { items: [], total: 0, offset: 0, limit: 50 } }
    },

    // GET /api/v1/builds/:id/nodes
    (url, method) => {
      if (method !== 'GET') return null
      const m = url.pathname.match(/^\/api\/v1\/builds\/(\d+)\/nodes$/)
      if (!m) return null
      const id = Number(m[1])
      if (id === SEED_BUILD.id) return { status: 200, body: SEED_FLOW_NODES }
      return { status: 200, body: [] }
    },

    // POST /api/v1/builds/:id/cancel
    (url, method) => {
      if (method !== 'POST') return null
      if (!url.pathname.match(/^\/api\/v1\/builds\/\d+\/cancel$/)) return null
      return { status: 202, body: {} }
    },

    // GET /api/v1/builds/:id  (single build)
    (url, method) => {
      if (method !== 'GET') return null
      const m = url.pathname.match(/^\/api\/v1\/builds\/(\d+)$/)
      if (!m) return null
      const id = Number(m[1])
      const build = [SEED_BUILD, SEED_RUNNING_BUILD].find((b) => b.id === id)
      if (build) return { status: 200, body: build }
      return {
        status: 404,
        body: { type: 'about:blank', title: 'Not Found', status: 404, detail: 'build not found', instance: null },
      }
    },
  ]
}

// ── Setup / teardown ─────────────────────────────────────────────────────────

let _originalFetch: typeof globalThis.fetch

export function setupFetchMock(handlers: Handler[] = defaultHandlers()) {
  _originalFetch = globalThis.fetch

  globalThis.fetch = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const rawUrl =
      typeof input === 'string'
        ? input
        : input instanceof URL
          ? input.href
          : (input as Request).url
    const url = new URL(rawUrl, 'http://localhost:8080')
    const method = (init?.method ?? (input instanceof Request ? input.method : 'GET')).toUpperCase()
    const reqBody =
      typeof init?.body === 'string'
        ? init.body
        : init?.body === undefined || init?.body === null
          ? undefined
          : String(init.body)

    for (const handler of handlers) {
      const result = handler(url, method, reqBody)
      if (result !== null) {
        const body = JSON.stringify(result.body)
        return new Response(body, {
          status: result.status,
          headers: { 'Content-Type': 'application/json' },
        })
      }
    }

    throw new Error(`[fetch mock] No handler matched: ${method} ${url.pathname}`)
  }) as typeof globalThis.fetch
}

export function resetFetchMock() {
  if (_originalFetch) {
    globalThis.fetch = _originalFetch
  }
}
