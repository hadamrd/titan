/**
 * TanStack Query hooks over the real Titan API client.
 * All queries are CSP-clean — no window.* globals.
 */
import {
  keepPreviousData,
  useInfiniteQuery,
  useMutation,
  useQueries,
  useQuery,
  useQueryClient,
} from '@tanstack/react-query'
import {
  cancelBuild,
  fetchBuild,
  fetchBuildNodes,
  fetchJob,
  fetchJobBuilds,
  fetchJobs,
  triggerBuild,
} from './client'
import { useAuth } from '@/auth/AuthProvider'
import { getAccessToken } from '@/auth/tokenStore'
import { TITAN_UI_VERSION } from '@/lib/version'
import { isTerminal, ApiError } from './types'
import type {
  ActivityPage,
  AgentEventDto,
  ApprovalDecisionResponse,
  ApprovalPage,
  ApprovalStatus,
  BulkApprovalRequest,
  BulkApprovalResponse,
  BuildsPage,
  BuildStatus,
  ArtifactsPage,
  AuditAction,
  AuditPage,
  AuditTargetType,
  BuildDto,
  DrainResponseDto,
  GateDecisionResponse,
  GateDto,
  JobDto,
  JobsPage,
  JobStatsDto,
  JobStatsWindow,
  StageTimingsDto,
  JobTriggerDto,
  PatScope,
  PipelineParameterDto,
  PersonalAccessTokenCreatedDto,
  PersonalAccessTokenDto,
  ProblemJson,
  QueueDrainResponse,
  QueuePage,
  QueueReorderRequest,
  RbacAuditPage,
  RbacVerdict,
  RecentTaskDto,
  RetryStageOutcome,
  StatsDto,
  SystemInfoDto,
  TopFailingJobDto,
  TestResultsPage,
  TriggerBuildRequest,
  UserDto,
  WorkersPage,
} from './types'

// ── Local fetch helper ───────────────────────────────────────────────────────
// Mirrors the apiFetch shape in client.ts but lives here because the file-level
// rule forbids editing client.ts. New backend integrations (gates, artifacts)
// land here until the next refactor consolidates them.

// Same-origin: the SPA is served by nginx, which proxies /api → titan-server.
// Closes #898 — was VITE_TITAN_API_URL (build-time-baked).
const BASE_URL = ''

async function apiFetch<T>(path: string, init?: RequestInit): Promise<T> {
  const url = `${BASE_URL}${path}`
  const token = getAccessToken()
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    ...(init?.headers as Record<string, string> | undefined),
  }
  if (token) headers['Authorization'] = `Bearer ${token}`
  const res = await fetch(url, { ...init, headers })
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

// ── Jobs ──────────────────────────────────────────────────────────────────────

export function useJobs(offset = 0, limit = 50) {
  return useQuery({
    queryKey: ['jobs', offset, limit],
    queryFn: () => fetchJobs(offset, limit),
  })
}

/**
 * GET /api/v1/jobs?search=&limit= — debounced text-search variant used by the
 * ⌘K command palette (closes #687).
 *
 * <p>Today the server ignores the {@code ?search=} param (back-compat: unknown
 * query params pass through JAX-RS); we still send it so a server-side filter
 * can land as a single-PR addition without re-wiring the UI. Results are
 * client-filtered by fullName / displayName (case-insensitive substring) until
 * then. Disabled when {@code search} is empty so the palette's "no fetch on
 * empty query" contract holds.
 */
export function useJobsSearch(search: string, limit = 8) {
  const trimmed = search.trim()
  const enabled = trimmed.length > 0
  return useQuery<JobsPage, ApiError>({
    queryKey: ['jobs', 'search', trimmed, limit],
    queryFn: () => {
      const params = new URLSearchParams()
      params.set('search', trimmed)
      params.set('limit', String(limit))
      return apiFetch<JobsPage>(`/api/v1/jobs?${params.toString()}`)
    },
    enabled,
    placeholderData: keepPreviousData,
    staleTime: 5_000,
  })
}

export function useJob(jobId: number | undefined) {
  return useQuery({
    queryKey: ['jobs', jobId],
    queryFn: () => fetchJob(jobId!),
    enabled: jobId !== undefined,
  })
}

/**
 * GET /api/v1/jobs/{id}/triggers — typed list of trigger runtime state (closes #725).
 *
 * <p>Joins the parsed triggers from config_json with the per-trigger runtime
 * state in titan.job_triggers, so the panel can render lastFiredAt / lastError
 * without having to parse YAML itself. The wire enum {@code JobTriggerType} is
 * closed; switches on it are exhaustive.
 */
export function useJobTriggers(jobId: number | undefined) {
  return useQuery<JobTriggerDto[], ApiError>({
    queryKey: ['jobs', jobId, 'triggers'],
    queryFn: () => apiFetch<JobTriggerDto[]>(`/api/v1/jobs/${jobId}/triggers`),
    enabled: jobId !== undefined,
    // Runtime state turns over on the trigger-engine cadence (every minute-ish).
    // 30 s is a calm middle ground — visible "last fired now" within a tick or
    // two of an actual fire, no heavy polling load.
    refetchInterval: 30_000,
    staleTime: 5_000,
  })
}

/**
 * POST /api/v1/jobs — create a new job (closes #512, follow-up to #511).
 *
 * <p>Server validates the {@code pipelineScript} via {@code TitanYamlParser.parseAndValidate};
 * a parse failure surfaces as HTTP 400 problem+json with the parser's located message in
 * {@code detail}. A duplicate {@code fullName} surfaces as HTTP 409. Both are returned to
 * the caller as {@link ApiError}; the dialog renders these inline (no toast — see #512).
 */
export interface CreateJobRequest {
  fullName: string
  displayName?: string | null
  folderPath?: string | null
  pipelineScript: string
  configJson?: string | null
  enabled?: boolean
}

export function useCreateJob() {
  const qc = useQueryClient()
  return useMutation<JobDto, ApiError, CreateJobRequest>({
    mutationFn: (body) =>
      apiFetch<JobDto>('/api/v1/jobs', {
        method: 'POST',
        body: JSON.stringify(body),
      }),
    onSuccess: (data) => {
      qc.setQueryData(['jobs', data.id], data)
      void qc.invalidateQueries({ queryKey: ['jobs'] })
    },
  })
}

/**
 * PATCH /api/v1/jobs/{jobId} — partial update; currently only {@code pipelineScript}.
 * Round-trips through the server-side {@code TitanYamlParser}, so an invalid YAML
 * surfaces as an {@link ApiError} (400) with the parser's located message.
 */
export function useUpdateJobScript() {
  const qc = useQueryClient()
  return useMutation<JobDto, ApiError, { jobId: number; pipelineScript: string }>({
    mutationFn: ({ jobId, pipelineScript }) =>
      apiFetch<JobDto>(`/api/v1/jobs/${jobId}`, {
        method: 'PATCH',
        body: JSON.stringify({ pipelineScript }),
      }),
    onSuccess: (data, { jobId }) => {
      qc.setQueryData(['jobs', jobId], data)
      void qc.invalidateQueries({ queryKey: ['jobs'] })
    },
  })
}

// ── Credentials list (#439 — for github trigger credentialsId picker) ────────

export interface CredentialDto {
  id: number
  kind: string
  scope: string
  key: string
  createdAt: string
  updatedAt: string
}

interface CredentialsPage {
  items: CredentialDto[]
  total: number
  offset: number
  limit: number
}

/**
 * GET /api/v1/credentials?scope=github-webhook — list HMAC secrets eligible for
 * a github trigger. Empty list is normal on fresh installs; the trigger-edit UI
 * falls back to a free-text input in that case.
 */
export function useGithubWebhookCredentials() {
  return useQuery({
    queryKey: ['credentials', 'github-webhook'],
    queryFn: () =>
      apiFetch<CredentialsPage>('/api/v1/credentials?scope=github-webhook&limit=200'),
    staleTime: 30_000,
  })
}

/**
 * GET /api/v1/jobs/{id}/parameters — typed list of declared pipeline parameters
 * (closes #779 / UI half of #774).
 *
 * <p>Empty array (length 0) is the "no modal — fire directly" signal; the
 * trigger-with-params modal opens iff length ≥ 1. The endpoint also returns
 * {@code []} on a YAML parse failure (server-side log), so a stale/broken
 * pipeline doesn't block the Run button — the bake stage will surface the
 * parse error to the build log on the resulting build.
 *
 * <p>{@code enabled} guards the lazy fetch — we only ask when the caller has a
 * jobId AND has decided to consult it (gating on the click). {@code staleTime}
 * is short: the user can edit the pipeline YAML between trigger attempts, so
 * we don't want a 30-min cache hiding a freshly-added param.
 */
export function useJobParameters(jobId: number | undefined, enabled = true) {
  return useQuery<PipelineParameterDto[], ApiError>({
    queryKey: ['jobs', jobId, 'parameters'],
    queryFn: () => apiFetch<PipelineParameterDto[]>(`/api/v1/jobs/${jobId}/parameters`),
    enabled: jobId !== undefined && enabled,
    staleTime: 5_000,
    retry: false,
  })
}

// ── Builds ────────────────────────────────────────────────────────────────────

export function useJobBuilds(jobId: number | undefined, offset = 0, limit = 50) {
  return useQuery({
    queryKey: ['jobs', jobId, 'builds', offset, limit],
    queryFn: () => fetchJobBuilds(jobId!, offset, limit),
    enabled: jobId !== undefined,
  })
}

/**
 * Bulk per-job recent-builds fetch — GET /api/v1/jobs/recent-builds?jobIds=...&limit=N
 * (closes #650).
 *
 * <p>Backs the /jobs sparkline column without the N+1 fan-out the v1 implementation
 * (one {@link useJobBuilds} per row) suffered from. The server returns a JSON object
 * keyed by stringified jobId; we normalise to a {@code Map<number, BuildDto[]>} so
 * call sites can {@code .get(jobId) ?? []} without re-coercing string keys.
 *
 * <p>An empty {@code jobIds} array returns an empty map without a network call —
 * a fresh /jobs render with no rows must not waste a round-trip.
 *
 * <p>Polling cadence matches the per-row {@link useJobBuilds}-driven sparkline a
 * caller would otherwise need: 5 s. That keeps a freshly-finished build visible
 * in the sparkline within one tick without hammering the DAO.
 */
export function useJobsRecentBuilds(jobIds: readonly number[], limit = 20) {
  // Stabilise the cache key — caller's array reference can change every render, but
  // a sorted CSV is value-equal across renders with the same ids.
  const sorted = [...jobIds].sort((a, b) => a - b)
  const csv = sorted.join(',')
  return useQuery<Map<number, BuildDto[]>>({
    queryKey: ['jobs', 'recent-builds', csv, limit],
    queryFn: async () => {
      if (sorted.length === 0) return new Map()
      const raw = await apiFetch<Record<string, BuildDto[]>>(
        `/api/v1/jobs/recent-builds?jobIds=${encodeURIComponent(csv)}&limit=${limit}`,
      )
      const out = new Map<number, BuildDto[]>()
      for (const [k, v] of Object.entries(raw)) {
        const id = Number(k)
        if (Number.isFinite(id)) out.set(id, v)
      }
      return out
    },
    enabled: sorted.length > 0,
    refetchInterval: 5000,
  })
}

/**
 * Aggregate builds across multiple jobs into a single flattened, sorted list.
 *
 * <p>The backend currently exposes only per-job listing ({@code GET /api/v1/jobs/:id/builds}).
 * The {@code /builds} route needs a global view; rather than add a new endpoint
 * (forbidden by the current constitution), we fan-out a parallel
 * {@link useQueries} over the provided job ids and merge the resulting pages
 * client-side.
 *
 * <p>Returned struct:
 * <ul>
 *   <li>{@code builds} — merged BuildDto[] sorted by {@code queuedAt} desc
 *   <li>{@code total}  — sum of per-job {@code total} (true global total, NOT
 *       just the merged-page length — matches what the KPIs would compute over
 *       the entire population)
 *   <li>{@code isLoading} — true while ANY underlying query is loading
 *   <li>{@code isError}   — true when ALL underlying queries errored (partial
 *       success still surfaces what we have)
 *   <li>{@code error}     — first encountered error, or null
 * </ul>
 */
export function useAllBuilds(jobIds: number[], perJobLimit = 50) {
  const results = useQueries({
    queries: jobIds.map((jobId) => ({
      queryKey: ['jobs', jobId, 'builds', 0, perJobLimit] as const,
      queryFn: () => fetchJobBuilds(jobId, 0, perJobLimit),
    })),
  })
  // Loading: any in-flight (matches react-query semantics — fetchStatus !== 'idle' before first settle)
  const isLoading = results.some((r) => r.isLoading)
  const isError = results.length > 0 && results.every((r) => r.isError)
  const error = results.find((r) => r.isError)?.error ?? null
  // Merge + sort desc by queuedAt (falls back to id for builds with no timestamp yet).
  const builds = results
    .flatMap((r) => r.data?.items ?? [])
    .slice()
    .sort((a, b) => {
      const ta = a.queuedAt ? Date.parse(a.queuedAt) : 0
      const tb = b.queuedAt ? Date.parse(b.queuedAt) : 0
      if (tb !== ta) return tb - ta
      return b.id - a.id
    })
  const total = results.reduce((sum, r) => sum + (r.data?.total ?? 0), 0)
  return { builds, total, isLoading, isError, error }
}

// ── Global filterable build list (#682) ──────────────────────────────────────

/**
 * Typed query shape for {@link useFilteredBuilds} — mirrors the server-side
 * {@code BuildsQuery} DTO (one-to-one). Every field is optional; empty / null
 * fields are stripped from the outgoing query string so the server's
 * back-compat "no filter" path kicks in. AND semantics across all populated
 * filters.
 *
 * <p>Discriminated-union friendly: {@code status} is a closed {@link BuildStatus}
 * union, not a free-form string — adding a new status server-side MUST extend
 * the union or tsc fails.
 */
export interface BuildsFilterQuery {
  /** Multi-select; empty = no status filter. Sent as repeated `?status=X&status=Y`. */
  status?: readonly BuildStatus[]
  /** Exact-match branch. Falsy / empty = no branch filter. */
  branch?: string | null
  /** Free-text search (ILIKE on triggered_by / failure / commit SHA, exact on build #). */
  search?: string | null
  /** ISO-8601 instant; rows older than this are excluded. */
  since?: string | null
  /**
   * Case-insensitive exact match on {@code triggered_by} — the actor (closes
   * #746). Self-scoped: the only legitimate value is the signed-in user's own
   * identifier. NOT a who-triggered-this lookup (no substring, no
   * autocomplete) — that would be a privacy regression.
   */
  triggeredBy?: string | null
  offset?: number
  limit?: number
}

/**
 * GET /api/v1/builds — global, filterable, paginated build list (closes #682).
 *
 * <p>Replaces the per-job fan-out {@link useAllBuilds} for the {@code /builds}
 * page: one round-trip, server does the filtering and pagination. Empty
 * filters degenerate to "newest first" on the server side, so the call is safe
 * to make with an empty query object on first paint.
 *
 * <p>{@code keepPreviousData} (placeholderData) keeps the prior page visible
 * while a filter change refetches — the table does not flash empty.
 */
export function useFilteredBuilds(query: BuildsFilterQuery = {}) {
  const { status = [], branch, search, since, triggeredBy, offset = 0, limit = 50 } = query
  const params = new URLSearchParams()
  for (const s of status) {
    params.append('status', s)
  }
  if (branch && branch.trim() !== '') params.set('branch', branch.trim())
  if (search && search.trim() !== '') params.set('search', search.trim())
  if (since && since.trim() !== '') params.set('since', since.trim())
  if (triggeredBy && triggeredBy.trim() !== '')
    params.set('triggeredBy', triggeredBy.trim())
  params.set('offset', String(offset))
  params.set('limit', String(limit))
  const qs = params.toString()
  return useQuery<BuildsPage, ApiError>({
    queryKey: [
      'builds',
      'filtered',
      [...status].sort().join(','),
      branch ?? null,
      search ?? null,
      since ?? null,
      triggeredBy ?? null,
      offset,
      limit,
    ],
    queryFn: () => apiFetch<BuildsPage>(`/api/v1/builds?${qs}`),
    placeholderData: keepPreviousData,
    staleTime: 2_000,
  })
}

export function useBuild(buildId: number | undefined) {
  return useQuery({
    queryKey: ['builds', buildId],
    queryFn: () => fetchBuild(buildId!),
    enabled: buildId !== undefined,
    // Poll every 2 s while the build is not terminal; stop once it reaches a terminal status.
    refetchInterval: (query) => {
      const status = query.state.data?.status
      if (status === undefined) return 2000 // not yet loaded — keep polling
      return isTerminal(status) ? false : 2000
    },
  })
}

/**
 * GET /api/v1/builds/{id}/nodes — the flow-node list that powers the Pipeline
 * tab.
 *
 * <p>Polls every 2 s while the build is not terminal so newly-spawned nodes
 * appear without a manual refresh. Once the cached build reaches a terminal
 * status we do ONE final fetch (handled by the status-change effect below)
 * then stop. This mirrors {@link useBuild}'s polling discipline.
 *
 * <p>Bug fix — issue #514 (build 21 Pipeline tab empty):
 * <ul>
 *   <li>Without polling, a build that started with zero nodes (still
 *       orchestrating) cached an empty array forever; the Pipeline tab then
 *       showed "0 stages" even after the API exposed 2 nodes for the failed
 *       build.</li>
 *   <li>We also need the cache to invalidate when the build flips to a
 *       terminal status, because the orchestrator can write the final node
 *       rows in the same tick that transitions the build — a single 2 s tick
 *       can land between the last poll and the transition.</li>
 * </ul>
 */
export function useBuildNodes(buildId: number | undefined) {
  const qc = useQueryClient()
  const query = useQuery({
    queryKey: ['builds', buildId, 'nodes'],
    queryFn: () => fetchBuildNodes(buildId!),
    enabled: buildId !== undefined,
    refetchInterval: (q) => {
      const cached = qc.getQueryData<{ status?: string }>(['builds', buildId])
      const status = cached?.status
      if (status === undefined) return 2000
      if (isTerminal(status)) {
        // Build is terminal. Stop polling only once EVERY node is also
        // terminal — otherwise the orchestrator's final node-status updates
        // (written in the same or next tick as the build transition) would
        // be invisible to the UI and the Pipeline tab would show stale
        // RUNNING badges. Build 24 hit exactly this: build SUCCESS, hello
        // STAGE + hello-s0 STEP both SUCCESS in DB, UI still showed RUNNING.
        const data = q.state.data
        if (data && data.length > 0 && data.every((n) => isTerminal(n.status))) {
          return false
        }
        return 2000
      }
      return 2000
    },
  })
  return query
}

// ── Mutations ─────────────────────────────────────────────────────────────────

export function useTriggerBuild() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ jobId, req }: { jobId: number; req?: TriggerBuildRequest }) =>
      triggerBuild(jobId, req ?? {}),
    onSuccess: (_data, { jobId }) => {
      // Invalidate the builds list for this job so the new build appears immediately.
      void qc.invalidateQueries({ queryKey: ['jobs', jobId, 'builds'] })
    },
  })
}

export function useCancelBuild() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (buildId: number) => cancelBuild(buildId),
    onSuccess: (_data, buildId) => {
      // Re-fetch the build so the status badge flips without waiting for the polling interval.
      void qc.invalidateQueries({ queryKey: ['builds', buildId] })
    },
  })
}

// ── Gates (PR #299) ──────────────────────────────────────────────────────────

export function useGates(buildId: number | undefined) {
  return useQuery({
    queryKey: ['builds', buildId, 'gates'],
    queryFn: () => apiFetch<GateDto[]>(`/api/v1/builds/${buildId}/gates`),
    enabled: buildId !== undefined,
    // Pending gates are rare but the UX is much better if we notice resolution
    // within a couple of seconds (e.g. another approver decides). 4s is a good
    // balance vs. backend load.
    refetchInterval: 4000,
  })
}

export function useApproveGate(buildId: number) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ nodeId, reason }: { nodeId: string; reason?: string }) =>
      apiFetch<GateDecisionResponse>(
        `/api/v1/builds/${buildId}/gates/${encodeURIComponent(nodeId)}/approve`,
        {
          method: 'POST',
          body: JSON.stringify({ reason: reason ?? null }),
        },
      ),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ['builds', buildId, 'gates'] })
      void qc.invalidateQueries({ queryKey: ['builds', buildId] })
      void qc.invalidateQueries({ queryKey: ['builds', buildId, 'nodes'] })
    },
  })
}

export function useRejectGate(buildId: number) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ nodeId, reason }: { nodeId: string; reason?: string }) =>
      apiFetch<GateDecisionResponse>(
        `/api/v1/builds/${buildId}/gates/${encodeURIComponent(nodeId)}/reject`,
        {
          method: 'POST',
          body: JSON.stringify({ reason: reason ?? null }),
        },
      ),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ['builds', buildId, 'gates'] })
      void qc.invalidateQueries({ queryKey: ['builds', buildId] })
      void qc.invalidateQueries({ queryKey: ['builds', buildId, 'nodes'] })
    },
  })
}

// ── Approvals (#715 / #720 backend, #721 UI) ─────────────────────────────────

/** Stable query key used by both the inbox and the topbar count chip. */
export const APPROVALS_QUERY_KEY = ['approvals'] as const

/**
 * GET /api/v1/approvals?status=PENDING — paginated approval inbox (#721).
 *
 * <p>Polls every 5 s so a newly-parked build appears in the inbox without a
 * manual refresh AND a timeout-sweep flips a stale PENDING row off-screen
 * promptly. {@code placeholderData} keeps the prior page on filter / page
 * change so the table does not flash empty.
 */
export function useApprovals(
  { status = 'PENDING', offset = 0, limit = 50 }: {
    status?: ApprovalStatus
    offset?: number
    limit?: number
  } = {},
) {
  return useQuery<ApprovalPage, ApiError>({
    queryKey: [...APPROVALS_QUERY_KEY, status, offset, limit],
    queryFn: () =>
      apiFetch<ApprovalPage>(
        `/api/v1/approvals?status=${status}&offset=${offset}&limit=${limit}`,
      ),
    placeholderData: keepPreviousData,
    refetchInterval: 5_000,
    retry: false,
    staleTime: 2_000,
  })
}

/**
 * Per-build pending approvals filter — fed by the same {@link useApprovals}
 * query so the inline banner and the inbox stay in lockstep. We use the
 * page-wide query (rather than a per-build endpoint that doesn't exist) so
 * the cached data is shared across surfaces.
 */
export function usePendingApprovalsForBuild(buildId: number | undefined) {
  const q = useApprovals({ status: 'PENDING', limit: 200 })
  const filtered = (q.data?.items ?? []).filter((a) => a.buildId === buildId)
  return { ...q, items: filtered }
}

export function useApproveApproval() {
  const qc = useQueryClient()
  return useMutation<ApprovalDecisionResponse, ApiError, { id: number; buildId?: number; reason?: string }>({
    mutationFn: ({ id, reason }) =>
      apiFetch<ApprovalDecisionResponse>(`/api/v1/approvals/${id}/approve`, {
        method: 'POST',
        body: JSON.stringify({ reason: reason ?? null }),
      }),
    onSuccess: (_data, { buildId }) => {
      void qc.invalidateQueries({ queryKey: APPROVALS_QUERY_KEY })
      if (buildId !== undefined) {
        void qc.invalidateQueries({ queryKey: ['builds', buildId] })
        void qc.invalidateQueries({ queryKey: ['builds', buildId, 'nodes'] })
      }
    },
  })
}

export function useRejectApproval() {
  const qc = useQueryClient()
  return useMutation<ApprovalDecisionResponse, ApiError, { id: number; buildId?: number; reason?: string }>({
    mutationFn: ({ id, reason }) =>
      apiFetch<ApprovalDecisionResponse>(`/api/v1/approvals/${id}/reject`, {
        method: 'POST',
        body: JSON.stringify({ reason: reason ?? null }),
      }),
    onSuccess: (_data, { buildId }) => {
      void qc.invalidateQueries({ queryKey: APPROVALS_QUERY_KEY })
      if (buildId !== undefined) {
        void qc.invalidateQueries({ queryKey: ['builds', buildId] })
        void qc.invalidateQueries({ queryKey: ['builds', buildId, 'nodes'] })
      }
    },
  })
}

// ── Bulk approve / reject (#734) ────────────────────────────────────────────
//
// Single round-trip for release-train workflows where an SRE has 5+ pending
// rows queued up. Per-id outcome semantics mirror the single endpoint's HTTP
// statuses (200 / 409 / 403 / 404) collapsed into a {applied, reason} pair
// inside one 200 envelope — partial success is the norm and surfacing it as
// a single 4xx would lose information.

export function useBulkApprove() {
  const qc = useQueryClient()
  return useMutation<BulkApprovalResponse, ApiError, BulkApprovalRequest>({
    mutationFn: (body) =>
      apiFetch<BulkApprovalResponse>('/api/v1/approvals/bulk/approve', {
        method: 'POST',
        body: JSON.stringify(body),
      }),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: APPROVALS_QUERY_KEY })
    },
  })
}

export function useBulkReject() {
  const qc = useQueryClient()
  return useMutation<BulkApprovalResponse, ApiError, BulkApprovalRequest>({
    mutationFn: (body) =>
      apiFetch<BulkApprovalResponse>('/api/v1/approvals/bulk/reject', {
        method: 'POST',
        body: JSON.stringify(body),
      }),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: APPROVALS_QUERY_KEY })
    },
  })
}

/**
 * Summarise a bulk-decision response into a human banner: "N approved, M
 * skipped (already decided · forbidden · not found)". Stable English keyed by
 * the closed {@link BulkApprovalReason} union — no Map<string,unknown>, no
 * inlined sniffing at call sites.
 */
export function summarizeBulkOutcomes(
  resp: BulkApprovalResponse,
  verb: 'approved' | 'rejected',
): string {
  const applied = resp.outcomes.filter((o) => o.applied).length
  const skipped = resp.outcomes.filter((o) => !o.applied)
  if (skipped.length === 0) {
    return `${applied} ${verb}`
  }
  const reasonCounts: Record<string, number> = {}
  for (const o of skipped) {
    const key = o.reason ?? 'unknown'
    reasonCounts[key] = (reasonCounts[key] ?? 0) + 1
  }
  const labels: Record<string, string> = {
    already_decided: 'already decided',
    forbidden: 'forbidden',
    not_found: 'not found',
    unknown: 'unknown reason',
  }
  const parts = Object.entries(reasonCounts).map(
    ([k, n]) => `${n} ${labels[k] ?? k}`,
  )
  return `${applied} ${verb}, ${skipped.length} skipped (${parts.join(' · ')})`
}

// ── Replay-from-node (issue #307) ─────────────────────────────────────────────

/**
 * Replay a build from a given flow node — POST /api/v1/builds/{buildId}/replay.
 * Body: { nodeId, withChanges?: { params? } }. Returns the new build's id; the
 * caller (typically the build-detail page) navigates to /builds/{newBuildId}
 * on success.
 */
export interface ReplayBuildResponse {
  newBuildId: number
  queuedPosition: number | null
}

export function useReplayBuild() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({
      buildId,
      nodeId,
      paramOverrides,
    }: {
      buildId: number
      nodeId: string
      paramOverrides?: Record<string, string>
    }) =>
      apiFetch<ReplayBuildResponse>(`/api/v1/builds/${buildId}/replay`, {
        method: 'POST',
        body: JSON.stringify({
          nodeId,
          withChanges:
            paramOverrides && Object.keys(paramOverrides).length > 0
              ? { params: paramOverrides }
              : undefined,
        }),
      }),
    onSuccess: () => {
      // The new build joins its job's build list; invalidating the keyless
      // "builds" prefix is enough to refresh both the global builds page and
      // any per-job listing on screen.
      void qc.invalidateQueries({ queryKey: ['builds'] })
      void qc.invalidateQueries({ queryKey: ['jobs'] })
    },
  })
}

/**
 * Replay-from-first-failed-stage (issue #664) — POST
 * /api/v1/builds/{buildId}/replay-from-failed. The server picks the first
 * stage whose flow node ended FAILED (in declared YAML order) and creates a
 * new build pinned to it. Returns the full new BuildDto (not the compact
 * /replay shape) so the caller can navigate without an extra fetch.
 *
 * Discriminated-union typed on the wire: this is its own endpoint, not a
 * "fromStage" flavour of /replay — the UI hook mirrors that by being its
 * own mutation, not a parameter on useReplayBuild.
 */
export function useReplayFromFailedStage() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({
      buildId,
      paramOverrides,
    }: {
      buildId: number
      paramOverrides?: Record<string, string>
    }) =>
      apiFetch<BuildDto>(`/api/v1/builds/${buildId}/replay-from-failed`, {
        method: 'POST',
        body: JSON.stringify(
          paramOverrides && Object.keys(paramOverrides).length > 0
            ? { withChanges: { params: paramOverrides } }
            : {},
        ),
      }),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ['builds'] })
      void qc.invalidateQueries({ queryKey: ['jobs'] })
    },
  })
}

// ── Stage retry (#748 — UI half of #744) ─────────────────────────────────────

/**
 * POST /api/v1/builds/{buildId}/stages/{stageId}/retry — re-run a failed stage
 * in place (no new build forked). Server resets the stage flow_node + every
 * DAG descendant back to QUEUED and enqueues ORCHESTRATE/ADVANCE.
 *
 * <p>RBAC gate (server-side): {@code REPLAY_BUILD} or {@code ADMIN}. The UI
 * pre-disables the button for users without the role (see StageTimingPanel)
 * so the 403 round-trip is the fallback, not the primary feedback.
 *
 * <p>Server errors mapped to {@link ApiError}:
 * <ul>
 *   <li>404 — unknown build or stage; caller should refresh the build view.</li>
 *   <li>409 — stage is no longer in FAILED state (idempotent semantics: a
 *       double-click resolves the first call to RUNNING, the second 409s).</li>
 *   <li>403 — caller lacks the role.</li>
 * </ul>
 *
 * <p>On success we invalidate the build + nodes queries so the freshly-QUEUED
 * descendants render without a manual refresh, mirroring the gate-decision
 * invalidation discipline.
 */
export function useRetryStage(buildId: number) {
  const qc = useQueryClient()
  return useMutation<RetryStageOutcome, ApiError, { stageId: string }>({
    mutationFn: ({ stageId }) =>
      apiFetch<RetryStageOutcome>(
        `/api/v1/builds/${buildId}/stages/${encodeURIComponent(stageId)}/retry`,
        { method: 'POST' },
      ),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ['builds', buildId] })
      void qc.invalidateQueries({ queryKey: ['builds', buildId, 'nodes'] })
      void qc.invalidateQueries({ queryKey: ['builds', buildId, 'gates'] })
    },
  })
}

// ── Test results (issue #296) ────────────────────────────────────────────────

/**
 * Paginated browser for the parsed JUnit results of a single build.
 *
 * <p>Server caps {@code limit} at 500 (default 200); summary counts span the
 * whole build, not just the page — see {@code TestResultsApi}.
 */
export function useTests(buildId: number, offset = 0, limit = 200) {
  return useQuery({
    queryKey: ['tests', buildId, offset, limit],
    queryFn: () =>
      apiFetch<TestResultsPage>(
        `/api/v1/builds/${buildId}/tests?offset=${offset}&limit=${limit}`,
      ),
    enabled: Number.isFinite(buildId),
  })
}

// ── Artifacts (PR #300) ──────────────────────────────────────────────────────

export function useArtifacts(buildId: number | undefined, offset = 0, limit = 200) {
  return useQuery({
    queryKey: ['builds', buildId, 'artifacts', offset, limit],
    queryFn: () =>
      apiFetch<ArtifactsPage>(
        `/api/v1/builds/${buildId}/artifacts?offset=${offset}&limit=${limit}`,
      ),
    enabled: buildId !== undefined,
  })
}

/**
 * Wire-shape for {@code POST /api/v1/artifacts/{id}/sign-download} (closes #849).
 *
 * Backend mints a short-lived HMAC-signed URL whose authentication is in the {@code ?token=}
 * query param, NOT a bearer header. Browsers can navigate this URL directly from an iframe or
 * anchor tag — which they CANNOT do with a bearer-only endpoint.
 */
export interface SignedDownloadDto {
  url: string
  expiresAt: string
}

/**
 * In-memory per-artifact cache of signed-download URLs. A Preview-then-Get pair on the same
 * row should not round-trip /sign-download twice. We refetch once we're within
 * {@link SIGNED_URL_REFRESH_BUFFER_MS} of expiry so a slow human (or a long iframe load) never
 * navigates a just-expired URL.
 */
const SIGNED_URL_REFRESH_BUFFER_MS = 30_000
const signedUrlCache = new Map<number, { url: string; expiresAtMs: number }>()

/**
 * Async resolve a browser-navigable URL for an artifact (closes #849).
 *
 * <p>Replaces the v0 {@code artifactDownloadUrl(downloadUrl)} string-builder: that returned a
 * URL pointing at a bearer-gated endpoint, which {@code <iframe>}/{@code <a download>}
 * navigations could not authenticate. The replacement POSTs /sign-download (which IS
 * bearer-gated, via {@link apiFetch}) and returns the signed URL the response carries.
 *
 * <p>Cached per-artifact in-memory; the cache key is the artifact id. The cached URL is reused
 * until it's within 30 s of expiring, then a fresh one is minted on the next call.
 */
export async function resolveArtifactDownloadUrl(artifactId: number): Promise<string> {
  const cached = signedUrlCache.get(artifactId)
  if (cached && cached.expiresAtMs - Date.now() > SIGNED_URL_REFRESH_BUFFER_MS) {
    return cached.url
  }
  const signed = await apiFetch<SignedDownloadDto>(
    `/api/v1/artifacts/${artifactId}/sign-download`,
    { method: 'POST' },
  )
  const absolute = `${BASE_URL}${signed.url}`
  signedUrlCache.set(artifactId, {
    url: absolute,
    expiresAtMs: Date.parse(signed.expiresAt),
  })
  return absolute
}

/**
 * Test-only: clear the per-artifact signed-URL cache between test cases so a stale entry from
 * test A doesn't leak into test B's expectations. Not part of the public hooks surface.
 */
export function __clearSignedDownloadUrlCacheForTesting() {
  signedUrlCache.clear()
}

// ── Workers (issue #313 — wire Workers grid) ─────────────────────────────────

/**
 * Workers list — GET /api/v1/workers. Returns the full agent fleet via
 * {@code WorkersPage}. The list polls every 5 s while mounted so drain state
 * transitions, heartbeat changes, and currentTasks updates are visible without
 * a manual refresh.
 */
export function useWorkers(offset = 0, limit = 200) {
  return useQuery({
    queryKey: ['workers', offset, limit],
    queryFn: () =>
      apiFetch<WorkersPage>(`/api/v1/workers?offset=${offset}&limit=${limit}`),
    refetchInterval: 5000,
  })
}

export function useDrainWorker() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (workerId: string) =>
      apiFetch<DrainResponseDto>(`/api/v1/workers/${encodeURIComponent(workerId)}/drain`, {
        method: 'POST',
      }),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ['workers'] })
    },
  })
}

/**
 * Worker lifecycle events — GET /api/v1/agents/events?limit=N (closes #714).
 *
 * <p>Drives the Home activity timeline's join/leave rows. 10 s poll cadence:
 * the event log is append-only and a freshly-joined worker should appear within
 * one tick without the user refreshing. Server hard-caps {@code limit} at 500;
 * the default of 20 matches the timeline's render budget.
 */
export function useAgentEvents(limit = 20, refetchIntervalMs = 10_000) {
  return useQuery({
    queryKey: ['agents', 'events', limit],
    queryFn: () =>
      apiFetch<AgentEventDto[]>(`/api/v1/agents/events?limit=${limit}`),
    refetchInterval: refetchIntervalMs,
  })
}

export function useUndrainWorker() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (workerId: string) =>
      apiFetch<DrainResponseDto>(`/api/v1/workers/${encodeURIComponent(workerId)}/undrain`, {
        method: 'POST',
      }),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ['workers'] })
    },
  })
}

// ── Queue (PR #306 — wire Queue page) ────────────────────────────────────────

/**
 * Build queue — GET /api/v1/queue. Server hard-caps {@code limit} at 500.
 * 5 s polling so the page reflects live claim/dispatch activity (a task can
 * be claimed by a worker between renders; we want that to show up promptly).
 */
export function useQueue(limit = 200) {
  return useQuery({
    queryKey: ['queue', limit],
    queryFn: () => apiFetch<QueuePage>(`/api/v1/queue?limit=${limit}`),
    refetchInterval: 5000,
  })
}

/**
 * Recent task activity — GET /api/v1/queue/recent?limit=N (issue #523).
 *
 * <p>Reads the most-recently-archived tasks from {@code task_archive}. The Queue
 * page renders this as a "Recent activity" card BELOW the live queue when the
 * live queue is empty, so an idle controller doesn't read as broken. Server
 * envelope is a bare {@code RecentTaskDto[]} (no pagination — the cap is the
 * limit). 15 s poll cadence: terminal events are rare relative to claim/dispatch
 * churn, and the card is a secondary surface, so we don't need 5 s here.
 */
export function useRecentTasks({ limit = 20 }: { limit?: number } = {}) {
  return useQuery({
    queryKey: ['queue', 'recent', limit],
    queryFn: () =>
      apiFetch<RecentTaskDto[]>(`/api/v1/queue/recent?limit=${limit}`),
    refetchInterval: 15_000,
  })
}

// ── Admin queue (PR #373 — closes #347) ──────────────────────────────────────

/**
 * Drain every currently-{@code QUEUED} task — POST /api/v1/queue/drain.
 * Idempotent; CLAIMED/PROCESSING tasks are untouched (drain is a queue-clear,
 * not a worker-kill).
 */
export function useDrainQueue() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: () =>
      apiFetch<QueueDrainResponse>('/api/v1/queue/drain', { method: 'POST' }),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ['queue'] })
    },
  })
}

/**
 * Re-prioritize a set of {@code QUEUED} tasks — POST /api/v1/queue/reorder.
 * {@code taskIds} is head-first: first id becomes highest-priority. Returns
 * 204; unknown / non-QUEUED / duplicate ids surface as 400 (ApiError).
 */
export function useReorderQueue() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (req: QueueReorderRequest) =>
      apiFetch<void>('/api/v1/queue/reorder', {
        method: 'POST',
        body: JSON.stringify(req),
      }),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ['queue'] })
    },
  })
}

// ── Stats (PR #362 — closes #346) ────────────────────────────────────────────

/**
 * Overview KPI aggregate — GET /api/v1/stats. Three numbers (buildsToday,
 * successRate ∈ [0,1], medianDurationMs) in one round-trip. Polls every 30 s
 * so the Overview tile reflects current state without hammering the DAO.
 */
export function useStats() {
  return useQuery({
    queryKey: ['stats'],
    queryFn: () => apiFetch<StatsDto>('/api/v1/stats'),
    refetchInterval: 30_000,
  })
}

// ── Top failing jobs (T67 — closes #769) ─────────────────────────────────────

/**
 * Home "Top failing jobs" widget — GET /api/v1/jobs/top-failing?since=…&limit=…
 *
 * Server clamps {@code since} to {24h, 7d} and {@code limit} to [1, 20]. The
 * response is an array (never null), sorted by failure rate descending; rows
 * with fewer than 3 builds in the window are filtered server-side. Polls every
 * 30 s — same cadence as {@link useStats} so the Home dashboard refresh feels
 * coordinated.
 */
export function useTopFailingJobs(
  since: '24h' | '7d' = '24h',
  limit = 5,
) {
  return useQuery({
    queryKey: ['top-failing-jobs', since, limit],
    queryFn: () =>
      apiFetch<TopFailingJobDto[]>(
        `/api/v1/jobs/top-failing?since=${since}&limit=${limit}`,
      ),
    refetchInterval: 30_000,
  })
}

// ── Per-job stats (T68 — closes #775) ────────────────────────────────────────

/**
 * Per-job analytics — GET /api/v1/jobs/{id}/stats?window=7d|30d|90d.
 *
 * Server clamps {@code window} to the closed set; the typed {@link JobStatsWindow}
 * mirrors that so a typo can't slip through. {@code p50DurationMs} / {@code
 * p95DurationMs} are nullable — null means "no completed build", NOT zero.
 *
 * <p>Polls every 60 s — analytics are a calm secondary surface; aggressive polling
 * would burn server cycles for changes the user can comfortably wait one minute
 * for. {@code keepPreviousData} keeps the prior window on screen while a chip
 * switch refetches so the sparkline doesn't flash empty.
 */
export function useJobStats(
  jobId: number | undefined,
  window: JobStatsWindow = '30d',
) {
  return useQuery<JobStatsDto, ApiError>({
    queryKey: ['jobs', jobId, 'stats', window],
    queryFn: () =>
      apiFetch<JobStatsDto>(`/api/v1/jobs/${jobId}/stats?window=${window}`),
    enabled: jobId !== undefined,
    placeholderData: keepPreviousData,
    refetchInterval: 60_000,
    staleTime: 10_000,
  })
}

// ── Per-job stage timing percentiles (closes #1095) ─────────────────────────

/**
 * Per-job stage timings — GET /api/v1/jobs/{id}/stage-timings?n=30.
 *
 * Powers the "Stage Timing — last N builds" panel that lets SREs see whether a
 * build is slower than usual without clicking through history. Server returns
 * p50/p95/p99 + per-build samples for every stage with at least one finished
 * sample in the window.
 *
 * <p>Polls every 60s (analytics is a calm secondary surface — see {@link
 * useJobStats}). Empty stages list with {@code buildsConsidered: 0} is the
 * "not enough history yet" signal the UI renders as an empty state.
 */
export function useJobStageTimings(jobId: number | undefined, n: number = 30) {
  return useQuery<StageTimingsDto, ApiError>({
    queryKey: ['jobs', jobId, 'stage-timings', n],
    queryFn: () =>
      apiFetch<StageTimingsDto>(`/api/v1/jobs/${jobId}/stage-timings?n=${n}`),
    enabled: jobId !== undefined,
    placeholderData: keepPreviousData,
    refetchInterval: 60_000,
    staleTime: 10_000,
  })
}

// ── System info (#676 — engine health dashboard) ─────────────────────────────

/**
 * Engine health snapshot — GET /api/v1/system/info. One round-trip carrying
 * version, build SHA, DB status, queue depth, workers online, and the server
 * clock. Polled every 10 s so the /system page reflects the live engine state
 * without operator action. The endpoint never 500s on a down DB — it returns
 * {@code dbStatus: 'DOWN'} so the page can render the outage rather than going
 * blank.
 */
export function useSystemInfo() {
  return useQuery({
    queryKey: ['system-info'],
    queryFn: () => apiFetch<SystemInfoDto>('/api/v1/system/info'),
    refetchInterval: 10_000,
  })
}

// ── Activity feed (PR #370 — closes #304) ────────────────────────────────────

/**
 * Paginated Overview activity feed — GET /api/v1/activity?limit=N&before=cursor.
 * v1 emits only {@code build.terminal} items derived from terminal-state rows
 * in {@code titan.builds}. Uses {@code useInfiniteQuery} so the caller can
 * fetch additional pages via {@code fetchNextPage()} when {@code nextCursor}
 * is non-null.
 */
// ── Current user (forge-loop tick #45 — Profile polish) ─────────────────────
//
// Surfaces the OIDC user already held in AuthContext as a single struct so the
// Profile page (and any future "Signed in as …" badge) doesn't poke at the
// raw User.profile claim bag inline. No network call — the OIDC user is
// already in memory after the redirect-callback completes.

export interface CurrentUser {
  name: string
  email: string
  initials: string
  issuer: string | null
  /** OIDC `iat` claim (seconds since epoch) — when this token was issued. */
  issuedAt: number | null
}

export function useCurrentUser(): CurrentUser | null {
  const { user } = useAuth()
  if (user === null) return null
  const profile = user.profile as Record<string, unknown> | undefined
  const name =
    (profile?.preferred_username as string | undefined) ??
    (profile?.name as string | undefined) ??
    (profile?.email as string | undefined) ??
    'user'
  const email = (profile?.email as string | undefined) ?? '—'
  const initials =
    name
      .split(/[.\s_-]+/)
      .slice(0, 2)
      .map((s) => s.charAt(0).toUpperCase())
      .join('') || 'U'
  const issuer = (profile?.iss as string | undefined) ?? null
  const iat = profile?.iat
  const issuedAt = typeof iat === 'number' ? iat : null
  return { name, email, initials, issuer, issuedAt }
}

// ── Workspace + server info (forge-loop tick #45 — Settings polish) ─────────
//
// 0.1.0 has no `/api/v1/info` endpoint — settings reads the worker fleet via
// the existing `/api/v1/workers` endpoint and pins the version literal from
// {@link TITAN_UI_VERSION}. Returning a typed struct here lets the Settings
// route stay declarative and gives a single seam to swap in a real backend
// info endpoint later.

export interface WorkspaceInfo {
  /** Display-only workspace name; for 0.1.0 we pin the product identity. */
  name: string
  /** Browser-detected default timezone — display only. */
  timezone: string
  /** Total registered workers across all pools. */
  workerCount: number | null
  /** Distinct pool labels — sorted, ASCII case-insensitive. */
  pools: string[]
  /** UI version literal — see lib/version.ts. */
  uiVersion: string
}

// ── Server info (forge-loop tick #48 — GET /api/v1/info) ─────────────────────

export interface ServerInfo {
  /** Gradle project version (e.g. "0.1.0"); "unknown" when build-info absent. */
  version: string
  /** Short git SHA (7 chars); "unknown" when build-info absent. */
  commit: string
  /** ISO-8601 build timestamp; "unknown" when build-info absent. */
  builtAt: string
  /** Seconds since the JVM started; computed live on every request. */
  uptimeSeconds: number
}

/**
 * Server identity tile — GET /api/v1/info. Public endpoint (no bearer required);
 * Settings page renders version + commit-sha + builtAt from this hook with a
 * graceful fallback to the pinned {@link TITAN_UI_VERSION} when the fetch fails.
 *
 * <p>One-shot fetch — the server doesn't change identity at runtime, so the
 * query is cached for the session and never re-polled. Retries disabled so a
 * 500/timeout falls through to the UI-side fallback immediately.
 */
async function fetchServerInfo(): Promise<ServerInfo> {
  // Bypass apiFetch — it forces a bearer header; /api/v1/info is public.
  const res = await fetch(`${BASE_URL}/api/v1/info`)
  if (!res.ok) throw new ApiError(res.status, {
    type: 'about:blank',
    title: res.statusText || 'info fetch failed',
    status: res.status,
    detail: null,
    instance: null,
  })
  return (await res.json()) as ServerInfo
}

export function useServerInfo() {
  return useQuery({
    queryKey: ['server-info'],
    queryFn: fetchServerInfo,
    retry: false,
    staleTime: Number.POSITIVE_INFINITY,
    // gcTime defaults — fine; the response is tiny.
  })
}

export function useWorkspaceInfo(): WorkspaceInfo {
  const { data } = useWorkers()
  const workerCount = data?.total ?? null
  const pools = data
    ? Array.from(new Set(data.items.map((w) => w.pool))).sort()
    : []
  const timezone =
    typeof Intl !== 'undefined'
      ? (Intl.DateTimeFormat().resolvedOptions().timeZone ?? 'UTC')
      : 'UTC'
  return {
    name: 'Titan',
    timezone,
    workerCount,
    pools,
    uiVersion: TITAN_UI_VERSION,
  }
}

export function useActivity(limit = 25) {
  return useInfiniteQuery({
    queryKey: ['activity', limit],
    queryFn: ({ pageParam }) => {
      const params = new URLSearchParams({ limit: String(limit) })
      if (pageParam) params.set('before', pageParam)
      return apiFetch<ActivityPage>(`/api/v1/activity?${params.toString()}`)
    },
    initialPageParam: '' as string,
    getNextPageParam: (last) => last.nextCursor ?? undefined,
    // Mild polling so newly-finished builds appear on the Overview without a
    // hard refresh; the feed is read-only so the cost is one cheap SQL pass.
    refetchInterval: 15_000,
  })
}

// ── Personal access tokens (#434) ────────────────────────────────────────────

const TOKENS_QUERY_KEY = ['me', 'tokens'] as const

/**
 * GET /api/v1/me/tokens — list the signed-in user's personal access tokens.
 * The server scopes by OIDC `sub`; an empty array on a fresh user is normal.
 */
export function useMyTokens() {
  return useQuery({
    queryKey: TOKENS_QUERY_KEY,
    queryFn: () => apiFetch<PersonalAccessTokenDto[]>('/api/v1/me/tokens'),
    // Don't background-refetch — token state only changes on this user's actions.
    staleTime: 30_000,
  })
}

/**
 * POST /api/v1/me/tokens — generate a fresh token. The response carries the
 * plaintext exactly once; callers MUST surface it immediately and never persist
 * it. On success the list query is invalidated so the new row appears in the
 * table.
 */
export function useCreateToken() {
  const qc = useQueryClient()
  return useMutation<
    PersonalAccessTokenCreatedDto,
    ApiError,
    { name: string; scopes?: PatScope[] | null; jobPattern?: string | null }
  >({
    mutationFn: (body) =>
      apiFetch<PersonalAccessTokenCreatedDto>('/api/v1/me/tokens', {
        method: 'POST',
        body: JSON.stringify(body),
      }),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: TOKENS_QUERY_KEY })
    },
  })
}

/**
 * DELETE /api/v1/me/tokens/:id — soft-revoke. The row stays for audit;
 * `revoked_at` flips to a non-null timestamp. The mutation invalidates the
 * list so the row shows its revoked badge immediately.
 */
export function useRevokeToken() {
  const qc = useQueryClient()
  return useMutation<void, ApiError, { id: number }>({
    mutationFn: ({ id }) =>
      apiFetch<void>(`/api/v1/me/tokens/${id}`, { method: 'DELETE' }),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: TOKENS_QUERY_KEY })
    },
  })
}

// ── Audit log (#517 — closes #478 UI half) ───────────────────────────────────

export interface AuditEventsQuery {
  /** ILIKE substring on the actor column. */
  actor?: string
  /**
   * Repeatable action filter (#727). Each entry is sent as a separate
   * {@code ?action=X&action=Y} query param; the server treats the list as an
   * OR-within / AND-against-the-other-filters subset.
   * Empty list / undefined = no action filter.
   */
  action?: AuditAction | readonly AuditAction[]
  /** Legacy single-enum target type — preserved for back-compat with the v0 wire. */
  targetType?: AuditTargetType
  /** ILIKE substring across target_type and target_id (#727). */
  resource?: string
  /** ISO-8601 timestamp; server requires the {@code Z} suffix. */
  since?: string
  offset?: number
  limit?: number
}

/**
 * GET /api/v1/audit — ADMIN-only paginated audit feed.
 *
 * <p>Filters are funneled through query params; blank filters are omitted so
 * the server's null-branch (no-WHERE) kicks in. {@code placeholderData} keeps
 * the prior page on screen while the next-page request resolves, so the table
 * does not flash empty during pagination.
 *
 * <p>On 403 (caller lacks ADMIN), the query errors with {@link ApiError}; the
 * page renders "Admin role required …" instead of a blank table.
 */
// ── Admin · Users (#609 — UI half of #603/#607) ─────────────────────────────

/**
 * GET /api/v1/admin/users — ADMIN-only Keycloak user-list proxy.
 *
 * <p>Until #608 lands the real KC service-account, the server returns a STUB
 * list (alice + bob). The wire shape is identical against both, so this hook
 * works against the rig today and against a live KC tomorrow with no change.
 *
 * <p>On 403 (caller lacks ADMIN) the query errors with {@link ApiError}; the
 * page renders an "Admin role required" placeholder instead of a blank table.
 * {@code retry: false} so the 403 surfaces immediately rather than after 3
 * retries.
 */
export function useAdminUsers(
  { offset = 0, limit = 100 }: { offset?: number; limit?: number } = {},
) {
  return useQuery<UserDto[], ApiError>({
    queryKey: ['admin', 'users', offset, limit],
    queryFn: () =>
      apiFetch<UserDto[]>(`/api/v1/admin/users?offset=${offset}&limit=${limit}`),
    retry: false,
    staleTime: 30_000,
  })
}

export function useAuditEvents(
  query: AuditEventsQuery = {},
  options: { refetchInterval?: number } = {},
) {
  const { actor, action, targetType, resource, since, offset = 0, limit = 50 } = query
  const actions: readonly AuditAction[] = Array.isArray(action)
    ? action
    : action
      ? [action]
      : []
  const params = new URLSearchParams()
  if (actor && actor.trim() !== '') params.set('actor', actor.trim())
  for (const a of actions) {
    params.append('action', a)
  }
  if (targetType) params.set('targetType', targetType)
  if (resource && resource.trim() !== '') params.set('resource', resource.trim())
  if (since && since.trim() !== '') params.set('since', since.trim())
  params.set('offset', String(offset))
  params.set('limit', String(limit))
  const qs = params.toString()
  return useQuery<AuditPage, ApiError>({
    queryKey: [
      'audit',
      actor ?? null,
      [...actions].sort().join(','),
      targetType ?? null,
      resource ?? null,
      since ?? null,
      offset,
      limit,
    ],
    queryFn: () => apiFetch<AuditPage>(`/api/v1/audit?${qs}`),
    // Retain previous page on filter/offset change so the table does not flash.
    placeholderData: (prev) => prev,
    staleTime: 5_000,
    retry: false,
    refetchInterval: options.refetchInterval,
  })
}

// ── RBAC audit trail (#1167 — the typed allow/deny read surface) ──────────────

export interface RbacAuditEventsQuery {
  /** ILIKE substring on the actor (user id) column. */
  actor?: string
  /**
   * Repeatable verdict filter. Each entry is sent as a separate {@code ?verdict=X} param; the
   * server collapses ALLOW+DENY (or empty) to "no filter". Closed union — a bad value cannot be
   * constructed here.
   */
  verdict?: RbacVerdict | readonly RbacVerdict[]
  /** Scope kind (ORG / REPO); validated server-side against the ScopeKind enum. */
  scopeKind?: string
  /** ISO-8601 timestamp lower bound; server requires the {@code Z} suffix. */
  since?: string
  offset?: number
  limit?: number
}

/**
 * GET /api/v1/rbac-audit — READ_AUDIT/ADMIN-gated paginated feed of the typed RBAC allow/deny
 * trail over titan.rbac_audit.
 *
 * <p>Mirrors {@link useAuditEvents}: blank filters are omitted so the server's null-branch (no
 * WHERE) kicks in; {@code placeholderData} keeps the prior page on screen during pagination so the
 * table does not flash empty. {@code retry: false} so a 403 (caller lacks the role) surfaces
 * immediately as an {@link ApiError} the page renders as the role-gate message, not after 3
 * retries.
 */
export function useRbacAuditEvents(
  query: RbacAuditEventsQuery = {},
  options: { refetchInterval?: number } = {},
) {
  const { actor, verdict, scopeKind, since, offset = 0, limit = 50 } = query
  const verdicts: readonly RbacVerdict[] = Array.isArray(verdict)
    ? verdict
    : verdict
      ? [verdict]
      : []
  const params = new URLSearchParams()
  if (actor && actor.trim() !== '') params.set('actor', actor.trim())
  for (const v of verdicts) {
    params.append('verdict', v)
  }
  if (scopeKind && scopeKind.trim() !== '') params.set('scopeKind', scopeKind.trim())
  if (since && since.trim() !== '') params.set('since', since.trim())
  params.set('offset', String(offset))
  params.set('limit', String(limit))
  const qs = params.toString()
  return useQuery<RbacAuditPage, ApiError>({
    queryKey: [
      'rbac-audit',
      actor ?? null,
      [...verdicts].sort().join(','),
      scopeKind ?? null,
      since ?? null,
      offset,
      limit,
    ],
    queryFn: () => apiFetch<RbacAuditPage>(`/api/v1/rbac-audit?${qs}`),
    placeholderData: (prev) => prev,
    staleTime: 5_000,
    retry: false,
    refetchInterval: options.refetchInterval,
  })
}

// ── Starred jobs (#703) ──────────────────────────────────────────────────────

/**
 * Server-side per-user pinned jobs (#703). Replaces the v0 localStorage favorites
 * (#636) — the OIDC subject is the scope key so stars follow the user across tabs,
 * machines, and re-installs.
 *
 * <p>Wire shape: {@code GET /api/v1/me/starred-jobs} → {@code JobDto[]} newest-pin
 * first. Empty array (never null) when the user has no stars. The 10-cap lives
 * in the server (StarredJobsApi.MAX_STARS_PER_USER); the UI mirrors it so the
 * StarButton can early-disable rather than wait for a 409 round-trip.
 */
export const MAX_STARRED_JOBS = 10

export const STARRED_JOBS_QUERY_KEY = ['me', 'starred-jobs'] as const

export function useStarredJobs() {
  return useQuery<JobDto[], ApiError>({
    queryKey: STARRED_JOBS_QUERY_KEY,
    queryFn: () => apiFetch<JobDto[]>('/api/v1/me/starred-jobs'),
    // Stars are user-controlled — refetch on focus is enough; no polling needed.
    staleTime: 30_000,
  })
}

/**
 * PUT /api/v1/me/starred-jobs/{jobId}. Optimistic — the row appears in the cache
 * instantly; on 409 (cap reached) or network failure we roll back AND surface the
 * problem.detail so the caller can show a toast.
 */
export function useStarJob() {
  const qc = useQueryClient()
  return useMutation<
    void,
    ApiError,
    { jobId: number; job: JobDto },
    { previous: JobDto[] | undefined }
  >({
    mutationFn: ({ jobId }) =>
      apiFetch<void>(`/api/v1/me/starred-jobs/${jobId}`, { method: 'PUT' }),
    onMutate: async ({ job }) => {
      await qc.cancelQueries({ queryKey: STARRED_JOBS_QUERY_KEY })
      const previous = qc.getQueryData<JobDto[]>(STARRED_JOBS_QUERY_KEY)
      // Optimistic: prepend (newest-pin-first ordering). Dedupe defensively.
      const next: JobDto[] = previous ? [job, ...previous.filter((j) => j.id !== job.id)] : [job]
      qc.setQueryData<JobDto[]>(STARRED_JOBS_QUERY_KEY, next)
      return { previous }
    },
    onError: (_err, _vars, ctx) => {
      if (ctx?.previous !== undefined) {
        qc.setQueryData<JobDto[]>(STARRED_JOBS_QUERY_KEY, ctx.previous)
      }
    },
    onSettled: () => {
      void qc.invalidateQueries({ queryKey: STARRED_JOBS_QUERY_KEY })
    },
  })
}

/**
 * DELETE /api/v1/me/starred-jobs/{jobId}. Optimistic — drop the row from cache,
 * rollback on network failure. The server treats unstar-of-non-starred as 204
 * (idempotent) so we don't need to special-case it client-side.
 */
export function useUnstarJob() {
  const qc = useQueryClient()
  return useMutation<void, ApiError, { jobId: number }, { previous: JobDto[] | undefined }>({
    mutationFn: ({ jobId }) =>
      apiFetch<void>(`/api/v1/me/starred-jobs/${jobId}`, { method: 'DELETE' }),
    onMutate: async ({ jobId }) => {
      await qc.cancelQueries({ queryKey: STARRED_JOBS_QUERY_KEY })
      const previous = qc.getQueryData<JobDto[]>(STARRED_JOBS_QUERY_KEY)
      if (previous) {
        qc.setQueryData<JobDto[]>(
          STARRED_JOBS_QUERY_KEY,
          previous.filter((j) => j.id !== jobId),
        )
      }
      return { previous }
    },
    onError: (_err, _vars, ctx) => {
      if (ctx?.previous !== undefined) {
        qc.setQueryData<JobDto[]>(STARRED_JOBS_QUERY_KEY, ctx.previous)
      }
    },
    onSettled: () => {
      void qc.invalidateQueries({ queryKey: STARRED_JOBS_QUERY_KEY })
    },
  })
}
