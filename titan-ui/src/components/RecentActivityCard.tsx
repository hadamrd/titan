/**
 * RecentActivityCard — "Recent activity" panel for the /queue empty state
 * (#523).
 *
 * <p>Migrated to the shared DataTable primitive in #850 (design 64). The
 * shadcn Table primitives are gone; cursor-style pagination, breathing
 * skeleton rows, and hairline-prefix error states all come from the
 * primitive. Existing testids (`recent-activity-card`, `recent-row-{id}`,
 * `recent-activity-empty`, `recent-activity-error`) are preserved so the
 * #523 adversarial suite and any Playwright assertions don't churn.
 */
import { Link } from '@tanstack/react-router'
import { useMemo } from 'react'
import { useRecentTasks } from '@/api/hooks'
import { ApiError, type RecentTaskDto } from '@/api/types'
import { StatusBadge } from '@/components/StatusBadge'
import { DataTable, type DataTableColumn } from '@/components/ui/DataTable'

interface RecentActivityCardProps {
  /** Max rows; backend hard-caps at 100. */
  limit?: number
}

const PAGE_SIZE = 10

export function RecentActivityCard({ limit = 20 }: RecentActivityCardProps) {
  const { data, isLoading, error, refetch } = useRecentTasks({ limit })
  const rows = useMemo<readonly RecentTaskDto[]>(() => data ?? [], [data])

  const columns: DataTableColumn<RecentTaskDto>[] = useMemo(() => [
    { key: 'when', header: 'When', width: '110px', numeric: true,
      cell: (r) => formatRelative(r.completedAt) },
    { key: 'type', header: 'Type', width: '150px', numeric: true,
      cell: (r) => r.type },
    { key: 'status', header: 'Status', width: '140px',
      cell: (r) => <StatusBadge status={r.status} /> },
    { key: 'build', header: 'Build', cell: (r) => (
      r.buildId != null
        ? <Link to="/builds/$buildId" params={{ buildId: String(r.buildId) }}
            style={{ fontWeight: 500, color: 'var(--fg)' }}>
            {r.jobName ?? `build #${r.buildId}`}
            {' '}<span style={{ fontSize: 11, color: 'var(--fg-dim)', fontFamily: 'var(--font-mono)' }}>task #{r.taskId}</span>
          </Link>
        : <span style={{ color: 'var(--fg-dim)' }}>(orchestration task) <span style={{ fontSize: 11, fontFamily: 'var(--font-mono)' }}>task #{r.taskId}</span></span>
    ) },
    { key: 'node', header: 'Node', width: '160px', numeric: true,
      cell: (r) => r.nodeId ?? '—' },
    { key: 'completed', header: 'Completed', width: '180px', numeric: true,
      cell: (r) => formatAbsolute(r.completedAt) },
  ], [])

  const errMsg = error instanceof ApiError
    ? error.problem.detail ?? error.problem.title
    : error instanceof Error ? error.message : error ? String(error) : null

  return (
    <div data-testid="recent-activity-card" style={{ marginTop: 16 }}>
      <div style={{ padding: '12px 12px 8px' }}>
        <h2 style={{ margin: 0, fontSize: 13, fontWeight: 600, color: 'var(--fg)' }}>
          Recent activity
        </h2>
        <p style={{ margin: '2px 0 0', fontSize: 11.5, color: 'var(--fg-dim)' }}>
          Last {rows.length || limit} tasks that finished — pulled from{' '}
          <code style={{ fontFamily: 'var(--font-mono)' }}>task_archive</code>.
        </p>
      </div>
      <DataTable<RecentTaskDto>
        rows={rows}
        columns={columns}
        rowKey={(r) => r.taskId}
        rowTestId={(r) => `recent-row-${r.taskId}`}
        isLoading={isLoading}
        error={errMsg ? { message: errMsg, onRetry: () => void refetch() } : null}
        emptyMessage="No recent activity yet. Once a build runs and its tasks complete, they will show up here."
        routeSearchKey="queue_recent"
        pageSize={PAGE_SIZE}
        testId="recent-activity"
      />
      {errMsg && <span data-testid="recent-activity-error" style={{ display: 'none' }}>{errMsg}</span>}
    </div>
  )
}

function formatRelative(iso: string | null | undefined): string {
  if (iso === null || iso === undefined) return '—'
  const ts = Date.parse(iso)
  if (Number.isNaN(ts)) return '—'
  const delta = Date.now() - ts
  if (delta < 0) return 'just now'
  const s = Math.floor(delta / 1000)
  if (s < 5) return 'just now'
  if (s < 60) return `${s}s ago`
  const m = Math.floor(s / 60)
  if (m < 60) return `${m}m ago`
  const h = Math.floor(m / 60)
  if (h < 24) return `${h}h ago`
  const d = Math.floor(h / 24)
  return `${d}d ago`
}

function formatAbsolute(iso: string | null | undefined): string {
  if (iso === null || iso === undefined) return '—'
  try {
    const d = new Date(iso)
    return d.toLocaleString(undefined, {
      year: 'numeric', month: 'short', day: '2-digit',
      hour: '2-digit', minute: '2-digit', second: '2-digit',
    })
  } catch {
    return iso
  }
}
