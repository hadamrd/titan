/**
 * NodeDetail — small definition list for the selected step in the Steps tab.
 * Extracted from `/builds/$buildId.tsx` (ticket #851).
 */
import { StatusBadge } from '@/components/StatusBadge'
import { formatDuration } from '@/lib/format'
import type { FlowNodeDto } from '@/api/types'

export function NodeDetail({ node }: { node: FlowNodeDto | undefined }) {
  if (!node) return <p style={{ color: 'var(--fg-dim)', fontSize: 12 }}>Step not found.</p>
  return (
    <dl
      style={{
        display: 'grid',
        gridTemplateColumns: '90px 1fr',
        gap: '6px 12px',
        fontSize: 12,
        margin: 0,
      }}
    >
      <dt style={{ color: 'var(--fg-dim)' }}>Name</dt>
      <dd style={{ margin: 0, fontWeight: 500 }}>{node.displayName ?? node.nodeType}</dd>
      <dt style={{ color: 'var(--fg-dim)' }}>Type</dt>
      <dd style={{ margin: 0, fontFamily: 'var(--font-mono)' }}>{node.nodeType}</dd>
      <dt style={{ color: 'var(--fg-dim)' }}>Status</dt>
      <dd style={{ margin: 0 }}>
        <StatusBadge status={node.status} />
      </dd>
      <dt style={{ color: 'var(--fg-dim)' }}>Duration</dt>
      <dd style={{ margin: 0, fontFamily: 'var(--font-mono)' }}>
        {formatDuration(node.durationMs)}
      </dd>
      {node.stepDescriptor && (
        <>
          <dt style={{ color: 'var(--fg-dim)' }}>Step</dt>
          <dd style={{ margin: 0, fontFamily: 'var(--font-mono)' }}>{node.stepDescriptor}</dd>
        </>
      )}
    </dl>
  )
}
