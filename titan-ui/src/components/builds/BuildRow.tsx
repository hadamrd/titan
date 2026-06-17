/**
 * BuildRow — calm-list row for /builds (denser v2, info-rich).
 *
 * Hand-rolled per docs/design/64-titan-tables.md ("Bloomberg-terminal ×
 * Linear"). Replaces the 5-column dot/avatar/body/duration/started grid
 * with a tighter 4-column shape:
 *
 *   [status rail 4px] [primary 1fr] [duration 100px] [time-ago 90px]
 *
 * The rail is a status-colored 4px box-shadow on the row's left edge,
 * absorbing what used to be the standalone "status dot" column — the dot's
 * job (signal at a glance) collapses into the rail, freeing the middle 60%
 * of the row that used to sit empty.
 *
 * Primary block (line 1): bold job name + faint mono build label (#13 or
 * the setBuildName value) + status pill.
 *
 * Primary block (line 2): dense mono meta line — branch chip, short SHA,
 * trigger-source pill ("via GitHub push" / "via cron" / "manual"), actor,
 * and (when failed) the failed-step tail.
 *
 * Duration cell shows the actual duration for terminal builds — including
 * FAILED. The old "--" rendering on failed rows was a lie: failures DO
 * have a finishedAt and a durationMs (BuildDto fields).
 *
 * The row is a Link so cmd-click / middle-click still open in a new tab.
 */
import { Link } from '@tanstack/react-router'
import { GitBranch } from 'lucide-react'
import { buildDisplayLabel, type BuildStatus, type TriggerMetaDto } from '@/api/types'
import { resolveTriggerSource, type TriggerSource } from '@/components/TriggerSourceIcon'
import {
  formatDuration as fmtDuration,
  formatBuildDuration,
  formatDate as fmtDate,
} from '@/lib/format'

export interface BuildRowItem {
  id: number
  jobId: number
  buildNumber: number
  status: BuildStatus
  durationMs: number | null
  startedAt: string | null
  finishedAt: string | null
  triggeredBy: string | null
  triggerType: string | null
  triggerMeta?: TriggerMetaDto | null
  displayName?: string | null
  /** Resolved at the parent — the job's display name (or `job #<id>` fallback). */
  jobName: string
  /** Optional failed-step hint surfaced in the meta line when terminal-failed. */
  failedStep?: string | null
}

interface BuildRowProps {
  build: BuildRowItem
  /** `relative` | `absolute` time-format from TweaksPanel. */
  timeFormat: 'relative' | 'absolute'
  testIdPrefix?: string
}

/** Per-row duration — same null-safety rules as the rest of the UI. */
function rowDuration(b: BuildRowItem): string {
  if (b.status === 'QUEUED') return 'queued'
  if (b.status === 'RUNNING') {
    // Live elapsed while the build is still in flight.
    const live = formatBuildDuration(b.startedAt, b.finishedAt)
    return live === '—' ? 'running…' : live
  }
  if (typeof b.durationMs === 'number' && Number.isFinite(b.durationMs) && b.durationMs >= 0) {
    return fmtDuration(b.durationMs)
  }
  return formatBuildDuration(b.startedAt, b.finishedAt)
}

function shortSha(sha: string | null | undefined): string {
  if (!sha) return ''
  return sha.length > 7 ? sha.slice(0, 7) : sha
}

// Friendly tail for the trigger-source pill on line 2. Kept short so the
// meta line still feels "dense mono" rather than "sentence soup".
const TRIGGER_LABEL: Record<TriggerSource, string> = {
  GITHUB: 'via GitHub',
  CRON: 'via cron',
  MANUAL: 'manual',
  DOGFOOD: 'dogfood',
}

// Lowercase status label for the pill. Matches the verbal register of the
// design brief (we say "success / failed / running" not "SUCCESS / FAILED").
const STATUS_LABEL: Record<BuildStatus, string> = {
  SUCCESS: 'success',
  FAILED: 'failed',
  RUNNING: 'running',
  QUEUED: 'queued',
  ABORTED: 'aborted',
  UNSTABLE: 'unstable',
}

export function BuildRow({ build, timeFormat, testIdPrefix = 'builds-row' }: BuildRowProps) {
  const branch = build.triggerMeta?.branch?.trim() ?? ''
  const sha = shortSha(build.triggerMeta?.commitSha)
  const actor = build.triggerMeta?.actor?.trim() ?? ''
  const label = buildDisplayLabel(build)
  const triggerSource = resolveTriggerSource(build.triggerType, build.triggerMeta)
  const triggerLabel = TRIGGER_LABEL[triggerSource]

  const isInflight = build.status === 'RUNNING' || build.status === 'QUEUED'
  const isFailed = build.status === 'FAILED'
  const isAborted = build.status === 'ABORTED'
  const isUnstable = build.status === 'UNSTABLE'

  // Native `title=` on the time cell shows the absolute ISO timestamp on
  // hover (the muted relative text alone is too coarse for SREs).
  const startedAbsTitle = build.startedAt ?? ''

  return (
    <Link
      to="/builds/$buildId"
      params={{ buildId: String(build.id) }}
      className="calm-list-row build-row"
      data-testid={`${testIdPrefix}-${build.id}`}
      data-inflight={isInflight ? 'true' : undefined}
      data-failed={isFailed || isAborted ? 'true' : undefined}
      data-aborted={isAborted ? 'true' : undefined}
      data-status={build.status}
    >
      {/* Status rail — colored left-edge bar, 4px wide. Painted via a span
          rather than ::before so e2e tests can target it directly. */}
      <span
        className="cl-rail"
        data-testid={`row-rail-${build.id}`}
        data-status={build.status}
        aria-hidden
      />

      <span className="cl-body">
        <span className="cl-title" title={`${build.jobName} · ${label}`}>
          <span className="cl-title-name">{build.jobName}</span>
          <span className="cl-build-num" data-testid={`${testIdPrefix}-label-${build.id}`}>
            {label}
          </span>
          <span
            className="cl-pill"
            data-status={build.status}
            data-testid={`${testIdPrefix}-pill-${build.id}`}
          >
            {STATUS_LABEL[build.status]}
          </span>
        </span>
        <span className="cl-meta">
          {branch && (
            <span className="cl-branch">
              <GitBranch size={11} aria-hidden />
              <span>{branch}</span>
            </span>
          )}
          {sha && (
            <>
              {branch && <span className="cl-sep" aria-hidden>·</span>}
              <span className="cl-sha">{sha}</span>
            </>
          )}
          {(branch || sha) && <span className="cl-sep" aria-hidden>·</span>}
          <span className="cl-trigger">{triggerLabel}</span>
          {actor && (
            <>
              <span className="cl-sep" aria-hidden>·</span>
              <span className="cl-actor">{actor}</span>
            </>
          )}
          {!actor && build.triggeredBy && triggerSource !== 'GITHUB' && (
            <>
              <span className="cl-sep" aria-hidden>·</span>
              <span className="cl-actor">{build.triggeredBy}</span>
            </>
          )}
          {(isFailed || isAborted || isUnstable) && build.failedStep && (
            <>
              <span className="cl-sep" aria-hidden>·</span>
              <span className="cl-fail">{build.failedStep} failed</span>
            </>
          )}
        </span>
      </span>

      <span
        className="cl-duration mono"
        data-testid={`${testIdPrefix}-duration-${build.id}`}
        data-inflight={isInflight ? 'true' : undefined}
      >
        {rowDuration(build)}
      </span>
      <span
        className="cl-started mono"
        title={startedAbsTitle || undefined}
      >
        {fmtDate(build.startedAt, timeFormat)}
      </span>
    </Link>
  )
}
