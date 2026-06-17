/**
 * Workers — operator fleet list (closes #580; consolidated to the shared
 * list pattern in #1190).
 *
 * Layout:
 *   - shared PageContainer + PageHeader frame (UX chart H1)
 *   - 3 big-number metric tiles (Active workers, Running tasks, Idle workers)
 *   - one DataTable per pool (the shared list primitive — H2), with columns:
 *       worker (name + id) | pool | status | last heartbeat | running | actions
 *
 * All four view states (loading / empty / error / populated) are rendered
 * through DataTable so they match /queue, /approvals and /audit exactly (H4).
 * Status uses the shared <StatusCell> + workerStatusView map (H8) — never raw
 * coloured text.
 *
 * Data: GET /api/v1/workers (5s poll via useWorkers).
 * Mutations: useDrainWorker / useUndrainWorker. "Reset" = Undrain for DRAINING
 * rows; on ONLINE/BUSY rows the only action is Drain.
 */
import { createFileRoute, Link } from '@tanstack/react-router'
import { useMemo, useState } from 'react'
import { useDrainWorker, useUndrainWorker, useWorkers } from '@/api/hooks'
import type { WorkerDto } from '@/api/types'
import { ApiError } from '@/api/types'
import { Button } from '@/components/ui/Button'
import { DataTable, type DataTableColumn } from '@/components/ui/DataTable'
import { PageContainer, PageHeader } from '@/components/ui/Page'
import { Skeleton } from '@/components/ui/Skeleton'
import { StatusCell, RelativeTime } from '@/components/StatusCell'
import { workerStatusView } from '@/lib/listStatus'
import { useDocumentTitle } from '@/lib/useDocumentTitle'

export const Route = createFileRoute('/workers')({
  component: WorkersPage,
})

function errMessage(error: unknown): string {
  return error instanceof ApiError
    ? error.problem.detail ?? error.problem.title
    : error instanceof Error
      ? error.message
      : String(error)
}

function WorkersPage() {
  useDocumentTitle('Workers')
  const { data, isLoading, error, refetch } = useWorkers()

  const items: WorkerDto[] = data?.items ?? []

  // Metric tiles. "Active workers" = anything not OFFLINE / DRAINING — matches
  // the Overview KPI definition.
  const activeWorkers = items.filter(
    (w) => w.state !== 'OFFLINE' && w.state !== 'DRAINING',
  ).length
  const runningTasks = items.reduce((sum, w) => sum + (w.currentTasks ?? 0), 0)
  const idleCount = items.filter(
    (w) => w.state !== 'OFFLINE' && w.state !== 'DRAINING' && w.currentTasks === 0,
  ).length

  // Group by pool — pool ASC, within-pool name ASC. OFFLINE workers are hidden
  // by default (they accumulate on dev rigs after restarts); a toggle reveals
  // them.
  const [showOffline, setShowOffline] = useState(false)
  const offlineCount = items.filter((w) => w.state === 'OFFLINE').length
  const groups = useMemo(() => {
    const visible = showOffline ? items : items.filter((w) => w.state !== 'OFFLINE')
    const byPool = new Map<string, WorkerDto[]>()
    for (const w of visible) {
      const list = byPool.get(w.pool) ?? []
      list.push(w)
      byPool.set(w.pool, list)
    }
    return Array.from(byPool.entries())
      .sort(([a], [b]) => a.localeCompare(b))
      .map(([pool, list]) => ({
        pool,
        list: list.slice().sort((a, b) => a.name.localeCompare(b.name)),
      }))
  }, [items, showOffline])

  const columns = useWorkerColumns()
  const isEmpty = !isLoading && !error && items.length === 0

  return (
    <PageContainer>
      <PageHeader
        title="Workers"
        description="Connected workers — by pool, status, heartbeat, in-flight tasks."
        actions={
          <Link to="/workers/events" data-testid="workers-events-link">
            <Button variant="ghost" size="sm">
              Worker events
            </Button>
          </Link>
        }
      />

      <div className="metric-grid" data-testid="workers-metrics">
        <MetricTile
          label="Active workers"
          value={isLoading ? null : String(activeWorkers)}
          testid="metric-active-workers"
        />
        <MetricTile
          label="Running tasks"
          value={isLoading ? null : String(runningTasks)}
          testid="metric-running-tasks"
        />
        <MetricTile
          label="Idle workers"
          value={isLoading ? null : String(idleCount)}
          testid="metric-idle-workers"
        />
      </div>

      {/* loading / error / empty all flow through DataTable so the states are
          identical to the sibling list pages. */}
      {isLoading || error || isEmpty ? (
        <div className="card list-table-wrap">
          <DataTable<WorkerDto>
            rows={[]}
            columns={columns}
            rowKey={(w) => w.id}
            isLoading={isLoading}
            error={error ? { message: errMessage(error), onRetry: () => void refetch() } : null}
            emptyMessage={
              <span>
                No workers connected — start a worker against this controller and it
                appears here on its next heartbeat.
              </span>
            }
            testId="workers"
          />
        </div>
      ) : (
        <>
          {groups.map((group) => (
            <PoolSection key={group.pool} pool={group.pool} workers={group.list} columns={columns} />
          ))}
          {offlineCount > 0 && (
            <div className="workers-offline-toggle">
              <Button
                variant="ghost"
                size="sm"
                onClick={() => setShowOffline((v) => !v)}
                data-testid="workers-toggle-offline"
              >
                {showOffline
                  ? `Hide ${offlineCount} offline worker${offlineCount === 1 ? '' : 's'}`
                  : `Show ${offlineCount} offline worker${offlineCount === 1 ? '' : 's'}`}
              </Button>
            </div>
          )}
        </>
      )}
    </PageContainer>
  )
}

/** Column set shared across every per-pool table + the state-only table. */
function useWorkerColumns(): DataTableColumn<WorkerDto>[] {
  return useMemo(
    () => [
      {
        key: 'worker',
        header: 'Worker',
        cell: (w) => (
          <span className="cell-stack" title={w.name}>
            <span className="cell-stack-primary">{w.name}</span>
            <span className="cell-mono cell-stack-secondary">{w.id}</span>
          </span>
        ),
      },
      {
        key: 'pool',
        header: 'Pool',
        width: '140px',
        cell: (w) => <span className="cell-mono" title={w.pool}>{w.pool}</span>,
      },
      {
        key: 'status',
        header: 'Status',
        width: '130px',
        cell: (w) => {
          const view = workerStatusView(w.state, w.currentTasks)
          return <StatusCell variant={view.variant} label={view.label} data-testid={`worker-status-${w.id}`} />
        },
      },
      {
        key: 'heartbeat',
        header: 'Last heartbeat',
        width: '130px',
        cell: (w) => <span className="cell-mono"><RelativeTime iso={w.lastSeenAt} /></span>,
      },
      {
        key: 'running',
        header: 'Running',
        width: '90px',
        align: 'right',
        numeric: true,
        cell: (w) => (
          <span>
            {w.currentTasks}
            {w.maxConcurrent > 0 ? <span className="cell-muted"> / {w.maxConcurrent}</span> : null}
          </span>
        ),
      },
      {
        key: 'actions',
        header: '',
        width: '110px',
        align: 'right',
        cell: (w) => <WorkerActions worker={w} />,
      },
    ],
    [],
  )
}

function PoolSection({
  pool,
  workers,
  columns,
}: {
  pool: string
  workers: WorkerDto[]
  columns: DataTableColumn<WorkerDto>[]
}) {
  return (
    <div className="card list-table-wrap pool-section" data-testid={`pool-section-${pool}`}>
      <div className="card-header">
        <h3 className="card-title">{pool}</h3>
        <span className="badge" style={{ marginLeft: 'auto' }}>
          {workers.length} worker{workers.length === 1 ? '' : 's'}
        </span>
      </div>
      <DataTable<WorkerDto>
        rows={workers}
        columns={columns}
        rowKey={(w) => w.id}
        rowTestId={(w) => `worker-row-${w.id}`}
        testId={`workers-${pool}`}
      />
    </div>
  )
}

function WorkerActions({ worker }: { worker: WorkerDto }) {
  const drainMut = useDrainWorker()
  const undrainMut = useUndrainWorker()
  if (worker.state === 'OFFLINE') {
    return <span className="cell-muted">—</span>
  }
  if (worker.state === 'DRAINING') {
    return (
      <Button
        variant="ghost"
        size="sm"
        onClick={() => undrainMut.mutate(worker.id)}
        disabled={undrainMut.isPending}
        data-testid={`worker-reset-${worker.id}`}
        aria-label={`Reset ${worker.name}`}
      >
        {undrainMut.isPending ? 'Resetting…' : 'Reset'}
      </Button>
    )
  }
  return (
    <Button
      variant="ghost"
      size="sm"
      onClick={() => drainMut.mutate(worker.id)}
      disabled={drainMut.isPending}
      data-testid={`worker-drain-${worker.id}`}
      aria-label={`Drain ${worker.name}`}
    >
      {drainMut.isPending ? 'Draining…' : 'Drain'}
    </Button>
  )
}

function MetricTile({
  label,
  value,
  testid,
}: {
  label: string
  value: string | null
  testid: string
}) {
  return (
    <div className="metric" data-testid={testid}>
      <div className="metric-label">{label}</div>
      <div className="metric-value">
        {value === null ? <Skeleton style={{ height: 24, width: 80 }} /> : value}
      </div>
    </div>
  )
}
