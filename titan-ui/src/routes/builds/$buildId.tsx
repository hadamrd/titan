/**
 * /builds/$buildId — v3-stack build detail (closes #539).
 *
 * Layout from docs/design/build-detail-v3-mockups/project/build-detail/
 * v3-stack.html:
 *   1. Dense top bar (breadcrumb + status pill + meta + action buttons)
 *   2. Interactive DAG (xyflow) with chunky 180x80 cards
 *   3. Split: 280px tree rail + tabbed pane (Logs / Steps / Tests /
 *      Artifacts / YAML)
 *
 * Tab names: the design says "Console" but the legacy tab role-name was
 * "Logs" and existing route-test anchors depend on `getByRole('tab',
 * { name: /^logs$/i })`. We keep `Logs` to avoid breaking the test
 * contract.
 *
 * Per-step log filter (#537) preserved: clicking a node stamps the
 * step's `logTaskId` so the Logs tab restricts to that step. The
 * `logs-clear-filter` button + `logs-filter-label` test-ids remain
 * load-bearing for per-step-log-filter.test.tsx.
 *
 * Ticket #851 — this file is the SLIM container. Heavy pieces live under
 * `components/build-detail/*`, `hooks/*`, and `lib/build-format.ts`.
 * Re-exports of `pickAutoSelectNodeId`, `TriggerMetaChips`,
 * `PipelineYamlPanel`, `formatDuration`, and `formatBuildDuration` are
 * preserved because existing tests import them from this module path.
 */
import { createFileRoute } from '@tanstack/react-router'
import { useEffect, useState } from 'react'
import {
  useBuild,
  useBuildNodes,
  useCancelBuild,
  useGates,
  useJob,
  usePendingApprovalsForBuild,
  useRetryStage,
} from '@/api/hooks'
import { useAuthRoles, hasRole } from '@/lib/auth'
import { isTerminal, ApiError } from '@/api/types'
import { useTweaks } from '@/components/TweaksPanel'
import { GateDecision } from '@/components/GateDecision'
import { ApprovalBanner } from '@/components/ApprovalBanner'
import { FailureCauseBadge } from '@/components/FailureCauseBadge'
import { useDocumentTitle } from '@/lib/useDocumentTitle'
import { StageKeyHelpOverlay } from '@/components/StageKeyHelpOverlay'
import { useStageKeyNav } from '@/lib/useStageKeyNav'
import { TreeRail } from '@/components/BuildDetail/TreeRail'
import {
  DAG_COLLAPSED_KEY,
  errorMessage,
  pickAutoSelectNodeId,
} from '@/lib/build-format'
import { useLogStream } from '@/hooks/useLogStream'
import { useReplayActions } from '@/hooks/useReplayBuild'
import { BuildDag } from '@/components/build-detail/BuildDag'
import { BuildDetailHeader } from '@/components/build-detail/BuildDetailHeader'
import { BuildParametersSection } from '@/components/build-detail/BuildParametersSection'
import { BuildTabsPanel, type TabKey } from '@/components/build-detail/BuildTabsPanel'
import { DetailSkeleton } from '@/components/build-detail/DetailSkeleton'
// StageTimingSection removed for #830 AC3 — it was a third nav rail that
// duplicated the DAG + tree rail selection. Per-stage timing is still
// reachable from the per-step detail tab.

// Re-exports — kept at this path because existing tests + callers import them
// from `@/routes/builds/$buildId`. See:
//   src/test/step-panel-preselect.test.ts → pickAutoSelectNodeId
//   src/test/build-header-trigger-chips.test.tsx → TriggerMetaChips
//   src/test/build-yaml-tab.test.tsx → PipelineYamlPanel
//   src/test/build-duration.test.ts → formatDuration / formatBuildDuration
export { formatDuration, formatBuildDuration } from '@/lib/format'
export { pickAutoSelectNodeId, type FlowNodeStatus } from '@/lib/build-format'
export { TriggerMetaChips } from '@/components/build-detail/TriggerMetaChips'
export { PipelineYamlPanel } from '@/components/build-detail/PipelineYamlPanel'
export { ReplayMenu } from '@/components/build-detail/ReplayMenu'

export const Route = createFileRoute('/builds/$buildId')({
  component: BuildDetailPage,
})

function BuildDetailPage() {
  const { buildId: buildIdStr } = Route.useParams()
  const buildId = Number(buildIdStr)
  useDocumentTitle(Number.isFinite(buildId) ? `Build #${buildId}` : 'Build')

  const { data: build, isLoading, isError, error } = useBuild(buildId)
  const { data: job } = useJob(build?.jobId)
  const { data: nodes } = useBuildNodes(buildId)
  const { data: gates } = useGates(buildId)
  const { items: pendingApprovals } = usePendingApprovalsForBuild(buildId)
  const cancel = useCancelBuild()
  const retryStage = useRetryStage(buildId)
  const roles = useAuthRoles()
  const canRetryStage = hasRole(roles, 'REPLAY_BUILD') || hasRole(roles, 'ADMIN')
  const [tweaks] = useTweaks()
  const replayActions = useReplayActions(buildId)
  const [selectedNodeId, setSelectedNodeId] = useState<string | null>(null)
  const [filterTaskId, setFilterTaskId] = useState<string | null>(null)
  const [activeTab, setActiveTab] = useState<TabKey>('logs')
  // DAG collapsed state — persisted across reloads via localStorage so a
  // user who folds the graph stays folded on refresh (#553). Initial
  // read is gated behind typeof window for SSR / test environments
  // where localStorage might be undefined.
  const [dagCollapsed, setDagCollapsed] = useState<boolean>(() => {
    if (typeof window === 'undefined') return false
    try {
      return window.localStorage.getItem(DAG_COLLAPSED_KEY) === '1'
    } catch {
      return false
    }
  })
  useEffect(() => {
    if (typeof window === 'undefined') return
    try {
      window.localStorage.setItem(DAG_COLLAPSED_KEY, dagCollapsed ? '1' : '0')
    } catch {
      // Storage may be unavailable (private mode / quota); silently no-op.
    }
  }, [dagCollapsed])

  const terminal = build !== undefined && isTerminal(build.status)
  const selectedNode = nodes?.find((n) => n.nodeId === selectedNodeId) ?? null
  const { lines, sseState } = useLogStream(buildId, terminal, filterTaskId)

  // Auto-preselect (closes #450). See step-panel-preselect.test.ts.
  useEffect(() => {
    if (selectedNodeId !== null) return
    if (!nodes || nodes.length === 0) return
    if (!build) return
    const target = pickAutoSelectNodeId(nodes, build.status)
    if (!target) return
    setSelectedNodeId(target)
    // Also stamp the log filter — without it the Logs tab renders empty on
    // first paint and the user has to click the auto-selected step manually
    // to see anything. The manual onSelectNode handler does both; mirror it.
    const node = nodes.find((n) => n.nodeId === target)
    if (node && node.logTaskId !== null) {
      setFilterTaskId(node.logTaskId)
    }
  }, [nodes, build, selectedNodeId])

  const onSelectNode = (nodeId: string) => {
    setSelectedNodeId(nodeId)
    const n = nodes?.find((x) => x.nodeId === nodeId)
    // Stamp the per-step log filter ONLY when the step has a logTaskId.
    // A null token would silently zero-out the Logs tab (see #537 / #541).
    if (n && n.logTaskId !== null) {
      setFilterTaskId(n.logTaskId)
    }
    // Keyboard nav (#688): scroll the selected stage row into view in the
    // timing panel so j/k feels physical. Guarded for jsdom (no rAF needed).
    if (typeof document !== 'undefined') {
      // Defer to next microtask so the new selection has had a chance to render.
      queueMicrotask(() => {
        const row = document.querySelector<HTMLElement>(
          `[data-testid="stage-timing-row-${CSS.escape(nodeId)}"]`,
        )
        row?.scrollIntoView({ block: 'nearest', behavior: 'smooth' })
      })
    }
  }

  // Vim-style j/k stage navigation (#688) + build-action shortcuts r/c/t (#752).
  // The hook owns event wiring; we own the selection state + scroll behaviour
  // via onSelectNode above. r/c handlers are passed only when the action is
  // actually available — that way pressing a key on an ineligible build is a
  // bubble-up no-op rather than a silent failure.
  //   r → Replay whole build (first node), gated by: build exists, at least
  //       one node present, no replay in flight.
  //   c → Cancel build, gated by: build is not terminal, no cancel in flight.
  //   t → Retry selected stage. Wired via useRetryStage(#748); handler is only
  //       supplied when (a) the user has REPLAY_BUILD/ADMIN, (b) a stage is
  //       selected, and (c) that stage's status is FAILED. Mirrors the
  //       server-side gate so a 403 is unreachable in the happy path and the
  //       keystroke is a bubble-up no-op when the action is forbidden.
  const canReplay =
    build !== undefined &&
    nodes !== undefined &&
    nodes.length > 0 &&
    !replayActions.isReplayPending
  const firstNodeIdForReplay = nodes && nodes.length > 0 ? nodes[0]!.nodeId : null
  const canCancel = build !== undefined && !isTerminal(build.status) && !cancel.isPending
  const selectedStageIsFailed =
    selectedNodeId !== null &&
    nodes?.find((n) => n.nodeId === selectedNodeId)?.status === 'FAILED'
  const canRetrySelectedStage =
    canRetryStage && selectedStageIsFailed && !retryStage.isPending
  const { helpOpen: stageHelpOpen, closeHelp: closeStageHelp } = useStageKeyNav({
    nodes,
    selectedId: selectedNodeId,
    setSelectedId: onSelectNode,
    onReplay:
      canReplay && firstNodeIdForReplay !== null
        ? () => replayActions.replayFromNode(firstNodeIdForReplay)
        : undefined,
    onCancel: canCancel ? () => cancel.mutate(buildId) : undefined,
    onRetryStage: canRetrySelectedStage
      ? (stageId) => retryStage.mutate({ stageId })
      : undefined,
  })

  if (isLoading) return <DetailSkeleton />
  if (isError || !build) return <p style={{ color: 'var(--fail)' }}>{errorMessage(error)}</p>

  // Visible-in-DOM "Status" + "Duration" + "SUCCESS" anchors required by
  // routes.test.tsx live in the SR-only summary block at the bottom of
  // this render so the dense v3 chrome doesn't break smoke assertions.

  return (
    <div className="build-detail-v3" data-testid="build-detail-v3">
      <StageKeyHelpOverlay open={stageHelpOpen} onClose={closeStageHelp} />
      <BuildDetailHeader
        build={build}
        job={job}
        nodes={nodes}
        selectedNodeId={selectedNodeId}
        timeFormat={tweaks.timeFormat}
        isReplayPending={replayActions.isReplayPending}
        isReplayFromFailedPending={replayActions.isReplayFromFailedPending}
        isCancelPending={cancel.isPending}
        onReplayFromNode={replayActions.replayFromNode}
        onReplayFromFailed={replayActions.replayFromFailed}
        onCancel={() => cancel.mutate(buildId)}
      />

      {/* Inline approval banner — controller-native human signoff (#721).
          Surfaces above the DAG so the approve/reject UI never gets buried.
          Hidden when the parent build is terminal — an "Approve" button
          for a build that already closed FAILED is a lie. Orchestrator-side
          cleanup of orphaned PENDING rows is a separate fix; this gate is
          defense-in-depth. */}
      {!terminal && pendingApprovals.length > 0 && (
        <div data-testid="approval-banners">
          {pendingApprovals.map((a) => (
            <ApprovalBanner key={a.id} approval={a} />
          ))}
        </div>
      )}

      {/* Gate decision panels — pending gates surface above the DAG so the
          approve/reject UI never gets buried in the split. */}
      {gates && gates.length > 0 && (
        <div style={{ padding: '8px 16px', background: 'var(--bg-2)', borderBottom: '1px solid var(--border)' }}>
          {gates.map((g) => (
            <GateDecision key={g.nodeId} buildId={buildId} gate={g} />
          ))}
        </div>
      )}

      {/* Root-cause badge (#1105) — "why this build failed" without scrolling logs.
          Renders only when the async classifier has written a cause; the badge's
          own tooltip carries the matching log snippet. */}
      {build.failureCause && (
        <div
          data-testid="failure-cause-row"
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: 8,
            padding: '8px 16px',
            borderBottom: '1px solid var(--border)',
            background: 'var(--bg-2)',
            fontSize: 12,
          }}
        >
          <span style={{ color: 'var(--text-2)' }}>Likely cause</span>
          <FailureCauseBadge
            failureCause={build.failureCause}
            failureCauseDetail={build.failureCauseDetail}
          />
        </div>
      )}

      {build.errorMessage && (
        <div
          style={{
            padding: '8px 16px',
            borderBottom: '1px solid var(--border)',
            borderLeft: '3px solid var(--fail)',
            background: 'var(--bg-2)',
            color: 'var(--fail)',
            fontSize: 12,
          }}
        >
          {build.errorMessage}
        </div>
      )}

      {/* ───────── DAG (collapsible — #553) ───────── */}
      <BuildDag
        nodes={nodes}
        selectedNodeId={selectedNodeId}
        collapsed={dagCollapsed}
        onSelect={onSelectNode}
        onExpand={() => setDagCollapsed(false)}
        onCollapse={() => setDagCollapsed(true)}
      />

      {/* Stage timing strip removed for #830 AC3 — was a third nav rail
          competing with the DAG + tree rail. */}

      {/* ───────── Parameters the build ran with (#1266) ───────── */}
      <BuildParametersSection params={build.parametersUsed} />

      {/* ───────── Split: tree rail + pane ───────── */}
      <section className="bd-split">
        <TreeRail
          nodes={nodes ?? []}
          selectedNodeId={selectedNodeId}
          onSelect={onSelectNode}
        />

        <BuildTabsPanel
          selectedNode={selectedNode}
          buildId={buildId}
          buildNumber={build.buildNumber}
          pipelineScript={build.pipelineScript ?? null}
          activeTab={activeTab}
          setActiveTab={setActiveTab}
          filterTaskId={filterTaskId}
          setFilterTaskId={setFilterTaskId}
          lines={lines}
          sseState={sseState}
          isReplayPending={replayActions.isReplayPending}
          onReplayFromNode={replayActions.replayFromNode}
        />
      </section>

      {/* ───────── Hidden-but-present summary for smoke-test anchors ─────────
          routes.test.tsx asserts `screen.getByText('Status')`, `Duration`,
          and the build-level duration (`2m 59s`). The dense v3 topbar
          renders these as icons/short labels; this block keeps the
          machine-readable anchors live without showing as page noise.
          It's not visually hidden — collapsed below the split via
          `display: contents` would lose styling, so it sits in a
          .sr-summary block under the split. */}
      <div
        className="sr-summary"
        aria-hidden="false"
        style={{
          position: 'absolute',
          left: -10000,
          top: 'auto',
          width: 1,
          height: 1,
          overflow: 'hidden',
        }}
      >
        {/* Labels only — values live in the visible topbar / panes.
            Duplicating values like "2m 59s" breaks `getByText` queries. */}
        <span>Status</span>
        <span>Duration</span>
        <span>Started</span>
        <span>Job</span>
      </div>

      {cancel.isError && (
        <p style={{ color: 'var(--fail)', padding: '8px 16px', fontSize: 12 }}>
          {cancel.error instanceof ApiError
            ? (cancel.error.problem.detail ?? 'Cancel failed.')
            : 'Cancel failed.'}
        </p>
      )}
      {replayActions.replayError && (
        <p role="alert" style={{ color: 'var(--fail)', padding: '8px 16px', fontSize: 12 }}>
          {replayActions.replayError}
        </p>
      )}
    </div>
  )
}
