/**
 * Pulsar SCM-source API — wire layer for the Integrations "Connect a Pulsar
 * node" card (#1283). Backs the admin registration endpoint that landed on
 * trunk in #1293 (`io.adaptiq.titan.api.PulsarSourcesApi`):
 *
 *   GET    /api/v1/pulsar/sources                 — list registered sources
 *   POST   /api/v1/pulsar/sources                 — register a node (probes repos)
 *   POST   /api/v1/pulsar/sources/{id}/sync       — re-probe → fresh repoCount/lastPolledAt
 *
 * Field names mirror {@code PulsarSourceDto.java} exactly. Optional fields
 * (`nodeName`, `repoCount`, `lastPolledAt`) are stripped from the JSON by the
 * server's {@code JsonInclude.NON_NULL}, so they are nullable/optional here.
 *
 * Error contract (RFC-7807 problem+json, see PulsarSourcesApi):
 *   400  invalid / missing nodeUrl           (title "nodeUrl ...")
 *   409  duplicate nodeUrl                    (title "pulsar-source-exists")
 *   404  unknown source id on sync            (ApiNotFoundException)
 *   502  node unreachable                     (title "pulsar-node-unreachable")
 *
 * Same apiFetch idiom as api/githubApp.ts — bearer mirrored from the
 * AuthProvider via tokenStore, ApiError carries the problem body so the
 * connect form can show a real inline message instead of a generic failure.
 */
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { getAccessToken } from '@/auth/tokenStore'
import { ApiError, type ProblemJson } from './types'

// Same-origin: nginx proxies /api → titan-server.
const BASE_URL = ''

// ── wire types ───────────────────────────────────────────────────────────────

/**
 * One registered Pulsar SCM source — wire mirror of {@code PulsarSourceDto}
 * (titan-server). {@code nodeName} / {@code repoCount} / {@code lastPolledAt}
 * are omitted from the JSON when null (server {@code JsonInclude.NON_NULL}),
 * so they are optional + nullable here; callers must treat absent === null.
 */
export interface PulsarSourceDto {
  id: number
  nodeUrl: string
  nodeName?: string | null
  /** Repo count from the last successful probe; null until the node is reached. */
  repoCount?: number | null
  /** ISO-8601 instant of the last successful probe; null until first reach. */
  lastPolledAt?: string | null
  createdAt: string // ISO-8601
}

/**
 * Request body for {@code POST /api/v1/pulsar/sources}. Mirror of
 * {@code RegisterPulsarSourceRequest}. {@code nodeName} is optional — blank /
 * omitted is stored as null server-side.
 */
export interface RegisterPulsarSourceRequest {
  nodeUrl: string
  nodeName?: string | null
}

// ── apiFetch (local copy — same rationale as api/githubApp.ts) ───────────────

async function apiFetch<T>(path: string, init?: RequestInit): Promise<T> {
  const token = getAccessToken()
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    ...(init?.headers as Record<string, string> | undefined),
  }
  if (token) headers['Authorization'] = `Bearer ${token}`
  const res = await fetch(`${BASE_URL}${path}`, { ...init, headers })
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
  if (res.status === 204 || res.headers.get('content-length') === '0') {
    return undefined as T
  }
  return res.json() as Promise<T>
}

// ── plain client fns (testable without React) ────────────────────────────────

/** GET /api/v1/pulsar/sources — every registered Pulsar node. */
export function listPulsarSources(): Promise<PulsarSourceDto[]> {
  return apiFetch<PulsarSourceDto[]>('/api/v1/pulsar/sources')
}

/**
 * POST /api/v1/pulsar/sources — register a node. The server validates the URL
 * (400), rejects a duplicate (409), and probes the node's repos before
 * persisting (502 if unreachable). Resolves with the created {@link
 * PulsarSourceDto}; rejects with {@link ApiError} carrying the typed problem.
 */
export function registerPulsarSource(
  req: RegisterPulsarSourceRequest,
): Promise<PulsarSourceDto> {
  const body: RegisterPulsarSourceRequest = { nodeUrl: req.nodeUrl.trim() }
  const name = req.nodeName?.trim()
  if (name) body.nodeName = name
  return apiFetch<PulsarSourceDto>('/api/v1/pulsar/sources', {
    method: 'POST',
    body: JSON.stringify(body),
  })
}

/**
 * POST /api/v1/pulsar/sources/{id}/sync — re-probe a node and refresh its
 * {@code repoCount} + {@code lastPolledAt}. 404 if the id is unknown, 502 if
 * the node is unreachable.
 */
export function syncPulsarSource(id: number): Promise<PulsarSourceDto> {
  return apiFetch<PulsarSourceDto>(`/api/v1/pulsar/sources/${id}/sync`, {
    method: 'POST',
  })
}

/**
 * Human-readable message for a register/sync failure, branched on the typed
 * problem so the form shows "already registered" / "couldn't reach" rather
 * than a generic error.
 */
export function pulsarErrorMessage(err: unknown): string {
  if (err instanceof ApiError) {
    if (err.status === 409) return 'A Pulsar source is already registered for that URL.'
    if (err.status === 400)
      return err.problem.detail ?? 'That does not look like a valid http(s) URL.'
    if (err.status === 404) return 'That Pulsar source no longer exists.'
    if (err.status === 502)
      return err.problem.detail ?? 'Could not reach the Pulsar node — check the URL and that it is online.'
    if (err.status === 403) return 'You need the ADMIN role to manage Pulsar sources.'
    if (err.status >= 500) return 'Server error — please retry.'
    return err.problem.detail ?? err.message
  }
  return 'Request failed.'
}

// ── TanStack Query hooks ──────────────────────────────────────────────────────

const PULSAR_SOURCES_KEY = ['pulsar', 'sources'] as const

/** GET /api/v1/pulsar/sources as a TanStack Query. */
export function usePulsarSources() {
  return useQuery<PulsarSourceDto[], ApiError>({
    queryKey: PULSAR_SOURCES_KEY,
    queryFn: listPulsarSources,
    staleTime: 10_000,
  })
}

/** POST register → invalidate the list on success. */
export function useRegisterPulsarSource() {
  const qc = useQueryClient()
  return useMutation<PulsarSourceDto, ApiError, RegisterPulsarSourceRequest>({
    mutationFn: registerPulsarSource,
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: PULSAR_SOURCES_KEY })
    },
  })
}

/** POST sync → patch the refreshed row into the list cache. */
export function useSyncPulsarSource() {
  const qc = useQueryClient()
  return useMutation<PulsarSourceDto, ApiError, { id: number }>({
    mutationFn: ({ id }) => syncPulsarSource(id),
    onSuccess: (fresh) => {
      qc.setQueryData<PulsarSourceDto[]>(PULSAR_SOURCES_KEY, (prev) =>
        prev ? prev.map((s) => (s.id === fresh.id ? fresh : s)) : prev,
      )
    },
  })
}
