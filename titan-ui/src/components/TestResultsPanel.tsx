/**
 * TestResultsPanel — v3-stack restyle (#559).
 *
 * Dense table for parsed JUnit results, sourced from
 * GET /api/v1/builds/{buildId}/tests (see {@link useTests}).
 *
 * v3 design floor (docs/design/build-detail-v3-mockups/.../v3-stack.html):
 *   - Geist + oklch tokens (var(--surface), var(--border), var(--fg-muted))
 *   - .bd-pill .ok / .fail / .skip for status pills (matches DAG + tree rail)
 *   - .filter-chip / .filter-chip.on for multi-select filter row
 *   - Failed-row expansion shows the failure body in a .term-style block
 *     (oklch(0.12 0 0) bg + mono Geist) so the visual cue echoes Console.
 *   - FAILED rows sort first — the SRE-default reading order.
 *
 * Filter state is a discriminated set { all|failed|passed|skipped } combined
 * with multi-select semantics: clicking the same chip with nothing else
 * selected re-opens "all"; clicking different chips toggles them.
 */
import { useMemo, useState } from 'react'
import { ApiError, type TestRowDto, type TestStatus } from '@/api/types'
import { useTests } from '@/api/hooks'
import { Skeleton } from '@/components/ui/Skeleton'
import { cn } from '@/lib/utils'

type FilterKey = 'failed' | 'passed' | 'skipped'

type SortKey = 'failed-first' | 'natural'

const PILL_CLASS: Record<TestStatus, string> = {
  PASSED: 'ok',
  FAILED: 'fail',
  SKIPPED: 'skip',
}

const PILL_LABEL: Record<TestStatus, string> = {
  PASSED: 'PASS',
  FAILED: 'FAIL',
  SKIPPED: 'SKIP',
}

const STATUS_ORDER: Record<TestStatus, number> = {
  FAILED: 0,
  PASSED: 1,
  SKIPPED: 2,
}

function formatDuration(ms: number): string {
  if (ms < 1) return '<1ms'
  if (ms < 1000) return `${ms}ms`
  const s = ms / 1000
  if (s < 60) return `${s.toFixed(s < 10 ? 2 : 1)}s`
  const m = Math.floor(s / 60)
  return `${m}m ${Math.floor(s % 60)}s`
}

function errorMessage(err: unknown): string {
  if (err instanceof ApiError) {
    if (err.status === 404) return 'Build not found.'
    return err.problem.detail ?? err.message
  }
  return 'Failed to load test results.'
}

function sortRows(rows: TestRowDto[], sortKey: SortKey): TestRowDto[] {
  if (sortKey === 'natural') return rows
  // FAILED first, then PASSED, then SKIPPED — stable within each group.
  return [...rows].sort((a, b) => STATUS_ORDER[a.status] - STATUS_ORDER[b.status])
}

export interface TestResultsPanelProps {
  buildId: number
}

export function TestResultsPanel({ buildId }: TestResultsPanelProps) {
  const { data, isLoading, isError, error } = useTests(buildId)
  // Multi-select filter set; empty set = "All".
  const [filterSet, setFilterSet] = useState<ReadonlySet<FilterKey>>(new Set())
  const [expanded, setExpanded] = useState<Record<number, boolean>>({})

  const sortedRows = useMemo(() => {
    if (!data) return []
    return sortRows(data.items, 'failed-first')
  }, [data])

  if (isLoading) return <PanelSkeleton />
  if (isError || !data) {
    return (
      <div className="empty" style={{ color: 'var(--fail)' }}>
        {errorMessage(error)}
      </div>
    )
  }

  if (data.total === 0) {
    return (
      <div className="empty" data-testid="tests-empty">
        No test results recorded for this build.
      </div>
    )
  }

  const { summary } = data

  const toggleFilter = (k: FilterKey) => {
    setFilterSet((prev) => {
      const next = new Set(prev)
      if (next.has(k)) next.delete(k)
      else next.add(k)
      return next
    })
  }
  const clearFilter = () => setFilterSet(new Set())

  const filtered = filterSet.size === 0
    ? sortedRows
    : sortedRows.filter((r) => filterSet.has(r.status.toLowerCase() as FilterKey))

  const toggleExpanded = (id: number) =>
    setExpanded((prev) => ({ ...prev, [id]: !prev[id] }))

  return (
    <div className="tab-pane" style={{ padding: 12 }}>
      {/* Summary header */}
      <div
        data-testid="tests-summary"
        style={{
          fontFamily: 'var(--font-mono)',
          fontSize: 12,
          color: 'var(--fg-dim)',
          marginBottom: 10,
          fontVariantNumeric: 'tabular-nums',
        }}
      >
        <span style={{ color: 'var(--fail)' }}>{summary.failed} failed</span>
        <span style={{ color: 'var(--fg-faint)', margin: '0 6px' }}>·</span>
        <span style={{ color: 'var(--ok)' }}>{summary.passed} passed</span>
        <span style={{ color: 'var(--fg-faint)', margin: '0 6px' }}>·</span>
        <span>{summary.skipped} skipped</span>
      </div>

      {/* Filter chips */}
      <div
        role="group"
        aria-label="Filter test results"
        style={{ display: 'flex', gap: 6, marginBottom: 10, flexWrap: 'wrap' }}
      >
        <FilterChip
          label="All"
          active={filterSet.size === 0}
          onClick={clearFilter}
        />
        <FilterChip
          label="Failed"
          count={summary.failed}
          active={filterSet.has('failed')}
          onClick={() => toggleFilter('failed')}
        />
        <FilterChip
          label="Passed"
          count={summary.passed}
          active={filterSet.has('passed')}
          onClick={() => toggleFilter('passed')}
        />
        <FilterChip
          label="Skipped"
          count={summary.skipped}
          active={filterSet.has('skipped')}
          onClick={() => toggleFilter('skipped')}
        />
      </div>

      {/* Table */}
      {filtered.length === 0 ? (
        <div className="empty" style={{ padding: 32 }}>
          No tests match this filter.
        </div>
      ) : (
        <div
          role="table"
          aria-label="Test results"
          data-testid="tests-table"
          style={{
            background: 'var(--surface)',
            border: '1px solid var(--border)',
            borderRadius: 'var(--r-md)',
            overflow: 'hidden',
          }}
        >
          {filtered.map((row) => (
            <TestRow
              key={row.id}
              row={row}
              expanded={!!expanded[row.id]}
              onToggle={() => toggleExpanded(row.id)}
            />
          ))}
        </div>
      )}
    </div>
  )
}

// ── Sub-components ──────────────────────────────────────────────────────────

function FilterChip({
  label,
  count,
  active,
  onClick,
}: {
  label: string
  count?: number
  active: boolean
  onClick: () => void
}) {
  return (
    <button
      type="button"
      className={cn('filter-chip', active && 'on')}
      aria-pressed={active}
      onClick={onClick}
    >
      <span>{label}</span>
      {count !== undefined && (
        <span
          style={{
            fontFamily: 'var(--font-mono)',
            fontSize: 11,
            opacity: 0.8,
            fontVariantNumeric: 'tabular-nums',
          }}
        >
          {count}
        </span>
      )}
    </button>
  )
}

function TestRow({
  row,
  expanded,
  onToggle,
}: {
  row: TestRowDto
  expanded: boolean
  onToggle: () => void
}) {
  const status = row.status
  const isFailed = status === 'FAILED'
  const panelId = `test-fail-${row.id}`

  return (
    <div
      data-testid={`test-row-${row.id}`}
      data-status={status}
      style={{ borderBottom: '1px solid var(--border)' }}
    >
      <div
        role="row"
        onClick={isFailed ? onToggle : undefined}
        style={{
          display: 'grid',
          gridTemplateColumns: '64px 1fr 72px auto',
          alignItems: 'center',
          gap: 12,
          padding: '6px 14px',
          minHeight: 32,
          cursor: isFailed ? 'pointer' : 'default',
        }}
      >
        <span className={`bd-pill ${PILL_CLASS[status]}`} aria-label={status}>
          {PILL_LABEL[status]}
        </span>
        <span
          style={{
            fontFamily: 'var(--font-mono)',
            fontSize: 12,
            overflow: 'hidden',
            textOverflow: 'ellipsis',
            whiteSpace: 'nowrap',
            color: 'var(--fg)',
          }}
          title={`${row.className}.${row.name}`}
        >
          <span style={{ color: 'var(--fg-muted)' }}>{row.className}</span>
          <span style={{ color: 'var(--fg-faint)' }}>.</span>
          {row.name}
          <span
            style={{
              marginLeft: 10,
              color: 'var(--fg-faint)',
              fontSize: 11,
            }}
          >
            {row.suite}
          </span>
        </span>
        <span
          style={{
            fontFamily: 'var(--font-mono)',
            fontSize: 12,
            color: 'var(--fg-dim)',
            textAlign: 'right',
            fontVariantNumeric: 'tabular-nums',
          }}
        >
          {formatDuration(row.durationMs)}
        </span>
        {isFailed ? (
          <button
            type="button"
            aria-expanded={expanded}
            aria-controls={panelId}
            aria-label={expanded ? 'Collapse failure details' : 'Expand failure details'}
            onClick={(e) => {
              e.stopPropagation()
              onToggle()
            }}
            style={{
              background: 'transparent',
              border: 'none',
              color: 'var(--fg-dim)',
              cursor: 'pointer',
              padding: '0 6px',
              fontFamily: 'var(--font-mono)',
              fontSize: 14,
              lineHeight: 1,
              transform: expanded ? 'rotate(90deg)' : 'none',
              transition: 'transform 0.12s',
            }}
          >
            ›
          </button>
        ) : (
          <span aria-hidden="true" />
        )}
      </div>
      {isFailed && expanded && row.failureMessage && (
        <pre
          id={panelId}
          data-testid={`test-fail-body-${row.id}`}
          style={{
            margin: 0,
            padding: '10px 14px 12px 16px',
            background: 'oklch(0.12 0 0)',
            color: 'oklch(0.88 0 0)',
            borderLeft: '2px solid var(--fail)',
            fontFamily: 'var(--font-mono)',
            fontSize: 12,
            whiteSpace: 'pre-wrap',
            wordBreak: 'break-word',
          }}
        >
          {row.failureMessage}
        </pre>
      )}
    </div>
  )
}

function PanelSkeleton() {
  return (
    <div className="tab-pane" style={{ padding: 12 }}>
      <Skeleton style={{ height: 14, width: 220, marginBottom: 10 }} />
      <div style={{ display: 'flex', gap: 6, marginBottom: 10 }}>
        {[0, 1, 2, 3].map((i) => (
          <Skeleton key={i} style={{ height: 22, width: 80, borderRadius: 999 }} />
        ))}
      </div>
      <div
        style={{
          background: 'var(--surface)',
          border: '1px solid var(--border)',
          borderRadius: 'var(--r-md)',
          overflow: 'hidden',
        }}
      >
        {Array.from({ length: 6 }).map((_, i) => (
          <div
            key={i}
            style={{
              display: 'grid',
              gridTemplateColumns: '64px 1fr 72px auto',
              alignItems: 'center',
              gap: 12,
              padding: '6px 14px',
              borderBottom: '1px solid var(--border)',
              minHeight: 32,
            }}
          >
            <Skeleton style={{ height: 16, width: 44, borderRadius: 999 }} />
            <Skeleton style={{ height: 12, width: '70%' }} />
            <Skeleton style={{ height: 12, width: 50, justifySelf: 'end' }} />
            <span />
          </div>
        ))}
      </div>
    </div>
  )
}
