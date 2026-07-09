/**
 * /builds — the calm editorial pivot (doc 64 grounded).
 *
 * <p>URL state is intentionally minimal:
 *   <ul>
 *     <li>{@code ?tab=all|running|failed|mine} — default {@code all}.
 *     <li>{@code ?q=<text>} — free-text over title / branch / sha / build#.
 *     <li>{@code ?page=<n>} — 0-indexed page cursor.
 *   </ul>
 *
 * <p>Ticket #1071 — this file is the SLIM container. The four rendering
 * concerns (filter bar / stats strip / table / pagination) live under
 * {@code components/builds/*} and receive resolved data via props only.
 * All TanStack Query usage stays here so the children stay pure +
 * snapshot-testable.
 */
import { useEffect, useMemo, useState } from 'react'
import { createFileRoute, Link, useNavigate, useSearch } from '@tanstack/react-router'
import { Search } from 'lucide-react'
import {
  useCurrentUser,
  useJobs,
  useFilteredBuilds,
  type BuildsFilterQuery,
} from '@/api/hooks'
import {
  ApiError,
  TERMINAL_STATUSES,
  type BuildStatus,
  type TriggerMetaDto,
} from '@/api/types'
import { useTweaks } from '@/components/TweaksPanel'
import { useDocumentTitle } from '@/lib/useDocumentTitle'
import { useTickWhileActive } from '@/lib/useTickWhileActive'
import { type BuildRowItem } from '@/components/builds/BuildRow'
import { BuildsFilterBar } from '@/components/builds/BuildsFilterBar'
import { BuildsStatsStrip, type BuildsTab } from '@/components/builds/BuildsStatsStrip'
import { BuildsSummary } from '@/components/builds/BuildsSummary'
import { BuildsTable } from '@/components/builds/BuildsTable'
import { BuildsPagination } from '@/components/builds/BuildsPagination'
import { BuildsEmptyLine } from '@/components/builds/BuildsEmptyLine'
import { PageContainer, PageHeader } from '@/components/ui/Page'
import { partitionBuilds } from './partitionBuilds'

// ── URL search-param schema ──────────────────────────────────────────────────

const PAGE_SIZE = 100

interface BuildsSearch {
  tab: BuildsTab
  q: string
  /** 0-indexed page cursor. Optional in URL — defaults to 0 in the validator. */
  page?: number
}

const DEFAULT_SEARCH: BuildsSearch = { tab: 'all', q: '', page: 0 }

function isTab(s: unknown): s is BuildsTab {
  return s === 'all' || s === 'running' || s === 'failed' || s === 'mine'
}

function validateSearch(raw: Record<string, unknown>): BuildsSearch {
  const tab = isTab(raw.tab) ? raw.tab : DEFAULT_SEARCH.tab
  const q = typeof raw.q === 'string' ? raw.q : ''
  const pageRaw = typeof raw.page === 'number' ? raw.page : Number(raw.page)
  const page = Number.isFinite(pageRaw) && pageRaw >= 0 ? Math.floor(pageRaw) : 0
  return { tab, q, page }
}

export const Route = createFileRoute('/builds/')({
  component: BuildsPage,
  validateSearch,
})

// ── Local Build shape (mirror of the DTO subset this surface renders) ───────

interface Build {
  id: number
  jobId: number
  buildNumber: number
  status: BuildStatus
  durationMs: number | null
  startedAt: string | null
  finishedAt: string | null
  queuedAt: string | null
  triggeredBy: string | null
  triggerType: string | null
  triggerMeta?: TriggerMetaDto | null
  displayName?: string | null
}

// ── Helpers ─────────────────────────────────────────────────────────────────

function errorMessage(err: unknown): string {
  if (err instanceof ApiError) {
    if (err.status >= 500) return 'Server error — please retry.'
    return err.problem.detail ?? err.message
  }
  return 'Failed to load data.'
}

function tabToFilter(tab: BuildsTab, mineSubject: string | null): Partial<BuildsFilterQuery> {
  switch (tab) {
    case 'all':
      return {}
    case 'running':
      return { status: ['RUNNING', 'QUEUED'] }
    case 'failed':
      return { status: ['FAILED', 'ABORTED'] }
    case 'mine':
      return mineSubject ? { triggeredBy: mineSubject } : {}
  }
}

/** Free-text match across title / branch / sha / build#. */
function clientFilter(b: BuildRowItem, q: string): boolean {
  if (q.trim() === '') return true
  const needle = q.trim().toLowerCase()
  const fields: Array<string | null | undefined> = [
    b.jobName,
    b.displayName,
    String(b.buildNumber),
    b.triggerMeta?.branch,
    b.triggerMeta?.commitSha,
  ]
  return fields.some((f) => typeof f === 'string' && f.toLowerCase().includes(needle))
}

// ── Page ────────────────────────────────────────────────────────────────────

function BuildsPage() {
  useDocumentTitle('Builds')
  const search = useSearch({ from: '/builds/' })
  const navigate = useNavigate({ from: '/builds/' })
  const currentUser = useCurrentUser()
  const [tweaks] = useTweaks()

  // Debounce the search input: typing 5 chars → 1 URL write.
  const [searchInput, setSearchInput] = useState(search.q)
  useEffect(() => {
    setSearchInput(search.q)
  }, [search.q])
  useEffect(() => {
    if (searchInput === search.q) return
    const t = setTimeout(() => {
      void navigate({
        search: (prev) => ({ ...prev, q: searchInput, page: 0 }),
        replace: true,
      })
    }, 300)
    return () => clearTimeout(t)
  }, [searchInput, search.q, navigate])

  const mineSubject = currentUser?.name ?? null

  const filterQuery: BuildsFilterQuery = useMemo(() => {
    const base: BuildsFilterQuery = {
      search: search.q || null,
      offset: (search.page ?? 0) * PAGE_SIZE,
      limit: PAGE_SIZE,
    }
    return { ...base, ...tabToFilter(search.tab, mineSubject) }
  }, [search.tab, search.q, search.page, mineSubject])

  // Per-tab count fetches — small queries (limit=1) so the tab strip + summary
  // strip carry honest totals rather than guessing from the currently-
  // paginated slice.
  // Each count query carries its own isError + refetch: a count can fail
  // independently of the main builds/jobs fetch (a transient 5xx on one cheap
  // query). When that happens the summary tile renders a neutral dash rather
  // than a permanent skeleton — see BuildsSummary (H4: no infinite skeleton).
  const { data: allCount } = useFilteredBuilds({ limit: 1, offset: 0 })
  const {
    data: runningCount,
    isError: runningCountError,
    refetch: refetchRunningCount,
  } = useFilteredBuilds({
    limit: 1,
    offset: 0,
    status: ['RUNNING', 'QUEUED'],
  })
  const {
    data: failedCount,
    isError: failedCountError,
    refetch: refetchFailedCount,
  } = useFilteredBuilds({
    limit: 1,
    offset: 0,
    status: ['FAILED', 'ABORTED'],
  })
  // SUCCESS count drives the "Passing" summary tile — fetched the same cheap
  // way as the other counts (limit=1, honest server-side total).
  const {
    data: passingCount,
    isError: passingCountError,
    refetch: refetchPassingCount,
  } = useFilteredBuilds({
    limit: 1,
    offset: 0,
    status: ['SUCCESS'],
  })
  const { data: mineCount } = useFilteredBuilds(
    mineSubject
      ? { limit: 1, offset: 0, triggeredBy: mineSubject }
      : { limit: 1, offset: 0 },
  )

  const {
    data: jobsPage,
    isLoading: jobsLoading,
    isError: jobsError,
    error: jobsErr,
    refetch: refetchJobs,
  } = useJobs()
  const {
    data: buildsPage,
    isLoading: buildsLoading,
    isError: buildsError,
    error: buildsErr,
    refetch: refetchBuilds,
  } = useFilteredBuilds(filterQuery)

  // Live-tick while any row is non-terminal. Hook MUST run unconditionally —
  // computed pre-filter so the dependency is stable across the early returns.
  const hasInflight = (buildsPage?.items ?? []).some(
    (b) => !TERMINAL_STATUSES.has(b.status as BuildStatus),
  )
  useTickWhileActive(hasInflight)

  const isLoading = jobsLoading || buildsLoading

  // Single dominant primary action (UX chart H5) — builds are triggered from a
  // job, so "New build" routes to the Jobs page. Reused across the error frame
  // and the populated frame so the header is identical in every state (H8).
  const primaryAction = (
    <Link to="/jobs" className="btn btn-primary" data-testid="builds-new-build">
      New build
    </Link>
  )

  // Error degrades to a readable, framed message + retry — NOT a blank page or
  // an endless skeleton (UX chart H4; adversarial #1187). The header stays so
  // the operator never lands on a marooned error string.
  if (jobsError || buildsError) {
    return (
      <PageContainer width="wide">
        <PageHeader
          title="Builds"
          description="Pipeline runs across your jobs."
          actions={primaryAction}
        />
        {/*
          The framed error panel carries the stable `data-builds-error="jobs|builds"`
          marker + `role="alert"` that the golden-path auth e2e spec (#1200,
          e2e/specs/v3/56-golden-path-auth.spec.ts) reads via `[data-builds-error]`
          to distinguish a real data view from an error boundary. Jobs is checked
          first, so a both-reject case reports "jobs" (the e2e + unit contract).
        */}
        <div
          className="calm-empty"
          data-testid="builds-error"
          data-builds-error={jobsError ? 'jobs' : 'builds'}
          role="alert"
        >
          {errorMessage(jobsError ? jobsErr : buildsErr)}
          {' — '}
          <button
            type="button"
            className="calm-link"
            data-testid="builds-error-retry"
            onClick={() => {
              if (jobsError) void refetchJobs()
              if (buildsError) void refetchBuilds()
              // Retry the cheap count queries too — a full retry should refresh
              // every region, not just the table (critic #1197 sev2).
              if (passingCountError) void refetchPassingCount()
              if (failedCountError) void refetchFailedCount()
              if (runningCountError) void refetchRunningCount()
            }}
          >
            Retry
          </button>
        </div>
      </PageContainer>
    )
  }

  const jobNameById = new Map((jobsPage?.items ?? []).map((j) => [j.id, j.displayName]))
  const rawBuilds = (buildsPage?.items ?? []) as unknown as Build[]
  const visibleBuilds: BuildRowItem[] = rawBuilds.map((b) => ({
    ...b,
    jobName: jobNameById.get(b.jobId) ?? `job #${b.jobId}`,
  }))

  const matched = visibleBuilds.filter((b) => clientFilter(b, search.q))
  const { inFlight, history } = partitionBuilds(matched)
  const showSplit = search.tab === 'all' && inFlight.length > 0

  function setTab(next: BuildsTab) {
    void navigate({ search: (prev) => ({ ...prev, tab: next, page: 0 }), replace: true })
  }
  function setPage(next: number) {
    void navigate({ search: (prev) => ({ ...prev, page: next }), replace: true })
  }
  function resetAll() {
    setSearchInput('')
    void navigate({ search: () => ({ ...DEFAULT_SEARCH }), replace: true })
  }

  const total = buildsPage?.total ?? matched.length

  return (
    <PageContainer width="wide">
      <PageHeader
        title="Builds"
        description={
          isLoading ? (
            <>&nbsp;</>
          ) : (
            // #77 (defect-6 no-rot): the header's leading count MUST be the
            // builds total — a bare jobs count as the page's only numeral
            // reintroduces the jobs/builds-count confusion (#825 defect 6).
            // The em-dash covers the brief window where the cheap all-count
            // query is still in flight (same neutral-dash rule as BuildsSummary).
            <>
              <span className="mono">{allCount?.total ?? '—'}</span> build
              {allCount?.total === 1 ? '' : 's'} across{' '}
              <span className="mono">{jobsPage?.total ?? 0}</span> job
              {(jobsPage?.total ?? 0) === 1 ? '' : 's'}
            </>
          )
        }
        actions={primaryAction}
      />

      <BuildsSummary
        isLoading={isLoading}
        counts={{
          passing: passingCount?.total,
          failed: failedCount?.total,
          running: runningCount?.total,
        }}
        errors={{
          passing: passingCountError,
          failed: failedCountError,
          running: runningCountError,
        }}
      />

      <div className="calm-toolbar" data-testid="builds-filter-strip">
        {isLoading ? (
          <div className="cl-search" aria-hidden>
            <Search size={14} className="cl-search-icon" aria-hidden />
            <input type="search" placeholder="Search title, branch, commit…" disabled />
          </div>
        ) : (
          <>
            <BuildsFilterBar
              value={{ q: searchInput }}
              onChange={(next) => setSearchInput(next.q)}
            />
            <BuildsStatsStrip
              activeTab={search.tab}
              counts={{
                all: allCount?.total,
                running: runningCount?.total,
                failed: failedCount?.total,
                mine: mineSubject ? mineCount?.total : undefined,
              }}
              showMine={mineSubject !== null}
              onTabChange={setTab}
            />
          </>
        )}
      </div>

      <BuildsTable
        builds={matched}
        isLoading={isLoading}
        timeFormat={tweaks.timeFormat}
        split={showSplit ? { inFlight, history } : null}
        emptySlot={<BuildsEmptyLine query={search.q} tab={search.tab} onClear={resetAll} />}
      />

      {!isLoading && matched.length > 0 && (
        <BuildsPagination
          shown={matched.length}
          total={total}
          page={search.page ?? 0}
          pageSize={PAGE_SIZE}
          onPageChange={setPage}
        />
      )}
    </PageContainer>
  )
}

