/**
 * Pipelines index — table of configured pipelines (design 66 vocabulary).
 *
 * UI vocabulary note: this surface used to be /jobs. The backend keeps
 * `/api/v1/jobs` + `JobDto` + `useJobs` — the rename is UI-only. A redirect
 * shim at /jobs forwards bookmarks.
 *
 * Issue #544: default sort is FAILED-first. When the fleet goes red, an SRE
 * landing on /pipelines must see red rows above the fold. Sort modes, their
 * persistence, and the favorites partition live in
 * `@/components/jobs/jobsSort`.
 *
 * Issue #1070: this route is intentionally a thin data+layout shell. It owns
 * the TanStack Query hooks + sort state and passes data down to presentational
 * components under `@/components/jobs/`:
 *   - {@link JobsTable}      — sortable header, favorites/rest split, rows,
 *                              per-row quick-trigger, and the loading skeleton.
 *   - {@link JobsEmptyState} — zero-pipelines state.
 *   - {@link StarErrorToast} / {@link NewJobErrorToast} — transient banners.
 *   - {@link NewJobDialog}   — already a standalone component; wired up here.
 *
 * {@link sortJobs} and {@link SortKey} are re-exported below so the existing
 * `src/test/jobs-sort.test.tsx` import path stays valid.
 */
import { useEffect, useMemo, useState } from 'react'
import { createFileRoute } from '@tanstack/react-router'
import { Plus } from 'lucide-react'
import { useJobs, useJobsRecentBuilds, useStarredJobs } from '@/api/hooks'
import { useDocumentTitle } from '@/lib/useDocumentTitle'
import { NewJobDialog } from '@/components/NewJobDialog'
import { JobsTable } from '@/components/jobs/JobsTable'
import { JobsSkeleton } from '@/components/jobs/JobsTableSkeleton'
import { JobsEmptyState } from '@/components/jobs/JobsEmptyState'
import { NewJobErrorToast, StarErrorToast } from '@/components/jobs/JobsToasts'
import { PageContainer, PageHeader } from '@/components/ui/Page'
import {
  loadSort,
  partitionFavorites,
  saveSort,
  sortJobs,
  type SortKey,
} from '@/components/jobs/jobsSort'
import { ApiError } from '@/api/types'

// Re-exported for backward compatibility with src/test/jobs-sort.test.tsx,
// which imports the pure sort helpers from this route path.
export { sortJobs, partitionFavorites }
export type { SortKey }

export const Route = createFileRoute('/pipelines/')({
  component: PipelinesPage,
})

function errorMessage(err: unknown): string {
  if (err instanceof ApiError) {
    if (err.status >= 500) return 'Server error — please retry.'
    return err.problem.detail ?? err.message
  }
  return 'Failed to load pipelines.'
}

function PipelinesPage() {
  useDocumentTitle('Pipelines')
  const { data, isLoading, isError, error } = useJobs()
  // Bulk-fetch recent builds for every visible row in a single round-trip (#650).
  const jobIds = useMemo(() => data?.items.map((j) => j.id) ?? [], [data])
  // 30-build window so the "Duration trend (30d)" column (#1096) gets the
  // AC-mandated last-30 sample; the status sparkline (#650) shares this fetch.
  const { data: recentBuildsMap } = useJobsRecentBuilds(jobIds, 30)
  const [dialogOpen, setDialogOpen] = useState(false)
  const [networkError, setNetworkError] = useState<string | null>(null)
  const [starError, setStarError] = useState<string | null>(null)
  const [sort, setSort] = useState<SortKey>(() => loadSort())
  // Server-backed stars (#703).
  const { data: starredJobs } = useStarredJobs()
  const favoriteSet = useMemo(
    () => new Set((starredJobs ?? []).map((j) => j.id)),
    [starredJobs],
  )

  useEffect(() => {
    saveSort(sort)
  }, [sort])

  const { favorites, rest } = useMemo(() => {
    if (!data) return { favorites: [], rest: [] }
    const sorted = sortJobs(data.items, sort)
    return partitionFavorites(sorted, favoriteSet)
  }, [data, sort, favoriteSet])

  function onClickName(): void {
    // Header click on Name toggles alphabetical asc → desc → asc …
    if (sort.key === 'alpha') {
      setSort({ key: 'alpha', dir: sort.dir === 'asc' ? 'desc' : 'asc' })
    } else {
      setSort({ key: 'alpha', dir: 'asc' })
    }
  }

  function onClickLastBuild(): void {
    // Header click on Last build toggles between recency (newest first) and the
    // default status sort.
    if (sort.key === 'recency') {
      setSort({ key: 'status' })
    } else {
      setSort({ key: 'recency' })
    }
  }

  return (
    <PageContainer width="default">
      <PageHeader
        title="Pipelines"
        description={
          data
            ? `${data.total} pipeline${data.total === 1 ? '' : 's'} configured`
            : 'Configured pipelines.'
        }
        actions={
          <button
            type="button"
            className="btn btn-sm btn-primary"
            title="Create a new job"
            data-testid="new-job-open"
            onClick={() => {
              setNetworkError(null)
              setDialogOpen(true)
            }}
          >
            <Plus size={14} aria-hidden />
            New job
          </button>
        }
      />

      <NewJobDialog
        open={dialogOpen}
        onOpenChange={setDialogOpen}
        onNetworkError={setNetworkError}
      />

      {starError ? (
        <StarErrorToast message={starError} onDismiss={() => setStarError(null)} />
      ) : null}

      {networkError ? (
        <NewJobErrorToast
          message={networkError}
          onRetry={() => {
            setNetworkError(null)
            setDialogOpen(true)
          }}
        />
      ) : null}

      {isError ? (
        <div className="card empty" style={{ padding: 24 }}>
          <p style={{ fontSize: 13, color: 'var(--fail)' }}>{errorMessage(error)}</p>
        </div>
      ) : isLoading ? (
        <JobsSkeleton />
      ) : !data || data.items.length === 0 ? (
        <JobsEmptyState />
      ) : (
        <JobsTable
          favorites={favorites}
          rest={rest}
          recentBuildsMap={recentBuildsMap}
          sort={sort}
          onClickName={onClickName}
          onClickLastBuild={onClickLastBuild}
          onStarError={setStarError}
        />
      )}
    </PageContainer>
  )
}
