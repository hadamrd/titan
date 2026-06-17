/**
 * TopFailingJobsCard — Home widget ranking the jobs with the highest failure
 * rate in the last 24h (closes #769).
 *
 * Data: GET /api/v1/jobs/top-failing?since=24h&limit=5 via useTopFailingJobs.
 * Server-side filters: only jobs with totalBuilds ≥ 3 and at least one FAILED
 * build appear. Empty response → calm placeholder "All jobs healthy in the
 * last 24h" (no emoji, no gamification — issue spec).
 *
 * Each row:
 *   - jobName (display name from server)
 *   - "N/M failed (P%)" — the actual numerator/denominator + percent
 *   - row click → navigate to /jobs/{jobId}
 *   - if {@code lastFailedBuildId} is set, a secondary affordance navigates to
 *     /builds/{lastFailedBuildId}.
 */
import { Link, useNavigate } from '@tanstack/react-router'
import { useTopFailingJobs } from '@/api/hooks'
import type { TopFailingJobDto } from '@/api/types'
import { Skeleton } from '@/components/ui/Skeleton'

export function TopFailingJobsCard() {
  const query = useTopFailingJobs('24h', 5)
  const rows = query.data ?? []
  return (
    <div className="card" data-testid="top-failing-jobs">
      <div className="card-header">
        <h3 className="card-title">Top failing jobs</h3>
        <span className="badge" style={{ marginLeft: 'auto' }}>
          last 24h
        </span>
      </div>
      {query.isLoading ? (
        Array.from({ length: 3 }).map((_, i) => (
          <div
            key={i}
            style={{
              padding: '10px 14px',
              borderBottom: '1px solid var(--border)',
            }}
          >
            <Skeleton style={{ height: 12, width: '70%' }} />
            <Skeleton style={{ height: 11, width: '40%', marginTop: 4 }} />
          </div>
        ))
      ) : query.error ? (
        <div className="empty" style={{ padding: 18 }}>
          <p style={{ fontSize: 12, color: 'var(--fg-dim)' }}>
            Top failing jobs unavailable.
          </p>
        </div>
      ) : rows.length === 0 ? (
        <div
          className="empty"
          style={{ padding: 18 }}
          data-testid="top-failing-jobs-empty"
        >
          <p style={{ fontSize: 12, color: 'var(--fg-dim)' }}>
            All jobs healthy in the last 24h.
          </p>
        </div>
      ) : (
        rows.map((row) => <FailingRow key={row.jobId} row={row} />)
      )}
    </div>
  )
}

function FailingRow({ row }: { row: TopFailingJobDto }) {
  const pct = Math.round(row.failureRate * 100)
  const navigate = useNavigate()
  return (
    <div
      role="link"
      tabIndex={0}
      onClick={() => navigate({ to: '/pipelines/$pipelineId', params: { pipelineId: String(row.jobId) } })}
      onKeyDown={(e) => {
        if (e.key === 'Enter' || e.key === ' ') {
          e.preventDefault()
          navigate({ to: '/pipelines/$pipelineId', params: { pipelineId: String(row.jobId) } })
        }
      }}
      data-testid={`top-failing-row-${row.jobId}`}
      style={{
        display: 'grid',
        gridTemplateColumns: '1fr auto',
        gap: 10,
        alignItems: 'center',
        padding: '10px 14px',
        borderBottom: '1px solid var(--border)',
        textDecoration: 'none',
        color: 'inherit',
        cursor: 'pointer',
      }}
    >
      <div style={{ minWidth: 0 }}>
        <div
          style={{
            fontSize: 13,
            fontWeight: 500,
            overflow: 'hidden',
            textOverflow: 'ellipsis',
            whiteSpace: 'nowrap',
          }}
        >
          {row.jobName}
        </div>
        <div
          style={{
            fontSize: 11,
            fontFamily: 'var(--font-mono)',
            color: 'var(--fg-dim)',
          }}
          data-testid={`top-failing-stats-${row.jobId}`}
        >
          {row.failedBuilds}/{row.totalBuilds} failed ({pct}%)
        </div>
      </div>
      {row.lastFailedBuildId != null && (
        <Link
          to="/builds/$buildId"
          params={{ buildId: String(row.lastFailedBuildId) }}
          data-testid={`top-failing-last-${row.jobId}`}
          onClick={(e) => e.stopPropagation()}
          style={{
            fontSize: 11,
            fontFamily: 'var(--font-mono)',
            color: 'var(--fg-muted)',
            textDecoration: 'none',
            padding: '4px 8px',
            border: '1px solid var(--border)',
            borderRadius: 4,
          }}
        >
          #{row.lastFailedBuildId}
        </Link>
      )}
    </div>
  )
}
