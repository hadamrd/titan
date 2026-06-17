/**
 * BuildDag — the collapsible DAG block on /builds/$id. Renders one of three
 * states: collapsed summary bar, full DagView, or an empty placeholder.
 * Extracted from `/builds/$buildId.tsx` (ticket #851).
 */
import type { FlowNodeDto } from '@/api/types'
import { DagView } from '@/components/BuildDetail/DagView'
import { stageBucketCount } from '@/lib/build-format'

interface Props {
  nodes: FlowNodeDto[] | undefined
  selectedNodeId: string | null
  collapsed: boolean
  onSelect: (nodeId: string) => void
  onExpand: () => void
  onCollapse: () => void
}

export function BuildDag({
  nodes,
  selectedNodeId,
  collapsed,
  onSelect,
  onExpand,
  onCollapse,
}: Props) {
  if (collapsed) {
    return (
      <div
        className="bd-dag-collapsed"
        data-testid="dag-collapsed-bar"
        style={{
          height: 32,
          display: 'flex',
          alignItems: 'center',
          gap: 8,
          padding: '0 12px',
          borderBottom: '1px solid var(--border)',
          background: 'var(--bg-2)',
          fontSize: 12,
          color: 'var(--fg-dim)',
        }}
      >
        <span>
          Pipeline ({nodes?.length ?? 0} node{(nodes?.length ?? 0) === 1 ? '' : 's'}
          {nodes && nodes.length > 0 ? ` · ${stageBucketCount(nodes)} stages` : ''})
        </span>
        <button
          type="button"
          className="btn btn-sm btn-ghost"
          data-testid="dag-expand-btn"
          aria-label="Expand pipeline graph"
          aria-expanded={false}
          onClick={onExpand}
          style={{ marginLeft: 'auto' }}
          title="Expand graph"
        >
          <svg
            width="14"
            height="14"
            viewBox="0 0 16 16"
            fill="none"
            stroke="currentColor"
            strokeWidth="1.5"
            aria-hidden="true"
          >
            <path d="M4 6l4 4 4-4" />
          </svg>
        </button>
      </div>
    )
  }

  if (nodes && nodes.length > 0) {
    return (
      <DagView
        nodes={nodes}
        selectedNodeId={selectedNodeId}
        onSelect={onSelect}
        onCollapse={onCollapse}
        collapsed={false}
      />
    )
  }

  return (
    <div
      className="bd-dag-wrap"
      style={{ alignItems: 'center', justifyContent: 'center', display: 'flex' }}
    >
      <p style={{ color: 'var(--fg-dim)', fontSize: 12 }}>
        No flow nodes recorded yet.
      </p>
    </div>
  )
}
