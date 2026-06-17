/**
 * Per-job duration-trend sparkline for the /pipelines index (issue #1096).
 *
 * Renders a small inline SVG line of the row's recent finished-build durations —
 * oldest left, newest right. The line is coloured by trend:
 *   - improving (recent avg < prior avg) → var(--ok)  (green)
 *   - degrading / flat                   → var(--fail) (red)
 * per the acceptance criterion "green if improving, red otherwise".
 *
 * <p>Reuses the shared {@link Sparkline} primitive (no new chart dep — manifesto
 * "reuse an existing ui/ primitive"). The trend math lives in the pure,
 * unit-tested {@code lib/durationTrend} module.
 *
 * <p><strong>Data source.</strong> The trend is derived from the {@code builds}
 * prop — the page-level bulk {@code useJobsRecentBuilds} fetch (#650) that the
 * sibling status sparkline already consumes. Rendering from that shared list
 * means the index issues NO per-row duration-trend request, so adding this
 * column does not introduce an N+1 fan-out. The per-job
 * {@code GET /api/v1/jobs/{id}/duration-trend} endpoint remains the documented
 * single-job API (e.g. a per-pipeline detail surface) but is intentionally not
 * called from the fleet index.
 */
import type { BuildDto } from '@/api/types'
import { Sparkline } from '@/components/ui/Sparkline'
import {
  finishedDurationsSeconds,
  trendColor,
  trendDirection,
} from '@/lib/durationTrend'

const WIDTH = 90
const HEIGHT = 22

function formatDurationS(s: number): string {
  if (s < 60) return `${s.toFixed(s < 10 ? 1 : 0)}s`
  const m = Math.floor(s / 60)
  const rem = Math.round(s % 60)
  return `${m}m ${rem}s`
}

export interface JobDurationTrendProps {
  jobId: number
  /**
   * Recent builds for this row, from the page-level bulk fetch (#650) — the same
   * source the status sparkline reads. Newest-first (as the API returns them);
   * we filter to finished builds with a duration and reverse to oldest→newest.
   * {@code undefined}/empty (loading, or a never-run job) → the em-dash state.
   */
  builds?: readonly BuildDto[]
}

export function JobDurationTrend({ jobId, builds }: JobDurationTrendProps) {
  // Shared derivation (finished-only, ms→s, oldest→newest) — same predicate as
  // the server-side DurationTrendDao, so column and endpoint can't drift.
  const durations = finishedDurationsSeconds(builds)

  // A single point can't express a trend and Sparkline needs ≥2 to draw a line;
  // show the muted em-dash rather than a misleading flat stub.
  if (durations.length < 2) {
    return (
      <span
        data-testid={`job-row-${jobId}-duration-trend-empty`}
        style={{
          display: 'inline-block',
          width: WIDTH,
          textAlign: 'center',
          fontSize: 12,
          fontFamily: 'var(--font-mono)',
          color: 'var(--fg-dim)',
        }}
        aria-label="Not enough build history yet"
      >
        —
      </span>
    )
  }

  const direction = trendDirection(durations)
  const color = trendColor(durations)

  return (
    <span
      data-testid={`job-row-${jobId}-duration-trend`}
      data-trend={direction}
      title={`Duration trend over last ${durations.length} builds — ${direction}`}
      aria-label={`Build duration trend over the last ${durations.length} builds: ${direction}`}
      role="img"
      style={{ display: 'inline-block' }}
    >
      <Sparkline
        data={durations}
        width={WIDTH}
        height={HEIGHT}
        stroke={color}
        formatValue={(v) => formatDurationS(v)}
      />
    </span>
  )
}
