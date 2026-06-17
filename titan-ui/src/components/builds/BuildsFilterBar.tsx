/**
 * BuildsFilterBar — search input for /builds.
 *
 * Controlled component: parent owns the debounced text + URL sync, this
 * component only renders the input + clear affordance. Extracted from
 * `routes/builds/index.tsx` per #1071, mirroring the decomposition pattern
 * landed in #851 for `routes/builds/$buildId.tsx`.
 *
 * Test-ids preserved verbatim so the existing Playwright specs (21-builds-
 * page-no-rot, 29-builds-inflight-sticky) continue to pass without edits.
 */
import { Search, X } from 'lucide-react'

export interface BuildsFilterBarValue {
  /** Search query — title / branch / sha / build#. */
  q: string
}

interface BuildsFilterBarProps {
  value: BuildsFilterBarValue
  onChange: (next: BuildsFilterBarValue) => void
}

export function BuildsFilterBar({ value, onChange }: BuildsFilterBarProps) {
  return (
    <div className="cl-search">
      <Search size={14} className="cl-search-icon" aria-hidden />
      <input
        type="search"
        data-testid="filter-search"
        placeholder="Search title, branch, commit…"
        value={value.q}
        onChange={(e) => onChange({ ...value, q: e.target.value })}
        aria-label="Search builds"
      />
      {value.q !== '' && (
        <button
          type="button"
          className="cl-search-clear"
          data-testid="filter-clear"
          onClick={() => onChange({ ...value, q: '' })}
          aria-label="Clear search"
        >
          <X size={14} aria-hidden />
        </button>
      )}
    </div>
  )
}
