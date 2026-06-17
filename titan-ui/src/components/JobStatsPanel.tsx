/**
 * JobStatsPanel — per-job analytics tile on /jobs/$jobId (closes #775).
 *
 * <p>Sources GET /api/v1/jobs/{id}/stats?window=… via {@link useJobStats}. Renders:
 * <ul>
 *   <li>Three KPI tiles: Total builds · Failure rate (red tint when {@code &gt;10%})
 *       · p95 duration. {@code p50}/{@code p95} render as the muted em-dash when the
 *       server returns {@code null} (no completed build) — never as "0 ms".</li>
 *   <li>A daily-failure-rate sparkline of exactly {@code windowDays} bars (server
 *       fills zero-buckets so the x-axis is always dense).</li>
 *   <li>Window-select chips (7d / 30d / 90d, default 30d). Switching invalidates
 *       the React-Query cache implicitly via the {@code window} key.</li>
 * </ul>
 *
 * <p>Adversarial: zero-builds is the calm placeholder, not a misleading "0%". The
 * sparkline still renders the bucket strip (just empty) so the layout doesn't
 * jump when the first build lands.
 */
import { useState } from 'react'
import { useJobStats } from '@/api/hooks'
import type { JobStatsDto, JobStatsWindow } from '@/api/types'
import { Skeleton } from '@/components/ui/Skeleton'
import { formatDuration } from '@/lib/format'

const WINDOWS: readonly JobStatsWindow[] = ['7d', '30d', '90d']

// Failure rate above this gets a red tint on the KPI tile. 10% mirrors the
// SLO bar SREs typically agree on for "healthy" CI.
const FAILURE_TINT_THRESHOLD = 0.1

// Sparkline geometry — fixed so it slots cleanly into a card without
// stretch-jitter on window changes.
const SPARKLINE_HEIGHT = 36
const SPARKLINE_GAP = 1
const SPARKLINE_MIN_BAR_PX = 2

export interface JobStatsPanelProps {
  jobId: number
}

export function JobStatsPanel({ jobId }: JobStatsPanelProps) {
  const [window, setWindow] = useState<JobStatsWindow>('30d')
  const query = useJobStats(jobId, window)

  return (
    <div className="card" data-testid="job-stats-panel">
      <div className="card-header">
        <h3 className="card-title">Stats</h3>
        <div
          role="tablist"
          aria-label="Stats window"
          data-testid="job-stats-window-chips"
          style={{ marginLeft: 'auto', display: 'inline-flex', gap: 4 }}
        >
          {WINDOWS.map((w) => (
            <button
              key={w}
              role="tab"
              aria-selected={w === window}
              data-testid={`job-stats-window-${w}`}
              onClick={() => setWindow(w)}
              className="filter-chip"
              style={{
                cursor: 'pointer',
                opacity: w === window ? 1 : 0.6,
                fontWeight: w === window ? 600 : 400,
              }}
            >
              {w}
            </button>
          ))}
        </div>
      </div>

      {query.isLoading && !query.data ? (
        <StatsSkeleton />
      ) : query.error ? (
        <div
          className="empty"
          style={{ padding: 18 }}
          data-testid="job-stats-error"
        >
          <p style={{ fontSize: 12, color: 'var(--fg-dim)' }}>
            Stats unavailable.
          </p>
        </div>
      ) : query.data ? (
        <StatsBody data={query.data} />
      ) : null}
    </div>
  )
}

function StatsSkeleton() {
  return (
    <div style={{ padding: 14 }} data-testid="job-stats-loading">
      <div
        className="metric-grid"
        style={{ gridTemplateColumns: 'repeat(3, 1fr)', marginBottom: 14 }}
      >
        {[0, 1, 2].map((i) => (
          <div className="metric" key={i}>
            <Skeleton style={{ height: 11, width: 80 }} />
            <Skeleton style={{ height: 22, width: 100, marginTop: 6 }} />
          </div>
        ))}
      </div>
      <Skeleton style={{ height: SPARKLINE_HEIGHT, width: '100%', display: 'block' }} />
    </div>
  )
}

function StatsBody({ data }: { data: JobStatsDto }) {
  const failurePct = Math.round(data.failureRate * 100)
  const failureTint = data.failureRate > FAILURE_TINT_THRESHOLD
  const empty = data.totalBuilds === 0

  return (
    <div style={{ padding: 14 }}>
      <div
        className="metric-grid"
        style={{ gridTemplateColumns: 'repeat(3, 1fr)', marginBottom: 14 }}
      >
        <div className="metric" data-testid="job-stats-total">
          <div className="metric-label">Total builds</div>
          <div className="metric-value" style={{ fontSize: 18 }}>
            {data.totalBuilds}
          </div>
        </div>
        <div
          className="metric"
          data-testid="job-stats-failure-rate"
          data-tinted={failureTint && !empty ? 'true' : undefined}
          style={{
            color: !empty && failureTint ? 'var(--fail)' : undefined,
          }}
        >
          <div className="metric-label">Failure rate</div>
          <div className="metric-value" style={{ fontSize: 18 }}>
            {empty ? '—' : `${failurePct}%`}
          </div>
        </div>
        <div className="metric" data-testid="job-stats-p95">
          <div className="metric-label">p95 duration</div>
          <div className="metric-value" style={{ fontSize: 18 }}>
            {/*
              null === "no completed builds". Render the em-dash placeholder
              (matches lib/format conventions). Rendering "0 ms" here would lie
              about engine throughput.
            */}
            {data.p95DurationMs == null ? '—' : formatDuration(data.p95DurationMs)}
          </div>
        </div>
      </div>

      <DailyFailureSparkline buckets={data.dailyBuckets} window={data.window} />
      {empty && (
        <p
          data-testid="job-stats-empty"
          style={{
            marginTop: 10,
            fontSize: 12,
            color: 'var(--fg-dim)',
            textAlign: 'center',
          }}
        >
          No builds in the last {data.window}.
        </p>
      )}
    </div>
  )
}

function DailyFailureSparkline({
  buckets,
  window,
}: {
  buckets: JobStatsDto['dailyBuckets']
  window: JobStatsWindow
}) {
  // The server pads to windowDays so length is stable; we still defensively
  // handle a zero-length response by rendering an empty strip.
  if (buckets.length === 0) {
    return (
      <div
        data-testid="job-stats-sparkline"
        data-bars="0"
        style={{ height: SPARKLINE_HEIGHT }}
      />
    )
  }
  // Heights normalise to the per-bucket failure-rate, NOT the absolute failed
  // count — a day with 1/1 failed and a day with 50/100 failed should both
  // appear at full height. A day with zero builds has rate=0 → min-height bar.
  const rates = buckets.map((b) =>
    b.totalBuilds > 0 ? b.failedBuilds / b.totalBuilds : 0,
  )
  const maxRate = rates.reduce((m, r) => (r > m ? r : m), 0)
  return (
    <svg
      data-testid="job-stats-sparkline"
      data-bars={String(buckets.length)}
      data-window={window}
      width="100%"
      height={SPARKLINE_HEIGHT}
      viewBox={`0 0 ${buckets.length * 4} ${SPARKLINE_HEIGHT}`}
      preserveAspectRatio="none"
      role="img"
      aria-label={`Daily failure rate over the last ${buckets.length} day${buckets.length === 1 ? '' : 's'}`}
      style={{ display: 'block' }}
    >
      {buckets.map((b, i) => {
        const slotWidth = 4
        const barWidth = Math.max(1, slotWidth - SPARKLINE_GAP)
        const rate = rates[i]
        const ratio = maxRate > 0 ? rate / maxRate : 0
        const h = Math.max(
          SPARKLINE_MIN_BAR_PX,
          Math.round(ratio * SPARKLINE_HEIGHT),
        )
        const x = i * slotWidth
        const y = SPARKLINE_HEIGHT - h
        const fill =
          b.totalBuilds === 0
            ? 'var(--border)'
            : b.failedBuilds > 0
              ? 'var(--fail)'
              : 'var(--ok)'
        const pct = b.totalBuilds === 0 ? 0 : Math.round(rate * 100)
        return (
          <rect
            key={b.day}
            x={x}
            y={y}
            width={barWidth}
            height={h}
            fill={fill}
            data-day={b.day}
            data-total={b.totalBuilds}
            data-failed={b.failedBuilds}
          >
            <title>
              {b.day} — {b.failedBuilds}/{b.totalBuilds} failed ({pct}%)
            </title>
          </rect>
        )
      })}
    </svg>
  )
}
