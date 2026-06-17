/**
 * Per-job sparkline of the last N builds (issue #648).
 *
 * Renders up to {@code maxBars} vertical bars (default 20) — oldest left, newest
 * right — coloured via the v3 oklch tokens:
 *   - SUCCESS  → var(--ok)
 *   - FAILED / FAILURE / ABORTED / UNSTABLE → var(--fail)
 *   - everything else (RUNNING / QUEUED / unknown) → var(--muted)
 *
 * Each bar carries a native browser tooltip ({@code <title>}) of the form
 * "Build #N · STATUS · Xs ago" and click-navigates to {@code /builds/$id}.
 *
 * <p>v1 fetch strategy: one {@link useJobBuilds} call per row (N+1). Acceptable
 * for the small fleet sizes /jobs is designed for; a backend bulk endpoint will
 * follow (see follow-up issue cited in the PR body). When the fleet grows we
 * swap to that endpoint without changing this component's surface.
 *
 * <p>The component is hand-rolled SVG — no new chart library dep (CONSTITUTION
 * §6, §4 stack lock).
 */
import { useNavigate } from '@tanstack/react-router'
import type { MouseEvent } from 'react'
import { useJobBuilds } from '@/api/hooks'
import { Skeleton } from '@/components/ui/Skeleton'
import type { BuildDto } from '@/api/types'

const WIDTH = 90
const HEIGHT = 22
const MAX_BARS = 20
const BAR_GAP = 1

function statusColor(status: string): string {
  switch (status) {
    case 'SUCCESS':
      return 'var(--ok)'
    case 'FAILED':
    case 'FAILURE':
    case 'ABORTED':
    case 'UNSTABLE':
      return 'var(--fail)'
    default:
      return 'var(--muted)'
  }
}

function relativeTime(iso: string | null): string {
  if (!iso) return 'unknown'
  const t = Date.parse(iso)
  if (Number.isNaN(t)) return 'unknown'
  const deltaMs = Date.now() - t
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

function tooltipFor(b: BuildDto): string {
  const when = relativeTime(b.finishedAt ?? b.startedAt ?? b.queuedAt)
  return `Build #${b.buildNumber} · ${b.status} · ${when}`
}

export interface JobSparklineProps {
  jobId: number
  /**
   * Optional pre-fetched build list — when provided, the per-row {@link useJobBuilds}
   * call is skipped entirely. This is the page-level bulk-fetch path (#650): the /jobs
   * route fetches every row's recent builds in a single round-trip via
   * {@code useJobsRecentBuilds} and passes the per-job slice down through this prop.
   * Omitting it falls back to the legacy per-row fetch — kept so isolated usages
   * (tests, future detail pages) still work without a parent fetcher.
   */
  builds?: readonly BuildDto[]
}

export function JobSparkline({ jobId, builds }: JobSparklineProps) {
  // Only fan-out a per-row request when the caller did NOT pre-fetch. Passing
  // {@code undefined} for jobId disables {@link useJobBuilds} entirely — that
  // keeps the bulk path free of N+1 requests.
  const { data, isLoading } = useJobBuilds(builds !== undefined ? undefined : jobId, 0, MAX_BARS)
  const navigate = useNavigate()

  // Loading only when we have neither pre-fetched data NOR per-row data yet.
  const effectiveIsLoading = builds === undefined && isLoading

  if (effectiveIsLoading) {
    return (
      <Skeleton
        data-testid={`job-row-${jobId}-sparkline-loading`}
        style={{ height: HEIGHT, width: WIDTH, borderRadius: 3 }}
      />
    )
  }

  // Prefer the bulk-fetched list; fall back to the per-row query result.
  const items: readonly BuildDto[] = builds ?? data?.items ?? []
  if (items.length === 0) {
    return (
      <span
        data-testid={`job-row-${jobId}-sparkline-empty`}
        style={{
          display: 'inline-block',
          width: WIDTH,
          textAlign: 'center',
          fontSize: 12,
          fontFamily: 'var(--font-mono)',
          color: 'var(--fg-dim)',
        }}
        aria-label="No builds yet"
      >
        —
      </span>
    )
  }

  // useJobBuilds returns newest-first. Sparkline reads left = oldest, right =
  // newest, so reverse and clamp to MAX_BARS.
  const ordered = items.slice(0, MAX_BARS).reverse()
  const slotWidth = WIDTH / MAX_BARS
  const barWidth = Math.max(1, slotWidth - BAR_GAP)
  // Right-align: render in the trailing slots so a job with 3 builds shows
  // them at the right edge, matching "newest = right".
  const leadingEmpty = MAX_BARS - ordered.length

  function onBarClick(e: MouseEvent<SVGRectElement>, buildId: number) {
    e.stopPropagation()
    e.preventDefault()
    void navigate({ to: '/builds/$buildId', params: { buildId: String(buildId) } })
  }

  return (
    <svg
      data-testid={`job-row-${jobId}-sparkline`}
      width={WIDTH}
      height={HEIGHT}
      viewBox={`0 0 ${WIDTH} ${HEIGHT}`}
      role="img"
      aria-label={`Last ${ordered.length} build${ordered.length === 1 ? '' : 's'} sparkline`}
      style={{ display: 'block' }}
    >
      {ordered.map((b, i) => {
        const x = (leadingEmpty + i) * slotWidth
        return (
          <rect
            key={b.id}
            x={x}
            y={0}
            width={barWidth}
            height={HEIGHT}
            fill={statusColor(b.status)}
            rx={1}
            style={{ cursor: 'pointer' }}
            data-build-id={b.id}
            data-status={b.status}
            onClick={(e) => onBarClick(e, b.id)}
          >
            <title>{tooltipFor(b)}</title>
          </rect>
        )
      })}
    </svg>
  )
}
