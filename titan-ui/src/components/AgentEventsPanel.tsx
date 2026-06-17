/**
 * AgentEventsPanel — "Recent worker events" panel on /workers (closes #733).
 *
 * <p>Migrated to the shared {@link DataTable} primitive in #850 (design 64).
 * The hand-rolled `<ul>` + "Load 30 more" pager is replaced by the table
 * primitive's cursor pager; the existing data-testids on rows + chips remain
 * stable so Playwright suites don't churn.
 *
 * <p>Auto-refresh 30 s — calm cadence; this is a deep-dive surface, not the
 * home glance.
 */
import { useMemo } from 'react'
import { useAgentEvents } from '@/api/hooks'
import type { AgentEventDto } from '@/api/types'
import { ApiError } from '@/api/types'
import { formatRelative } from '@/components/RecentActivityTimeline'
import { DataTable, type DataTableColumn } from '@/components/ui/DataTable'

const PAGE_SIZE = 25
const REFETCH_MS = 30_000

export function AgentEventsPanel() {
  const { data, isLoading, error, refetch } = useAgentEvents(PAGE_SIZE, REFETCH_MS)

  // Defensive: server returns newest-first already, but pin the order so a
  // future ordering regression doesn't silently break this view.
  const events = useMemo<readonly AgentEventDto[]>(() => {
    const list = data ?? []
    return list.slice().sort((a, b) => {
      const ta = Date.parse(a.occurredAt)
      const tb = Date.parse(b.occurredAt)
      if (tb !== ta) return tb - ta
      return b.id - a.id
    })
  }, [data])

  const columns: DataTableColumn<AgentEventDto>[] = useMemo(
    () => [
      {
        key: 'agent',
        header: 'Agent',
        cell: (evt) => (
          <span style={{ display: 'inline-flex', alignItems: 'baseline', gap: 8 }} title={evt.agentName}>
            <span style={{ fontFamily: 'var(--font-mono)', fontSize: 12 }}>{evt.agentId}</span>
            <span style={{ fontSize: 11, color: 'var(--fg-faint)' }}>{evt.agentName}</span>
          </span>
        ),
      },
      {
        key: 'type',
        header: 'Type',
        width: '120px',
        cell: (evt) => <EventChip joined={evt.type === 'JOINED'} label={evt.type} />,
      },
      {
        key: 'when',
        header: 'When',
        width: '120px',
        align: 'right',
        numeric: true,
        cell: (evt) => formatRelative(evt.occurredAt),
      },
    ],
    [],
  )

  const errMsg =
    error instanceof ApiError ? error.problem.detail ?? error.problem.title : error ? String(error) : null

  return (
    <div data-testid="agent-events-panel" style={{ marginTop: 14 }}>
      <div
        style={{
          display: 'flex',
          alignItems: 'baseline',
          gap: 10,
          padding: '8px 12px 6px',
        }}
      >
        <h3 style={{ margin: 0, fontSize: 13, fontWeight: 600 }}>Recent worker events</h3>
        <span
          style={{ marginLeft: 'auto', fontSize: 11, color: 'var(--fg-dim)', fontFamily: 'var(--font-mono)' }}
        >
          last {events.length}
        </span>
      </div>
      <DataTable<AgentEventDto>
        rows={events}
        columns={columns}
        rowKey={(e) => e.id}
        rowTestId={(e) => `agent-event-row-${e.id}`}
        rowData={(e) => ({ 'data-event-type': e.type })}
        isLoading={isLoading}
        error={errMsg ? { message: errMsg, onRetry: () => void refetch() } : null}
        emptyMessage={
          <span data-testid="agent-events-panel-empty">No worker events in recent history.</span>
        }
        routeSearchKey="events"
        pageSize={PAGE_SIZE}
        testId="agent-events"
      />
      {errMsg && (
        <span data-testid="agent-events-panel-error" style={{ display: 'none' }}>
          {errMsg}
        </span>
      )}
    </div>
  )
}

function EventChip({ joined, label }: { joined: boolean; label: string }) {
  return (
    <span
      data-testid={`agent-event-chip-${label}`}
      className={joined ? 'agent-event-chip joined' : 'agent-event-chip left'}
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        padding: '2px 8px',
        borderRadius: 4,
        fontFamily: 'var(--font-mono)',
        fontSize: 11,
        letterSpacing: '0.04em',
        fontWeight: 500,
        background: joined ? 'oklch(0.96 0.04 145)' : 'var(--bg-2)',
        color: joined ? 'oklch(0.42 0.14 145)' : 'var(--fg-muted)',
        border: `1px solid ${joined ? 'oklch(0.85 0.08 145)' : 'var(--border)'}`,
      }}
    >
      {label}
    </span>
  )
}
