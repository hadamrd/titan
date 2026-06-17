/**
 * JobStageTimingsPanel — "Stage Timing — last N builds" tile on the pipeline
 * detail page (closes #1095).
 *
 * <p>Sources GET /api/v1/jobs/{id}/stage-timings?n=30 via {@link
 * useJobStageTimings}. Gives SREs a single-glance answer to "is this build
 * slower than usual?" by showing each stage's duration distribution (a bar
 * per finished build) with p50/p95/p99 markers. Clicking a bar navigates to
 * that build's detail page.
 *
 * <p>Owns the three async surfaces so the chart stays a pure render:
 * <ul>
 *   <li>loading → skeleton bars (no layout jump when data lands)</li>
 *   <li>error → calm "unavailable" placeholder (mirrors {@link JobStatsPanel})</li>
 *   <li>empty / not-enough-history → the chart's own empty state</li>
 * </ul>
 */
import { useJobStageTimings } from '@/api/hooks'
import { StageTimingChart } from '@/components/StageTimingChart'
import { Skeleton } from '@/components/ui/Skeleton'

const WINDOW = 30

export interface JobStageTimingsPanelProps {
  jobId: number
}

export function JobStageTimingsPanel({ jobId }: JobStageTimingsPanelProps) {
  const query = useJobStageTimings(jobId, WINDOW)

  return (
    <div className="card" data-testid="job-stage-timings-panel">
      <div className="card-header">
        <h3 className="card-title">Stage Timing — last {WINDOW} builds</h3>
      </div>

      {query.isLoading && !query.data ? (
        <TimingsSkeleton />
      ) : query.error ? (
        <div
          className="empty"
          style={{ padding: 18 }}
          data-testid="job-stage-timings-error"
        >
          <p style={{ fontSize: 12, color: 'var(--fg-dim)' }}>
            Stage timings unavailable.
          </p>
        </div>
      ) : query.data ? (
        <StageTimingChart data={query.data} />
      ) : null}
    </div>
  )
}

function TimingsSkeleton() {
  return (
    <div style={{ padding: 14 }} data-testid="job-stage-timings-loading">
      {[0, 1, 2].map((i) => (
        <div
          key={i}
          style={{
            display: 'grid',
            gridTemplateColumns: '160px 1fr',
            gap: 12,
            padding: '10px 0',
            alignItems: 'center',
          }}
        >
          <div>
            <Skeleton style={{ height: 13, width: 90 }} />
            <Skeleton style={{ height: 11, width: 130, marginTop: 6 }} />
          </div>
          <Skeleton style={{ height: 56, width: '100%', display: 'block' }} />
        </div>
      ))}
    </div>
  )
}
