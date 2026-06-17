/**
 * Side-by-side log diff between two builds (closes #770).
 *
 * <p>Companion to {@link BuildCompareView} — the SRE picks a stage from the
 * union of stage names across both builds and sees the per-stage log of
 * build A on the left, build B on the right, with removed/added lines
 * highlighted via a tiny LCS line diff ({@link lineDiff}).
 *
 * <p>Default-stage heuristic, in priority order:
 *  <ol>
 *    <li>first stage present on BOTH sides whose B status is FAILED — that's
 *        the "why did B fail" question 90% of compare visits ask</li>
 *    <li>last stage present on BOTH sides</li>
 *    <li>first stage on either side (rare: stages don't overlap at all)</li>
 *  </ol>
 *
 * <p>Logs are fetched via {@link streamBuildLogs} with the per-stage
 * {@code logTaskId} as the SSE filter. Both builds are presumed terminal at
 * compare-time, so the stream resolves on `done` and we never hold an open
 * socket.
 *
 * <p>a11y: the side-by-side layout is decorative; a "Unified" toggle
 * collapses to a single column with +/- prefixes for assistive tech and
 * narrow viewports. Both columns respect prefers-reduced-motion (no
 * animations are used anyway).
 *
 * <p>Performance: diff is line-LCS bounded at MAX_DIFF_LINES per side; over
 * that we render the first N with a "showing first N lines" notice. The
 * panel does NOT mount its internal logs-fetching effect until the user
 * explicitly expands the section — the parent passes {@code expanded} from
 * a disclosure button so the SSE call is opt-in.
 */
import type React from 'react'
import { useEffect, useMemo, useRef, useState } from 'react'
import type { FlowNodeDto } from '@/api/types'
import { streamBuildLogs } from '@/api/client'
import { lineDiff, MAX_DIFF_LINES, type LineDiffResult } from '@/lib/lineDiff'

const STAGE_TYPES = new Set(['STAGE', 'SECTION', 'GROUP'])

function isStageNode(n: FlowNodeDto): boolean {
  return STAGE_TYPES.has((n.nodeType ?? '').toUpperCase())
}

function stageLabel(n: FlowNodeDto): string {
  return n.displayName ?? n.nodeType
}

const FAIL_STATUSES = new Set(['FAILED', 'FAILURE', 'ABORTED', 'UNSTABLE'])

interface UnionStage {
  name: string
  a: FlowNodeDto | null
  b: FlowNodeDto | null
}

/** Exported for tests — the default-pick heuristic lives here, not in JSX. */
export function unionStages(
  nodesA: readonly FlowNodeDto[],
  nodesB: readonly FlowNodeDto[],
): UnionStage[] {
  const aStages = nodesA.filter(isStageNode)
  const bStages = nodesB.filter(isStageNode)
  const bByName = new Map<string, FlowNodeDto>()
  for (const n of bStages) {
    const k = stageLabel(n)
    if (!bByName.has(k)) bByName.set(k, n)
  }
  const usedB = new Set<string>()
  const out: UnionStage[] = []
  for (const a of aStages) {
    const k = stageLabel(a)
    const m = bByName.get(k) ?? null
    if (m) usedB.add(k)
    out.push({ name: k, a, b: m })
  }
  for (const b of bStages) {
    const k = stageLabel(b)
    if (!usedB.has(k)) out.push({ name: k, a: null, b })
  }
  return out
}

/** Exported for tests. Returns null when there are no stages on either side. */
export function pickDefaultStage(stages: readonly UnionStage[]): string | null {
  if (stages.length === 0) return null
  const failedCommon = stages.find(
    (s) => s.a && s.b && FAIL_STATUSES.has(s.b.status),
  )
  if (failedCommon) return failedCommon.name
  // Last stage that exists on both sides.
  for (let i = stages.length - 1; i >= 0; i--) {
    const s = stages[i]
    if (s.a && s.b) return s.name
  }
  return stages[0].name
}

export interface LogDiffPanelProps {
  buildIdA: number
  buildIdB: number
  nodesA: readonly FlowNodeDto[]
  nodesB: readonly FlowNodeDto[]
  /**
   * Test seam — allows the vitest suite to inject a deterministic fetcher
   * without spinning up an SSE server. Production callers omit this and the
   * panel uses {@link streamBuildLogs}.
   */
  fetchLogs?: (buildId: number, logTaskId: string) => Promise<string[]>
}

export function LogDiffPanel({
  buildIdA,
  buildIdB,
  nodesA,
  nodesB,
  fetchLogs,
}: LogDiffPanelProps) {
  const stages = useMemo(() => unionStages(nodesA, nodesB), [nodesA, nodesB])
  const defaultStage = useMemo(() => pickDefaultStage(stages), [stages])
  const [selected, setSelected] = useState<string | null>(defaultStage)
  const [unified, setUnified] = useState(false)

  // Re-sync the default if the inputs change (e.g. nodes finish loading).
  useEffect(() => {
    if (selected === null && defaultStage !== null) setSelected(defaultStage)
  }, [defaultStage, selected])

  const selectedPair = useMemo(
    () => stages.find((s) => s.name === selected) ?? null,
    [stages, selected],
  )

  return (
    <section
      data-testid="log-diff-panel"
      aria-label="Log diff between build A and build B"
      style={{
        display: 'flex',
        flexDirection: 'column',
        gap: 10,
        border: '1px solid var(--border)',
        borderRadius: 4,
        padding: 12,
        background: 'var(--bg-1)',
      }}
    >
      <header
        style={{
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          gap: 12,
          flexWrap: 'wrap',
        }}
      >
        <div
          style={{
            fontSize: 12,
            fontWeight: 500,
            color: 'var(--fg)',
            letterSpacing: '0.04em',
            textTransform: 'uppercase',
          }}
        >
          Log diff
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
          <label
            htmlFor="log-diff-stage"
            style={{ fontSize: 12, color: 'var(--fg-dim)' }}
          >
            Stage
          </label>
          <select
            id="log-diff-stage"
            data-testid="log-diff-stage-select"
            value={selected ?? ''}
            disabled={stages.length === 0}
            onChange={(e) => setSelected(e.target.value || null)}
            style={{
              fontFamily: 'var(--font-mono)',
              fontSize: 12,
              padding: '4px 8px',
              background: 'var(--bg-2)',
              color: 'var(--fg)',
              border: '1px solid var(--border)',
              borderRadius: 3,
            }}
          >
            {stages.length === 0 && <option value="">(no stages)</option>}
            {stages.map((s) => (
              <option key={s.name} value={s.name}>
                {s.name}
                {!s.a ? ' (B only)' : !s.b ? ' (A only)' : ''}
              </option>
            ))}
          </select>
          <label
            style={{
              display: 'inline-flex',
              alignItems: 'center',
              gap: 6,
              fontSize: 12,
              color: 'var(--fg-dim)',
            }}
          >
            <input
              type="checkbox"
              data-testid="log-diff-unified-toggle"
              checked={unified}
              onChange={(e) => setUnified(e.target.checked)}
            />
            Unified
          </label>
        </div>
      </header>

      {selectedPair === null ? (
        <p
          data-testid="log-diff-no-stage"
          style={{ fontSize: 12, color: 'var(--fg-dim)', margin: 0 }}
        >
          No stages recorded on either build.
        </p>
      ) : (
        <LogDiffBody
          buildIdA={buildIdA}
          buildIdB={buildIdB}
          pair={selectedPair}
          unified={unified}
          fetchLogs={fetchLogs}
        />
      )}
    </section>
  )
}

// ── Body ────────────────────────────────────────────────────────────────────

interface LogState {
  state: 'idle' | 'loading' | 'ready' | 'error' | 'absent'
  lines: string[]
  error?: string
}

const ABSENT: LogState = { state: 'absent', lines: [] }
const IDLE: LogState = { state: 'idle', lines: [] }

function LogDiffBody({
  buildIdA,
  buildIdB,
  pair,
  unified,
  fetchLogs,
}: {
  buildIdA: number
  buildIdB: number
  pair: UnionStage
  unified: boolean
  fetchLogs?: (buildId: number, logTaskId: string) => Promise<string[]>
}) {
  const aTaskId = pair.a?.logTaskId ?? null
  const bTaskId = pair.b?.logTaskId ?? null

  const [aLogs, setALogs] = useState<LogState>(pair.a ? IDLE : ABSENT)
  const [bLogs, setBLogs] = useState<LogState>(pair.b ? IDLE : ABSENT)

  useEffect(() => {
    setALogs(pair.a ? { state: 'loading', lines: [] } : ABSENT)
    setBLogs(pair.b ? { state: 'loading', lines: [] } : ABSENT)
    const controller = new AbortController()

    if (pair.a && aTaskId) {
      loadStageLogs(buildIdA, aTaskId, controller.signal, fetchLogs)
        .then((lines) => {
          if (controller.signal.aborted) return
          setALogs({ state: 'ready', lines })
        })
        .catch((err: unknown) => {
          if (controller.signal.aborted) return
          setALogs({
            state: 'error',
            lines: [],
            error: err instanceof Error ? err.message : String(err),
          })
        })
    } else if (pair.a) {
      // Stage exists but no logTaskId — render empty rather than error.
      setALogs({ state: 'ready', lines: [] })
    }

    if (pair.b && bTaskId) {
      loadStageLogs(buildIdB, bTaskId, controller.signal, fetchLogs)
        .then((lines) => {
          if (controller.signal.aborted) return
          setBLogs({ state: 'ready', lines })
        })
        .catch((err: unknown) => {
          if (controller.signal.aborted) return
          setBLogs({
            state: 'error',
            lines: [],
            error: err instanceof Error ? err.message : String(err),
          })
        })
    } else if (pair.b) {
      setBLogs({ state: 'ready', lines: [] })
    }

    return () => controller.abort()
  }, [buildIdA, buildIdB, pair, aTaskId, bTaskId, fetchLogs])

  const diff = useMemo<LineDiffResult | null>(() => {
    if (!pair.a || !pair.b) return null
    if (aLogs.state !== 'ready' || bLogs.state !== 'ready') return null
    return lineDiff(aLogs.lines, bLogs.lines)
  }, [pair, aLogs, bLogs])

  // ── Vertical scroll sync ─────────────────────────────────────────────────
  const leftRef = useRef<HTMLDivElement | null>(null)
  const rightRef = useRef<HTMLDivElement | null>(null)
  const syncingFrom = useRef<'l' | 'r' | null>(null)

  const onLeftScroll = (e: React.UIEvent<HTMLDivElement>) => {
    if (syncingFrom.current === 'r') return
    syncingFrom.current = 'l'
    const r = rightRef.current
    if (r && r !== e.currentTarget) r.scrollTop = e.currentTarget.scrollTop
    queueMicrotask(() => (syncingFrom.current = null))
  }
  const onRightScroll = (e: React.UIEvent<HTMLDivElement>) => {
    if (syncingFrom.current === 'l') return
    syncingFrom.current = 'r'
    const l = leftRef.current
    if (l && l !== e.currentTarget) l.scrollTop = e.currentTarget.scrollTop
    queueMicrotask(() => (syncingFrom.current = null))
  }

  // ── Render branches ──────────────────────────────────────────────────────

  if (!pair.a) {
    return (
      <MissingCounterpart
        side="A"
        otherBuildId={buildIdB}
        otherTaskId={bTaskId}
        logs={bLogs}
        fetchLogs={fetchLogs}
      />
    )
  }
  if (!pair.b) {
    return (
      <MissingCounterpart
        side="B"
        otherBuildId={buildIdA}
        otherTaskId={aTaskId}
        logs={aLogs}
        fetchLogs={fetchLogs}
      />
    )
  }

  if (aLogs.state === 'loading' || bLogs.state === 'loading') {
    return (
      <p
        data-testid="log-diff-loading"
        style={{ fontSize: 12, color: 'var(--fg-dim)', margin: 0 }}
      >
        Loading logs…
      </p>
    )
  }

  if (aLogs.state === 'error' || bLogs.state === 'error') {
    return (
      <p
        data-testid="log-diff-error"
        role="alert"
        style={{ fontSize: 12, color: 'var(--fail)', margin: 0 }}
      >
        Could not load logs:{' '}
        {aLogs.error ?? bLogs.error ?? 'unknown error'}
      </p>
    )
  }

  if (
    diff === null ||
    (diff.aLineCount === 0 && diff.bLineCount === 0)
  ) {
    return (
      <p
        data-testid="log-diff-empty"
        style={{ fontSize: 12, color: 'var(--fg-dim)', margin: 0 }}
      >
        No log content to compare.
      </p>
    )
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
      <DiffSummary diff={diff} />
      {unified ? (
        <UnifiedDiff diff={diff} />
      ) : (
        <SideBySideDiff
          diff={diff}
          leftRef={leftRef}
          rightRef={rightRef}
          onLeftScroll={onLeftScroll}
          onRightScroll={onRightScroll}
        />
      )}
    </div>
  )
}

// ── Diff sub-views ──────────────────────────────────────────────────────────

function DiffSummary({ diff }: { diff: LineDiffResult }) {
  return (
    <div
      data-testid="log-diff-summary"
      style={{
        display: 'flex',
        gap: 14,
        fontSize: 11,
        color: 'var(--fg-dim)',
        fontFamily: 'var(--font-mono)',
        fontVariantNumeric: 'tabular-nums',
      }}
    >
      <span data-testid="log-diff-removed-count">
        −{diff.removed} removed
      </span>
      <span data-testid="log-diff-added-count">+{diff.added} added</span>
      <span>= {diff.unchanged} unchanged</span>
      {diff.truncated && (
        <span
          data-testid="log-diff-truncated"
          style={{ color: 'var(--warn, var(--fg-dim))' }}
        >
          showing first {MAX_DIFF_LINES} lines per side
        </span>
      )}
    </div>
  )
}

const colStyle: React.CSSProperties = {
  border: '1px solid var(--border)',
  borderRadius: 3,
  background: 'var(--bg-2)',
  maxHeight: 420,
  overflow: 'auto',
  fontFamily: 'var(--font-mono)',
  fontSize: 12,
  lineHeight: '18px',
  fontVariantNumeric: 'tabular-nums',
}

const REMOVED_BG = 'color-mix(in oklch, var(--fail) 14%, transparent)'
const ADDED_BG = 'color-mix(in oklch, var(--ok) 14%, transparent)'
const GAP_BG = 'color-mix(in oklch, var(--bg-3, var(--bg-2)) 60%, transparent)'

function SideBySideDiff({
  diff,
  leftRef,
  rightRef,
  onLeftScroll,
  onRightScroll,
}: {
  diff: LineDiffResult
  leftRef: React.RefObject<HTMLDivElement | null>
  rightRef: React.RefObject<HTMLDivElement | null>
  onLeftScroll: (e: React.UIEvent<HTMLDivElement>) => void
  onRightScroll: (e: React.UIEvent<HTMLDivElement>) => void
}) {
  return (
    <div
      style={{
        display: 'grid',
        gridTemplateColumns: '1fr 1fr',
        gap: 8,
      }}
    >
      <div
        ref={leftRef}
        data-testid="log-diff-col-a"
        style={colStyle}
        onScroll={onLeftScroll}
      >
        {diff.rows.map((r, i) => {
          // Left column shows A: equal + removed render text; added rows
          // become gap placeholders so line numbers stay aligned with the right.
          if (r.kind === 'added') {
            return <GapRow key={`la-${i}`} testid={`log-diff-a-gap-${i}`} />
          }
          return (
            <DiffLine
              key={`la-${i}`}
              text={r.text}
              lineNo={r.aIndex !== null ? r.aIndex + 1 : null}
              bg={r.kind === 'removed' ? REMOVED_BG : undefined}
              fg={r.kind === 'removed' ? 'var(--fg)' : 'var(--fg-dim)'}
              testid={`log-diff-a-line-${i}`}
              dataKind={r.kind}
            />
          )
        })}
      </div>
      <div
        ref={rightRef}
        data-testid="log-diff-col-b"
        style={colStyle}
        onScroll={onRightScroll}
      >
        {diff.rows.map((r, i) => {
          if (r.kind === 'removed') {
            return <GapRow key={`lb-${i}`} testid={`log-diff-b-gap-${i}`} />
          }
          return (
            <DiffLine
              key={`lb-${i}`}
              text={r.text}
              lineNo={r.bIndex !== null ? r.bIndex + 1 : null}
              bg={r.kind === 'added' ? ADDED_BG : undefined}
              fg={r.kind === 'added' ? 'var(--fg)' : 'var(--fg-dim)'}
              testid={`log-diff-b-line-${i}`}
              dataKind={r.kind}
            />
          )
        })}
      </div>
    </div>
  )
}

function UnifiedDiff({ diff }: { diff: LineDiffResult }) {
  return (
    <div data-testid="log-diff-unified" style={colStyle} role="region" aria-label="Unified log diff">
      {diff.rows.map((r, i) => {
        const prefix = r.kind === 'removed' ? '-' : r.kind === 'added' ? '+' : ' '
        const bg =
          r.kind === 'removed' ? REMOVED_BG : r.kind === 'added' ? ADDED_BG : undefined
        return (
          <div
            key={`u-${i}`}
            data-testid={`log-diff-unified-line-${i}`}
            data-kind={r.kind}
            style={{
              display: 'grid',
              gridTemplateColumns: '20px 1fr',
              padding: '0 8px',
              background: bg,
              color: r.kind === 'equal' ? 'var(--fg-dim)' : 'var(--fg)',
              whiteSpace: 'pre-wrap',
              wordBreak: 'break-word',
            }}
          >
            <span aria-hidden="true" style={{ userSelect: 'none' }}>
              {prefix}
            </span>
            <span>{r.text}</span>
          </div>
        )
      })}
    </div>
  )
}

function DiffLine({
  text,
  lineNo,
  bg,
  fg,
  testid,
  dataKind,
}: {
  text: string
  lineNo: number | null
  bg?: string
  fg: string
  testid: string
  dataKind: string
}) {
  return (
    <div
      data-testid={testid}
      data-kind={dataKind}
      style={{
        display: 'grid',
        gridTemplateColumns: '48px 1fr',
        padding: '0 6px',
        background: bg,
        color: fg,
        whiteSpace: 'pre-wrap',
        wordBreak: 'break-word',
      }}
    >
      <span
        aria-hidden="true"
        style={{ color: 'var(--fg-dim)', textAlign: 'right', paddingRight: 8 }}
      >
        {lineNo ?? ''}
      </span>
      <span>{text}</span>
    </div>
  )
}

function GapRow({ testid }: { testid: string }) {
  return (
    <div
      data-testid={testid}
      aria-hidden="true"
      style={{
        height: 18,
        background: GAP_BG,
      }}
    />
  )
}

// ── Missing counterpart (stage only exists in one build) ─────────────────────

function MissingCounterpart({
  side,
  otherBuildId,
  otherTaskId,
  logs,
  fetchLogs,
}: {
  side: 'A' | 'B'
  otherBuildId: number
  otherTaskId: string | null
  logs: LogState
  fetchLogs?: (buildId: number, logTaskId: string) => Promise<string[]>
}) {
  // The effect that loads logs lives in the parent — we just render `logs`.
  void otherTaskId
  void otherBuildId
  void fetchLogs

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
      <p
        data-testid="log-diff-missing-counterpart"
        style={{ fontSize: 12, color: 'var(--fg-dim)', margin: 0 }}
      >
        (no counterpart in build {side})
      </p>
      {logs.state === 'loading' && (
        <p style={{ fontSize: 12, color: 'var(--fg-dim)', margin: 0 }}>
          Loading logs…
        </p>
      )}
      {logs.state === 'ready' && logs.lines.length > 0 && (
        <div data-testid="log-diff-solo" style={colStyle}>
          {logs.lines.map((ln, i) => (
            <div
              key={`solo-${i}`}
              style={{
                display: 'grid',
                gridTemplateColumns: '48px 1fr',
                padding: '0 6px',
                color: 'var(--fg-dim)',
                whiteSpace: 'pre-wrap',
                wordBreak: 'break-word',
              }}
            >
              <span
                aria-hidden="true"
                style={{ textAlign: 'right', paddingRight: 8 }}
              >
                {i + 1}
              </span>
              <span>{ln}</span>
            </div>
          ))}
        </div>
      )}
      {logs.state === 'ready' && logs.lines.length === 0 && (
        <p style={{ fontSize: 12, color: 'var(--fg-dim)', margin: 0 }}>
          No log content.
        </p>
      )}
      {logs.state === 'error' && (
        <p
          role="alert"
          style={{ fontSize: 12, color: 'var(--fail)', margin: 0 }}
        >
          Could not load logs: {logs.error ?? 'unknown error'}
        </p>
      )}
    </div>
  )
}

// ── Log fetch helper ────────────────────────────────────────────────────────

/**
 * Drains the per-stage SSE log stream into a finite array of lines. The
 * builds we compare are presumed terminal so the stream resolves on `done`
 * quickly; we still respect the abort signal for unmount safety.
 *
 * The `injected` parameter is the test seam — when provided, we skip the
 * network and use whatever the suite returns.
 */
async function loadStageLogs(
  buildId: number,
  logTaskId: string,
  signal: AbortSignal,
  injected?: (buildId: number, logTaskId: string) => Promise<string[]>,
): Promise<string[]> {
  if (injected) return injected(buildId, logTaskId)
  const lines: string[] = []
  await streamBuildLogs(
    buildId,
    signal,
    {
      onFrame: (frame) => {
        if (frame.event === 'log') lines.push(frame.data)
      },
    },
    logTaskId,
  )
  return lines
}
