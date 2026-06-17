import { useState } from 'react'
import { createFileRoute, Link, useNavigate } from '@tanstack/react-router'
import { useJob, useJobBuilds, useTriggerBuild } from '@/api/hooks'
import { useParamAwareTrigger } from '@/lib/useParamAwareTrigger'
import { Button } from '@/components/ui/Button'
import { StarButton } from '@/components/StarButton'
import { StatusBadge } from '@/components/StatusBadge'
import { TriggerChips } from '@/components/TriggerChips'
import { TriggerEditor } from '@/components/TriggerEditor'
import { TriggerParamsModal } from '@/components/TriggerParamsModal'
import { TriggerPreviewModal } from '@/components/TriggerPreviewModal'
import { CronTriggersPanel } from '@/components/CronTriggersPanel'
import { JobStatsPanel } from '@/components/JobStatsPanel'
import { JobStageTimingsPanel } from '@/components/JobStageTimingsPanel'
import { PageContainer, PageHeader } from '@/components/ui/Page'
import { Skeleton } from '@/components/ui/Skeleton'
import { ApiError, TERMINAL_STATUSES, type BuildDto, type BuildStatus } from '@/api/types'
import { formatBuildDuration, formatDate, formatDuration } from '@/lib/format'
import { useDocumentTitle } from '@/lib/useDocumentTitle'
import { useTickWhileActive } from '@/lib/useTickWhileActive'

export const Route = createFileRoute('/pipelines/$pipelineId')({
  component: PipelineDetailPage,
})

function errorMessage(err: unknown): string {
  if (err instanceof ApiError) {
    if (err.status === 404) return 'Pipeline not found.'
    if (err.status >= 500) return 'Server error — please retry.'
    return err.problem.detail ?? err.message
  }
  return 'Failed to load pipeline.'
}

interface JobKpis {
  total: number
  successRate: number | null // 0..1, null if no completed builds
  avgDurationMs: number | null
  lastTriggeredAt: string | null
  lastStatus: string | null
}

function computeKpis(builds: BuildDto[]): JobKpis {
  if (builds.length === 0) {
    return {
      total: 0,
      successRate: null,
      avgDurationMs: null,
      lastTriggeredAt: null,
      lastStatus: null,
    }
  }
  // Sort newest first by queuedAt (fall back to id).
  const sorted = [...builds].sort((a, b) => {
    const ta = a.queuedAt ? Date.parse(a.queuedAt) : 0
    const tb = b.queuedAt ? Date.parse(b.queuedAt) : 0
    if (tb !== ta) return tb - ta
    return b.id - a.id
  })
  const slice = sorted.slice(0, 50)
  const terminal = slice.filter((b) =>
    ['SUCCESS', 'FAILED', 'ABORTED', 'UNSTABLE'].includes(b.status),
  )
  const successCount = terminal.filter((b) => b.status === 'SUCCESS').length
  const durations = slice
    .map((b) => b.durationMs)
    .filter((d): d is number => typeof d === 'number' && Number.isFinite(d) && d >= 0)
  const avg =
    durations.length > 0 ? durations.reduce((a, b) => a + b, 0) / durations.length : null
  return {
    total: builds.length,
    successRate: terminal.length > 0 ? successCount / terminal.length : null,
    avgDurationMs: avg,
    lastTriggeredAt: sorted[0].queuedAt ?? sorted[0].startedAt,
    lastStatus: sorted[0].status,
  }
}

function PipelineDetailPage() {
  const { pipelineId: pipelineIdStr } = Route.useParams()
  // The URL param was renamed jobId → pipelineId (design 66) but the variable
  // bound below stays `jobId` because every downstream hook + DTO field uses
  // the `jobId` name (backend nomenclature unchanged).
  const jobId = Number(pipelineIdStr)

  const { data: job, isLoading: jobLoading, isError: jobError, error: jobErr } = useJob(jobId)
  const { data: buildsPage, isLoading: buildsLoading } = useJobBuilds(jobId)
  const trigger = useTriggerBuild()
  const navigate = useNavigate()
  const [editingTriggers, setEditingTriggers] = useState(false)
  const [previewOpen, setPreviewOpen] = useState(false)
  // Params-modal flow (closes #779): clicking Run lazily fetches the declared
  // parameters; on resolution we open the params modal (≥1 declared) or fall
  // through to the existing preview modal (0). The fetch + resolve logic is
  // shared with the list-row quick-trigger via useParamAwareTrigger so the two
  // Run paths can't drift (the divergence that caused #1208).
  const [paramsModalOpen, setParamsModalOpen] = useState(false)
  const paramTrigger = useParamAwareTrigger(jobId, (params) => {
    if (params.length > 0) setParamsModalOpen(true)
    else setPreviewOpen(true)
  })
  useDocumentTitle(job ? `${job.displayName} — Pipeline` : 'Pipeline')

  // Tick once a second while any of this job's builds is non-terminal so the
  // in-flight row's duration cell live-ticks. Hook MUST run unconditionally —
  // computed from raw items pre-filter so the dependency is stable across the
  // early returns below (CONSTITUTION §6: no hooks-after-return). #927 mirror
  // of /builds (#917).
  const hasInflight = (buildsPage?.items ?? []).some(
    (b) => !TERMINAL_STATUSES.has(b.status as BuildStatus),
  )
  useTickWhileActive(hasInflight)

  if (jobLoading) {
    return (
      <PageContainer width="default">
        <PageHeader title={<Skeleton style={{ height: 22, width: 240 }} />} />
        <div className="metric-grid" style={{ gridTemplateColumns: 'repeat(4, 1fr)' }}>
          {[0, 1, 2, 3].map((i) => (
            <div className="metric" key={i}>
              <Skeleton style={{ height: 11, width: 80 }} />
              <Skeleton style={{ height: 22, width: 100, marginTop: 6 }} />
            </div>
          ))}
        </div>
        <Skeleton style={{ height: 240, width: '100%', display: 'block' }} />
      </PageContainer>
    )
  }
  if (jobError || !job)
    return (
      <PageContainer width="default">
        <PageHeader title="Pipeline" />
        <p style={{ color: 'var(--fail)' }}>{errorMessage(jobErr)}</p>
      </PageContainer>
    )

  const builds = buildsPage?.items ?? []
  const kpis = computeKpis(builds)
  const recent = [...builds]
    .sort((a, b) => {
      const ta = a.queuedAt ? Date.parse(a.queuedAt) : 0
      const tb = b.queuedAt ? Date.parse(b.queuedAt) : 0
      if (tb !== ta) return tb - ta
      return b.id - a.id
    })
    .slice(0, 10)

  // #927 — pin in-flight (non-terminal) runs above terminal History so a
  // single RUNNING build is never buried under 9 SUCCESS rows. Mirror of
  // /builds (#917). Defensive: when in-flight is empty we omit the header
  // + separator entirely (no orphan UI).
  const recentInflight = recent.filter(
    (b) => !TERMINAL_STATUSES.has(b.status as BuildStatus),
  )
  const recentHistory = recent.filter((b) =>
    TERMINAL_STATUSES.has(b.status as BuildStatus),
  )
  const showRecentSplit = recentInflight.length > 0

  // #853 UX-cleanup: the 4-card KPI strip was dropped; successRateLabel
  // (its derived value) was the only remaining consumer and is no longer
  // referenced anywhere on this page.

  return (
    <PageContainer width="default">
      <PageHeader
        title={
          <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
            <Link to="/pipelines" style={{ color: 'var(--fg-muted)' }}>
              Pipelines
            </Link>{' '}
            <span style={{ color: 'var(--fg-faint)' }}>/</span> {job.displayName}
            <StarButton job={job} size={16} testId={`pipeline-detail-${job.id}-star`} />
          </span>
        }
        description={
          kpis.lastTriggeredAt ? (
            <>
              last run {formatDate(kpis.lastTriggeredAt, 'relative')}
              {job.folderPath ? ` · ${job.folderPath}` : ''}
            </>
          ) : undefined
        }
        actions={
          <>
            {kpis.lastStatus ? (
              <StatusBadge status={kpis.lastStatus} />
            ) : (
              <span
                className="filter-chip"
                title={job.enabled ? 'Enabled' : 'Disabled'}
                style={{ cursor: 'default', opacity: job.enabled ? 1 : 0.65 }}
              >
                <span
                  className={`status-dot ${job.enabled ? 'success' : 'cancelled'}`}
                  aria-hidden
                />
                {job.enabled ? 'enabled' : 'disabled'}
              </span>
            )}
            <Button
              size="sm"
              disabled={!job.enabled || trigger.isPending || paramTrigger.isResolving}
              onClick={() => paramTrigger.start()}
              data-testid="pipeline-detail-trigger-btn"
            >
              {trigger.isPending
                ? 'Running…'
                : paramTrigger.isResolving
                  ? 'Loading…'
                  : 'Run pipeline'}
            </Button>
          </>
        }
      />

      <div data-testid="pipeline-detail-meta" style={{ marginBottom: 18 }}>
          {job.githubApp && (
            <div style={{ marginTop: 8 }}>
              <a
                href={`https://github.com/${job.githubApp.repoFullName}`}
                target="_blank"
                rel="noopener noreferrer"
                data-testid="job-github-app-badge"
                style={{
                  display: 'inline-flex',
                  alignItems: 'center',
                  gap: 6,
                  padding: '4px 10px',
                  borderRadius: 999,
                  background: 'var(--bg-2)',
                  border: '1px solid var(--line)',
                  fontSize: 11,
                  fontFamily: 'var(--font-mono)',
                  color: 'var(--fg-muted)',
                  textDecoration: 'none',
                }}
              >
                <svg
                  width="11"
                  height="11"
                  viewBox="0 0 16 16"
                  fill="currentColor"
                  aria-hidden
                >
                  <path d="M8 0C3.58 0 0 3.58 0 8a8 8 0 005.47 7.59c.4.07.55-.17.55-.38 0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82.72 1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82.64-.18 1.32-.27 2-.27.68 0 1.36.09 2 .27 1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.27.82 2.15 0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2 0 .21.15.46.55.38A8.013 8.013 0 0016 8c0-4.42-3.58-8-8-8z" />
                </svg>
                via GitHub App · {job.githubApp.repoFullName}
                <svg
                  width="10"
                  height="10"
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  strokeWidth="2"
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  aria-hidden
                >
                  <path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6" />
                  <polyline points="15 3 21 3 21 9" />
                  <line x1="10" y1="14" x2="21" y2="3" />
                </svg>
              </a>
            </div>
          )}
          <div
            style={{ marginTop: 8, display: 'inline-flex', gap: 8, alignItems: 'center' }}
            data-testid="trigger-chips"
          >
            <TriggerChips pipelineScript={job.pipelineScript} />
            <Button
              size="sm"
              variant="ghost"
              onClick={() => setEditingTriggers(true)}
              data-testid="edit-triggers-btn"
            >
              Edit triggers
            </Button>
          </div>
      </div>

      {/* Trigger result feedback */}
      {(trigger.isSuccess || trigger.isError) && (
        <div style={{ marginBottom: 14, fontSize: 13 }}>
          {trigger.isSuccess && (
            <span style={{ color: 'var(--ok)' }}>
              Build #{trigger.data.buildNumber} queued.{' '}
              <Link
                to="/builds/$buildId"
                params={{ buildId: String(trigger.data.buildId) }}
                style={{ color: 'var(--accent)', textDecoration: 'underline' }}
              >
                View
              </Link>
            </span>
          )}
          {trigger.isError && (
            <span style={{ color: 'var(--fail)' }}>
              {trigger.error instanceof ApiError
                ? (trigger.error.problem.detail ?? 'Trigger failed.')
                : 'Trigger failed.'}
            </span>
          )}
        </div>
      )}

      {editingTriggers && (
        <TriggerEditor
          jobId={jobId}
          pipelineScript={job.pipelineScript ?? ''}
          onClose={() => setEditingTriggers(false)}
        />
      )}

      {paramsModalOpen && (
        <TriggerParamsModal
          jobFullName={job.fullName}
          parameters={paramTrigger.parameters}
          isPending={trigger.isPending}
          errorMessage={
            trigger.isError && trigger.error instanceof ApiError
              ? (trigger.error.problem.detail ?? 'Trigger failed.')
              : trigger.isError
                ? 'Trigger failed.'
                : null
          }
          onCancel={() => {
            // ALWAYS dismiss — never trap the user, even mid-trigger (it may have
            // hung). reset() clears the mutation state so the modal can reopen clean.
            setParamsModalOpen(false)
            trigger.reset()
          }}
          onConfirm={(overrides) => {
            trigger.mutate(
              {
                jobId,
                req:
                  Object.keys(overrides).length > 0
                    ? { parameters: overrides }
                    : undefined,
              },
              {
                onSuccess: (data) => {
                  setParamsModalOpen(false)
                  void navigate({
                    to: '/builds/$buildId',
                    params: { buildId: String(data.buildId) },
                  })
                },
              },
            )
          }}
        />
      )}

      {previewOpen && (
        <TriggerPreviewModal
          jobFullName={job.fullName}
          pipelineScript={job.pipelineScript}
          isPending={trigger.isPending}
          onCancel={() => setPreviewOpen(false)}
          onConfirm={() => {
            trigger.mutate(
              { jobId },
              {
                onSuccess: (data) => {
                  setPreviewOpen(false)
                  void navigate({
                    to: '/builds/$buildId',
                    params: { buildId: String(data.buildId) },
                  })
                },
              },
            )
          }}
        />
      )}

      {/* Per-pipeline stats — #775: failure rate over the window, p95 duration, daily sparkline */}
      <JobStatsPanel jobId={jobId} />

      {/* Stage timing percentiles — #1095: p50/p95/p99 per stage over the last
          30 builds, one clickable bar per build → that build's detail page. */}
      <JobStageTimingsPanel jobId={jobId} />

      {/* Cron triggers panel — #722 */}
      <CronTriggersPanel
        jobId={jobId}
        pipelineScript={job.pipelineScript}
        jobEnabled={job.enabled}
      />

      {/* Source — YAML preview (collapsible). Folded in from the deleted
          /repositories surface (design 66): the YAML view lives next to the
          pipeline's runtime affordances rather than on its own page. The
          render style mirrors the /repositories <pre><code> pattern verbatim
          — no syntax-highlight dependency was added. */}
      <PipelineSource pipelineScript={job.pipelineScript} />

      {/* Recent builds — newest 10, row shape mirrors /builds list. #927:
          partition into In-flight (non-terminal) + History (terminal) so a
          single RUNNING build is never buried under SUCCESS rows. */}
      <div className="card">
        <div className="card-header">
          <h3 className="card-title">Recent builds</h3>
          <span className="card-sub">
            {recent.length} of {kpis.total}
          </span>
        </div>
        {buildsLoading ? (
          <div className="row-list">
            {Array.from({ length: 3 }).map((_, i) => (
              <Skeleton key={i} style={{ height: 32, width: '100%', display: 'block' }} />
            ))}
          </div>
        ) : recent.length === 0 ? (
          <div className="empty">No builds yet. Click Run pipeline to start one.</div>
        ) : showRecentSplit ? (
          <>
            <h4
              className="builds-section-heading builds-section-inflight"
              data-testid="pipeline-section-inflight"
            >
              <span className="builds-section-dot" aria-hidden />
              <span>In flight</span>
              <span className="builds-section-count mono">
                {recentInflight.length}
              </span>
            </h4>
            <div
              className="row-list stagger"
              data-testid="pipeline-recent-inflight"
            >
              {recentInflight.map((build, i) => (
                <RecentBuildRow key={build.id} build={build} index={i} />
              ))}
            </div>
            {recentHistory.length > 0 && (
              <>
                <div className="builds-section-separator" aria-hidden />
                <h4
                  className="builds-section-heading"
                  data-testid="pipeline-section-history"
                >
                  <span>History</span>
                  <span className="builds-section-count mono">
                    {recentHistory.length}
                  </span>
                </h4>
                <div
                  className="row-list stagger"
                  data-testid="pipeline-recent-history"
                >
                  {recentHistory.map((build, i) => (
                    <RecentBuildRow key={build.id} build={build} index={i} />
                  ))}
                </div>
              </>
            )}
          </>
        ) : (
          <div className="row-list stagger" data-testid="pipeline-recent-list">
            {recent.map((build, i) => (
              <RecentBuildRow key={build.id} build={build} index={i} />
            ))}
          </div>
        )}
      </div>
    </PageContainer>
  )
}

/**
 * One row of the "Recent builds" card. Extracted (#927) so the in-flight and
 * history sections share a single render path. RUNNING rows live-tick their
 * duration via {@link formatBuildDuration} — the page-level
 * {@link useTickWhileActive} forces a re-render each second so the cell
 * advances.
 */
function RecentBuildRow({ build, index }: { build: BuildDto; index: number }) {
  const isInflight =
    build.status === 'RUNNING' || build.status === 'QUEUED'
  const durationCell = isInflight
    ? build.status === 'QUEUED'
      ? 'queued'
      : (formatBuildDuration(build.startedAt, build.finishedAt) || '—')
    : formatDuration(build.durationMs)
  return (
    <Link
      to="/builds/$buildId"
      params={{ buildId: String(build.id) }}
      className="row"
      data-status={build.status}
      data-inflight={isInflight ? 'true' : undefined}
      data-testid={`pipeline-recent-row-${build.id}`}
      style={{
        gridTemplateColumns: '64px 110px 1fr 100px 80px',
        ['--i' as string]: index,
        textDecoration: 'none',
        color: 'inherit',
      }}
    >
      <span className="mono" style={{ fontSize: 12, color: 'var(--fg-muted)' }}>
        #{build.buildNumber}
      </span>
      <StatusBadge status={build.status} />
      <span style={{ fontSize: 12, color: 'var(--fg-dim)' }}>
        {build.triggeredBy ?? 'unknown'} · {build.triggerType ?? '—'}
      </span>
      <span
        className="mono dim tabular-nums"
        style={{ textAlign: 'right' }}
        data-inflight={isInflight ? 'true' : undefined}
      >
        {durationCell}
      </span>
      <span
        style={{
          fontSize: 12,
          color: 'var(--fg-dim)',
          textAlign: 'right',
        }}
      >
        {formatDate(build.startedAt ?? build.queuedAt, 'relative')}
      </span>
    </Link>
  )
}

/**
 * Pipeline source — collapsible YAML preview folded in from /repositories
 * (design 66). Plain {@code <pre><code>} with monospace + oklch tokens,
 * matching the deleted /repositories pane verbatim (no new dep). Default
 * collapsed so the build feed stays above the fold; expand for ad-hoc YAML
 * inspection.
 */
function PipelineSource({ pipelineScript }: { pipelineScript: string | null | undefined }) {
  const [expanded, setExpanded] = useState(false)
  const yaml = pipelineScript?.trim() ?? ''
  return (
    <div className="card" data-testid="pipeline-source">
      <div className="card-header">
        <h3 className="card-title">Source</h3>
        <button
          type="button"
          className="btn btn-sm btn-ghost"
          aria-expanded={expanded}
          onClick={() => setExpanded((v) => !v)}
          data-testid="pipeline-source-toggle"
        >
          {expanded ? 'Hide YAML' : 'Show YAML'}
        </button>
      </div>
      {expanded ? (
        yaml === '' ? (
          <div
            className="empty"
            style={{
              fontSize: 12,
              color: 'var(--fg-faint)',
              fontFamily: 'var(--font-mono)',
            }}
            data-testid="pipeline-source-empty"
          >
            No pipeline script captured for this pipeline.
          </div>
        ) : (
          <pre
            data-testid="pipeline-source-yaml"
            style={{
              margin: 0,
              padding: 12,
              background: 'var(--surface-1)',
              border: '1px solid var(--border)',
              borderRadius: 'var(--r-sm, 6px)',
              fontFamily: 'var(--font-mono)',
              fontSize: 12,
              lineHeight: 1.55,
              color: 'var(--fg-dim)',
              overflow: 'auto',
              maxHeight: 480,
            }}
          >
            <code>{yaml}</code>
          </pre>
        )
      ) : null}
    </div>
  )
}
