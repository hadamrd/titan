/**
 * Pure diff helpers for the side-by-side build comparison view
 * (closes #1077).
 *
 * <p>These functions are deliberately UI-free + dependency-free so they can
 * be unit-tested with hand-built DTOs and reused by the route and the view.
 * Three diff axes are covered:
 *
 *  <ol>
 *    <li>{@link durationDelta} — signed millisecond delta with an
 *        SRE-friendly label ({@code +12.3s} / {@code -3.4s} / {@code =}).</li>
 *    <li>{@link stageDiff} — labels each paired stage as
 *        {@code added} / {@code removed} / {@code regression} /
 *        {@code recovery} / {@code unchanged}. Re-exposes the labels in a
 *        machine-readable shape so the table cell can render
 *        "Added in B" / "Removed in B" explicitly rather than the bare
 *        em-dash the legacy view used.</li>
 *    <li>{@link artifactDiff} — joins artifact rows by name and emits a
 *        per-name {@link ArtifactDiffRow} with size delta + sha256-match
 *        boolean. The sha256-match flag is only meaningful when both sides
 *        carry the artifact; if either side is missing, {@code shaMatch}
 *        is {@code null} (NOT {@code false}, which would falsely imply a
 *        content change).</li>
 *    <li>{@link logPatternDiff} — cheap "distinct line patterns" diff that
 *        normalises each line (collapses whitespace, strips timestamps +
 *        UUIDs + hex blobs + numeric runs) and emits the three counts the
 *        issue calls out: A-only, B-only, common. Deliberately NOT a full
 *        unified diff — that's a follow-up ticket (see #1077 out-of-scope).</li>
 *  </ol>
 *
 * <p>Why "distinct patterns" instead of raw line counts: SREs comparing two
 * 50k-line build logs need a thumbnail of what's actually different, not a
 * count inflated by "[2026-05-24T11:12:13.123Z]" timestamps that change
 * every run. The normaliser tokenises away the noise so re-runs of an
 * identical script summarise as "100% common".
 */
import type { ArtifactDto, FlowNodeDto } from '@/api/types'

// ── Duration delta ──────────────────────────────────────────────────────────

/** A signed delta with a pre-formatted, SRE-readable label. */
export interface DurationDelta {
  /** Signed milliseconds, B - A. {@code null} when either side is missing. */
  deltaMs: number | null
  /**
   * Display label: {@code '+12.3s'} / {@code '-3.4s'} / {@code '='} (zero) /
   * {@code '—'} (one side missing).
   */
  label: string
}

function formatSeconds(absMs: number): string {
  if (absMs >= 60_000) {
    // Express as M:SS for SRE readability ("+1:23" beats "+83s").
    const totalSec = Math.round(absMs / 1000)
    const m = Math.floor(totalSec / 60)
    const s = totalSec % 60
    return `${m}:${s.toString().padStart(2, '0')}`
  }
  // Sub-minute → seconds with one decimal place (drop trailing .0).
  const sec = absMs / 1000
  const rounded = Math.round(sec * 10) / 10
  return `${rounded}s`.replace(/\.0s$/, 's')
}

export function durationDelta(
  aMs: number | null | undefined,
  bMs: number | null | undefined,
): DurationDelta {
  if (
    aMs === null ||
    aMs === undefined ||
    bMs === null ||
    bMs === undefined ||
    !Number.isFinite(aMs) ||
    !Number.isFinite(bMs)
  ) {
    return { deltaMs: null, label: '—' }
  }
  const delta = bMs - aMs
  if (delta === 0) return { deltaMs: 0, label: '=' }
  const sign = delta > 0 ? '+' : '-'
  return { deltaMs: delta, label: sign + formatSeconds(Math.abs(delta)) }
}

// ── Stage diff ──────────────────────────────────────────────────────────────

const STAGE_TYPES = new Set(['STAGE', 'SECTION', 'GROUP'])
const FAIL_STATUSES = new Set(['FAILED', 'FAILURE', 'ABORTED', 'UNSTABLE'])

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
    if (Number.isFinite(start) && Number.isFinite(end)) return Math.max(0, end - start)
  }
  return null
}

function stepCount(nodes: readonly FlowNodeDto[], stageId: string): number {
  // Naive: any node whose parentIds list contains the stage's nodeId. The
  // contract on FlowNodeDto.parentIds is "string | null" (JSON-encoded list or
  // single id), so we accept either shape.
  return nodes.filter((n) => {
    const p = n.parentIds
    if (!p) return false
    return p === stageId || p.includes(stageId)
  }).length
}

export type StageDiffKind =
  | 'added' // present only on B
  | 'removed' // present only on A
  | 'regression' // SUCCESS → FAILED-ish
  | 'recovery' // FAILED-ish → SUCCESS
  | 'unchanged' // status equal, no flip

export interface StageDiffRow {
  /** Join key — the human-visible label. */
  name: string
  kind: StageDiffKind
  a: FlowNodeDto | null
  b: FlowNodeDto | null
  /** Signed duration delta + label. {@code null}/'—' when one side is absent. */
  duration: DurationDelta
  /** Step-count delta, {@code null} when either side is absent. */
  stepCountDelta: number | null
}

/**
 * Classify a status pair. Exported for direct unit testing — independent of
 * the row-pair plumbing.
 */
export function classifyStageStatus(aStatus: string, bStatus: string): StageDiffKind {
  const aOk = aStatus === 'SUCCESS'
  const bOk = bStatus === 'SUCCESS'
  const aFail = FAIL_STATUSES.has(aStatus)
  const bFail = FAIL_STATUSES.has(bStatus)
  if (aOk && bFail) return 'regression'
  if (aFail && bOk) return 'recovery'
  return 'unchanged'
}

/**
 * Pair stages by label, preserving A's declared order; B-only stages append.
 * Each row carries an explicit {@code kind} so the renderer never has to
 * re-derive "is this added or removed" from null-checks.
 */
export function stageDiff(
  aNodes: readonly FlowNodeDto[],
  bNodes: readonly FlowNodeDto[],
): StageDiffRow[] {
  const aStages = aNodes.filter(isStageNode)
  const bStages = bNodes.filter(isStageNode)
  const bByName = new Map<string, FlowNodeDto>()
  for (const n of bStages) {
    const k = stageLabel(n)
    if (!bByName.has(k)) bByName.set(k, n)
  }
  const usedB = new Set<string>()
  const rows: StageDiffRow[] = []

  for (const a of aStages) {
    const key = stageLabel(a)
    const b = bByName.get(key) ?? null
    if (b) usedB.add(key)
    rows.push(buildRow(key, a, b, aNodes, bNodes))
  }
  for (const b of bStages) {
    const key = stageLabel(b)
    if (usedB.has(key)) continue
    if (rows.some((r) => r.name === key)) continue
    rows.push(buildRow(key, null, b, aNodes, bNodes))
  }
  return rows
}

function buildRow(
  name: string,
  a: FlowNodeDto | null,
  b: FlowNodeDto | null,
  aNodes: readonly FlowNodeDto[],
  bNodes: readonly FlowNodeDto[],
): StageDiffRow {
  let kind: StageDiffKind
  if (a === null && b !== null) kind = 'added'
  else if (a !== null && b === null) kind = 'removed'
  else if (a !== null && b !== null) kind = classifyStageStatus(a.status, b.status)
  else kind = 'unchanged' // unreachable in practice — both null can't happen

  const duration = durationDelta(a ? stageMs(a) : null, b ? stageMs(b) : null)
  const stepCountDelta =
    a && b ? stepCount(bNodes, b.nodeId) - stepCount(aNodes, a.nodeId) : null

  return { name, kind, a, b, duration, stepCountDelta }
}

// ── Artifact diff ───────────────────────────────────────────────────────────

export interface ArtifactDiffRow {
  /** Join key — artifact name. */
  name: string
  a: ArtifactDto | null
  b: ArtifactDto | null
  /** Signed byte delta, B − A. {@code null} when one side missing. */
  sizeDeltaBytes: number | null
  /**
   * sha256 match — {@code true} when both present and digests equal,
   * {@code false} when present-but-differ, {@code null} when one side
   * is missing (so the UI can render "—" rather than a misleading "✗").
   */
  shaMatch: boolean | null
  kind: 'added' | 'removed' | 'unchanged' | 'changed'
}

/**
 * Join two artifact lists by name, preserving A's order then appending
 * B-only rows. Same join discipline as {@link stageDiff} — the SRE expects
 * the older build's order to anchor the table.
 */
export function artifactDiff(
  aArts: readonly ArtifactDto[],
  bArts: readonly ArtifactDto[],
): ArtifactDiffRow[] {
  const bByName = new Map<string, ArtifactDto>()
  for (const art of bArts) {
    if (!bByName.has(art.name)) bByName.set(art.name, art)
  }
  const usedB = new Set<string>()
  const rows: ArtifactDiffRow[] = []
  for (const a of aArts) {
    const b = bByName.get(a.name) ?? null
    if (b) usedB.add(a.name)
    rows.push(buildArtifactRow(a.name, a, b))
  }
  for (const b of bArts) {
    if (usedB.has(b.name)) continue
    if (rows.some((r) => r.name === b.name)) continue
    rows.push(buildArtifactRow(b.name, null, b))
  }
  return rows
}

function buildArtifactRow(
  name: string,
  a: ArtifactDto | null,
  b: ArtifactDto | null,
): ArtifactDiffRow {
  let kind: ArtifactDiffRow['kind']
  let shaMatch: boolean | null
  let sizeDeltaBytes: number | null
  if (a === null && b !== null) {
    kind = 'added'
    shaMatch = null
    sizeDeltaBytes = null
  } else if (a !== null && b === null) {
    kind = 'removed'
    shaMatch = null
    sizeDeltaBytes = null
  } else if (a !== null && b !== null) {
    shaMatch = a.sha256 === b.sha256
    sizeDeltaBytes = b.sizeBytes - a.sizeBytes
    kind = shaMatch && sizeDeltaBytes === 0 ? 'unchanged' : 'changed'
  } else {
    // Defensive — both null is unreachable (caller always provides one side).
    kind = 'unchanged'
    shaMatch = null
    sizeDeltaBytes = null
  }
  return { name, a, b, sizeDeltaBytes, shaMatch, kind }
}

// ── Log pattern diff ────────────────────────────────────────────────────────

export interface LogPatternDiff {
  /** Distinct normalised line patterns present only in A. */
  aOnly: number
  /** Distinct normalised line patterns present only in B. */
  bOnly: number
  /** Distinct normalised line patterns present in both. */
  common: number
}

/**
 * Normalise a log line for "is this the same thing" comparison: collapses
 * whitespace, strips ISO-8601 timestamps, UUIDs, hex blobs, and runs of
 * digits. The point isn't perfect canonicalisation — it's killing the
 * noise that two re-runs of an identical script will produce so the
 * SRE's at-a-glance counts mean something.
 *
 * <p>Returned values are de-duped before counting, so a log full of
 * "[INFO] starting" repeats doesn't inflate the per-side counts.
 */
export function normaliseLogLine(line: string): string {
  return (
    line
      // ISO timestamps — leading or embedded.
      .replace(/\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?(Z|[+-]\d{2}:?\d{2})?/g, '<ts>')
      // Common bracketed time prefix: [12:34:56], [12:34:56.789]
      .replace(/\[\d{2}:\d{2}:\d{2}(\.\d+)?\]/g, '<ts>')
      // UUIDs.
      .replace(/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/gi, '<uuid>')
      // 40+ char hex blobs (sha1/sha256/etc).
      .replace(/\b[0-9a-f]{12,}\b/gi, '<hex>')
      // Bare digit runs (durations, pids, line numbers, ports). Length-3+
      // to avoid clobbering meaningful small integers like "1 file changed".
      .replace(/\b\d{3,}\b/g, '<num>')
      // Whitespace.
      .replace(/\s+/g, ' ')
      .trim()
  )
}

export function logPatternDiff(
  aLines: readonly string[],
  bLines: readonly string[],
): LogPatternDiff {
  const aSet = new Set<string>()
  for (const l of aLines) {
    const n = normaliseLogLine(l)
    if (n !== '') aSet.add(n)
  }
  const bSet = new Set<string>()
  for (const l of bLines) {
    const n = normaliseLogLine(l)
    if (n !== '') bSet.add(n)
  }
  let aOnly = 0
  let common = 0
  for (const a of aSet) {
    if (bSet.has(a)) common++
    else aOnly++
  }
  let bOnly = 0
  for (const b of bSet) {
    if (!aSet.has(b)) bOnly++
  }
  return { aOnly, bOnly, common }
}

// ── Misc formatters ─────────────────────────────────────────────────────────

/** Pretty-print a signed byte delta — "+1.2 MB", "-340 KB", "=" for 0. */
export function formatSizeDelta(bytes: number | null): string {
  if (bytes === null) return '—'
  if (bytes === 0) return '='
  const sign = bytes < 0 ? '-' : '+'
  const abs = Math.abs(bytes)
  if (abs >= 1_000_000) return `${sign}${(abs / 1_000_000).toFixed(1)} MB`
  if (abs >= 1_000) return `${sign}${(abs / 1_000).toFixed(1)} KB`
  return `${sign}${abs} B`
}

/** Pretty-print an absolute byte count for the per-side cell. */
export function formatBytes(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes < 0) return '—'
  if (bytes >= 1_000_000) return `${(bytes / 1_000_000).toFixed(1)} MB`
  if (bytes >= 1_000) return `${(bytes / 1_000).toFixed(1)} KB`
  return `${bytes} B`
}
