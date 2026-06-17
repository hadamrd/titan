/**
 * TreeRail — left 280px column on /builds/$id v3-stack.
 *
 * Filter input + status pill toggles + scrollable tree grouped by stage.
 * Each row: stage chevron + dot + name + duration. Selection mirrors the
 * DAG selection.
 *
 * Visual spec: docs/design/build-detail-v3-mockups/project/build-detail/
 * v3-stack.html lines 494–555.
 */
import { useMemo, useState } from 'react'
import type { FlowNodeDto } from '@/api/types'
import { variantOf, type CardVariant } from './DagView'
import { formatDuration } from '@/lib/format'

interface Props {
  nodes: FlowNodeDto[]
  selectedNodeId: string | null
  onSelect: (nodeId: string) => void
}

type StatusToggle = 'all' | 'ok' | 'fail' | 'skip'

interface Stage {
  name: string
  nodes: FlowNodeDto[]
}

function groupStages(nodes: FlowNodeDto[]): Stage[] {
  const order: string[] = []
  const by = new Map<string, FlowNodeDto[]>()
  for (const n of nodes) {
    const name = (n.displayName ?? n.nodeType).split(/[:/]/)[0]?.trim() || 'Steps'
    if (!by.has(name)) {
      order.push(name)
      by.set(name, [])
    }
    by.get(name)!.push(n)
  }
  return order.map((name) => ({ name, nodes: by.get(name)! }))
}

function dotClass(v: CardVariant): string {
  switch (v) {
    case 'ok': return 'status-dot success'
    case 'fail': return 'status-dot fail'
    case 'run': return 'status-dot running'
    case 'queued': return 'status-dot queued'
    default: return 'status-dot cancelled'
  }
}

function matchesToggle(v: CardVariant, t: StatusToggle): boolean {
  if (t === 'all') return true
  if (t === 'ok') return v === 'ok'
  if (t === 'fail') return v === 'fail'
  if (t === 'skip') return v === 'skip' || v === 'queued'
  return true
}

export function TreeRail({ nodes, selectedNodeId, onSelect }: Props) {
  const [filter, setFilter] = useState('')
  const [toggle, setToggle] = useState<StatusToggle>('all')

  const filteredNodes = useMemo(() => {
    const q = filter.trim().toLowerCase()
    return nodes.filter((n) => {
      const v = variantOf(n.status)
      if (!matchesToggle(v, toggle)) return false
      if (!q) return true
      const name = (n.displayName ?? n.nodeType).toLowerCase()
      return name.includes(q)
    })
  }, [nodes, filter, toggle])

  const stages = useMemo(() => groupStages(filteredNodes), [filteredNodes])

  const totals = useMemo(() => {
    let ok = 0, fail = 0, skip = 0
    for (const n of nodes) {
      const v = variantOf(n.status)
      if (v === 'ok') ok++
      else if (v === 'fail') fail++
      else skip++
    }
    return { ok, fail, skip }
  }, [nodes])

  return (
    <aside className="bd-rail" aria-label="Pipeline steps">
      <div className="bd-rail-head">
        <div className="bd-rail-filter">
          <svg
            width="14"
            height="14"
            viewBox="0 0 16 16"
            fill="none"
            stroke="currentColor"
            strokeWidth="1.5"
            style={{ color: 'var(--fg-faint)' }}
            aria-hidden="true"
          >
            <circle cx="7" cy="7" r="4" />
            <path d="M13 13l-3-3" />
          </svg>
          <input
            type="text"
            placeholder="Filter steps…"
            value={filter}
            onChange={(e) => setFilter(e.target.value)}
            aria-label="Filter steps"
            data-testid="tree-filter"
          />
        </div>
        <div className="bd-rail-toggles">
          <button
            type="button"
            className={`bd-pill ${toggle === 'ok' ? 'ok' : 'skip'}`}
            onClick={() => setToggle(toggle === 'ok' ? 'all' : 'ok')}
          >
            <span className="status-dot success" style={{ width: 6, height: 6 }} /> Pass
          </button>
          <button
            type="button"
            className={`bd-pill ${toggle === 'fail' ? 'fail' : 'skip'}`}
            onClick={() => setToggle(toggle === 'fail' ? 'all' : 'fail')}
          >
            <span className="status-dot fail" style={{ width: 6, height: 6 }} /> Fail
          </button>
          <button
            type="button"
            className={`bd-pill ${toggle === 'skip' ? 'accent' : 'skip'}`}
            onClick={() => setToggle(toggle === 'skip' ? 'all' : 'skip')}
          >
            <span className="status-dot cancelled" style={{ width: 6, height: 6 }} /> Skip
          </button>
          {toggle !== 'all' && (
            <button
              type="button"
              onClick={() => setToggle('all')}
              style={{
                marginLeft: 'auto',
                background: 'transparent',
                border: 0,
                color: 'var(--fg-faint)',
                fontSize: 10.5,
                fontFamily: 'var(--font-mono)',
              }}
            >
              clear
            </button>
          )}
        </div>
      </div>

      <div className="bd-rail-body" data-testid="tree-body">
        {stages.length === 0 ? (
          <div style={{ padding: 12, color: 'var(--fg-dim)', fontSize: 12 }}>
            No matching steps.
          </div>
        ) : (
          stages.map((stage) => (
            <div className="bd-tree-group" key={stage.name}>
              <div className="bd-tree-stage">{stage.name}</div>
              {stage.nodes.map((n) => {
                const v = variantOf(n.status)
                const isSelected = selectedNodeId === n.nodeId
                return (
                  <div
                    key={n.nodeId}
                    className={`bd-tree-row${isSelected ? ' selected' : ''}`}
                    data-testid={`tree-row-${n.nodeId}`}
                    onClick={() => onSelect(n.nodeId)}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter' || e.key === ' ') {
                        e.preventDefault()
                        onSelect(n.nodeId)
                      }
                    }}
                    role="button"
                    tabIndex={0}
                  >
                    <span />
                    <span className={dotClass(v)} style={{ width: 8, height: 8 }} />
                    <span className="name" title={n.displayName ?? n.nodeType}>
                      {n.displayName ?? n.nodeType}
                    </span>
                    <span className="dur">{formatDuration(n.durationMs)}</span>
                  </div>
                )
              })}
            </div>
          ))
        )}
      </div>

      <div className="bd-rail-foot">
        <span>{nodes.length} nodes</span>
        <span>
          {totals.ok} ok · {totals.fail} fail · {totals.skip} skip
        </span>
      </div>
    </aside>
  )
}
