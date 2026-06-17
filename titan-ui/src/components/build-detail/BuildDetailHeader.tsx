/**
 * BuildDetailHeader — dense topbar for /builds/$id: breadcrumb, status pill,
 * meta line (trigger chips + duration + trend + relative time), and the
 * action toolbar. Extracted from `/builds/$buildId.tsx` (ticket #851).
 *
 * Pure presentation — all callbacks + state come from the container.
 */
import { Link } from '@tanstack/react-router'
import type { BuildDto, FlowNodeDto, JobDto } from '@/api/types'
import { buildDisplayLabel } from '@/api/types'
import { StatusBadge } from '@/components/StatusBadge'
import { BuildDurationTrend } from '@/components/BuildDurationTrend'
import { formatBuildDuration } from '@/lib/format'
import { formatDate } from '@/lib/build-format'
import { TriggerMetaChips } from './TriggerMetaChips'
import { GithubProvenanceBadge } from './GithubProvenanceBadge'
import { BuildDetailToolbar } from './BuildDetailToolbar'

interface Props {
  build: BuildDto
  job: JobDto | undefined
  nodes: FlowNodeDto[] | undefined
  selectedNodeId: string | null
  timeFormat: 'relative' | 'absolute'
  isReplayPending: boolean
  isReplayFromFailedPending: boolean
  isCancelPending: boolean
  onReplayFromNode: (nodeId: string) => void
  onReplayFromFailed: () => void
  onCancel: () => void
}

export function BuildDetailHeader({
  build,
  job,
  nodes,
  selectedNodeId,
  timeFormat,
  isReplayPending,
  isReplayFromFailedPending,
  isCancelPending,
  onReplayFromNode,
  onReplayFromFailed,
  onCancel,
}: Props) {
  return (
    <header className="bd-topbar">
      <nav className="bd-crumbs" aria-label="Breadcrumb">
        <Link
          to="/builds"
          search={{ tab: 'all', q: '' }}
          style={{ color: 'var(--fg-muted)' }}
        >
          Builds
        </Link>
        <span className="sep">/</span>
        {job?.displayName ? (
          <span className="current">{job.displayName}</span>
        ) : (
          <span className="current">Job #{build.jobId}</span>
        )}
        <span className="sep">/</span>
        <span
          className="current bd-build-label"
          data-testid="bd-build-label"
          title={buildDisplayLabel(build)}
        >
          {buildDisplayLabel(build)}
        </span>
      </nav>

      {/* Overall build verdict — stable `build-verdict-badge` hook for the
          golden-path e2e (#1166). data-status carries the raw verdict;
          FAILURE/FAILED maps to the red `fail` dot variant. */}
      <StatusBadge status={build.status} testId="build-verdict-badge" />

      <div className="bd-meta" data-testid="bd-meta">
        {build.triggerMeta ? (
          <TriggerMetaChips meta={build.triggerMeta} />
        ) : (
          <>
            {build.triggeredBy && (
              <>
                <span>{build.triggeredBy}</span>
                <span className="sep">·</span>
              </>
            )}
            {build.triggerType && (
              <>
                <span>{build.triggerType}</span>
                <span className="sep">·</span>
              </>
            )}
          </>
        )}
        <span title="Duration">
          {formatBuildDuration(build.startedAt, build.finishedAt)}
        </span>
        <BuildDurationTrend jobId={build.jobId} currentBuildId={build.id} />
        <span className="sep">·</span>
        <span>{formatDate(build.startedAt, timeFormat)}</span>
      </div>

      {/* GitHub-App provenance (issue #892). Self-suppressing: renders only for
          App-triggered builds with a resolvable repo + commit link; otherwise
          null, leaving the header byte-identical to a manual build. */}
      <GithubProvenanceBadge meta={build.triggerMeta} />

      <BuildDetailToolbar
        build={build}
        nodes={nodes}
        selectedNodeId={selectedNodeId}
        isReplayPending={isReplayPending}
        isReplayFromFailedPending={isReplayFromFailedPending}
        isCancelPending={isCancelPending}
        onReplayFromNode={onReplayFromNode}
        onReplayFromFailed={onReplayFromFailed}
        onCancel={onCancel}
      />
    </header>
  )
}
