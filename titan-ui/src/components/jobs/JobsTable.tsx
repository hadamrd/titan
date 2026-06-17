/**
 * Pipelines table (extracted from `routes/pipelines/index.tsx` for issue #1070).
 *
 * Presentation only: the route owns the TanStack Query hooks + sort state and
 * passes the already-sorted, already-partitioned rows down. This module renders
 * the sortable header, the favorites/rest split with its separator, each row
 * ({@link JobsRow}), and the per-row quick-trigger ({@link QuickTriggerButton}).
 * The loading skeleton lives in its sibling `JobsTableSkeleton.tsx`.
 *
 * The only data hook here is {@link useTriggerBuild}, which is a row-local
 * action (manual build trigger) — it does not load the page's data, so it
 * stays co-located with the button that fires it.
 */
import { useEffect, useRef, useState, type MouseEvent } from 'react'
import { Link } from '@tanstack/react-router'
import { Play } from 'lucide-react'
import { useTriggerBuild } from '@/api/hooks'
import { useParamAwareTrigger } from '@/lib/useParamAwareTrigger'
import { StarButton } from '@/components/StarButton'
import { TriggerParamsModal } from '@/components/TriggerParamsModal'
import { StatusBadge } from '@/components/StatusBadge'
import { JobSparkline } from '@/components/JobSparkline'
import { JobDurationTrend } from '@/components/JobDurationTrend'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/Table'
import { ApiError, type BuildDto, type JobDto } from '@/api/types'
import { formatDate, formatDuration } from '@/lib/format'
import { ariaSortForLastBuild, ariaSortForName, type SortKey } from './jobsSort'

const headerBtnStyle = {
  background: 'none',
  border: 0,
  padding: 0,
  cursor: 'pointer',
  font: 'inherit',
} as const

export interface JobsTableProps {
  favorites: readonly JobDto[]
  rest: readonly JobDto[]
  /** Pre-fetched recent builds keyed by job id (bulk fetch — #650). */
  recentBuildsMap: ReadonlyMap<number, readonly BuildDto[]> | undefined
  sort: SortKey
  onClickName: () => void
  onClickLastBuild: () => void
  onStarError: (message: string) => void
}

export function JobsTable({
  favorites,
  rest,
  recentBuildsMap,
  sort,
  onClickName,
  onClickLastBuild,
  onStarError,
}: JobsTableProps) {
  return (
    <div className="card">
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead style={{ width: 1 }} aria-label="Favorite"></TableHead>
            <TableHead style={{ width: 1 }} aria-label="Quick actions"></TableHead>
            <TableHead aria-sort={ariaSortForName(sort)}>
              <button
                type="button"
                className="link"
                onClick={onClickName}
                style={{
                  ...headerBtnStyle,
                  color: sort.key === 'alpha' ? 'var(--fg)' : 'var(--fg-muted)',
                }}
                data-testid="jobs-sort-name"
                aria-pressed={sort.key === 'alpha'}
                title="Sort alphabetically (toggle asc/desc)"
              >
                Name
                {sort.key === 'alpha' ? (
                  <span aria-hidden style={{ marginLeft: 4, fontSize: 10 }}>
                    {sort.dir === 'asc' ? '↑' : '↓'}
                  </span>
                ) : null}
              </button>
            </TableHead>
            <TableHead>Enabled</TableHead>
            <TableHead aria-sort={ariaSortForLastBuild(sort)}>
              <button
                type="button"
                className="link"
                onClick={onClickLastBuild}
                style={{
                  ...headerBtnStyle,
                  color:
                    sort.key === 'status' || sort.key === 'recency'
                      ? 'var(--fg)'
                      : 'var(--fg-muted)',
                }}
                title={
                  sort.key === 'recency'
                    ? 'Sorted by recency — click for FAILED-first'
                    : 'Sorted FAILED-first — click for newest-first'
                }
                data-testid="jobs-sort-status"
                aria-pressed={sort.key === 'status' || sort.key === 'recency'}
              >
                Last build
                {sort.key === 'recency' ? (
                  <span aria-hidden style={{ marginLeft: 4, fontSize: 10 }}>
                    ↓
                  </span>
                ) : null}
              </button>
            </TableHead>
            <TableHead style={{ width: 100 }}>Recent</TableHead>
            <TableHead style={{ width: 110 }}>Duration trend (30d)</TableHead>
            <TableHead>Duration</TableHead>
            <TableHead>Finished</TableHead>
            <TableHead></TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {favorites.map((job) => (
            <JobsRow
              key={job.id}
              job={job}
              isFavorite
              onStarError={onStarError}
              recentBuilds={recentBuildsMap?.get(job.id)}
            />
          ))}
          {favorites.length > 0 && rest.length > 0 ? (
            <TableRow
              data-testid="jobs-favorites-separator"
              aria-hidden
              style={{ height: 1 }}
            >
              <TableCell
                colSpan={10}
                style={{
                  padding: 0,
                  borderBottom: '1px solid var(--border)',
                  height: 1,
                }}
              />
            </TableRow>
          ) : null}
          {rest.map((job) => (
            <JobsRow
              key={job.id}
              job={job}
              isFavorite={false}
              onStarError={onStarError}
              recentBuilds={recentBuildsMap?.get(job.id)}
            />
          ))}
        </TableBody>
      </Table>
    </div>
  )
}

interface JobsRowProps {
  job: JobDto
  isFavorite: boolean
  onStarError: (message: string) => void
  /**
   * Pre-fetched sparkline data for this row, sourced from the page-level bulk
   * {@code useJobsRecentBuilds} call (#650). When present, {@link JobSparkline}
   * skips its per-row fetch — that is the whole point of this thread.
   * {@code undefined} on first render before the bulk query settles, or when a
   * job has no builds yet.
   */
  recentBuilds: readonly BuildDto[] | undefined
}

function JobsRow({ job, isFavorite, onStarError, recentBuilds }: JobsRowProps) {
  return (
    <TableRow data-testid={`job-row-${job.id}`}>
      <TableCell style={{ width: 1, paddingRight: 0 }}>
        <StarButton job={job} onError={onStarError} testId={`job-row-${job.id}-star`} />
        {/* isFavorite mirrors the server cache and is only consumed for the
            partition above — the StarButton owns its own filled/outline state
            via useStarredJobs so the two sources stay in sync. */}
        <span style={{ display: 'none' }} data-testid={`job-row-${job.id}-favorite`}>
          {isFavorite ? '1' : '0'}
        </span>
      </TableCell>
      <TableCell style={{ width: 1, paddingRight: 0 }}>
        <QuickTriggerButton job={job} />
      </TableCell>
      <TableCell className="font-medium">{job.displayName}</TableCell>
      <TableCell>
        <span
          className="filter-chip"
          title={job.enabled ? 'Enabled' : 'Disabled'}
          style={{
            cursor: 'default',
            opacity: job.enabled ? 1 : 0.65,
          }}
        >
          <span
            className={`status-dot ${job.enabled ? 'success' : 'cancelled'}`}
            aria-hidden
          />
          {job.enabled ? 'enabled' : 'disabled'}
        </span>
      </TableCell>
      <TableCell data-testid={`job-row-${job.id}-last-build`}>
        {job.lastBuild ? (
          <StatusBadge status={job.lastBuild.status} />
        ) : (
          <span
            style={{
              fontSize: 12,
              fontFamily: 'var(--font-mono)',
              color: 'var(--fg-dim)',
            }}
          >
            never run
          </span>
        )}
      </TableCell>
      <TableCell style={{ width: 100 }}>
        <JobSparkline jobId={job.id} builds={recentBuilds} />
      </TableCell>
      <TableCell style={{ width: 110 }}>
        <JobDurationTrend jobId={job.id} builds={recentBuilds} />
      </TableCell>
      <TableCell className="text-muted-foreground text-sm tabular-nums">
        {job.lastBuild ? formatDuration(job.lastBuild.durationMs) : '—'}
      </TableCell>
      <TableCell className="text-muted-foreground text-sm tabular-nums">
        {job.lastBuild ? formatDate(job.lastBuild.finishedAt) : '—'}
      </TableCell>
      <TableCell>
        <Link
          to="/pipelines/$pipelineId"
          params={{ pipelineId: String(job.id) }}
          className="text-sm font-medium text-primary underline-offset-4 hover:underline"
        >
          View
        </Link>
      </TableCell>
    </TableRow>
  )
}

/**
 * Per-row quick-trigger button (issue #632).
 *
 * Fires {@link useTriggerBuild} on click and surfaces an inline status message
 * next to the icon that auto-fades after ~3s. We deliberately avoid a global
 * toast primitive — no such component exists in the bundle today, and adding
 * `sonner` for a single confirmation would inflate the wire weight + force a
 * `<Toaster />` mount in __root.tsx. Inline confirmation keeps the action
 * affordance and its acknowledgement on the same row, which is the design
 * brief's "moments-that-matter" rule for action+feedback locality.
 *
 * Disabled when the job's lastBuild is RUNNING — back-to-back manual triggers
 * are nearly always an accident (double-click, panic-retry). The disabled
 * tooltip ("Build already in flight") explains why.
 *
 * `e.stopPropagation()` + `e.preventDefault()` keep the row's "View" navigation
 * from firing on the same click — the button sits inside a row whose
 * row-level affordances should not be hijacked.
 */
function QuickTriggerButton({ job }: { job: JobDto }) {
  const trigger = useTriggerBuild()
  const [confirmation, setConfirmation] = useState<string | null>(null)
  const [paramsModalOpen, setParamsModalOpen] = useState(false)
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  useEffect(() => {
    return () => {
      if (timerRef.current !== null) clearTimeout(timerRef.current)
    }
  }, [])

  function flash(msg: string) {
    setConfirmation(msg)
    if (timerRef.current !== null) clearTimeout(timerRef.current)
    timerRef.current = setTimeout(() => setConfirmation(null), 3000)
  }

  function fireBuild(overrides?: Record<string, string>) {
    trigger.mutate(
      {
        jobId: job.id,
        req:
          overrides && Object.keys(overrides).length > 0
            ? { parameters: overrides }
            : undefined,
      },
      {
        onSuccess: (data) => {
          setParamsModalOpen(false)
          flash(`Build #${data.buildNumber} queued`)
        },
        onError: () => flash('Trigger failed'),
      },
    )
  }

  // #779 parity (closes the list-page gap, #1208): a parameterized pipeline must
  // open the params modal here too — not silently fire a build with defaults.
  // The lazy-fetch + resolve logic is shared with the detail page via
  // useParamAwareTrigger so the two trigger paths can't drift again.
  const paramTrigger = useParamAwareTrigger(job.id, (params) => {
    if (params.length > 0) setParamsModalOpen(true)
    else fireBuild()
  })

  const isRunning = job.lastBuild?.status === 'RUNNING'
  const isResolving = paramTrigger.isResolving
  const isDisabled = isRunning || trigger.isPending || isResolving || !job.enabled

  function onClick(e: MouseEvent<HTMLButtonElement>) {
    e.stopPropagation()
    e.preventDefault()
    if (isDisabled) return
    paramTrigger.start()
  }

  const title = isRunning
    ? 'Build already in flight'
    : !job.enabled
      ? 'Job disabled'
      : trigger.isPending
        ? 'Triggering…'
        : isResolving
          ? 'Loading parameters…'
          : 'Trigger build'

  return (
    <div style={{ display: 'inline-flex', alignItems: 'center', gap: 8 }}>
      <button
        type="button"
        className="btn btn-sm"
        onClick={onClick}
        disabled={isDisabled}
        title={title}
        aria-label={title}
        data-testid={`job-row-${job.id}-trigger`}
        style={{
          padding: 4,
          width: 26,
          height: 26,
          display: 'inline-flex',
          alignItems: 'center',
          justifyContent: 'center',
          opacity: isDisabled ? 0.5 : 1,
          cursor: isDisabled ? 'not-allowed' : 'pointer',
        }}
      >
        <Play size={12} aria-hidden />
      </button>
      {confirmation !== null ? (
        <span
          role="status"
          data-testid={`job-row-${job.id}-trigger-status`}
          style={{
            fontSize: 11,
            fontFamily: 'var(--font-mono)',
            color: 'var(--fg-dim)',
            whiteSpace: 'nowrap',
          }}
        >
          {confirmation}
        </span>
      ) : null}
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
            // ALWAYS dismiss — never trap the user, even if a trigger is mid-flight
            // (the request may have hung). reset() clears the mutation state.
            setParamsModalOpen(false)
            trigger.reset()
          }}
          onConfirm={(overrides) => fireBuild(overrides)}
        />
      )}
    </div>
  )
}
