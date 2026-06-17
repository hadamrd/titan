/**
 * Per-stage timing breakdown for a build (closes #670).
 *
 * On a long failed build, SREs need to spot which stage dominated wallclock
 * without paging through every node. This panel renders one horizontal bar
 * per STAGE-type flow node, in declared YAML order (NOT longest-first — the
 * pipeline narrative is half the diagnostic value).
 *
 * <p>Each bar:
 *   - width  = (finished_at − started_at), normalised to the longest stage
 *   - colour = status, via shared {@link buildStatusColor}
 *   - label  = stage name on the left, duration on the right
 *
 * <p>Edge cases handled:
 *   - RUNNING stage (no finishedAt) → width uses (now − startedAt) and a
 *     muted bar with a calm pulse indicator
 *   - skipped / instant stage (duration ≤ 0) → 1px-min bar so the row still
 *     reads visually
 *   - empty input → tasteful empty-state placeholder
 *
 * <p>Clicking a row invokes {@link Props.onSelectStage} (the same handler the
 * flow graph uses for selection) so the user can drill into a stage's logs
 * directly from the timing view.
 *
 * <p>v3 design: oklch tokens only, calm motion (respects
 * prefers-reduced-motion), no rainbow.
 */
import { useEffect, useState } from 'react'
import { ApiError, type FlowNodeDto } from '@/api/types'
import { useRetryStage } from '@/api/hooks'
import { useAuthRoles, hasRole } from '@/lib/auth'
import { buildStatusColor } from '@/lib/buildStatusColor'

const MIN_BAR_PX = 1
const PULSE_KEYFRAME_NAME = 'titan-stage-pulse'

export interface StageTimingPanelProps {
  /** All flow nodes for the build. Component filters to STAGE-type itself. */
  nodes: readonly FlowNodeDto[]
  /** Click handler — receives the stage node's id. Mirrors the flow graph's onSelect. */
  onSelectStage?: (nodeId: string) => void
  /** Optional currently-selected node id (for visual highlight parity with the graph). */
  selectedNodeId?: string | null
  /**
   * Build id powering this panel — required to wire the per-FAILED-stage
   * "Retry" button (#748). Omit (or pass undefined) to render the read-only
   * panel without retry affordances (e.g. archival views / compare).
   */
  buildId?: number
}

function isStageNode(n: FlowNodeDto): boolean {
  const t = n.nodeType?.toUpperCase() ?? ''
  return t === 'STAGE' || t === 'SECTION' || t === 'GROUP'
}

/** Compute elapsed ms for a stage. RUNNING (no completedAt) → now − startedAt. */
export function stageElapsedMs(n: FlowNodeDto, nowMs: number): number {
  if (!n.startedAt) return 0
  const started = Date.parse(n.startedAt)
  if (Number.isNaN(started)) return 0
  if (n.completedAt) {
    const finished = Date.parse(n.completedAt)
    if (Number.isNaN(finished)) return 0
    return Math.max(0, finished - started)
  }
  return Math.max(0, nowMs - started)
}

/** Short human duration, e.g. "0s" "12s" "2m 14s" "1h 03m". Used right-aligned. */
export function formatStageDuration(ms: number): string {
  if (!Number.isFinite(ms) || ms < 0) return '—'
  const s = Math.round(ms / 1000)
  if (s < 1) return '0s'
  if (s < 60) return `${s}s`
  const m = Math.floor(s / 60)
  const rem = s % 60
  if (m < 60) return rem === 0 ? `${m}m` : `${m}m ${rem}s`
  const h = Math.floor(m / 60)
  const mm = m % 60
  return mm === 0 ? `${h}h` : `${h}h ${String(mm).padStart(2, '0')}m`
}

export function StageTimingPanel({
  nodes,
  onSelectStage,
  selectedNodeId,
  buildId,
}: StageTimingPanelProps) {
  const stages = nodes.filter(isStageNode)
  const hasRunning = stages.some((n) => !n.completedAt && n.startedAt)

  // Tick once a second WHILE a stage is running so the elapsed bar grows.
  // No interval at all once everything is terminal — keeps the page calm.
  const [nowMs, setNowMs] = useState<number>(() => Date.now())
  useEffect(() => {
    if (!hasRunning) return
    const handle = window.setInterval(() => setNowMs(Date.now()), 1000)
    return () => window.clearInterval(handle)
  }, [hasRunning])

  // Per-FAILED-stage retry wiring (#748). All hooks ALWAYS run — never gate
  // a hook behind buildId being defined (CONSTITUTION §6: no hooks after
  // early-return). We pass buildId ?? 0 to the mutation and rely on the UI
  // to never expose the button when buildId is absent. The role check runs
  // unconditionally too so the button state stays stable across re-renders.
  const roles = useAuthRoles()
  const canRetry = hasRole(roles, 'REPLAY_BUILD') || hasRole(roles, 'ADMIN')
  const retry = useRetryStage(buildId ?? 0)
  const [retryNotice, setRetryNotice] = useState<
    { kind: 'pending' | 'ok' | 'err'; stageId: string; message: string } | null
  >(null)

  const onRetryClick = (stageId: string) => {
    if (buildId === undefined) return
    setRetryNotice({ kind: 'pending', stageId, message: 'Retrying…' })
    retry.mutate(
      { stageId },
      {
        onSuccess: (outcome) => {
          setRetryNotice({
            kind: 'ok',
            stageId,
            message: `Stage retried — ${outcome.resetNodeIds.length} node${
              outcome.resetNodeIds.length === 1 ? '' : 's'
            } reset.`,
          })
        },
        onError: (err) => {
          const message =
            err instanceof ApiError
              ? err.status === 409
                ? 'Cannot retry — the stage is no longer in FAILED state.'
                : err.status === 403
                  ? 'Forbidden — you need REPLAY_BUILD to retry stages.'
                  : err.status === 404
                    ? 'Stage no longer exists — refresh the build view.'
                    : (err.problem.detail ?? `Retry failed (${err.status}).`)
              : 'Retry failed — network error.'
          setRetryNotice({ kind: 'err', stageId, message })
        },
      },
    )
  }

  if (stages.length === 0) {
    return (
      <div
        data-testid="stage-timing-empty"
        style={{
          padding: '12px 16px',
          fontSize: 12,
          color: 'var(--fg-dim)',
        }}
      >
        No stages recorded for this build.
      </div>
    )
  }

  const durations = stages.map((n) => stageElapsedMs(n, nowMs))
  const maxDuration = durations.reduce((m, d) => (d > m ? d : m), 0)

  return (
    <div
      data-testid="stage-timing-panel"
      style={{
        display: 'flex',
        flexDirection: 'column',
        gap: 4,
        padding: '10px 14px',
      }}
    >
      {/* Inline keyframes — kept here (and not in a global stylesheet) so the
          component stays self-contained. CSP-clean: no inline <script>, just
          a <style> block that respects prefers-reduced-motion. */}
      <style>{`
        @keyframes ${PULSE_KEYFRAME_NAME} {
          0%, 100% { opacity: 0.55; }
          50%      { opacity: 0.85; }
        }
        @media (prefers-reduced-motion: reduce) {
          [data-testid="stage-timing-running-pulse"] {
            animation: none !important;
            opacity: 0.75 !important;
          }
        }
      `}</style>

      {retryNotice && (
        <div
          data-testid={`stage-retry-notice-${retryNotice.stageId}`}
          data-kind={retryNotice.kind}
          role={retryNotice.kind === 'err' ? 'alert' : 'status'}
          style={{
            fontSize: 11,
            padding: '4px 8px',
            marginBottom: 4,
            borderRadius: 3,
            color:
              retryNotice.kind === 'err'
                ? 'var(--fail)'
                : retryNotice.kind === 'ok'
                  ? 'var(--ok)'
                  : 'var(--fg-dim)',
            background: 'var(--bg-2)',
            border: '1px solid var(--border)',
          }}
        >
          {retryNotice.message}
        </div>
      )}

      {stages.map((stage, i) => {
        const elapsed = durations[i] ?? 0
        const running = !stage.completedAt && !!stage.startedAt
        const ratio = maxDuration > 0 ? elapsed / maxDuration : 0
        const widthPct = Math.max(0, Math.min(1, ratio)) * 100
        const colour = running ? 'var(--muted)' : buildStatusColor(stage.status)
        const isSelected = selectedNodeId === stage.nodeId
        const label = stage.displayName ?? stage.nodeType
        // Retry affordance — visible only when (a) the panel knows the build
        // id, and (b) this stage actually ended FAILED. Disabled (with a
        // tooltip explaining why) when the caller lacks REPLAY_BUILD/ADMIN.
        // We mirror the server-side gate exactly so a 403 is unreachable in
        // the happy path; the inline notice covers the unhappy edge cases.
        const showRetry = buildId !== undefined && stage.status === 'FAILED'
        const retryDisabled = !canRetry || retry.isPending
        const retryTip = !canRetry
          ? 'You need REPLAY_BUILD to retry stages'
          : retry.isPending
            ? 'Retry already in flight'
            : 'Re-run this stage in place (resets the stage + DAG descendants to QUEUED)'
        return (
          <div
            key={stage.nodeId}
            style={{
              display: 'grid',
              gridTemplateColumns: showRetry ? '180px 1fr 72px 64px' : '180px 1fr 72px',
              alignItems: 'center',
              gap: 10,
              padding: '4px 6px',
              borderRadius: 4,
              background: isSelected ? 'var(--bg-2)' : 'transparent',
              outline: isSelected ? '1px solid var(--border)' : 'none',
            }}
          >
          <button
            type="button"
            data-testid={`stage-timing-row-${stage.nodeId}`}
            data-status={stage.status}
            data-running={running ? 'true' : undefined}
            data-selected={isSelected ? 'true' : undefined}
            onClick={() => onSelectStage?.(stage.nodeId)}
            title={`${label} · ${stage.status} · ${formatStageDuration(elapsed)}`}
            style={{
              all: 'unset',
              cursor: onSelectStage ? 'pointer' : 'default',
              display: 'grid',
              gridTemplateColumns: '180px 1fr 72px',
              gridColumn: '1 / span 3',
              alignItems: 'center',
              gap: 10,
              fontSize: 12,
            }}
          >
            <span
              style={{
                color: 'var(--fg)',
                overflow: 'hidden',
                textOverflow: 'ellipsis',
                whiteSpace: 'nowrap',
              }}
            >
              {label}
            </span>
            <span
              data-testid={`stage-timing-track-${stage.nodeId}`}
              style={{
                position: 'relative',
                height: 10,
                background: 'var(--bg-2)',
                borderRadius: 2,
                overflow: 'hidden',
              }}
            >
              <span
                data-testid={`stage-timing-bar-${stage.nodeId}`}
                style={{
                  position: 'absolute',
                  left: 0,
                  top: 0,
                  bottom: 0,
                  // Always render at least MIN_BAR_PX so a skipped/zero stage
                  // still reads as a row.
                  width: `max(${MIN_BAR_PX}px, ${widthPct.toFixed(2)}%)`,
                  background: colour,
                  borderRadius: 2,
                }}
              />
              {running && (
                <span
                  data-testid="stage-timing-running-pulse"
                  aria-hidden="true"
                  style={{
                    position: 'absolute',
                    inset: 0,
                    background:
                      'linear-gradient(90deg, transparent, var(--fg-dim) 50%, transparent)',
                    opacity: 0.55,
                    animation: `${PULSE_KEYFRAME_NAME} 1.8s ease-in-out infinite`,
                    pointerEvents: 'none',
                  }}
                />
              )}
            </span>
            <span
              style={{
                fontFamily: 'var(--font-mono)',
                fontVariantNumeric: 'tabular-nums',
                color: 'var(--fg-dim)',
                textAlign: 'right',
              }}
            >
              {formatStageDuration(elapsed)}
            </span>
          </button>
          {showRetry && (
            <button
              type="button"
              data-testid={`stage-retry-${stage.nodeId}`}
              data-disabled={retryDisabled ? 'true' : undefined}
              disabled={retryDisabled}
              aria-disabled={retryDisabled || undefined}
              title={retryTip}
              onClick={(e) => {
                e.stopPropagation()
                onRetryClick(stage.nodeId)
              }}
              style={{
                all: 'unset',
                cursor: retryDisabled ? 'not-allowed' : 'pointer',
                fontSize: 11,
                padding: '2px 8px',
                borderRadius: 3,
                textAlign: 'center',
                color: retryDisabled ? 'var(--fg-dim)' : 'var(--fg)',
                background: 'var(--bg-2)',
                border: '1px solid var(--border)',
                opacity: retryDisabled ? 0.6 : 1,
              }}
            >
              {retry.isPending && retryNotice?.stageId === stage.nodeId
                ? 'Retrying…'
                : 'Retry'}
            </button>
          )}
          </div>
        )
      })}
    </div>
  )
}
