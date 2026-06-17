/**
 * BuildsSummary — at-a-glance pass / fail / running summary for /builds.
 *
 * Issue #1187: previously the only "summary" on /builds was the count fused
 * into the four-tab filter strip ({@link BuildsStatsStrip}). That reads as
 * tab labels, not as an at-a-glance health summary, and left the page feeling
 * empty in a wide canvas. This component pulls pass / fail / running out into
 * a compact metric row using the SAME `.metric` tile look as the workers /
 * overview pages (UX chart H8 — consistency) so the page has a dense,
 * scannable header summary independent of which tab is active.
 *
 * Adversarial invariant (preserved from #1071): the Failed tile is NEUTRAL
 * when the count is 0 — a clean failure column is GOOD news, never a red
 * alert. It only goes destructive when failed > 0.
 *
 * Pure / presentational: the parent owns the count queries and passes resolved
 * totals (or `undefined` while a count is still in flight).
 */
import { Skeleton } from '@/components/ui/Skeleton'

export interface BuildsSummaryCounts {
  /** SUCCESS builds. */
  passing?: number
  /** FAILED + ABORTED builds. */
  failed?: number
  /** RUNNING + QUEUED builds. */
  running?: number
}

/**
 * Per-metric error flags. A count query can fail independently of the main
 * builds/jobs fetch (network blip, transient 5xx) — when it does, the tile
 * must render a neutral terminal state, NOT a permanent skeleton.
 */
export interface BuildsSummaryErrors {
  passing?: boolean
  failed?: boolean
  running?: boolean
}

interface BuildsSummaryProps {
  counts: BuildsSummaryCounts
  /** Page-level loading — render skeleton tiles instead of stale zeros. */
  isLoading: boolean
  /** Per-metric count-query failures — render a neutral dash, not a skeleton. */
  errors?: BuildsSummaryErrors
}

interface SummaryTileProps {
  label: string
  value: number | undefined
  testid: string
  isLoading: boolean
  /** When true, the value is rendered in the destructive token (failures). */
  danger?: boolean
  /** Count query for this tile errored — show a neutral dash, never a skeleton. */
  errored?: boolean
}

function SummaryTile({
  label,
  value,
  testid,
  isLoading,
  danger = false,
  errored = false,
}: SummaryTileProps) {
  // A failed count query is a TERMINAL state, not a transient one: if we let it
  // fall through to the skeleton branch the operator would stare at a spinner
  // forever (the infinite-skeleton anti-pattern H4 forbids). Render a neutral
  // em-dash instead — honest "couldn't load this count" without a false 0.
  const failedToLoad = errored && value === undefined
  // A count that hasn't resolved yet is indistinguishable from "still loading"
  // for the operator — both render as a skeleton rather than a misleading 0.
  const pending = !failedToLoad && (isLoading || value === undefined)
  return (
    <div className="metric" data-testid={testid}>
      <div className="metric-label">{label}</div>
      <div className="metric-value">
        {failedToLoad ? (
          <span
            data-testid={`${testid}-error`}
            className="builds-summary-value-muted"
            aria-label={`${label} count unavailable`}
            title="Count unavailable — failed to load"
          >
            —
          </span>
        ) : pending ? (
          <Skeleton style={{ height: 24, width: 48 }} />
        ) : (
          <span
            data-testid={`${testid}-value`}
            className={danger ? 'builds-summary-value-danger' : undefined}
          >
            {value}
          </span>
        )}
      </div>
    </div>
  )
}

export function BuildsSummary({ counts, isLoading, errors = {} }: BuildsSummaryProps) {
  // Failed goes destructive ONLY when > 0 (the #1071 adversarial invariant).
  const failedDanger = typeof counts.failed === 'number' && counts.failed > 0
  return (
    <div className="metric-grid builds-summary" data-testid="builds-summary">
      <SummaryTile
        label="Passing"
        value={counts.passing}
        testid="builds-summary-passing"
        isLoading={isLoading}
        errored={errors.passing}
      />
      <SummaryTile
        label="Failed"
        value={counts.failed}
        testid="builds-summary-failed"
        isLoading={isLoading}
        danger={failedDanger}
        errored={errors.failed}
      />
      <SummaryTile
        label="Running"
        value={counts.running}
        testid="builds-summary-running"
        isLoading={isLoading}
        errored={errors.running}
      />
    </div>
  )
}
