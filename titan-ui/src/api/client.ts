/**
 * Fetch-based API client for the Titan server.
 *
 * Base URL: same-origin (`""`). The SPA is served by nginx (rig/local) or by
 * the Helm-managed nginx pod (rig/k3s), both of which proxy `/api` and `/q`
 * to titan-server. Cross-origin deployments are not supported in V1.
 *
 * Closes antipattern #898: previously this read `VITE_TITAN_API_URL` and
 * baked the value into the bundle at build time — one image per rig.
 *
 * When the AuthProvider has loaded an OIDC user, every request carries
 * `Authorization: Bearer <access_token>`. Tokens live in sessionStorage
 * (via oidc-client-ts) and are mirrored into an in-memory slot — see
 * src/auth/tokenStore.ts.
 *
 * Errors from the server are surfaced as ApiError (RFC 7807 problem+json).
 * Network failures propagate as plain Error.
 */
import { getAccessToken } from '@/auth/tokenStore'
import type {
  BuildDto,
  BuildsPage,
  FlowNodeDto,
  JobDto,
  JobsPage,
  ProblemJson,
  TriggerBuildRequest,
  TriggerBuildResponse,
} from './types'
import { ApiError } from './types'

const BASE_URL = ''

async function apiFetch<T>(path: string, init?: RequestInit): Promise<T> {
  const url = `${BASE_URL}${path}`
  const token = getAccessToken()
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    ...(init?.headers as Record<string, string> | undefined),
  }
  if (token) {
    headers['Authorization'] = `Bearer ${token}`
  }
  const res = await fetch(url, {
    ...init,
    headers,
  })

  if (!res.ok) {
    let problem: ProblemJson
    try {
      problem = (await res.json()) as ProblemJson
    } catch {
      problem = {
        type: 'about:blank',
        title: res.statusText || 'Unknown error',
        status: res.status,
        detail: null,
        instance: null,
      }
    }
    throw new ApiError(res.status, problem)
  }

  // 202/204/201 with no body
  if (res.status === 204 || res.headers.get('content-length') === '0') {
    return undefined as T
  }

  return res.json() as Promise<T>
}

// ── ETag-aware fetch (#1099) ─────────────────────────────────────────────────
//
// The build-detail view polls /api/v1/builds/<id> every few seconds during
// streaming; most polls return identical state. The server emits a weak
// `ETag` header and honours `If-None-Match` (-> 304). We remember the etag
// per URL and replay the cached body when the server confirms it's still
// fresh. Module-level cache (per tab); cleared on full reload.
//
// The cache is intentionally tiny — only the GET endpoints we explicitly
// pipe through `apiFetchCached` are stored, keyed by URL path. The
// `__resetEtagCacheForTests` export is wired only for vitest.

type EtagCacheEntry = { etag: string; value: unknown }
const etagCache = new Map<string, EtagCacheEntry>()

/** Test-only: clear the per-URL ETag cache. Vitest imports this directly. */
export function __resetEtagCacheForTests(): void {
  etagCache.clear()
}

async function apiFetchCached<T>(path: string): Promise<T> {
  const url = `${BASE_URL}${path}`
  const token = getAccessToken()
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
  }
  if (token) {
    headers['Authorization'] = `Bearer ${token}`
  }
  const cached = etagCache.get(path)
  if (cached) {
    headers['If-None-Match'] = cached.etag
  }

  const res = await fetch(url, { headers })

  if (res.status === 304 && cached) {
    return cached.value as T
  }

  if (!res.ok) {
    let problem: ProblemJson
    try {
      problem = (await res.json()) as ProblemJson
    } catch {
      problem = {
        type: 'about:blank',
        title: res.statusText || 'Unknown error',
        status: res.status,
        detail: null,
        instance: null,
      }
    }
    throw new ApiError(res.status, problem)
  }

  const value = (await res.json()) as T
  const etag = res.headers.get('ETag')
  if (etag) {
    etagCache.set(path, { etag, value })
  }
  return value
}

// ── Jobs ─────────────────────────────────────────────────────────────────────

export function fetchJobs(offset = 0, limit = 50): Promise<JobsPage> {
  return apiFetch<JobsPage>(`/api/v1/jobs?offset=${offset}&limit=${limit}`)
}

export function fetchJob(jobId: number): Promise<JobDto> {
  // ETag-aware: #1099. Job-detail polling collapses to 304 + cached body.
  return apiFetchCached<JobDto>(`/api/v1/jobs/${jobId}`)
}

// ── Builds ────────────────────────────────────────────────────────────────────

export function fetchJobBuilds(jobId: number, offset = 0, limit = 50): Promise<BuildsPage> {
  return apiFetch<BuildsPage>(`/api/v1/jobs/${jobId}/builds?offset=${offset}&limit=${limit}`)
}

export function fetchBuild(buildId: number): Promise<BuildDto> {
  // ETag-aware: #1099. Cuts streaming-build poll traffic >70%.
  return apiFetchCached<BuildDto>(`/api/v1/builds/${buildId}`)
}

export function fetchBuildNodes(buildId: number): Promise<FlowNodeDto[]> {
  return apiFetch<FlowNodeDto[]>(`/api/v1/builds/${buildId}/nodes`)
}

export function triggerBuild(
  jobId: number,
  req: TriggerBuildRequest = {},
): Promise<TriggerBuildResponse> {
  return apiFetch<TriggerBuildResponse>(`/api/v1/jobs/${jobId}/builds`, {
    method: 'POST',
    body: JSON.stringify(req),
  })
}

export function cancelBuild(buildId: number): Promise<void> {
  return apiFetch<void>(`/api/v1/builds/${buildId}/cancel`, { method: 'POST' })
}

/**
 * Returns the full SSE URL for a build's log stream.
 *
 * NOTE: browser `EventSource` cannot attach an `Authorization` header, so we
 * stream this endpoint with `fetch` + a manual SSE parser instead (see
 * {@link streamBuildLogs}). This export is kept for callers that need the URL
 * itself (tests, debugging links).
 */
export function buildLogsUrl(buildId: number, taskId?: string | null): string {
  const base = `${BASE_URL}/api/v1/builds/${buildId}/logs`
  return taskId ? `${base}?taskId=${encodeURIComponent(taskId)}` : base
}

/** One Server-Sent-Events frame, in the shape our server emits them. */
export interface SseFrame {
  event: string
  data: string
}

export interface StreamBuildLogsCallbacks {
  onFrame: (frame: SseFrame) => void
  onOpen?: () => void
}

/**
 * Streams `GET /api/v1/builds/{buildId}/logs` as text/event-stream using
 * `fetch` (so we can send `Authorization: Bearer <token>` — `EventSource`
 * can't). Resolves when the server closes the stream; rejects if the HTTP
 * response is not 2xx or if the underlying read errors.
 *
 * Frames are parsed by splitting on the standard SSE delimiter (`\n\n`).
 * Multi-line `data:` segments are joined with a single `\n` per the spec.
 * The `signal` should come from an `AbortController` owned by the caller so
 * the stream cleans up on unmount.
 */
export async function streamBuildLogs(
  buildId: number,
  signal: AbortSignal,
  callbacks: StreamBuildLogsCallbacks,
  taskId?: string | null,
): Promise<void> {
  const token = getAccessToken()
  const headers: Record<string, string> = { Accept: 'text/event-stream' }
  if (token) headers['Authorization'] = `Bearer ${token}`

  const res = await fetch(buildLogsUrl(buildId, taskId), { headers, signal })
  if (!res.ok) {
    let problem: ProblemJson
    try {
      problem = (await res.json()) as ProblemJson
    } catch {
      problem = {
        type: 'about:blank',
        title: res.statusText || 'Log stream failed',
        status: res.status,
        detail: null,
        instance: null,
      }
    }
    throw new ApiError(res.status, problem)
  }
  if (!res.body) {
    throw new Error('Log stream response has no body')
  }
  callbacks.onOpen?.()

  const reader = res.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  // Loop until EOF or abort. We tolerate `\r\n\r\n` as well as `\n\n` to be
  // forgiving of proxies that rewrite line endings.
  // eslint-disable-next-line no-constant-condition
  while (true) {
    const { value, done } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })

    let sep = findFrameSeparator(buffer)
    while (sep !== -1) {
      const raw = buffer.slice(0, sep.index)
      buffer = buffer.slice(sep.index + sep.length)
      const frame = parseSseFrame(raw)
      if (frame) callbacks.onFrame(frame)
      sep = findFrameSeparator(buffer)
    }
  }
}

interface FrameSep {
  index: number
  length: number
}
function findFrameSeparator(buffer: string): FrameSep | -1 {
  const lf = buffer.indexOf('\n\n')
  const crlf = buffer.indexOf('\r\n\r\n')
  if (lf === -1 && crlf === -1) return -1
  if (crlf !== -1 && (lf === -1 || crlf < lf)) return { index: crlf, length: 4 }
  return { index: lf, length: 2 }
}

function parseSseFrame(raw: string): SseFrame | null {
  let event = 'message'
  const dataLines: string[] = []
  for (const line of raw.split(/\r?\n/)) {
    if (line.startsWith('event:')) {
      event = line.slice(6).trim()
    } else if (line.startsWith('data:')) {
      // Per the spec, a single leading space after `data:` is stripped.
      const v = line.slice(5)
      dataLines.push(v.startsWith(' ') ? v.slice(1) : v)
    }
    // Other fields (id:, retry:, comments) are ignored — we don't need them.
  }
  if (dataLines.length === 0 && event === 'message') return null
  return { event, data: dataLines.join('\n') }
}
