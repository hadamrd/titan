/**
 * Side-by-side build comparison view (closes #716).
 *
 * <p>SREs investigating "why did this break" frequently want build N (last
 * green) vs build N+1 (first red) on one screen — stage timing, status, and
 * basic trigger metadata. This component is the visual surface; the route
 * file ({@code routes/builds/compare.tsx}) does the data fetching via
 * {@link useBuild} + {@link useBuildNodes} for each side and hands the
 * resolved data to this component.
 *
 * <p>Design floor (v3): oklch tokens, calm/dense, no rainbow. Stage-pair
 * rows reuse {@link buildStatusColor} for the leading dot and respect the
 * same status discipline as {@link StageTimingPanel}.
 *
 * <p>Pair-by-stage rule: we join stages by {@code displayName ?? nodeType}
 * (the human-visible label). Missing-on-one-side renders an em-dash on that
 * side. Status-flip rows are highlighted: regression ({@code ✓→✗}) gets a
 * subtle red wash, recovery ({@code ✗→✓}) a subtle green one.
 */
import type React from 'react'
import { useState } from 'react'
import type { ArtifactDto, BuildDto, FlowNodeDto } from '@/api/types'
import { LogDiffPanel } from '@/components/LogDiffPanel'
import { StatusBadge } from '@/components/StatusBadge'
import { buildStatusColor } from '@/lib/buildStatusColor'
import { formatBuildDuration, formatDate, formatDuration } from '@/lib/format'
import {
  artifactDiff,
  formatBytes,
  formatSizeDelta,
  type ArtifactDiffRow,
} from '@/lib/buildCompare'

// ── Stage helpers ───────────────────────────────────────────────────────────

const STAGE_TYPES = new Set(['STAGE', 'SECTION', 'GROUP'])

function isStageNode(n: FlowNodeDto): boolean {
  return STAGE_TYPES.has((n.nodeType ?? '').toUpperCase())
}

function stageLabel(n: FlowNodeDto): string {
  return n.displayName ?? n.nodeType
}

function stageMs(n: FlowNodeDto): number | null {
  if (typeof n.durationMs === 'number' && Number.isFinite(n.durationMs) && n.durationMs >= 0) {
    return n.durationMs
  }
  if (n.startedAt && n.completedAt) {
    const start = Date.parse(n.startedAt)
    const end = Date.parse(n.completedAt)
    if (Number.isFinite(start) && Number.isFinite(end)) {
      return Math.max(0, end - start)
    }
  }
  return null
}

/** Pretty-print signed millisecond delta — '+45s' / '-12s' / '=' for 0. */
function formatSignedDelta(ms: number): string {
  if (!Number.isFinite(ms)) return '—'
  if (ms === 0) return '='
  const sign = ms > 0 ? '+' : '-'
  return sign + formatDuration(Math.abs(ms))
}

export type StatusFlip = 'regression' | 'recovery' | 'none'

export function classifyStatusFlip(a: string, b: string): StatusFlip {
  const FAIL = new Set(['FAILED', 'FAILURE', 'ABORTED', 'UNSTABLE'])
  const aOk = a === 'SUCCESS'
  const bOk = b === 'SUCCESS'
  const aFail = FAIL.has(a)
  const bFail = FAIL.has(b)
  if (aOk && bFail) return 'regression'
  if (aFail && bOk) return 'recovery'
  return 'none'
}

/**
 * One paired-stage row. Either side may be {@code null} (stage present on
 * only one build). Exported for the test surface so each adversarial case
 * can assert against the row's shape directly.
 */
export interface StagePairRow {
  /** The stage label used for the join — non-empty. */
  name: string
  a: FlowNodeDto | null
  b: FlowNodeDto | null
}

/**
 * Pair stages by label, preserving A's declared order, then appending any
 * stages present only in B (in B's declared order). Stable ordering matters
 * — an SRE reading the table top-to-bottom should see the pipeline narrative.
 */
export function pairStagesByName(
  aNodes: readonly FlowNodeDto[],
  bNodes: readonly FlowNodeDto[],
): StagePairRow[] {
  const aStages = aNodes.filter(isStageNode)
  const bStages = bNodes.filter(isStageNode)
  const bByName = new Map<string, FlowNodeDto>()
  for (const n of bStages) {
    const key = stageLabel(n)
    // First occurrence wins — duplicate stage names within a build are degenerate
    // but we keep behaviour predictable rather than throwing.
    if (!bByName.has(key)) bByName.set(key, n)
  }
  const usedB = new Set<string>()
  const rows: StagePairRow[] = []
  for (const a of aStages) {
    const key = stageLabel(a)
    const match = bByName.get(key) ?? null
    if (match) usedB.add(key)
    rows.push({ name: key, a, b: match })
  }
  for (const b of bStages) {
    const key = stageLabel(b)
    if (!usedB.has(key) && !rows.some((r) => r.a === null && r.name === key)) {
      rows.push({ name: key, a: null, b })
    }
  }
  return rows
}

// ── Component ───────────────────────────────────────────────────────────────

export interface BuildCompareViewProps {
  buildA: BuildDto
  buildB: BuildDto
  nodesA: readonly FlowNodeDto[]
  nodesB: readonly FlowNodeDto[]
  /** Display names for each side's job — used in the header + cross-job warning. */
  jobNameA?: string | null
  jobNameB?: string | null
  /** Artifacts published by each side. Optional — empty = no artifact panel. */
  artifactsA?: readonly ArtifactDto[]
  artifactsB?: readonly ArtifactDto[]
  /**
   * {@code true} when the route resolved A === B. Renders a friendly notice
   * but still draws the table — the diff machinery is the actual sanity-test
   * surface here.
   */
  sameBuild?: boolean
}

export function BuildCompareView({
  buildA,
  buildB,
  nodesA,
  nodesB,
  jobNameA,
  jobNameB,
  artifactsA,
  artifactsB,
  sameBuild,
}: BuildCompareViewProps) {
  const rows = pairStagesByName(nodesA, nodesB)
  const artifactRows = artifactDiff(artifactsA ?? [], artifactsB ?? [])
  const crossJob = buildA.jobId !== buildB.jobId
  // Lazy mount the log-diff section — the SSE call is opt-in (closes #770).
  const [logDiffOpen, setLogDiffOpen] = useState(false)

  return (
    <div
      data-testid="build-compare-view"
      style={{
        display: 'flex',
        flexDirection: 'column',
        gap: 12,
        padding: 16,
      }}
    >
      {sameBuild && (
        <div
          data-testid="compare-same-build-notice"
          role="status"
          style={{
            padding: '8px 12px',
            fontSize: 12,
            color: 'var(--fg-muted)',
            background: 'var(--bg-2)',
            border: '1px solid var(--border)',
            borderLeft: '3px solid var(--ok, var(--muted))',
            borderRadius: 4,
          }}
        >
          Comparing build #{buildA.buildNumber} with itself — every delta will
          read "=" by construction. Pick a different build from the Compare
          menu to see a meaningful diff.
        </div>
      )}

      {crossJob && (
        <div
          data-testid="compare-cross-job-warning"
          role="status"
          style={{
            padding: '8px 12px',
            fontSize: 12,
            color: 'var(--fg-muted)',
            background: 'var(--bg-2)',
            border: '1px solid var(--border)',
            borderLeft: '3px solid var(--warn, var(--muted))',
            borderRadius: 4,
          }}
        >
          (different jobs — comparison may not be meaningful)
        </div>
      )}

      {/* ───────── 2-column summary ───────── */}
      <div
        data-testid="compare-summary"
        style={{
          display: 'grid',
          gridTemplateColumns: '1fr 1fr',
          gap: 12,
        }}
      >
        <BuildSummaryCard build={buildA} jobName={jobNameA} side="a" />
        <BuildSummaryCard build={buildB} jobName={jobNameB} side="b" />
      </div>

      {/* ───────── Stage-by-stage table ───────── */}
      {rows.length === 0 ? (
        <div
          data-testid="compare-stages-empty"
          style={{
            padding: '12px 16px',
            fontSize: 12,
            color: 'var(--fg-dim)',
            border: '1px solid var(--border)',
            borderRadius: 4,
          }}
        >
          No stages recorded on either build.
        </div>
      ) : (
        <div
          data-testid="compare-stages-table-wrap"
          style={{
            border: '1px solid var(--border)',
            borderRadius: 4,
            overflow: 'hidden',
          }}
        >
          <table
            data-testid="compare-stages-table"
            style={{
              width: '100%',
              borderCollapse: 'collapse',
              fontSize: 12,
            }}
          >
            <thead>
              <tr style={{ background: 'var(--bg-2)', color: 'var(--fg-dim)' }}>
                <th style={thStyle}>Stage</th>
                <th style={thStyle}>A · #{buildA.buildNumber}</th>
                <th style={thStyle}>B · #{buildB.buildNumber}</th>
                <th style={{ ...thStyle, width: 96 }}>Delta</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((row) => (
                <StagePairTr key={row.name} row={row} />
              ))}
            </tbody>
          </table>
        </div>
      )}

      {/* ───────── Artifacts diff (closes #1077 AC3) ───────── */}
      <ArtifactsDiffSection rows={artifactRows} />

      {/* ───────── Log diff (closes #770) ───────── */}
      <details
        data-testid="log-diff-disclosure"
        open={logDiffOpen}
        onToggle={(e) => setLogDiffOpen((e.currentTarget as HTMLDetailsElement).open)}
        style={{
          border: '1px solid var(--border)',
          borderRadius: 4,
          padding: '8px 12px',
          background: 'var(--bg-1)',
        }}
      >
        <summary
          style={{
            cursor: 'pointer',
            fontSize: 12,
            fontWeight: 500,
            color: 'var(--fg)',
            letterSpacing: '0.04em',
            textTransform: 'uppercase',
          }}
        >
          Log diff
        </summary>
        <div style={{ marginTop: 10 }}>
          {logDiffOpen && (
            <LogDiffPanel
              buildIdA={buildA.id}
              buildIdB={buildB.id}
              nodesA={nodesA}
              nodesB={nodesB}
            />
          )}
        </div>
      </details>
    </div>
  )
}

const thStyle: React.CSSProperties = {
  textAlign: 'left',
  padding: '6px 10px',
  fontWeight: 500,
  borderBottom: '1px solid var(--border)',
  fontFamily: 'var(--font-mono)',
}

const tdStyle: React.CSSProperties = {
  padding: '6px 10px',
  borderBottom: '1px solid var(--border)',
  verticalAlign: 'middle',
}

function StagePairTr({ row }: { row: StagePairRow }) {
  const aMs = row.a ? stageMs(row.a) : null
  const bMs = row.b ? stageMs(row.b) : null

  // Delta + flip classification. The four cases:
  //   both present, status flip      → ✓→✗ / ✗→✓ wash
  //   both present, no flip          → signed duration delta
  //   only A present (removed in B)  → 'Removed in B' marker, muted wash
  //   only B present (added in B)    → 'Added in B' marker, muted wash
  let deltaLabel = '—'
  let flip: StatusFlip = 'none'
  let bgWash: string | undefined
  let presenceLabel: 'added-in-b' | 'removed-in-b' | null = null
  if (row.a && row.b) {
    flip = classifyStatusFlip(row.a.status, row.b.status)
    if (flip === 'regression') {
      deltaLabel = '✓→✗'
      bgWash = 'color-mix(in oklch, var(--fail) 12%, transparent)'
    } else if (flip === 'recovery') {
      deltaLabel = '✗→✓'
      bgWash = 'color-mix(in oklch, var(--ok) 12%, transparent)'
    } else if (aMs !== null && bMs !== null) {
      deltaLabel = formatSignedDelta(bMs - aMs)
    } else {
      deltaLabel = '—'
    }
  } else if (row.a && !row.b) {
    presenceLabel = 'removed-in-b'
    deltaLabel = 'Removed in B'
    bgWash = 'color-mix(in oklch, var(--muted) 8%, transparent)'
  } else if (!row.a && row.b) {
    presenceLabel = 'added-in-b'
    deltaLabel = 'Added in B'
    bgWash = 'color-mix(in oklch, var(--muted) 8%, transparent)'
  }

  return (
    <tr
      data-testid={`compare-row-${row.name}`}
      data-flip={flip}
      data-presence={presenceLabel ?? undefined}
      style={{
        background: bgWash,
      }}
    >
      <td style={{ ...tdStyle, fontWeight: 500, color: 'var(--fg)' }}>
        <span style={{ display: 'inline-flex', alignItems: 'center', gap: 8 }}>
          <span
            aria-hidden="true"
            style={{
              display: 'inline-block',
              width: 8,
              height: 8,
              borderRadius: 2,
              background:
                row.a && row.b
                  ? buildStatusColor(row.b.status)
                  : 'var(--muted)',
            }}
          />
          {row.name}
        </span>
      </td>
      <td style={tdStyle}>
        <StageCell node={row.a} ms={aMs} testid={`compare-cell-a-${row.name}`} />
      </td>
      <td style={tdStyle}>
        <StageCell node={row.b} ms={bMs} testid={`compare-cell-b-${row.name}`} />
      </td>
      <td
        data-testid={`compare-delta-${row.name}`}
        style={{
          ...tdStyle,
          fontFamily: 'var(--font-mono)',
          fontVariantNumeric: 'tabular-nums',
          color: 'var(--fg-muted)',
          textAlign: 'right',
        }}
      >
        {deltaLabel}
      </td>
    </tr>
  )
}

function StageCell({
  node,
  ms,
  testid,
}: {
  node: FlowNodeDto | null
  ms: number | null
  testid: string
}) {
  if (!node) {
    return (
      <span data-testid={testid} style={{ color: 'var(--fg-dim)' }}>
        —
      </span>
    )
  }
  return (
    <span
      data-testid={testid}
      style={{ display: 'inline-flex', alignItems: 'center', gap: 8 }}
    >
      <StatusBadge status={node.status} />
      <span
        style={{
          fontFamily: 'var(--font-mono)',
          fontVariantNumeric: 'tabular-nums',
          color: 'var(--fg-dim)',
        }}
      >
        {formatDuration(ms)}
      </span>
    </span>
  )
}

// ── Per-side summary card ───────────────────────────────────────────────────

function BuildSummaryCard({
  build,
  jobName,
  side,
}: {
  build: BuildDto
  jobName?: string | null
  side: 'a' | 'b'
}) {
  const shortSha = build.triggerMeta?.commitSha
    ? build.triggerMeta.commitSha.slice(0, 7)
    : null
  const branch = build.triggerMeta?.branch ?? null
  return (
    <div
      data-testid={`compare-summary-${side}`}
      style={{
        border: '1px solid var(--border)',
        borderRadius: 4,
        padding: 12,
        display: 'flex',
        flexDirection: 'column',
        gap: 8,
        background: 'var(--bg-1)',
      }}
    >
      <div
        style={{
          display: 'flex',
          alignItems: 'baseline',
          justifyContent: 'space-between',
          gap: 8,
        }}
      >
        <div style={{ fontSize: 12, color: 'var(--fg-dim)', letterSpacing: '0.04em' }}>
          {side === 'a' ? 'A' : 'B'} · {jobName ?? `Job #${build.jobId}`}
        </div>
        <div
          style={{
            fontFamily: 'var(--font-mono)',
            fontSize: 13,
            fontWeight: 600,
            color: 'var(--fg)',
          }}
        >
          #{build.buildNumber}
        </div>
      </div>
      <div>
        <StatusBadge status={build.status} />
      </div>
      <dl
        style={{
          display: 'grid',
          gridTemplateColumns: '80px 1fr',
          gap: '4px 10px',
          margin: 0,
          fontSize: 12,
        }}
      >
        <dt style={dtStyle}>Duration</dt>
        <dd style={ddStyle}>
          {formatBuildDuration(build.startedAt, build.finishedAt)}
        </dd>
        <dt style={dtStyle}>Branch</dt>
        <dd style={ddStyle}>{branch ?? '—'}</dd>
        <dt style={dtStyle}>Commit</dt>
        <dd style={{ ...ddStyle, fontFamily: 'var(--font-mono)' }}>
          {shortSha ?? '—'}
        </dd>
        <dt style={dtStyle}>Started</dt>
        <dd style={ddStyle}>{formatDate(build.startedAt, 'relative')}</dd>
      </dl>
    </div>
  )
}

const dtStyle: React.CSSProperties = { color: 'var(--fg-dim)', margin: 0 }
const ddStyle: React.CSSProperties = { margin: 0, color: 'var(--fg)' }

// ── Artifacts diff section ──────────────────────────────────────────────────

function ArtifactsDiffSection({ rows }: { rows: readonly ArtifactDiffRow[] }) {
  if (rows.length === 0) {
    return (
      <details
        data-testid="compare-artifacts-disclosure"
        style={{
          border: '1px solid var(--border)',
          borderRadius: 4,
          padding: '8px 12px',
          background: 'var(--bg-1)',
        }}
      >
        <summary style={summaryStyle}>Artifacts</summary>
        <p
          data-testid="compare-artifacts-empty"
          style={{ marginTop: 8, fontSize: 12, color: 'var(--fg-dim)' }}
        >
          Neither build published any artifacts.
        </p>
      </details>
    )
  }
  const addedCount = rows.filter((r) => r.kind === 'added').length
  const removedCount = rows.filter((r) => r.kind === 'removed').length
  const changedCount = rows.filter((r) => r.kind === 'changed').length

  return (
    <details
      data-testid="compare-artifacts-disclosure"
      open
      style={{
        border: '1px solid var(--border)',
        borderRadius: 4,
        padding: '8px 12px',
        background: 'var(--bg-1)',
      }}
    >
      <summary style={summaryStyle}>
        Artifacts
        <span
          data-testid="compare-artifacts-summary"
          style={{
            marginLeft: 8,
            color: 'var(--fg-dim)',
            fontWeight: 400,
            textTransform: 'none',
            letterSpacing: 0,
          }}
        >
          {rows.length} total · +{addedCount} / −{removedCount} / {changedCount}{' '}
          changed
        </span>
      </summary>
      <div style={{ marginTop: 10 }}>
        <table
          data-testid="compare-artifacts-table"
          style={{ width: '100%', borderCollapse: 'collapse', fontSize: 12 }}
        >
          <thead>
            <tr style={{ background: 'var(--bg-2)', color: 'var(--fg-dim)' }}>
              <th style={thStyle}>Artifact</th>
              <th style={thStyle}>A</th>
              <th style={thStyle}>B</th>
              <th style={thStyle}>Size Δ</th>
              <th style={thStyle}>sha256</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => (
              <ArtifactDiffTr key={r.name} row={r} />
            ))}
          </tbody>
        </table>
      </div>
    </details>
  )
}

function ArtifactDiffTr({ row }: { row: ArtifactDiffRow }) {
  let kindLabel: string
  let bgWash: string | undefined
  if (row.kind === 'added') {
    kindLabel = 'Added in B'
    bgWash = 'color-mix(in oklch, var(--ok) 8%, transparent)'
  } else if (row.kind === 'removed') {
    kindLabel = 'Removed in B'
    bgWash = 'color-mix(in oklch, var(--muted) 8%, transparent)'
  } else if (row.kind === 'changed') {
    kindLabel = formatSizeDelta(row.sizeDeltaBytes)
    bgWash = 'color-mix(in oklch, var(--warn, var(--muted)) 6%, transparent)'
  } else {
    kindLabel = '='
  }

  let shaCell: React.ReactNode
  if (row.shaMatch === null) {
    shaCell = <span style={{ color: 'var(--fg-dim)' }}>—</span>
  } else if (row.shaMatch) {
    shaCell = (
      <span
        data-testid={`compare-artifact-sha-match-${row.name}`}
        title="sha256 digests match — identical content"
        style={{ color: 'var(--ok)', fontFamily: 'var(--font-mono)' }}
      >
        ✓ match
      </span>
    )
  } else {
    shaCell = (
      <span
        data-testid={`compare-artifact-sha-mismatch-${row.name}`}
        title="sha256 digests differ — content changed"
        style={{ color: 'var(--fail)', fontFamily: 'var(--font-mono)' }}
      >
        ✗ differ
      </span>
    )
  }

  return (
    <tr
      data-testid={`compare-artifact-row-${row.name}`}
      data-kind={row.kind}
      style={{ background: bgWash }}
    >
      <td style={{ ...tdStyle, fontFamily: 'var(--font-mono)', color: 'var(--fg)' }}>
        {row.name}
      </td>
      <td style={tdStyle}>
        {row.a ? formatBytes(row.a.sizeBytes) : <span style={{ color: 'var(--fg-dim)' }}>—</span>}
      </td>
      <td style={tdStyle}>
        {row.b ? formatBytes(row.b.sizeBytes) : <span style={{ color: 'var(--fg-dim)' }}>—</span>}
      </td>
      <td
        data-testid={`compare-artifact-delta-${row.name}`}
        style={{
          ...tdStyle,
          fontFamily: 'var(--font-mono)',
          fontVariantNumeric: 'tabular-nums',
          color: 'var(--fg-muted)',
        }}
      >
        {kindLabel}
      </td>
      <td style={tdStyle}>{shaCell}</td>
    </tr>
  )
}

const summaryStyle: React.CSSProperties = {
  cursor: 'pointer',
  fontSize: 12,
  fontWeight: 500,
  color: 'var(--fg)',
  letterSpacing: '0.04em',
  textTransform: 'uppercase',
}
