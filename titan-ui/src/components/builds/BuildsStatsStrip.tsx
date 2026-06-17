/**
 * BuildsStatsStrip — tab/stats strip for /builds.
 *
 * Renders the four-tab filter strip (All / Running / Failed / Mine) with
 * per-tab counts. Each tab doubles as a "stat" cell: the count for the
 * "Failed" tab carries the destructive style class when > 0, neutral when
 * 0 — the adversarial test in #1071 pins this so an empty failure count
 * never renders as red.
 *
 * Pure / presentational: parent owns active-tab state + count queries.
 * Extracted from `routes/builds/index.tsx` per #1071.
 */

export type BuildsTab = 'all' | 'running' | 'failed' | 'mine'

export interface BuildsStatsStripCounts {
  all?: number
  running?: number
  failed?: number
  mine?: number
}

interface BuildsStatsStripProps {
  activeTab: BuildsTab
  counts: BuildsStatsStripCounts
  /** Show the "Mine" tab? Hidden when nobody is signed in. */
  showMine: boolean
  onTabChange: (tab: BuildsTab) => void
}

interface TabDef {
  key: BuildsTab
  label: string
  visible: boolean
}

export function BuildsStatsStrip({
  activeTab,
  counts,
  showMine,
  onTabChange,
}: BuildsStatsStripProps) {
  const tabs: TabDef[] = [
    { key: 'all', label: 'All', visible: true },
    { key: 'running', label: 'Running', visible: true },
    { key: 'failed', label: 'Failed', visible: true },
    { key: 'mine', label: 'Mine', visible: showMine },
  ]

  return (
    <div className="cl-tabs" role="tablist" aria-label="Filter builds by status">
      {tabs
        .filter((t) => t.visible)
        .map((t) => {
          const count = counts[t.key]
          const selected = activeTab === t.key
          // Failed cell goes destructive when > 0; neutral when 0 or
          // undefined (the adversarial test guards this — a zero-count
          // failure tab is GOOD news, not a red alert).
          const failedDanger = t.key === 'failed' && typeof count === 'number' && count > 0
          const countClass = failedDanger
            ? 'cl-tab-count cl-tab-count-danger'
            : 'cl-tab-count'
          return (
            <button
              key={t.key}
              type="button"
              role="tab"
              aria-selected={selected}
              data-testid={`filter-tab-${t.key}`}
              className="cl-tab"
              onClick={() => onTabChange(t.key)}
            >
              <span>{t.label}</span>
              {typeof count === 'number' && (
                <span data-testid={`filter-tab-${t.key}-count`} className={countClass}>
                  {count}
                </span>
              )}
            </button>
          )
        })}
    </div>
  )
}
