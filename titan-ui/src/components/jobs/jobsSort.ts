/**
 * Pipelines-list sort model + persistence (extracted from
 * `routes/pipelines/index.tsx` for issue #1070).
 *
 * Pure, dependency-free helpers so the route file stays a thin data+layout
 * shell and the sort invariants can be unit-tested in isolation. The route
 * re-exports {@link sortJobs} and {@link SortKey} for backward compatibility
 * with `src/test/jobs-sort.test.tsx`.
 *
 * UI vocabulary note: the surface is `/pipelines` (design 66) but the backend
 * nomenclature is unchanged — `JobDto`, `useJobs`, `titan.ui.jobsSort`. These
 * helpers keep the backend "job" naming to match the persisted-key contract.
 */
import type { JobDto } from '@/api/types'

/**
 * Discriminated sort key — every consumer pattern-matches on `.key` so the
 * compiler enforces exhaustive handling. No URL/string sniffing anywhere.
 */
export type SortKey =
  | { key: 'status' }
  | { key: 'alpha'; dir: 'asc' | 'desc' }
  | { key: 'recency' }

export const STORAGE_KEY = 'titan.ui.jobsSort'
export const DEFAULT_SORT: SortKey = { key: 'status' }

/**
 * Sort priority bucket for the default status-first sort.
 *
 * <p>SREs scanning for fleet-red rows expect FAILED first; in-flight builds
 * sit next so an active incident is one glance away; never-run rows show
 * before the green wall so freshly-created jobs are visible; SUCCESS is the
 * happy-path baseline and sinks last. Unknown statuses default to the
 * RUNNING bucket so the page never throws when the server adds a new code.
 */
export function statusSortPriority(status: string | undefined): number {
  if (status === undefined) return 2 // never-run / no build row
  switch (status) {
    case 'FAILED':
    case 'FAILURE':
    case 'ABORTED':
    case 'CANCELLED':
      return 0
    case 'UNSTABLE':
      return 0
    case 'RUNNING':
    case 'QUEUED':
      return 1
    case 'SUCCESS':
      return 3
    default:
      return 1
  }
}

/**
 * Split sorted rows into [favorites, rest] preserving the input order within
 * each group. Exported for unit tests.
 */
export function partitionFavorites(
  items: readonly JobDto[],
  favoriteIds: ReadonlySet<number>,
): { favorites: JobDto[]; rest: JobDto[] } {
  const favorites: JobDto[] = []
  const rest: JobDto[] = []
  for (const j of items) {
    if (favoriteIds.has(j.id)) favorites.push(j)
    else rest.push(j)
  }
  return { favorites, rest }
}

export function isValidSort(v: unknown): v is SortKey {
  if (typeof v !== 'object' || v === null) return false
  const obj = v as Record<string, unknown>
  if (obj.key === 'status' || obj.key === 'recency') return true
  if (obj.key === 'alpha' && (obj.dir === 'asc' || obj.dir === 'desc')) return true
  return false
}

export function loadSort(): SortKey {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return DEFAULT_SORT
    const parsed: unknown = JSON.parse(raw)
    return isValidSort(parsed) ? parsed : DEFAULT_SORT
  } catch {
    return DEFAULT_SORT
  }
}

export function saveSort(s: SortKey): void {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(s))
  } catch {
    /* localStorage unavailable (private mode, quota) — silently ignore. */
  }
}

/**
 * Pure sort — exported for unit tests. Stable across the alphabetical
 * tie-break because the API already returns rows alphabetically and
 * Array.prototype.sort is stable since ES2019.
 */
export function sortJobs(items: readonly JobDto[], sort: SortKey): JobDto[] {
  const copy = items.slice()
  switch (sort.key) {
    case 'alpha': {
      copy.sort((a, b) => {
        const cmp = a.displayName.localeCompare(b.displayName)
        return sort.dir === 'asc' ? cmp : -cmp
      })
      return copy
    }
    case 'recency': {
      copy.sort((a, b) => {
        const af = a.lastBuild?.finishedAt ?? ''
        const bf = b.lastBuild?.finishedAt ?? ''
        if (af === bf) return a.displayName.localeCompare(b.displayName)
        // Empty strings sort last (never-run rows have no recency signal).
        if (!af) return 1
        if (!bf) return -1
        // DESC — newest first.
        return af < bf ? 1 : -1
      })
      return copy
    }
    case 'status': {
      copy.sort((a, b) => {
        const pa = statusSortPriority(a.lastBuild?.status)
        const pb = statusSortPriority(b.lastBuild?.status)
        if (pa !== pb) return pa - pb
        // Within the FAILED bucket: most-recent failure first — the active
        // incident wins the eyeballs.
        if (pa === 0) {
          const af = a.lastBuild?.finishedAt ?? ''
          const bf = b.lastBuild?.finishedAt ?? ''
          if (af !== bf) {
            if (!af) return 1
            if (!bf) return -1
            return af < bf ? 1 : -1
          }
        }
        // Everywhere else: alphabetical by displayName.
        return a.displayName.localeCompare(b.displayName)
      })
      return copy
    }
  }
}

export function ariaSortForName(sort: SortKey): 'ascending' | 'descending' | 'none' {
  if (sort.key !== 'alpha') return 'none'
  return sort.dir === 'asc' ? 'ascending' : 'descending'
}

export function ariaSortForLastBuild(
  sort: SortKey,
): 'ascending' | 'descending' | 'none' {
  if (sort.key === 'recency') return 'descending'
  if (sort.key === 'status') return 'descending'
  return 'none'
}
