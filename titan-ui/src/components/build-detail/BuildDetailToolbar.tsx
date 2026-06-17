/**
 * BuildDetailToolbar — right-aligned action cluster in the build-detail
 * topbar: Compare picker, Replay menu, "Replay from failed", Cancel.
 * Extracted from `/builds/$buildId.tsx` (ticket #851).
 *
 * Behaviour-preserving: every test-id and disabled/visibility rule is the
 * same as the pre-refactor inline block.
 */
import type { BuildDto, FlowNodeDto } from '@/api/types'
import { isTerminal } from '@/api/types'
import { Button } from '@/components/ui/Button'
import { CompareBuildPicker } from '@/components/CompareBuildPicker'
import { ReplayMenu } from './ReplayMenu'

interface Props {
  build: BuildDto
  nodes: FlowNodeDto[] | undefined
  selectedNodeId: string | null
  isReplayPending: boolean
  isReplayFromFailedPending: boolean
  isCancelPending: boolean
  onReplayFromNode: (nodeId: string) => void
  onReplayFromFailed: () => void
  onCancel: () => void
}

export function BuildDetailToolbar({
  build,
  nodes,
  selectedNodeId,
  isReplayPending,
  isReplayFromFailedPending,
  isCancelPending,
  onReplayFromNode,
  onReplayFromFailed,
  onCancel,
}: Props) {
  const terminal = isTerminal(build.status)

  return (
    <div style={{ marginLeft: 'auto', display: 'flex', alignItems: 'center', gap: 6 }}>
      <CompareBuildPicker jobId={build.jobId} currentBuildId={build.id} />
      {nodes && nodes.length > 0 && (
        <ReplayMenu
          firstNodeId={nodes[0]!.nodeId}
          selectedNodeId={selectedNodeId}
          isPending={isReplayPending}
          onReplay={onReplayFromNode}
        />
      )}
      {/* Replay-from-failed shortcut (closes #664). Visible ONLY when:
          - the build is in the FAILED terminal state (anything else and the button is
            semantically wrong — a SUCCESS build has nothing to replay-from-failed), AND
          - at least one materialised flow node ended FAILED (defensive: a FAILED build
            with no failed nodes is degenerate — could happen on a synthesis-only failure
            where the DAG never materialised — and the backend would 400 anyway). */}
      {build.status === 'FAILED' &&
        nodes &&
        nodes.some((n) => n.status === 'FAILED') && (
          <Button
            variant="outline"
            size="sm"
            data-testid="replay-from-failed-button"
            disabled={isReplayFromFailedPending}
            onClick={onReplayFromFailed}
            title="Re-run from the first failed stage (skips stages that already succeeded)"
          >
            {isReplayFromFailedPending ? 'Replaying…' : 'Replay from failed'}
          </Button>
        )}
      {!terminal && (
        <Button
          variant="outline"
          size="sm"
          disabled={isCancelPending}
          onClick={onCancel}
        >
          {isCancelPending ? 'Cancelling…' : 'Cancel'}
        </Button>
      )}
      {terminal && (
        <Button variant="outline" size="sm" disabled>
          Cancel
        </Button>
      )}
    </div>
  )
}
