/**
 * StageTimingChart — per-stage histogram of duration across the last N builds,
 * with p50 / p95 / p99 markers (closes #1095).
 *
 * <p>One row per stage, ordered by descending p50 (slowest stages first — server
 * side). Each row carries:
 * <ul>
 *   <li>The stage name and the three percentile read-outs ({@code formatDuration}
 *       — em-dash placeholder for the {@code null} case so a zero-sample group
 *       never lies as "0 ms").</li>
 *   <li>A bar per finished build sample. Bar height is normalised to the
 *       stage's MAX so the shape of the distribution is preserved; every bar
 *       is a clickable {@code <Link>} that navigates to the build-detail page
 *       (acceptance criterion: "Click on a bar → navigate to the build-detail
 *       of that build").</li>
 *   <li>Three horizontal marker lines for p50/p95/p99 — positioned at their
 *       percentile-of-max relative position so the SRE can spot whether the
 *       current build is above the p95 marker at a glance.</li>
 * </ul>
 *
 * <p>Adversarial: a single-sample stage gets a single full-height bar with all
 * three markers collapsed to the top of the chart (Postgres returns the only
 * sample for every percentile in a single-row group — see DAO docstring). The
 * UI shows the three percentile labels stacked so an SRE knows the read-out
 * comes from one sample, not 30.
 *
 * <p>Testids are job-scoped ({@code job-stage-timing-*}) so they never collide
 * with the per-build {@code StageTimingPanel} (#670) that lives on the build
 * detail page under the unscoped {@code stage-timing-*} namespace.
 */
import { Link } from '@tanstack/react-router'
import type { StageTimingDto, StageTimingsDto } from '@/api/types'
import { formatDuration } from '@/lib/format'

// Chart geometry. Constants live at module scope so the test can read the
// fixed values out of the DOM via data-* attributes without doing math.
const CHART_HEIGHT = 56
const MIN_BAR_HEIGHT_PX = 2
const BAR_SLOT_PX = 14
const BAR_GAP_PX = 2

export interface StageTimingChartProps {
  data: StageTimingsDto
}

export function StageTimingChart({ data }: StageTimingChartProps) {
  // Empty contract: zero finished builds in the window → "not enough history"
  // empty state, NOT an empty chart with phantom axes.
  if (data.stages.length === 0 || data.buildsConsidered === 0) {
    return (
      <div
        className="empty"
        data-testid="job-stage-timing-empty"
        style={{ padding: 18, textAlign: 'center' }}
      >
        <p style={{ fontSize: 13, color: 'var(--fg-dim)', margin: 0 }}>
          Not enough history yet.
        </p>
        <p style={{ fontSize: 11, color: 'var(--fg-dim)', marginTop: 4 }}>
          Stage timings will appear once this pipeline has at least one finished
          build.
        </p>
      </div>
    )
  }
  return (
    <div
      data-testid="job-stage-timing-chart"
      data-builds-considered={data.buildsConsidered}
    >
      <div
        style={{
          fontSize: 11,
          color: 'var(--fg-dim)',
          padding: '0 14px 8px',
        }}
      >
        Based on the last {data.buildsConsidered} finished build
        {data.buildsConsidered === 1 ? '' : 's'}.
      </div>
      <ul
        style={{ listStyle: 'none', margin: 0, padding: 0 }}
        data-testid="job-stage-timing-stage-list"
      >
        {data.stages.map((stage) => (
          <li key={stage.stageName}>
            <StageRow stage={stage} />
          </li>
        ))}
      </ul>
    </div>
  )
}

function StageRow({ stage }: { stage: StageTimingDto }) {
  // Normalise bar heights to the max sample so a row with all-fast samples
  // doesn't render flat. min/max are nullable for forward-compat — coerce.
  const max = stage.maxMs ?? Math.max(0, ...stage.samples.map((s) => s.durationMs))
  return (
    <div
      data-testid="job-stage-timing-row"
      data-stage-name={stage.stageName}
      data-sample-count={stage.sampleCount}
      style={{
        display: 'grid',
        gridTemplateColumns: '160px 1fr',
        gap: 12,
        padding: '10px 14px',
        borderTop: '1px solid var(--border)',
        alignItems: 'center',
      }}
    >
      <div>
        <div style={{ fontSize: 13, fontWeight: 600 }}>{stage.stageName}</div>
        <div style={{ fontSize: 11, color: 'var(--fg-dim)', marginTop: 2 }}>
          <span data-testid="job-stage-timing-p50">
            p50&nbsp;{formatPercentile(stage.p50Ms)}
          </span>
          {' · '}
          <span data-testid="job-stage-timing-p95">
            p95&nbsp;{formatPercentile(stage.p95Ms)}
          </span>
          {' · '}
          <span data-testid="job-stage-timing-p99">
            p99&nbsp;{formatPercentile(stage.p99Ms)}
          </span>
        </div>
      </div>
      <StageBars stage={stage} max={max} />
    </div>
  )
}

function StageBars({ stage, max }: { stage: StageTimingDto; max: number }) {
  const samples = stage.samples
  if (samples.length === 0 || max <= 0) {
    return (
      <div
        data-testid="job-stage-timing-bars-empty"
        style={{ height: CHART_HEIGHT, color: 'var(--fg-dim)', fontSize: 11 }}
      >
        —
      </div>
    )
  }
  return (
    <div
      data-testid="job-stage-timing-bars"
      style={{
        position: 'relative',
        height: CHART_HEIGHT,
        display: 'flex',
        alignItems: 'flex-end',
        gap: BAR_GAP_PX,
        overflowX: 'auto',
      }}
    >
      {/* Percentile markers — drawn behind the bars, full chart width. */}
      <PercentileMarker label="p50" value={stage.p50Ms} max={max} colour="var(--ok)" />
      <PercentileMarker label="p95" value={stage.p95Ms} max={max} colour="var(--warn)" />
      <PercentileMarker label="p99" value={stage.p99Ms} max={max} colour="var(--fail)" />

      {samples.map((s) => {
        const ratio = max > 0 ? s.durationMs / max : 0
        const h = Math.max(MIN_BAR_HEIGHT_PX, Math.round(ratio * CHART_HEIGHT))
        const fill =
          s.status === 'FAILED'
            ? 'var(--fail)'
            : s.status === 'UNSTABLE'
              ? 'var(--warn)'
              : 'var(--accent)'
        return (
          <Link
            key={s.buildId}
            to="/builds/$buildId"
            params={{ buildId: String(s.buildId) }}
            data-testid="job-stage-timing-bar"
            data-build-id={s.buildId}
            data-build-number={s.buildNumber}
            data-duration-ms={s.durationMs}
            title={`Build #${s.buildNumber} — ${formatDuration(s.durationMs)} (${s.status})`}
            style={{
              display: 'inline-block',
              width: BAR_SLOT_PX - BAR_GAP_PX,
              minWidth: BAR_SLOT_PX - BAR_GAP_PX,
              height: h,
              background: fill,
              borderRadius: 2,
              cursor: 'pointer',
            }}
            aria-label={`Build ${s.buildNumber}, ${formatDuration(s.durationMs)}, ${s.status}`}
          />
        )
      })}
    </div>
  )
}

function PercentileMarker({
  label,
  value,
  max,
  colour,
}: {
  label: string
  value: number | null
  max: number
  colour: string
}) {
  if (value == null || max <= 0) return null
  const ratio = Math.min(1, value / max)
  // Marker line drawn from the bottom up to the percentile value's height.
  const fromTop = CHART_HEIGHT - Math.round(ratio * CHART_HEIGHT)
  return (
    <div
      data-testid={`job-stage-timing-marker-${label}`}
      data-value-ms={value}
      style={{
        position: 'absolute',
        top: fromTop,
        left: 0,
        right: 0,
        height: 1,
        background: colour,
        opacity: 0.55,
        pointerEvents: 'none',
      }}
    >
      <span
        style={{
          position: 'absolute',
          right: 2,
          top: -10,
          fontSize: 9,
          color: colour,
          background: 'var(--bg)',
          padding: '0 2px',
          borderRadius: 2,
        }}
      >
        {label}
      </span>
    </div>
  )
}

/** Render a percentile as "1.2s" or the em-dash for the null (no-data) case. */
function formatPercentile(ms: number | null): string {
  return ms == null ? '—' : formatDuration(ms)
}
