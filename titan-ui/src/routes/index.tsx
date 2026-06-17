/**
 * Overview — Titan landing dashboard (redesign of #580 → closes #1189).
 *
 * Goal: a scannable, dense, width-using dashboard that answers "is anything on
 * fire right now?" in one glance, conforming to every hard constraint (H1–H8)
 * of docs/design/ux-design-chart.md.
 *
 * Layout:
 *   - Shared page frame: <PageContainer width="default"> + <PageHeader> (H1).
 *     `default` == max-w-screen-xl — the token AC §1 names explicitly. Page.tsx
 *     reserves `wide` (max-w-screen-2xl) for data-dense *tables*; an operator
 *     dashboard maps to `default` per the Page component's own guidance (H8).
 *   - Top metric row: a *responsive* CSS grid of big-number tiles
 *     (Pipelines, Builds 24h, Fail rate 24h, Workers) that reflows
 *     4-up → 2-up → 1-up across 1440 / 1024 / 768px (H7).
 *   - Region grid: "Recent activity" (2/3) + "Top failing jobs" (1/3),
 *     each a Card with consistent gutters; stacks below lg (H3, H8).
 *
 * Data sources (read-only — no new endpoints, no invented data):
 *   - useJobs()     — Pipelines KPI tile.
 *   - useStats()    — Builds 24h (buildsToday) + success rate → fail rate.
 *   - useActivity() — terminal-build feed for Recent activity.
 *   - useWorkers()  — Active workers KPI (anything not OFFLINE/DRAINING).
 *
 * Four data states per region (H4): loading = Skeleton, empty = centred
 * "nothing yet" copy (never a bare header), error = a retry affordance,
 * populated = rows / numbers. An idle controller renders an explicit "—" /
 * "no data" — it MUST NOT claim "0% failure" / "100% success".
 *
 * One dominant primary action (H5): the "New pipeline" CTA in the header. On a
 * brand-new instance the OnboardingWelcomeCard owns the primary instead, so the
 * header CTA is suppressed — the page never shows two competing primaries.
 *
 * All React Query hooks are UNCONDITIONAL (PR #343 lesson) — every hook runs on
 * every render regardless of another query's loading/error state, so a single
 * failing region never trips the rules-of-hooks and never blanks the page.
 * No new colour tokens or hex values; spacing from the Tailwind scale only.
 */
import { createFileRoute, Link } from '@tanstack/react-router'
import { ArrowRight, Plus, RotateCw } from 'lucide-react'
import { useActivity, useJobs, useStats, useWorkers } from '@/api/hooks'
import type { ActivityItemDto } from '@/api/types'
import { OnboardingWelcomeCard } from '@/components/OnboardingWelcomeCard'
import { StatusBadge } from '@/components/StatusBadge'
import { TopFailingJobsCard } from '@/components/TopFailingJobsCard'
import { PageContainer, PageHeader } from '@/components/ui/Page'
import { Skeleton } from '@/components/ui/Skeleton'
import { useDocumentTitle } from '@/lib/useDocumentTitle'

export const Route = createFileRoute('/')({
  component: OverviewPage,
})

function OverviewPage() {
  useDocumentTitle('Overview')

  // ── Hooks: unconditional, always in this order (PR #343). ──────────────────
  const jobs = useJobs(0, 50)
  const stats = useStats()
  const activity = useActivity(25)
  const workers = useWorkers()

  const jobCount = jobs.data?.total ?? null

  // Active workers = anything that's not OFFLINE / DRAINING. We count strictly
  // from the items list so 'BUSY' (non-standard but emitted by some agents) is
  // also picked up — see WorkerDto.state in types.ts.
  const activeWorkers =
    workers.data?.items.filter(
      (w) => w.state !== 'OFFLINE' && w.state !== 'DRAINING',
    ).length ?? null

  // Stats wire = successRate ∈ [0, 1] over terminal builds 24h. Fail rate is
  // 1 - successRate, but only meaningful when there were terminal builds at
  // all; the StatsDto carries 0 in that case (server contract). Render "—"
  // when no signal so we don't claim "0% failure" for an idle controller.
  const buildsToday = stats.data?.buildsToday ?? null
  const failRatePct =
    stats.data == null
      ? null
      : stats.data.buildsToday === 0
        ? null
        : Math.round((1 - stats.data.successRate) * 100)

  // Activity — first page is the Overview slice; "View all" hands off to /builds.
  const activityItems: ActivityItemDto[] =
    activity.data?.pages.flatMap((p) => p.items) ?? []

  // Fresh instance = no builds have ever run. In that state the
  // OnboardingWelcomeCard surfaces the single dominant primary ("Start"), so we
  // suppress the header CTA to keep exactly one primary action on the page (H5).
  //
  // CRITICAL: neither a query *error* NOR an in-flight *load* may read as "fresh".
  //  - On error `stats.data` is undefined → `buildsToday ?? 0 === 0` would be true,
  //    and an errored activity feed is also empty — so an established controller
  //    whose stats/activity calls failed would falsely look fresh.
  //  - While stats is still LOADING `stats.data` is likewise undefined, so a slow
  //    stats endpoint on an established controller would *transiently* look fresh
  //    (the activity feed can settle empty first), flicker the header CTA off and
  //    surface the onboarding primary for the duration of the stats request.
  // Gate on `!isError` AND `!isLoading` for stats so we only treat a *confirmed*
  // empty controller as fresh. The OnboardingWelcomeCard's own gate mirrors this
  // (it bails on stats error/loading too), keeping H5 single-primary consistent
  // across the loading / empty / error / populated matrix.
  const isFreshInstance =
    !activity.isLoading &&
    !stats.isLoading &&
    !stats.isError &&
    !activity.isError &&
    activityItems.length === 0 &&
    (stats.data?.buildsToday ?? 0) === 0

  return (
    <PageContainer width="default" data-testid="overview-page">
      <PageHeader
        title="Overview"
        description="Live cluster status — is anything on fire right now?"
        actions={
          isFreshInstance ? undefined : (
            <Link
              to="/pipelines"
              className="btn btn-primary"
              data-testid="overview-primary-action"
            >
              <Plus size={14} aria-hidden /> New pipeline
            </Link>
          )
        }
      />

      <OnboardingWelcomeCard />

      {/* Metric row — responsive grid: 4-up (≥1280) → 2-up (≥1024) → 1-up. */}
      <div
        className="grid grid-cols-1 gap-4 lg:grid-cols-2 xl:grid-cols-4"
        data-testid="overview-metrics"
      >
        <MetricTile
          label="Pipelines"
          loading={jobs.isLoading}
          error={Boolean(jobs.error)}
          onRetry={() => void jobs.refetch()}
          value={jobCount == null ? '—' : String(jobCount)}
          testid="kpi-jobs"
        />
        <MetricTile
          label="Builds · 24h"
          loading={stats.isLoading}
          error={Boolean(stats.error)}
          onRetry={() => void stats.refetch()}
          value={buildsToday == null ? '—' : String(buildsToday)}
          testid="kpi-builds"
        />
        <MetricTile
          label="Fail rate · 24h"
          loading={stats.isLoading}
          error={Boolean(stats.error)}
          onRetry={() => void stats.refetch()}
          value={failRatePct == null ? '—' : `${failRatePct}%`}
          // No terminal builds → no signal. Say "no data", never "0% failure".
          subtle={failRatePct == null && !stats.error ? 'no data yet' : undefined}
          testid="kpi-fail-rate"
        />
        <MetricTile
          label="Workers"
          loading={workers.isLoading}
          error={Boolean(workers.error)}
          onRetry={() => void workers.refetch()}
          value={activeWorkers == null ? '—' : String(activeWorkers)}
          testid="kpi-workers"
        />
      </div>

      {/* Region grid — Recent activity (2fr) + Top failing jobs (1fr). */}
      <div className="mt-6 grid grid-cols-1 gap-6 lg:grid-cols-3">
        <div className="lg:col-span-2">
          <RecentActivityCard
            items={activityItems}
            isLoading={activity.isLoading}
            hasError={Boolean(activity.error)}
            onRetry={() => void activity.refetch()}
          />
        </div>
        <div className="lg:col-span-1">
          <TopFailingJobsCard />
        </div>
      </div>
    </PageContainer>
  )
}

// ── Metric tile ───────────────────────────────────────────────────────────────

function MetricTile({
  label,
  value,
  subtle,
  loading,
  error,
  onRetry,
  testid,
}: {
  label: string
  value: string
  /** Quiet caption under the value (e.g. "no data yet") — never a hard claim. */
  subtle?: string
  loading?: boolean
  error?: boolean
  onRetry?: () => void
  testid: string
}) {
  return (
    <div className="metric" data-testid={testid}>
      <div className="metric-label">{label}</div>
      {loading ? (
        <div className="metric-value">
          <Skeleton className="h-6 w-[72px]" />
        </div>
      ) : error ? (
        <>
          <div className="metric-value">—</div>
          <button
            type="button"
            className="btn btn-sm btn-ghost self-start"
            onClick={onRetry}
            data-testid={`${testid}-retry`}
          >
            <RotateCw size={11} aria-hidden /> Retry
          </button>
        </>
      ) : (
        <>
          <div className="metric-value">{value}</div>
          {subtle ? (
            <div
              className="faint mono text-[11px]"
              data-testid={`${testid}-subtle`}
            >
              {subtle}
            </div>
          ) : null}
        </>
      )}
    </div>
  )
}

// ── Recent activity ───────────────────────────────────────────────────────────

// Exported for component-level state tests (the loading branch renders only
// Skeletons, so it can be asserted without a router context).
export function RecentActivityCard({
  items,
  isLoading,
  hasError,
  onRetry,
}: {
  items: ActivityItemDto[]
  isLoading: boolean
  hasError: boolean
  onRetry: () => void
}) {
  const slice = items.slice(0, 10)
  return (
    <div className="card" data-testid="overview-recent-activity">
      <div className="card-header">
        <h3 className="card-title">Recent activity</h3>
        <span className="badge ml-auto">last {slice.length || 10}</span>
      </div>
      {isLoading ? (
        Array.from({ length: 4 }).map((_, i) => (
          <div key={i} className="ov-activity-skel">
            <Skeleton className="h-2 w-2 rounded-full" />
            <Skeleton className="h-3 w-[70%]" />
            <Skeleton className="h-3 w-20" />
            <Skeleton className="h-3 w-[50px]" />
          </div>
        ))
      ) : hasError ? (
        <div className="empty" data-testid="overview-recent-activity-error">
          <p className="dim mb-3 text-[13px]">
            Couldn&apos;t load the activity feed.
          </p>
          <button
            type="button"
            className="btn btn-sm btn-ghost"
            onClick={onRetry}
            data-testid="overview-recent-activity-retry"
          >
            <RotateCw size={12} aria-hidden /> Retry
          </button>
        </div>
      ) : slice.length === 0 ? (
        <div className="empty" data-testid="overview-recent-activity-empty">
          <p className="mb-1 text-[13px] font-medium">No builds yet</p>
          <p className="dim mb-3 text-xs">
            Builds will appear here the moment a pipeline runs.
          </p>
          <Link
            to="/pipelines"
            className="btn btn-sm btn-ghost"
            data-testid="overview-recent-activity-cta"
          >
            View pipelines <ArrowRight size={11} aria-hidden />
          </Link>
        </div>
      ) : (
        <>
          {slice.map((evt) => (
            <ActivityRow key={evt.id} event={evt} />
          ))}
          <div className="ov-activity-foot">
            <Link
              to="/builds"
              search={{ tab: 'all', q: '' }}
              className="btn btn-sm btn-ghost"
            >
              View all builds <ArrowRight size={11} aria-hidden />
            </Link>
          </div>
        </>
      )}
    </div>
  )
}

function ActivityRow({ event }: { event: ActivityItemDto }) {
  return (
    <Link
      to="/builds/$buildId"
      params={{ buildId: String(event.buildId) }}
      data-testid={`activity-row-${event.id}`}
      className="ov-activity-row"
    >
      <div className="ov-activity-main">
        <span className="ov-activity-job">{event.jobName}</span>
        <span className="ov-activity-id">#{event.buildId}</span>
      </div>
      <StatusBadge status={event.status} />
      <span className="ov-activity-dur tabnum">
        {event.durationMs > 0 ? formatDuration(event.durationMs) : '—'}
      </span>
      <span className="ov-activity-ago tabnum">{formatRelative(event.ts)}</span>
    </Link>
  )
}

// ── Formatters ────────────────────────────────────────────────────────────────

function formatRelative(iso: string | null | undefined): string {
  if (!iso) return '—'
  const ts = Date.parse(iso)
  if (!Number.isFinite(ts)) return '—'
  const deltaMs = Date.now() - ts
  if (deltaMs < 0) return 'just now'
  const s = Math.floor(deltaMs / 1000)
  if (s < 60) return `${s}s ago`
  const m = Math.floor(s / 60)
  if (m < 60) return `${m}m ago`
  const h = Math.floor(m / 60)
  if (h < 24) return `${h}h ago`
  const d = Math.floor(h / 24)
  return `${d}d ago`
}

function formatDuration(ms: number): string {
  if (ms < 1000) return `${ms}ms`
  const s = Math.floor(ms / 1000)
  if (s < 60) return `${s}s`
  const m = Math.floor(s / 60)
  if (m < 60) return `${m}m ${s % 60}s`
  const h = Math.floor(m / 60)
  return `${h}h ${m % 60}m`
}
