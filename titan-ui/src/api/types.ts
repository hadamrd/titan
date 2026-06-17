// ---------------------------------------------------------------------------
// Wire types matching titan-server DTO shapes exactly.
// Source of truth: titan-server/src/main/java/io/adaptiq/titan/api/dto/
// ---------------------------------------------------------------------------

/** Terminal statuses that indicate a build will not change further. */
export type BuildStatus = 'SUCCESS' | 'FAILED' | 'RUNNING' | 'QUEUED' | 'ABORTED' | 'UNSTABLE'

export const TERMINAL_STATUSES: ReadonlySet<BuildStatus> = new Set([
  'SUCCESS',
  'FAILED',
  'ABORTED',
  'UNSTABLE',
])

export function isTerminal(status: string): boolean {
  return TERMINAL_STATUSES.has(status as BuildStatus)
}

// ── JobDto ──────────────────────────────────────────────────────────────────

/**
 * Compact summary of a job's most-recent build — embedded in {@link JobDto.lastBuild}
 * so the {@code /jobs} fleet-health list can render a per-row status pill, duration,
 * and finish time without a second fetch (issue #529). Mirror of {@code
 * LastBuildSummaryDto} on the server.
 *
 * <p>{@code finishedAt} / {@code durationMs} are nullable because a {@code RUNNING}
 * or {@code QUEUED} build has neither yet. {@code status} is always present (the
 * build row exists).
 */
export interface LastBuildSummaryDto {
  id: number
  buildNumber: number
  status: string
  durationMs: number | null
  finishedAt: string | null // ISO-8601
}

export interface JobDto {
  id: number
  fullName: string
  displayName: string
  folderPath: string | null
  enabled: boolean
  createdAt: string // ISO-8601
  updatedAt: string // ISO-8601
  /** Raw pipeline YAML — added by PR #459. Optional for back-compat. */
  pipelineScript?: string
  /**
   * Snapshot of the most-recent build for this job (issue #529). {@code null} on
   * a job that has never run. Stripped by server-side {@code JsonInclude.NON_NULL}
   * so the field may be {@code undefined} on older servers — treat both as "no
   * build".
   */
  lastBuild?: LastBuildSummaryDto | null
  /**
   * Repository URL extracted from {@code configJson.scm.url} when a GitHub
   * trigger is configured (added by PR #610). {@code undefined} on jobs without
   * a configured SCM. UI uses this to render the "Open in GitHub" action.
   */
  repoUrl?: string
  /**
   * GitHub App provenance, populated when the job was created via the
   * /integrations/github flow (design/63, epic #831). Server-side mirror of
   * the relevant {@code configJson.scm} fields surfaced as a typed bag so the
   * UI doesn't reach into raw JSON. Absent on jobs created through any other
   * onboarding path — treat {@code undefined} as "not a GitHub App job".
   */
  githubApp?: GithubAppJobMetaDto
}

/**
 * Wire shape of the GitHub App provenance bag on {@link JobDto.githubApp}.
 * Mirror of {@code GithubAppJobMetaDto.java} on the server (child A — #832).
 */
export interface GithubAppJobMetaDto {
  /** Our row id in titan_github_installations — NOT GitHub's numeric id. */
  installationId: number
  /** Full repo name, e.g. {@code "acme-co/web"}. */
  repoFullName: string
  /** Relative path, e.g. {@code ".titan/pipelines/build.yml"}. */
  filename: string
}

export interface JobsPage {
  items: JobDto[]
  total: number
  offset: number
  limit: number
}

// ── BuildDto ────────────────────────────────────────────────────────────────

/**
 * Webhook-derived trigger metadata (issue #589) — mirror of {@code
 * BuildDto.TriggerMetaDto} on the server. Populated only on builds born
 * from a webhook receiver (github push today); manual / dogfood / replay
 * builds carry {@code null} or have the field stripped by JsonInclude.
 *
 * <p>All three fields are individually nullable — a force-push payload
 * may omit {@code head_commit}, so the server emits {@code commitSha: null}
 * rather than fabricating one. The UI must render present fields and
 * omit absent ones (no literal "undefined" / "null" strings).
 */
export interface TriggerMetaDto {
  branch?: string | null
  commitSha?: string | null
  actor?: string | null
  /**
   * GitHub-App provenance (issue #892). `"github-app"` when the build was
   * kicked off by the GitHub integration; absent/null for manual / scheduled
   * / generic-webhook builds. The build-detail provenance badge renders only
   * when this is `"github-app"` AND both {@link repoFullName} and
   * {@link commitUrl} are present.
   */
  provenance?: string | null
  /** `"{owner}/{name}"` of the linked repo, e.g. `"hadamrd/titan"`. */
  repoFullName?: string | null
  /** Deep link `https://github.com/{owner}/{name}/commit/{sha}`. */
  commitUrl?: string | null
}

export interface BuildDto {
  id: number
  jobId: number
  buildNumber: number
  status: string
  triggeredBy: string | null
  triggerType: string | null
  queuedAt: string | null // ISO-8601
  startedAt: string | null // ISO-8601
  finishedAt: string | null // ISO-8601
  durationMs: number | null
  errorMessage: string | null
  failureSummary: string | null
  /**
   * Diagnosed root cause of a FAILED build (issue #1105) — one of the closed set
   * `test_failure | compile_error | oom | timeout | network | rate_limit | unknown`.
   * Null/undefined on success or before the async classifier runs (server strips via
   * `JsonInclude.NON_NULL`); the build-detail page renders a colored cause badge when present.
   */
  failureCause?: string | null
  /**
   * The matching log snippet that drove {@link failureCause} (issue #1105), shown in the
   * "why this was classified" tooltip. Null for an `unknown` verdict.
   */
  failureCauseDetail?: string | null
  /**
   * Raw pipeline YAML the worker actually ran for this build (issue #533).
   * Nullable while backend persistence lands — UI shows a graceful "not
   * captured for this build (legacy)" state when absent / undefined. The
   * server may strip this via {@code JsonInclude.NON_NULL}, so treat
   * undefined identically to null.
   */
  pipelineScript?: string | null
  /**
   * Webhook-derived trigger facts (issue #589). Present on github-webhook
   * builds; absent (undefined via JsonInclude.NON_NULL) on manual / dogfood
   * / replay builds. Treat undefined identically to null at render time.
   */
  triggerMeta?: TriggerMetaDto | null
  /**
   * The parameters the build actually ran with (issue #1266) — typed projection
   * of {@code titan.builds.parameters_json}. Present ONLY on the build-detail
   * payload ({@code GET /api/v1/builds/{id}}); the builds-list endpoint never
   * ships it. Absent (undefined via server-side {@code JsonInclude.NON_NULL}) on
   * builds that ran with no params, on legacy/blank blobs, and on a malformed
   * blob the server degraded to omitted. Treat undefined/null/empty identically
   * as "no parameters" — the UI hides the Parameters section in all three cases.
   */
  parametersUsed?: Record<string, string> | null
  /**
   * Human-friendly build name set by the {@code setBuildName:} pipeline step
   * (#762 backend / #768 UI surfacing). Null/undefined when the pipeline never
   * invoked the step — the server strips the field via {@code JsonInclude.NON_NULL}
   * so legacy clients see no shape change. Treat empty-string identically to
   * absent: callers MUST use {@link buildDisplayLabel} for the canonical
   * `displayName ?? '#' + buildNumber` fallback chain.
   */
  displayName?: string | null
}

/**
 * Canonical fallback label for a build: prefer the human-friendly
 * {@code displayName} when present and non-blank; otherwise fall back to
 * {@code '#' + buildNumber}. Single source of truth for the chain documented
 * in #768 — header, /builds list and Cmd+K all delegate here so renaming a
 * build re-skins every surface in lockstep.
 */
export function buildDisplayLabel(b: {
  displayName?: string | null
  buildNumber: number
}): string {
  const name = b.displayName
  if (typeof name === 'string' && name.trim().length > 0) {
    return name
  }
  return `#${b.buildNumber}`
}

export interface BuildsPage {
  items: BuildDto[]
  total: number
  offset: number
  limit: number
}

// ── FlowNodeDto ─────────────────────────────────────────────────────────────

export interface FlowNodeDto {
  buildId: number
  nodeId: string
  parentIds: string | null
  nodeType: string
  displayName: string | null
  stepDescriptor: string | null
  status: string
  agentLabel: string | null
  startedAt: string | null // ISO-8601
  completedAt: string | null // ISO-8601
  durationMs: number | null
  attempt: number
  maxAttempts: number
  failureCategory: string | null
  failureReason: string | null
  logTaskId: string | null // UUID
  /**
   * Key/value bag a step published via {@code setOutput(k, v)} — design/29 §6, surfaced
   * by issue #782. Server emits {@code outputs: {}} when a node has none; older servers
   * may omit the field entirely (JsonInclude.NON_NULL on an empty Map). UI treats both
   * absent and empty as "(no outputs)".
   */
  outputs?: Record<string, string>
}

// ── Trigger / Cancel ────────────────────────────────────────────────────────

export interface TriggerBuildRequest {
  parametersJson?: string | null
  triggeredBy?: string | null
  /**
   * Typed parameter overrides — mirror of {@code TriggerBuildRequest.parameters}
   * on the server (closes #779 / follow-up to #774). Key = declared parameter
   * name; value = string form (the server coerces against the declared type).
   * Wins over {@link parametersJson} when both are present. Omit entirely when
   * the user kept every declared default — the server then applies its own
   * defaults rather than wire-trailing an empty map.
   */
  parameters?: Record<string, string> | null
}

// ── Pipeline parameters (#779 — UI half of #774) ─────────────────────────────
// Wire format from titan-server/src/main/java/io/adaptiq/titan/api/dto/PipelineParameterDto.java.
//
// `type` is a closed discriminator string — the modal switches on it to pick
// the input widget. Adding a new parameter kind server-side MUST extend this
// union or tsc fails.

export type ParameterType = 'string' | 'boolean' | 'number' | 'choice'

export const PARAMETER_TYPES: readonly ParameterType[] = [
  'string',
  'boolean',
  'number',
  'choice',
] as const

/**
 * One declared pipeline parameter — wire shape of {@code GET
 * /api/v1/jobs/{jobId}/parameters}. {@link defaultValue} is the raw primitive
 * from the YAML default (string stays string, boolean stays boolean) — the UI
 * normalises to string at edit time and parses back per type on submit.
 *
 * <p>{@link choices} is ALWAYS an array (server emits {@code []} for non-choice
 * types) so callers can safely {@code .map} without a null check.
 */
export interface PipelineParameterDto {
  name: string
  type: ParameterType
  defaultValue: unknown
  description?: string | null
  required: boolean
  choices: string[]
}

export interface TriggerBuildResponse {
  buildId: number
  buildNumber: number
  status: string
}

// ── RFC 7807 problem+json ───────────────────────────────────────────────────

export interface ProblemJson {
  type: string
  title: string
  status: number
  detail: string | null
  instance: string | null
}

/** Typed API error that carries the RFC 7807 problem body. */
export class ApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly problem: ProblemJson,
  ) {
    super(problem.detail ?? problem.title)
    this.name = 'ApiError'
  }
}

// ── RetryStageOutcome (#748 — UI half of #744) ───────────────────────────────
// Wire format from titan-server/src/main/java/io/adaptiq/titan/build/RetryStageOutcome.java.
// Discriminated-union typed: the `type` tag is the Jackson @JsonTypeInfo property.
// Today only the "applied" variant ships; the union is open-ended so a future
// dry-run / rejected variant lands without a wire-shape bump. Always switch on
// `type` — never sniff field presence.

export interface RetryStageOutcomeApplied {
  type: 'applied'
  buildId: number
  stageId: string
  /** Flow-node ids reset to QUEUED (the stage + all DAG descendants). */
  resetNodeIds: string[]
  /** Id of the enqueued ORCHESTRATE/ADVANCE task. */
  taskId: number
}

export type RetryStageOutcome = RetryStageOutcomeApplied

// ── GateDto (PR #299) ────────────────────────────────────────────────────────
// Wire format from titan-server/src/main/java/io/adaptiq/titan/api/dto/GateDto.java.
// Note: the GET endpoint returns a bare `List<GateDto>` — NOT `{ items }`.

export interface GateDto {
  nodeId: string
  name: string
  approvers: string[]
  state: string // "RUNNING" while awaiting, terminal otherwise (endpoint only emits RUNNING)
  awaitingSince?: string // ISO-8601, optional (JsonInclude.NON_NULL)
}

export interface GateDecisionResponse {
  applied: boolean
  status: string
  message: string
}

// ── ArtifactDto / ArtifactsPage (PR #300) ────────────────────────────────────
// Wire format from titan-server/src/main/java/io/adaptiq/titan/api/dto/ArtifactDto.java.

export interface ArtifactDto {
  id: number
  name: string
  sizeBytes: number
  sha256: string
  contentType?: string // omitted via JsonInclude.NON_NULL until schema gains the column
  uploadedAt: string // ISO-8601
  downloadUrl: string // backend-relative — caller must prefix BASE_URL
}

export interface ArtifactsPage {
  items: ArtifactDto[]
  total: number
}

// ── Test results (issue #296) ───────────────────────────────────────────────

/**
 * Row-shape mirror of {@code titan-server} {@code TestRowDto}.
 *
 * <p>{@code failureMessage} is stripped from the wire envelope by Jackson
 * {@code @JsonInclude(NON_NULL)} for {@code PASSED}/{@code SKIPPED} rows, so
 * the property may be absent (not just {@code null}) for non-failures.
 */
export interface TestRowDto {
  id: number
  suite: string
  className: string
  name: string
  status: TestStatus
  durationMs: number
  failureMessage?: string | null
}

export type TestStatus = 'PASSED' | 'FAILED' | 'SKIPPED'

/** Mirror of {@code TestResultDao.TestSummary} (passed/failed/skipped over the whole build). */
export interface TestSummary {
  passed: number
  failed: number
  skipped: number
}

/** Mirror of {@code TestResultsPage} envelope. */
export interface TestResultsPage {
  items: TestRowDto[]
  total: number
  summary: TestSummary
}

// ── Workers (issue #313 — wire Workers grid) ────────────────────────────────
// Wire format from titan-server/src/main/java/io/adaptiq/titan/api/dto/WorkerDto.java.

export interface WorkerDto {
  id: string
  name: string
  state: string // ONLINE | DRAINING | OFFLINE | BUSY
  pool: string
  labels: string[]
  currentTasks: number
  maxConcurrent: number
  cpuPct?: number | null
  memPct?: number | null
  diskPct?: number | null
  lastSeenAt?: string | null // ISO-8601
  registeredAt?: string | null // ISO-8601
}

export interface WorkersPage {
  items: WorkerDto[]
  total: number
  offset: number
  limit: number
}

/**
 * Drain/undrain response — mirror of {@code DrainResponseDto} on the server.
 * {@code state} is the post-update agent status (DRAINING, ONLINE, or — if the
 * worker was OFFLINE and not eligible — OFFLINE).
 */
export interface DrainResponseDto {
  workerId: string
  state: string
  inflightBuilds: number
  estimatedSecondsRemaining: number
}

// ── Agent events (issue #714 — worker join/leave timeline) ──────────────────
// Wire format from titan-server/src/main/java/io/adaptiq/titan/api/dto/AgentEventDto.java.

/**
 * One worker lifecycle event surfaced by {@code GET /api/v1/agents/events}.
 *
 * <p>{@code type} is the discriminator — the Home activity timeline switches on
 * it. {@code HEARTBEAT_LOST} is reserved server-side but not emitted in v1 (no
 * stale-agent reaper yet); listed in the union so future widening lands as a
 * pure additive change.
 */
export interface AgentEventDto {
  id: number
  agentId: string
  agentName: string
  type: 'JOINED' | 'LEFT' | 'HEARTBEAT_LOST'
  occurredAt: string // ISO-8601
}

// ── Queue (PR #306 — wire Queue page) ───────────────────────────────────────
// Wire format from titan-server/src/main/java/io/adaptiq/titan/api/dto/QueueEntryDto.java.

export interface QueueEntryDto {
  taskId: number
  buildId?: number | null
  jobId?: number | null
  jobName?: string | null
  queuedAt: string // ISO-8601
  waitingMs: number
  priority: number
  requestedLabels: string
}

export interface QueuePage {
  items: QueueEntryDto[]
  total: number
  offset: number
  limit: number
}

// ── Recent activity (issue #523 — wire /queue empty state) ─────────────────
// Wire format from titan-server/src/main/java/io/adaptiq/titan/api/dto/RecentTaskDto.java.
//
// Sourced from titan.task_archive (the live task_queue only carries claimable +
// in-flight rows; terminal rows are swept to the archive within seconds). The
// envelope is a bare List<RecentTaskDto> — NOT a paged { items, total } object.

export interface RecentTaskDto {
  taskId: number
  buildId?: number | null
  jobId?: number | null
  jobName?: string | null
  nodeId?: string | null
  type: string
  status: string
  queuedAt: string // ISO-8601
  completedAt?: string | null // ISO-8601
  priority: number
  requestedLabels: string
}

// ── Stats (PR #362 — closes #346) ───────────────────────────────────────────
// Wire format from titan-server/src/main/java/io/adaptiq/titan/api/dto/StatsDto.java.

/**
 * Overview KPI aggregate — GET /api/v1/stats.
 *
 * <ul>
 *   <li>{@code buildsToday} — count of builds queued since start-of-UTC-day.
 *   <li>{@code successRate} — terminal-SUCCESS / terminal-any over the last 24h, in [0.0, 1.0].
 *       0.0 when no terminal builds in the window.
 *   <li>{@code medianDurationMs} — median {@code finished_at - started_at} (ms) over terminal
 *       builds in the last 24h. 0 when no terminal builds in the window.
 * </ul>
 */
export interface StatsDto {
  buildsToday: number
  successRate: number
  medianDurationMs: number
}

// ── Top failing jobs (T67 — closes #769) ─────────────────────────────────────
// Wire format from titan-server/src/main/java/io/adaptiq/titan/api/dto/TopFailingJobDto.java.

/**
 * One row on the Home "Top failing jobs" widget — GET /api/v1/jobs/top-failing.
 *
 * Jobs are ranked by {@code failureRate} (failedBuilds / totalBuilds) over a
 * sliding time window (24h default, 7d max). Only jobs with totalBuilds ≥ 3
 * and at least one FAILED build appear; the server filters single-build noise.
 *
 * {@code lastFailedBuildId} is nullable — the row can qualify on aggregate
 * counts even if the latest FAILED build was pruned; the UI omits the link in
 * that case.
 */
export interface TopFailingJobDto {
  jobId: number
  jobName: string
  totalBuilds: number
  failedBuilds: number
  failureRate: number // 0..1
  lastFailedBuildId: number | null
}

// ── Per-job stats (T68 — closes #775) ────────────────────────────────────────
// Wire format from titan-server/src/main/java/io/adaptiq/titan/api/dto/JobStatsDto.java.

/**
 * Closed discriminator of {@link JobStatsDto.window} — the server clamps to this
 * set and returns 400 for anything else. Mirror it client-side so a typo in a
 * call site is a tsc error, not a 400 round-trip.
 */
export type JobStatsWindow = '7d' | '30d' | '90d'

/**
 * One bar of the daily-failure sparkline. {@code day} is a calendar date string
 * (ISO {@code YYYY-MM-DD}, no time, no zone) — the server bucketed in UTC.
 */
export interface JobStatsDailyBucketDto {
  day: string
  totalBuilds: number
  failedBuilds: number
}

/**
 * Wire response for {@code GET /api/v1/jobs/{id}/stats?window=…} — per-job
 * analytics over a sliding window.
 *
 * <p>{@code p50DurationMs} / {@code p95DurationMs} are nullable on purpose: the
 * server returns {@code null} (NOT zero) when there are no completed builds in
 * the window. Treat the two states as distinct in the UI — "no data" must not
 * render as "0 ms" (which would lie about the engine's speed).
 *
 * <p>{@code dailyBuckets} always has exactly as many entries as the window has
 * days (7 / 30 / 90), oldest-first; days with no builds carry zero counts so
 * the sparkline's x-axis is dense without UI-side date scaffolding.
 */
export interface JobStatsDto {
  totalBuilds: number
  failedBuilds: number
  failureRate: number // 0..1
  p50DurationMs: number | null
  p95DurationMs: number | null
  dailyBuckets: JobStatsDailyBucketDto[]
  window: JobStatsWindow
}

// ── Per-job stage timing percentiles (closes #1095) ─────────────────────────
// Wire format from titan-server/.../api/dto/StageTimingsDto.java.

/**
 * One historical sample of a stage's duration. Carries the {@code buildId} so
 * the histogram bar can be clickable — closes the "click on a bar → navigate
 * to the build-detail of that build" requirement on #1095.
 */
export interface StageSampleDto {
  buildId: number
  buildNumber: number
  durationMs: number
  status: string
}

/**
 * One stage's duration distribution + per-build samples over the last N
 * finished builds of a job. {@code p50Ms / p95Ms / p99Ms} are computed by
 * Postgres' {@code percentile_cont} (linear-interpolation; matches NumPy's
 * default {@code numpy.percentile(method='linear')}). They are {@code | null}
 * to stay honest if a future query path emits zero-sample groups; in practice
 * the server omits those rows.
 *
 * <p>{@code samples} is ordered oldest→newest by build_number, so a bar chart
 * reads left-to-right newest-build-on-the-right (the SRE convention).
 */
export interface StageTimingDto {
  stageName: string
  sampleCount: number
  p50Ms: number | null
  p95Ms: number | null
  p99Ms: number | null
  minMs: number | null
  maxMs: number | null
  samples: StageSampleDto[]
}

/**
 * Wire response for {@code GET /api/v1/jobs/{id}/stage-timings?n=30}. A fresh
 * job (no finished builds) returns {@code buildsConsidered=0} and an empty
 * {@code stages[]} — the UI renders the "not enough history yet" empty state
 * for that case rather than a misleading "all zeros" chart.
 */
export interface StageTimingsDto {
  n: number
  buildsConsidered: number
  stages: StageTimingDto[]
}

// ── Activity feed (PR #370 — closes #304) ───────────────────────────────────
// Wire format from titan-server/src/main/java/io/adaptiq/titan/api/dto/ActivityItemDto.java.

/**
 * One row on the Overview activity feed. {@code type} is a discriminator —
 * v1 only emits {@code "build.terminal"} (terminal-state builds from the
 * {@code titan.builds} table). Future event sources will add new {@code type}
 * values without changing the JSON shape.
 */
export interface ActivityItemDto {
  id: string
  type: string // "build.terminal" in v1
  ts: string // ISO-8601
  jobName: string
  buildId: number
  status: string // SUCCESS | FAILED | ABORTED | UNSTABLE
  durationMs: number
}

/**
 * Paginated wire shape for GET /api/v1/activity. {@code nextCursor} is an
 * opaque base64 string to be passed back as {@code ?before=...} on the next
 * page; {@code null} when this is the last page.
 */
export interface ActivityPage {
  items: ActivityItemDto[]
  nextCursor: string | null
}

// ── Admin queue (PR #373 — closes #347) ─────────────────────────────────────
// Wire format from titan-server/src/main/java/io/adaptiq/titan/api/dto/.

export interface QueueDrainResponse {
  drained: number
}

export interface QueueReorderRequest {
  taskIds: number[]
}

// ── Personal access tokens (#434 — GET/POST/DELETE /api/v1/me/tokens) ───────

/**
 * One personal access token, list-view shape. The server NEVER returns the
 * plaintext token here — the `prefix` (e.g. "titanpat_a3f4") is the only
 * disambiguator. The plaintext lives in {@link PersonalAccessTokenCreatedDto}
 * and only for the lifetime of a single POST response.
 */
export interface PersonalAccessTokenDto {
  id: number
  name: string
  prefix: string
  createdAt: string
  lastUsedAt: string | null
  revokedAt: string | null
  /**
   * Per-PAT scope restriction (closes #500). `null` = legacy token that inherits
   * the creator's full role set. A non-null array narrows the resolved identity
   * to (creator roles) ∩ scopes — strictly narrower, never wider.
   */
  scopes: PatScope[] | null
  /**
   * Per-PAT job-name glob restriction (closes #1082). `null` = no path restriction
   * (the token operates across every job inside its role scope). A non-null string
   * (e.g. `"acme/web-*"` or `"org/my-app/**"`) narrows the token to jobs whose
   * `full_name` matches the glob; mismatched requests get 403 + a PAT_SCOPE_DENIED
   * audit row.
   */
  jobPattern: string | null
}

/**
 * Response body for POST /api/v1/me/tokens. The {@code token} field is the
 * plaintext secret — the UI MUST surface it exactly once and never persist it.
 *
 * <p>{@code scopes} echoes back the server-side validated, normalised scope list
 * — useful for the post-creation confirmation panel. `null` = legacy "inherit
 * all".
 *
 * <p>{@code jobPattern} (added by #1082) echoes back the validated job-name glob
 * so the post-creation reveal panel can confirm exactly what was minted.
 */
export interface PersonalAccessTokenCreatedDto {
  id: number
  name: string
  prefix: string
  token: string
  createdAt: string
  scopes: PatScope[] | null
  jobPattern: string | null
}

/**
 * Grantable PAT scopes (closes #500). String-enum on the wire, sealed union
 * here: adding a new scope server-side MUST extend this union or tsc fails.
 * Mirrors {@code io.adaptiq.titan.auth.PatScopes.ALLOWED} (Roles minus ADMIN —
 * admin power is never granted via PAT).
 */
export type PatScope =
  | 'READ_JOB'
  | 'TRIGGER_BUILD'
  | 'EDIT_PIPELINE'
  | 'APPROVE_GATE'

export const PAT_SCOPES: readonly PatScope[] = [
  'READ_JOB',
  'TRIGGER_BUILD',
  'EDIT_PIPELINE',
  'APPROVE_GATE',
] as const

// ── Audit log (#478 / #517) ──────────────────────────────────────────────────
//
// Mirrors io.adaptiq.titan.audit.AuditAction + AuditTargetType. The string
// codes ARE the discriminator; AuditEventDto.action is a closed union so the
// Details column can branch per-action without sniffing the URL or detailsJson
// keys. Adding a new action server-side MUST extend this union or tsc fails.

export type AuditAction =
  | 'JOB_CREATE'
  | 'JOB_UPDATE'
  | 'BUILD_TRIGGER'
  | 'BUILD_ABORT'
  | 'PAT_CREATE'
  | 'PAT_REVOKE'
  | 'PAT_SCOPE_DENIED'
  | 'TRANSITION_CAP_WARN'
  | 'TRANSITION_CAP_HALT'

export type AuditTargetType = 'JOB' | 'BUILD' | 'PAT'

export const AUDIT_ACTIONS: readonly AuditAction[] = [
  'JOB_CREATE',
  'JOB_UPDATE',
  'BUILD_TRIGGER',
  'BUILD_ABORT',
  'PAT_CREATE',
  'PAT_REVOKE',
  'PAT_SCOPE_DENIED',
  'TRANSITION_CAP_WARN',
  'TRANSITION_CAP_HALT',
] as const

export const AUDIT_TARGET_TYPES: readonly AuditTargetType[] = [
  'JOB',
  'BUILD',
  'PAT',
] as const

export interface AuditEventDto {
  id: number
  occurredAt: string // ISO-8601
  actor: string
  action: AuditAction
  targetType: AuditTargetType
  targetId: string | null
  /** Opaque JSON string; the page parses it per-action for display. */
  detailsJson: string | null
}

export interface AuditPage {
  items: AuditEventDto[]
  total: number
  offset: number
  limit: number
}

// ── RBAC audit trail (#1167) ─────────────────────────────────────────────────
//
// Wire format from titan-server/.../api/dto/RbacAuditEventDto.java +
// RbacAuditPage.java. Backed by GET /api/v1/rbac-audit (READ_AUDIT/ADMIN).
//
// This is the TYPED allow/deny trail over titan.rbac_audit — distinct from the
// generic AuditEventDto above (where the RBAC verdict is buried in detailsJson).
// `verdict` is a CLOSED union mirroring io.adaptiq.titan.auth.RbacDecision: the
// Verdict column branches on it, so adding a value server-side MUST extend this
// union or tsc fails (no silent generic render).

export type RbacVerdict = 'ALLOW' | 'DENY'

export const RBAC_VERDICTS: readonly RbacVerdict[] = ['ALLOW', 'DENY'] as const

export function isRbacVerdict(s: unknown): s is RbacVerdict {
  return s === 'ALLOW' || s === 'DENY'
}

export interface RbacAuditEventDto {
  id: number
  occurredAt: string // ISO-8601
  /** The user id (Keycloak subject). Null for an anonymous/unauthenticated deny. */
  actor: string | null
  /** The gated endpoint, e.g. "JobsApi.create". */
  endpoint: string
  /** Scope kind — ORG or REPO (mirrors io.adaptiq.titan.auth.ScopeKind). */
  scopeKind: string
  scopeId: string
  requiredRole: string
  /** Caller's resolved role; null when the resolver returned no role. */
  effectiveRole: string | null
  verdict: RbacVerdict
}

export interface RbacAuditPage {
  items: RbacAuditEventDto[]
  total: number
  offset: number
  limit: number
}

// ── Admin · Users (#603 / #607) ──────────────────────────────────────────────
// Wire format from titan-server/src/main/java/io/adaptiq/titan/api/dto/UserDto.java.
//
// Backed by GET /api/v1/admin/users — ADMIN-only. Until #608 lands the Keycloak
// service-account, the endpoint emits a STUB list (alice + bob) so the UI can
// land independently. The wire shape is stable across stub and live.
//
// `realmRoles` is a flat string[] because Keycloak emits role names without
// category information; the UI partitions them into category buckets (admin /
// titan / system / other) at render time via {@link roleCategory}.

// One scoped Titan role assignment — wire format from
// titan-server/.../api/dto/RoleAssignmentDto.java (#1236). `role` is a TitanRole
// name (ADMIN/MAINTAINER/DEVELOPER/VIEWER); `scopeKind` is ORG | REPO.
export interface RoleAssignmentDto {
  role: string
  scopeKind: string
  scopeId: string
}

export interface UserDto {
  username: string
  email: string | null
  displayName: string | null
  realmRoles: string[]
  // Scoped Titan RBAC assignments from titan.rbac_user_role (#1236). Always
  // present (empty array when the user has no scoped grants).
  titanRoles: RoleAssignmentDto[]
}

// ── Approvals (#715 / #720 backend, #721 UI) ────────────────────────────────
// Wire format from titan-server/src/main/java/io/adaptiq/titan/api/dto/ApprovalDto.java.
//
// Closed status union locked to the V26 schema CHECK constraint. Adding a new
// terminal state server-side MUST extend this union or tsc fails. JsonInclude
// NON_NULL strips decidedBy/decidedAt on PENDING rows — the UI treats both
// missing and null as "not yet decided".

export type ApprovalStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'TIMED_OUT'

export const APPROVAL_STATUSES: readonly ApprovalStatus[] = [
  'PENDING',
  'APPROVED',
  'REJECTED',
  'TIMED_OUT',
] as const

export interface ApprovalDto {
  id: number
  buildId: number
  flowNodeId: string
  prompt: string
  approvers: string[]
  status: ApprovalStatus
  decidedBy?: string | null
  decidedAt?: string | null // ISO-8601
  expiresAt: string // ISO-8601
  createdAt: string // ISO-8601
}

/** Mirror of {@code ApprovalsApi.ApprovalPage}. */
export interface ApprovalPage {
  items: ApprovalDto[]
  total: number
  offset: number
  limit: number
}

/** Mirror of {@code ApprovalsApi.DecisionResponse}. */
export interface ApprovalDecisionResponse {
  applied: boolean
  status: string
  message: string
}

/**
 * Stable reason codes returned per id by the bulk endpoints (#734). Closed
 * discriminated union so the UI's switch over outcomes is exhaustive —
 * adding a new reason server-side MUST extend this union or tsc fails.
 */
export type BulkApprovalReason = 'already_decided' | 'forbidden' | 'not_found'

/** Mirror of {@code ApprovalsApi.BulkOutcome} (#734). */
export interface BulkApprovalOutcome {
  id: number
  applied: boolean
  /** Terminal status when known (always set on applied=true; set on already_decided). */
  status?: string | null
  /** Stable code; null/undefined on success. */
  reason?: BulkApprovalReason | null
}

/** Mirror of {@code ApprovalsApi.BulkApprovalResponse} (#734). */
export interface BulkApprovalResponse {
  outcomes: BulkApprovalOutcome[]
}

/** Mirror of {@code ApprovalsApi.BulkApprovalRequest} (#734). */
export interface BulkApprovalRequest {
  ids: number[]
}

// ── Legacy types kept for test-compat (mock.ts is removed; tests use mswHandlers) ──

/** @deprecated Use BuildDto. Kept only so existing test snapshots compile. */
export type BuildStatus_Legacy = BuildStatus

// ── System info (#676 — engine health dashboard) ─────────────────────────────
// Wire format from titan-server/src/main/java/io/adaptiq/titan/api/dto/SystemInfoDto.java.
// Closed two-value discriminator so the /system page branches exhaustively.

export type DbStatus = 'UP' | 'DOWN'

export interface SystemInfoDto {
  version: string
  buildSha: string
  dbStatus: DbStatus
  queueDepth: number
  workersOnline: number
  serverTime: string // ISO-8601
}

// ── JobTriggerDto (issue #725) ──────────────────────────────────────────────
//
// Wire shape of GET /api/v1/jobs/{id}/triggers — runtime state of every
// trigger declared on the job (joined from titan.jobs.config_json with the
// per-trigger runtime row in titan.job_triggers).
//
// `type` is a closed discriminated-union string — callers `switch (t.type)`
// and never sniff URL or field shape. The server filters any forward-compat
// trigger type out of the response, so this enum is the complete wire union.

export type JobTriggerType = 'cron' | 'github' | 'manual'

export interface JobTriggerDto {
  id: string
  type: JobTriggerType
  /** Cron spec for type=cron; absent for github/manual. */
  expression?: string | null
  /** ISO-8601 instant — absent if the trigger has never fired. */
  lastFiredAt?: string | null
  /** Most-recent evaluation error, if any. Absent in the happy path. */
  lastError?: string | null
  /** ISO-8601 instant — next cron occurrence; absent for non-cron types. */
  nextFireAt?: string | null
}
