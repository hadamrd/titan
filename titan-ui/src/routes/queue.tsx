/**
 * Queue — tasks waiting for a worker (GET /api/v1/queue; consolidated to the
 * shared list pattern in #1190).
 *
 * Frame: shared PageContainer + PageHeader (UX chart H1). Grid: the shared
 * DataTable primitive (H2) with its optional drag-to-reorder capability —
 * preserves the #379 reorder UX without a bespoke `.queue-row` grid. Columns:
 *   drag · priority (Badge) · build identity · age (StatusDot pressure) · labels.
 *
 * All four states (loading / empty / error / populated) come from DataTable so
 * they match /workers, /approvals, /audit exactly (H4). Status treatment uses
 * the shared listStatus map + <StatusCell> (H8) — no inline-coloured spans.
 *
 * Reorder (#379): drop is optimistic — local order updates instantly; the
 * "Save order" button (the single primary action, H5) calls POST
 * /api/v1/queue/reorder. On error a banner shows and we revert to the server
 * snapshot. Reorder is disabled while a save is in-flight. Polling: 5s.
 */
import { createFileRoute, Link } from '@tanstack/react-router'
import { useEffect, useMemo, useState } from 'react'
import { useDrainQueue, useQueue, useReorderQueue } from '@/api/hooks'
import type { QueueEntryDto } from '@/api/types'
import { ApiError } from '@/api/types'
import { RecentActivityCard } from '@/components/RecentActivityCard'
import { StatusCell } from '@/components/StatusCell'
import { Badge } from '@/components/ui/Badge'
import { Button } from '@/components/ui/Button'
import { ConfirmDialog } from '@/components/ui/ConfirmDialog'
import { DataTable, type DataTableColumn } from '@/components/ui/DataTable'
import { PageContainer, PageHeader } from '@/components/ui/Page'
import { priorityBadge, severityToDot, waitSeverity } from '@/lib/listStatus'
import { useDocumentTitle } from '@/lib/useDocumentTitle'

export const Route = createFileRoute('/queue')({
  component: QueuePage,
})

type Banner = { kind: 'ok' | 'err'; text: string }

function errText(err: unknown, fallback: string): string {
  return err instanceof ApiError
    ? err.problem.detail ?? err.problem.title
    : err instanceof Error
      ? err.message
      : fallback
}

function QueuePage() {
  useDocumentTitle('Queue')
  const { data, isLoading, error, refetch } = useQueue()
  const drainMut = useDrainQueue()
  const reorderMut = useReorderQueue()
  const [drainBanner, setDrainBanner] = useState<Banner | null>(null)
  const [reorderBanner, setReorderBanner] = useState<Banner | null>(null)
  const [drainConfirmOpen, setDrainConfirmOpen] = useState(false)

  const serverItems = useMemo(() => data?.items ?? [], [data])
  // Local order — initialised from the server and re-synced when the server
  // set / order changes. User drags mutate this; once it diverges we surface
  // the "Save order" button.
  const [localOrder, setLocalOrder] = useState<QueueEntryDto[]>(serverItems)

  const serverIdsKey = serverItems.map((t) => t.taskId).join(',')

  useEffect(() => {
    setLocalOrder(serverItems)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [serverIdsKey])

  const localIdsKey = localOrder.map((t) => t.taskId).join(',')
  const isDirty = serverIdsKey !== localIdsKey && localOrder.length > 0

  const onReorder = (orderedKeys: Array<string | number>) => {
    const byId = new Map(localOrder.map((t) => [String(t.taskId), t]))
    const next = orderedKeys
      .map((k) => byId.get(String(k)))
      .filter((t): t is QueueEntryDto => t !== undefined)
    setLocalOrder(next)
  }

  const onSaveOrder = () => {
    const taskIds = localOrder.map((t) => t.taskId)
    setReorderBanner(null)
    reorderMut.mutate(
      { taskIds },
      {
        onSuccess: () => {
          setReorderBanner({
            kind: 'ok',
            text: `Reordered ${taskIds.length} task${taskIds.length === 1 ? '' : 's'}.`,
          })
        },
        onError: (err) => {
          setReorderBanner({ kind: 'err', text: errText(err, 'Reorder failed.') })
          // Revert immediately — onSuccess invalidation won't fire on error.
          setLocalOrder(serverItems)
        },
      },
    )
  }

  const onResetOrder = () => {
    setLocalOrder(serverItems)
    setReorderBanner(null)
  }

  const onDrainClick = () => {
    if (localOrder.length === 0) return
    setDrainConfirmOpen(true)
  }

  const onDrainCancel = () => {
    if (drainMut.isPending) return
    setDrainConfirmOpen(false)
  }

  const onDrainConfirm = () => {
    drainMut.mutate(undefined, {
      onSuccess: (res) => {
        setDrainConfirmOpen(false)
        setDrainBanner({
          kind: 'ok',
          text: `Drained ${res.drained} task${res.drained === 1 ? '' : 's'}.`,
        })
      },
      onError: (err) => {
        setDrainConfirmOpen(false)
        setDrainBanner({ kind: 'err', text: errText(err, 'Drain failed.') })
      },
    })
  }

  const columns = useQueueColumns()
  const isEmpty = !isLoading && !error && localOrder.length === 0

  return (
    <PageContainer>
      <PageHeader
        title="Build queue"
        description={
          data
            ? `${data.total} task${data.total === 1 ? '' : 's'} waiting for a worker`
            : 'Tasks waiting for a worker — claimable, blocked on resources, or held by a gate.'
        }
        actions={
          <>
            {isDirty && (
              <Button
                variant="ghost"
                size="sm"
                onClick={onResetOrder}
                disabled={reorderMut.isPending}
                title="Discard local reorder and revert to the server's order"
              >
                Reset
              </Button>
            )}
            <Button
              variant="outline"
              size="sm"
              onClick={onDrainClick}
              disabled={drainMut.isPending || localOrder.length === 0}
              title={localOrder.length === 0 ? 'Queue is empty' : 'Cancel every currently-queued task'}
            >
              {drainMut.isPending ? 'Draining…' : 'Drain queue'}
            </Button>
            {isDirty && (
              <Button
                size="sm"
                onClick={onSaveOrder}
                disabled={reorderMut.isPending}
                title="Persist this order via POST /api/v1/queue/reorder"
              >
                {reorderMut.isPending ? 'Saving…' : 'Save order'}
              </Button>
            )}
          </>
        }
      />

      {drainBanner && (
        <BannerLine banner={drainBanner} onDismiss={() => setDrainBanner(null)} okLabel="Drained" />
      )}
      {reorderBanner && (
        <BannerLine banner={reorderBanner} onDismiss={() => setReorderBanner(null)} okLabel="Saved" />
      )}

      <div className="card list-table-wrap">
        <DataTable<QueueEntryDto>
          rows={localOrder}
          columns={columns}
          rowKey={(t) => t.taskId}
          rowTestId={(t) => `queue-row-${t.taskId}`}
          isLoading={isLoading}
          error={error ? { message: errText(error, 'Failed to load queue.'), onRetry: () => void refetch() } : null}
          emptyMessage={<span>No tasks currently queued.</span>}
          reorder={{
            onReorder,
            disabled: reorderMut.isPending,
            handleLabel: (k) => `Drag to reorder task ${k}`,
          }}
          testId="queue"
        />
      </div>

      {/* Recent activity — only when the live queue is loaded + empty (no error). */}
      {isEmpty && <RecentActivityCard />}

      <ConfirmDialog
        open={drainConfirmOpen}
        title="Drain queue"
        message={`Drain queue? This cancels ${localOrder.length} queued task${
          localOrder.length === 1 ? '' : 's'
        }. In-flight (claimed / processing) tasks are NOT affected.`}
        confirmLabel="Drain queue"
        destructive
        busy={drainMut.isPending}
        onConfirm={onDrainConfirm}
        onCancel={onDrainCancel}
        testId="drain-queue-dialog"
      />
    </PageContainer>
  )
}

function useQueueColumns(): DataTableColumn<QueueEntryDto>[] {
  return useMemo(
    () => [
      {
        key: 'priority',
        header: 'Priority',
        width: '90px',
        cell: (t) => (
          <Badge variant={priorityBadge(t.priority)} title={`priority ${t.priority}`}>
            P{t.priority}
          </Badge>
        ),
      },
      {
        key: 'build',
        header: 'Build',
        cell: (t) => (
          <span className="cell-stack" title={t.jobName ?? `task ${t.taskId}`}>
            {t.buildId != null ? (
              <Link
                to="/builds/$buildId"
                params={{ buildId: String(t.buildId) }}
                className="cell-stack-primary cell-link"
              >
                {t.jobName ?? `build #${t.buildId}`}
              </Link>
            ) : (
              <span className="cell-stack-primary">(orchestration task)</span>
            )}
            <span className="cell-mono cell-stack-secondary">
              task #{t.taskId}
              {t.buildId != null ? ` · build #${t.buildId}` : ''}
            </span>
          </span>
        ),
      },
      {
        key: 'age',
        header: 'Age',
        width: '130px',
        cell: (t) => (
          <StatusCell variant={severityToDot(waitSeverity(t.waitingMs))} label={formatWaiting(t.waitingMs)} />
        ),
      },
      {
        key: 'labels',
        header: 'Labels',
        width: '160px',
        align: 'right',
        cell: (t) => (
          <span className="cell-mono cell-muted" title={t.requestedLabels}>
            {t.requestedLabels || '—'}
          </span>
        ),
      },
    ],
    [],
  )
}

function BannerLine({
  banner,
  onDismiss,
  okLabel,
}: {
  banner: Banner
  onDismiss: () => void
  okLabel: string
}) {
  return (
    <div
      role="status"
      className="card"
      style={{
        padding: '10px 14px',
        marginBottom: 10,
        fontSize: 12.5,
        borderLeft: `3px solid ${banner.kind === 'ok' ? 'var(--ok)' : 'var(--fail)'}`,
      }}
    >
      <span style={{ color: banner.kind === 'ok' ? 'var(--ok)' : 'var(--fail)' }}>
        {banner.kind === 'ok' ? okLabel : 'Error'} ·
      </span>{' '}
      <span style={{ color: 'var(--fg-dim)' }}>{banner.text}</span>
      <button
        type="button"
        onClick={onDismiss}
        className="btn btn-sm btn-ghost"
        style={{ marginLeft: 'auto', float: 'right' }}
        aria-label="Dismiss"
      >
        Dismiss
      </button>
    </div>
  )
}

function formatWaiting(ms: number): string {
  if (ms < 1000) return `${ms}ms`
  const s = Math.floor(ms / 1000)
  if (s < 60) return `${s}s`
  const m = Math.floor(s / 60)
  if (m < 60) return `${m}m ${s % 60}s`
  const h = Math.floor(m / 60)
  return `${h}h ${m % 60}m`
}
