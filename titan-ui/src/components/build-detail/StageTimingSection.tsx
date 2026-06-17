/**
 * StageTimingSection — collapsible `<details>` wrapper around the stage
 * timing panel. Extracted from `/builds/$buildId.tsx` (ticket #851).
 *
 * Default-open on FAILED / RUNNING (timing is the diagnostic the SRE is
 * here for); default-closed otherwise so the page stays calm.
 */
import type { FlowNodeDto } from '@/api/types'
import { StageTimingPanel } from '@/components/StageTimingPanel'

interface Props {
  nodes: readonly FlowNodeDto[]
  selectedNodeId: string | null
  buildId: number
  buildStatus: string | undefined
  onSelectStage: (nodeId: string) => void
}

export function StageTimingSection({
  nodes,
  selectedNodeId,
  buildId,
  buildStatus,
  onSelectStage,
}: Props) {
  return (
    <details
      data-testid="stage-timing-section"
      open={buildStatus === 'FAILED' || buildStatus === 'RUNNING'}
      style={{
        borderBottom: '1px solid var(--border)',
        background: 'var(--bg-1)',
      }}
    >
      <summary
        style={{
          cursor: 'pointer',
          padding: '8px 14px',
          fontSize: 12,
          color: 'var(--fg-dim)',
          userSelect: 'none',
          listStyle: 'revert',
        }}
      >
        Stage timing
      </summary>
      <StageTimingPanel
        nodes={nodes}
        selectedNodeId={selectedNodeId}
        onSelectStage={onSelectStage}
        buildId={buildId}
      />
    </details>
  )
}
