/**
 * BuildsTable — calm-list rendering for /builds.
 *
 * Composes {@link BuildRow}. Three render states (all in one component so
 * the route file stays slim):
 *   - {@code isLoading} → skeleton row strip.
 *   - {@code builds.length === 0 && !isLoading} → empty-state slot.
 *   - otherwise → flat list, OR split into "In flight" + "History" when
 *     {@code split} is provided (used by the default "All" tab so a single
 *     RUNNING build is never buried under terminal SUCCESS rows — #917).
 *
 * Extracted from `routes/builds/index.tsx` per #1071. Test-ids preserved
 * verbatim so existing Playwright + Vitest specs continue to pass.
 */
import { BuildRow, type BuildRowItem } from '@/components/builds/BuildRow'
import type { ReactNode } from 'react'

interface BuildsTableSplit {
  inFlight: BuildRowItem[]
  history: BuildRowItem[]
}

interface BuildsTableProps {
  builds: BuildRowItem[]
  isLoading: boolean
  timeFormat: 'relative' | 'absolute'
  /** When present + non-empty inFlight, render the sticky-top split. */
  split?: BuildsTableSplit | null
  /** Rendered when {@code builds.length === 0 && !isLoading}. */
  emptySlot?: ReactNode
}

export function BuildsTable({
  builds,
  isLoading,
  timeFormat,
  split,
  emptySlot,
}: BuildsTableProps) {
  if (isLoading) {
    return (
      <div className="calm-list" aria-busy="true" data-testid="builds-list-loading">
        {[0, 1, 2, 3, 4].map((i) => (
          <div className="tt-skel-row" key={i} />
        ))}
      </div>
    )
  }

  if (builds.length === 0) {
    return <>{emptySlot}</>
  }

  if (split && split.inFlight.length > 0) {
    return (
      <>
        <h2
          className="builds-section-heading builds-section-inflight"
          data-testid="builds-section-inflight"
        >
          <span className="builds-section-dot" aria-hidden />
          <span>In flight</span>
          <span className="builds-section-count mono">{split.inFlight.length}</span>
        </h2>
        <div className="calm-list" role="list" data-testid="builds-list-inflight">
          {split.inFlight.map((b) => (
            <BuildRow key={b.id} build={b} timeFormat={timeFormat} />
          ))}
        </div>
        {split.history.length > 0 && (
          <>
            <div className="builds-section-separator" aria-hidden />
            <h2
              className="builds-section-heading"
              data-testid="builds-section-history"
            >
              <span>History</span>
              <span className="builds-section-count mono">{split.history.length}</span>
            </h2>
            <div className="calm-list" role="list" data-testid="builds-list-history">
              {split.history.map((b) => (
                <BuildRow key={b.id} build={b} timeFormat={timeFormat} />
              ))}
            </div>
          </>
        )}
      </>
    )
  }

  return (
    <div className="calm-list" role="list" data-testid="builds-list">
      {builds.map((b) => (
        <BuildRow key={b.id} build={b} timeFormat={timeFormat} />
      ))}
    </div>
  )
}
