/**
 * Build-detail formatting + small derivations.
 *
 * Extracted from `/builds/$buildId.tsx` (ticket #851). Pure functions only —
 * no React. Re-exported from the route shim for callers that imported from
 * the old location (`pickAutoSelectNodeId`, `formatDuration`,
 * `formatBuildDuration`).
 */
import { ApiError, type FlowNodeDto } from '@/api/types'

export const DAG_COLLAPSED_KEY = 'titan.ui.buildDetail.dagCollapsed'

export type FlowNodeStatus =
  | 'SUCCESS'
  | 'FAILED'
  | 'RUNNING'
  | 'QUEUED'
  | 'NOT_BUILT'
  | 'ABORTED'
  | 'CANCELLED'
  | 'UNSTABLE'
  | 'UNKNOWN'

export function asFlowNodeStatus(s: string): FlowNodeStatus {
  switch (s) {
    case 'SUCCESS':
    case 'FAILED':
    case 'RUNNING':
    case 'QUEUED':
    case 'NOT_BUILT':
    case 'ABORTED':
    case 'CANCELLED':
    case 'UNSTABLE':
      return s
    case 'FAILURE':
      return 'FAILED'
    default:
      return 'UNKNOWN'
  }
}

export function errorMessage(err: unknown): string {
  if (err instanceof ApiError) {
    if (err.status === 404) return 'Build not found.'
    if (err.status >= 500) return 'Server error — please retry.'
    return err.problem.detail ?? err.message
  }
  return 'Failed to load build.'
}

/**
 * Build-detail-local "time since" / "absolute" date formatter. Differs from
 * `lib/format.formatDate` only by accepting a strict `string | null` and
 * skipping `Number.isFinite` guarding — kept here to preserve the exact
 * pre-refactor output on the build header (no test contract changes).
 */
export function formatDate(iso: string | null, mode: 'relative' | 'absolute'): string {
  if (!iso) return '—'
  if (mode === 'absolute') return new Date(iso).toLocaleString()
  const sec = Math.floor((Date.now() - new Date(iso).getTime()) / 1000)
  if (sec < 60) return `${sec}s ago`
  if (sec < 3600) return `${Math.floor(sec / 60)}m ago`
  if (sec < 86_400) return `${Math.floor(sec / 3600)}h ago`
  return `${Math.floor(sec / 86_400)}d ago`
}

/**
 * Cheap stage-count for the collapsed-DAG summary bar. Same bucket key
 * DagView uses internally (first token of displayName / nodeType).
 */
export function stageBucketCount(nodes: readonly FlowNodeDto[]): number {
  const seen = new Set<string>()
  for (const n of nodes) {
    const name = n.displayName ?? n.nodeType
    const first = name.split(/[:/]/)[0]?.trim() || 'Steps'
    seen.add(first)
  }
  return seen.size
}

/** Derive the stage bucket label for a single node (mirrors DagView). */
export function stageOfNode(node: FlowNodeDto): string {
  const name = node.displayName ?? node.nodeType
  const first = name.split(/[:/]/)[0]?.trim()
  return first && first.length > 0 ? first : 'Steps'
}

/**
 * Pick the most-relevant flow node to auto-select on /builds/$id.
 * Contract kept identical to the pre-v3 version — see
 * step-panel-preselect.test.ts for the full rule matrix.
 */
export function pickAutoSelectNodeId(
  nodes: readonly FlowNodeDto[],
  buildStatus: string,
): string | null {
  if (nodes.length === 0) return null
  switch (buildStatus) {
    case 'FAILED':
    case 'FAILURE': {
      const failed = nodes.find((n) => asFlowNodeStatus(n.status) === 'FAILED')
      return failed?.nodeId ?? null
    }
    case 'RUNNING': {
      const running = nodes.find((n) => asFlowNodeStatus(n.status) === 'RUNNING')
      return running?.nodeId ?? null
    }
    case 'SUCCESS':
      return nodes[nodes.length - 1]!.nodeId
    default:
      return null
  }
}
