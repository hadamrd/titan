/**
 * Build duration trend chart (issue #663).
 *
 * Renders an inline mini-chart of the last N builds of a single job, with bar
 * heights proportional to duration_ms (normalised to the window max). Bars are
 * coloured by status (shared {@link buildStatusColor} mapping). The "current"
 * build — the one the user is currently viewing on /builds/$buildId — is
 * outlined with a contrasting ring so SREs can instantly spot whether THIS
 * build's duration is anomalous vs the recent population.
 *
 * <p>Hover surfaces a native browser tooltip ({@code <title>}) of the form
 * "#&lt;build&gt; · &lt;STATUS&gt; · &lt;duration&gt;". Click navigates to that
 * build via TanStack Router.
 *
 * <p>Data is sourced from the bulk endpoint already wired for /jobs
 * ({@link useJobsRecentBuilds}, GET /api/v1/jobs/recent-builds). The single-job
 * call shape is the same — we just pass a one-element jobIds array and read
 * the keyed slice.
 *
 * <p>v3 design: dense, calm, no animation, no rainbow. Width is fixed (does
 * not stretch to fill) so it slots cleanly into the existing header strip.
 */
import { useNavigate } from '@tanstack/react-router'
import type { MouseEvent } from 'react'
import { useJobsRecentBuilds } from '@/api/hooks'
import type { BuildDto } from '@/api/types'
import { buildStatusColor } from '@/lib/buildStatusColor'

const WIDTH = 110
const HEIGHT = 22
const BAR_GAP = 1
const MIN_BAR_HEIGHT = 2 // never render a 0-height bar — keep status colour visible

export interface BuildDurationTrendProps {
  jobId: number
  currentBuildId: number
  limit?: number
}

function formatDurationShort(ms: number | null | undefined): string {
  if (ms == null || !Number.isFinite(ms) || ms < 0) return '—'
  const s = Math.round(ms / 1000)
  if (s < 60) return `${s}s`
  const m = Math.floor(s / 60)
  const rem = s % 60
  if (m < 60) return rem === 0 ? `${m}m` : `${m}m ${rem}s`
  const h = Math.floor(m / 60)
  const mm = m % 60
  return mm === 0 ? `${h}h` : `${h}h ${mm}m`
}

function tooltipFor(b: BuildDto): string {
  return `#${b.buildNumber} · ${b.status} · ${formatDurationShort(b.durationMs)}`
}

export function BuildDurationTrend({
  jobId,
  currentBuildId,
  limit = 20,
}: BuildDurationTrendProps) {
  const navigate = useNavigate()
  const { data, isLoading } = useJobsRecentBuilds([jobId], limit)

  if (isLoading) {
    // Reserve the strip's footprint silently — no spinner so the header stays
    // calm. The bars appear under one refetch tick (~5 s) once the data lands.
    return (
      <span
        data-testid="build-duration-trend-loading"
        aria-hidden="true"
        style={{ display: 'inline-block', width: WIDTH, height: HEIGHT }}
      />
    )
  }

  const items: readonly BuildDto[] = data?.get(jobId) ?? []
  if (items.length === 0) {
    return (
      <span
        data-testid="build-duration-trend-empty"
        style={{
          display: 'inline-block',
          width: WIDTH,
          textAlign: 'center',
          fontSize: 12,
          fontFamily: 'var(--font-mono)',
          color: 'var(--fg-dim)',
        }}
        aria-label="No build duration trend"
        title="No recent builds"
      >
        —
      </span>
    )
  }

  // Server returns newest-first. Trend reads left = oldest, right = newest.
  const ordered = items.slice(0, limit).reverse()
  const slots = limit
  const slotWidth = WIDTH / slots
  const barWidth = Math.max(1, slotWidth - BAR_GAP)
  const leadingEmpty = slots - ordered.length

  // Normalise heights to the window max. Builds with null/0 duration (RUNNING,
  // QUEUED, instant failures) collapse to MIN_BAR_HEIGHT so the colour still
  // shows. If the entire window has no usable duration (all null/0), every bar
  // is the minimum — avoids divide-by-zero / NaN.
  const usableDurations = ordered
    .map((b) => (b.durationMs != null && b.durationMs > 0 ? b.durationMs : 0))
  const maxDuration = usableDurations.reduce((m, d) => (d > m ? d : m), 0)

  function onBarClick(e: MouseEvent<SVGRectElement>, buildId: number) {
    e.stopPropagation()
    e.preventDefault()
    void navigate({ to: '/builds/$buildId', params: { buildId: String(buildId) } })
  }

  return (
    <svg
      data-testid="build-duration-trend"
      width={WIDTH}
      height={HEIGHT}
      viewBox={`0 0 ${WIDTH} ${HEIGHT}`}
      role="img"
      aria-label={`Duration trend over last ${ordered.length} build${ordered.length === 1 ? '' : 's'}`}
      style={{ display: 'block' }}
    >
      {ordered.map((b, i) => {
        const x = (leadingEmpty + i) * slotWidth
        const d = b.durationMs != null && b.durationMs > 0 ? b.durationMs : 0
        const ratio = maxDuration > 0 ? d / maxDuration : 0
        const h = Math.max(MIN_BAR_HEIGHT, Math.round(ratio * HEIGHT))
        const y = HEIGHT - h
        const isCurrent = b.id === currentBuildId
        return (
          <g key={b.id}>
            <rect
              x={x}
              y={y}
              width={barWidth}
              height={h}
              fill={buildStatusColor(b.status)}
              rx={1}
              style={{ cursor: 'pointer' }}
              data-build-id={b.id}
              data-status={b.status}
              data-current={isCurrent ? 'true' : undefined}
              onClick={(e) => onBarClick(e, b.id)}
            >
              <title>{tooltipFor(b)}</title>
            </rect>
            {isCurrent && (
              <rect
                x={x - 0.5}
                y={0}
                width={barWidth + 1}
                height={HEIGHT}
                fill="none"
                stroke="var(--fg)"
                strokeWidth={1}
                rx={2}
                pointerEvents="none"
                data-testid="build-duration-trend-current-ring"
              />
            )}
          </g>
        )
      })}
    </svg>
  )
}
