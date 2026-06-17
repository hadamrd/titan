/**
 * BuildTabsPanel — the right pane of the build-detail split: selected-node
 * header + tab bar + per-tab body (Logs / Steps / Tests / Artifacts / YAML).
 * Extracted from `/builds/$buildId.tsx` (ticket #851).
 *
 * Stays a "dumb" composition: state (active tab, selection) lives on the
 * container and is passed down. Test-ids are preserved verbatim.
 */
import type { FlowNodeDto } from '@/api/types'
import { Button } from '@/components/ui/Button'
import { StatusBadge } from '@/components/StatusBadge'
import { ArtifactsPanel } from '@/components/ArtifactsPanel'
import { OutputsPanel } from '@/components/OutputsPanel'
import { TestResultsPanel } from '@/components/TestResultsPanel'
import { TerminalConsole } from '@/components/BuildDetail/TerminalConsole'
import { variantOf } from '@/components/BuildDetail/DagView'
import { formatDuration } from '@/lib/format'
import { NodeDetail } from './NodeDetail'
import { TabButton } from './TabButton'
import { PipelineYamlPanel } from './PipelineYamlPanel'
import type { SseState } from '@/hooks/useLogStream'

export type TabKey = 'logs' | 'steps' | 'tests' | 'artifacts' | 'yaml' | 'timing'

interface Props {
  selectedNode: FlowNodeDto | null
  buildId: number
  buildNumber: number
  pipelineScript: string | null
  activeTab: TabKey
  setActiveTab: (tab: TabKey) => void
  filterTaskId: string | null
  setFilterTaskId: (taskId: string | null) => void
  lines: string[]
  sseState: SseState
  isReplayPending: boolean
  onReplayFromNode: (nodeId: string) => void
}

export function BuildTabsPanel({
  selectedNode,
  buildId,
  buildNumber,
  pipelineScript,
  activeTab,
  setActiveTab,
  filterTaskId,
  setFilterTaskId,
  lines,
  sseState,
  isReplayPending,
  onReplayFromNode,
}: Props) {
  const sel = selectedNode
  const selVariant = sel ? variantOf(sel.status) : 'queued'
  const dotClassForVariant: Record<typeof selVariant, string> = {
    ok: 'status-dot success',
    fail: 'status-dot fail',
    run: 'status-dot running',
    queued: 'status-dot queued',
    skip: 'status-dot cancelled',
  }

  return (
    <main className="bd-pane">
      {/* Selected node header */}
      <div className="bd-node-head">
        {sel ? (
          <>
            <span
              className={dotClassForVariant[selVariant]}
              style={{ width: 10, height: 10 }}
            />
            <div
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: 8,
                fontSize: 13,
                fontWeight: 600,
              }}
              title={`${sel.nodeType} · ${sel.nodeId}`}
            >
              <span>{sel.displayName ?? sel.stepDescriptor ?? sel.nodeId}</span>
              <StatusBadge status={sel.status} />
            </div>
            <div className="right">
              <span>{formatDuration(sel.durationMs)}</span>
              {(selVariant === 'ok' || selVariant === 'fail') && (
                <Button
                  variant="outline"
                  size="sm"
                  onClick={() => onReplayFromNode(sel.nodeId)}
                  disabled={isReplayPending}
                >
                  Replay from here
                </Button>
              )}
            </div>
          </>
        ) : (
          <span style={{ color: 'var(--fg-dim)', fontSize: 12 }}>
            Select a step to inspect logs, tests, and artifacts.
          </span>
        )}
      </div>

      {/* Tabs */}
      <div className="bd-tabs" role="tablist">
        <TabButton active={activeTab === 'logs'} onClick={() => setActiveTab('logs')}>
          Logs
        </TabButton>
        <TabButton active={activeTab === 'steps'} onClick={() => setActiveTab('steps')}>
          Steps
        </TabButton>
        <TabButton active={activeTab === 'tests'} onClick={() => setActiveTab('tests')}>
          Tests
        </TabButton>
        <TabButton
          active={activeTab === 'artifacts'}
          onClick={() => setActiveTab('artifacts')}
        >
          Artifacts
        </TabButton>
        <TabButton active={activeTab === 'yaml'} onClick={() => setActiveTab('yaml')}>
          YAML
        </TabButton>
      </div>

      {/* Tab body */}
      {activeTab === 'logs' && (
        <div
          className="bd-tab-body terminal-host"
          style={{ display: 'flex', flexDirection: 'column' }}
        >
          {/* Sub-breadcrumb (#830 AC6): only shown when a per-step filter is
              active. With no filter, the strip read "Logs · all steps · All
              logs" — a tautology of the already-selected `Logs` tab. The
              `logs-filter-label` / `logs-clear-filter` test ids are kept
              load-bearing for per-step-log-filter.test.tsx. */}
          {filterTaskId !== null && (
            <div
              data-testid="logs-filter-bar"
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: 10,
                padding: '8px 12px',
                fontSize: 12,
                color: 'var(--fg-muted)',
                borderBottom: '1px solid var(--border)',
                background: 'var(--bg-2)',
              }}
            >
              <span>
                Filter ·{' '}
                <span
                  data-testid="logs-filter-label"
                  style={{ color: 'var(--fg)', fontWeight: 500 }}
                >
                  {sel ? (sel.displayName ?? sel.nodeType) : 'step'}
                </span>
              </span>
              <Button
                variant="outline"
                size="sm"
                data-testid="logs-clear-filter"
                onClick={() => setFilterTaskId(null)}
              >
                Clear
              </Button>
            </div>
          )}
          <div style={{ flex: 1, minHeight: 0, padding: 12, display: 'flex' }}>
            <TerminalConsole lines={lines} sseState={sseState} buildNumber={buildNumber} />
          </div>
        </div>
      )}

      {activeTab === 'steps' && (
        <div className="bd-tab-body">
          {sel ? (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
              <NodeDetail node={sel} />
              <OutputsPanel outputs={sel.outputs} />
            </div>
          ) : (
            <p style={{ color: 'var(--fg-dim)', fontSize: 12 }}>
              Select a step in the pipeline to see details.
            </p>
          )}
        </div>
      )}

      {activeTab === 'tests' && (
        <div className="bd-tab-body">
          <TestResultsPanel buildId={buildId} />
        </div>
      )}

      {activeTab === 'artifacts' && (
        <div className="bd-tab-body">
          <ArtifactsPanel buildId={buildId} />
        </div>
      )}

      {activeTab === 'yaml' && (
        <div className="bd-tab-body">
          <PipelineYamlPanel pipelineScript={pipelineScript} />
        </div>
      )}
    </main>
  )
}
