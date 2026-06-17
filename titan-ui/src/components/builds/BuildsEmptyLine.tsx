/**
 * BuildsEmptyLine — empty-state copy for /builds.
 *
 * Three branches: query-filtered ("No builds match X"), tab-filtered
 * ("Nothing in flight right now"), and bare ("No builds yet").
 * Extracted from `routes/builds/index.tsx` per #1071 to keep the route
 * file under the LOC cap.
 */
import type { BuildsTab } from '@/components/builds/BuildsStatsStrip'

const TAB_EMPTY_COPY: Record<BuildsTab, string> = {
  all: 'No builds in this view',
  running: 'Nothing in flight right now',
  failed: 'No failed builds — nice.',
  mine: "You haven't triggered anything recently",
}

interface BuildsEmptyLineProps {
  query: string
  tab: BuildsTab
  onClear: () => void
}

export function BuildsEmptyLine({ query, tab, onClear }: BuildsEmptyLineProps) {
  if (query !== '') {
    return (
      <div className="calm-empty" data-testid="builds-empty-filtered">
        No builds match <code>{query}</code>
        {tab !== 'all' && (
          <>
            {' '}
            in <code>{tab}</code>
          </>
        )}
        {' — '}
        <button type="button" className="calm-link" onClick={onClear}>
          Clear filters
        </button>
      </div>
    )
  }
  if (tab !== 'all') {
    return (
      <div className="calm-empty" data-testid="builds-empty-filtered">
        {TAB_EMPTY_COPY[tab]}
        {' — '}
        <button type="button" className="calm-link" onClick={onClear}>
          View all builds
        </button>
      </div>
    )
  }
  return (
    <div className="calm-empty" data-testid="builds-empty">
      No builds yet — trigger one from the <a href="/jobs">Jobs</a> page.
    </div>
  )
}
