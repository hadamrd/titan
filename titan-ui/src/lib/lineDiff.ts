/**
 * Tiny line-by-line LCS diff used by {@link LogDiffPanel} (closes #770).
 *
 * <p>We deliberately do not pull in jsdiff/fast-diff: the diff surface is
 * always line granularity (no intra-line word diff), the result set is capped
 * at {@link MAX_DIFF_LINES} per side, and the worst-case input is small
 * (500 x 500 cells = 250k ints) — well within a synchronous render budget
 * on jsdom and real browsers. Keeps the prod bundle byte-flat.
 *
 * <p>Output is a sequence of paired rows: each row carries the line text and
 * a {@link LineDiffKind}. `equal` rows carry both `a` and `b` line indices;
 * `removed` rows carry only an `a` index (gap on the B column); `added` rows
 * carry only a `b` index (gap on the A column). The caller renders this as
 * two synchronised columns or a single unified column.
 */

export type LineDiffKind = 'equal' | 'removed' | 'added'

export interface LineDiffRow {
  kind: LineDiffKind
  /** Line text (from A for `equal`/`removed`, from B for `added`). */
  text: string
  /** Original 0-based index in A — null when the line only exists in B. */
  aIndex: number | null
  /** Original 0-based index in B — null when the line only exists in A. */
  bIndex: number | null
}

/** Soft cap to keep the diff render synchronous even on degenerate logs. */
export const MAX_DIFF_LINES = 500

export interface LineDiffResult {
  rows: LineDiffRow[]
  aLineCount: number
  bLineCount: number
  /** True when either side was truncated to MAX_DIFF_LINES before diffing. */
  truncated: boolean
  added: number
  removed: number
  unchanged: number
}

/**
 * Compute a line-by-line diff. Inputs are trimmed of a trailing empty line
 * (a log frame typically ends with `\n` which would yield a spurious blank
 * row). The diff is bounded by {@link MAX_DIFF_LINES} on each side — over
 * that the caller surfaces a "showing first N lines" notice.
 */
export function lineDiff(aRaw: readonly string[], bRaw: readonly string[]): LineDiffResult {
  const aFull = stripTrailingBlank(aRaw)
  const bFull = stripTrailingBlank(bRaw)
  const truncated = aFull.length > MAX_DIFF_LINES || bFull.length > MAX_DIFF_LINES
  const a = aFull.slice(0, MAX_DIFF_LINES)
  const b = bFull.slice(0, MAX_DIFF_LINES)

  // Fast path: empty on both sides.
  if (a.length === 0 && b.length === 0) {
    return {
      rows: [],
      aLineCount: aFull.length,
      bLineCount: bFull.length,
      truncated,
      added: 0,
      removed: 0,
      unchanged: 0,
    }
  }

  // LCS length table — Hunt-McIlroy/Wagner-Fischer style.
  const n = a.length
  const m = b.length
  // Use a flat Int32Array for cache locality + no GC pressure.
  const dp = new Int32Array((n + 1) * (m + 1))
  const w = m + 1
  for (let i = n - 1; i >= 0; i--) {
    for (let j = m - 1; j >= 0; j--) {
      if (a[i] === b[j]) {
        dp[i * w + j] = dp[(i + 1) * w + (j + 1)] + 1
      } else {
        const down = dp[(i + 1) * w + j]
        const right = dp[i * w + (j + 1)]
        dp[i * w + j] = down >= right ? down : right
      }
    }
  }

  // Backtrack to emit removed-before-added (so the visual reads left → right
  // as "what disappeared / what was added").
  const rows: LineDiffRow[] = []
  let added = 0
  let removed = 0
  let unchanged = 0
  let i = 0
  let j = 0
  while (i < n && j < m) {
    if (a[i] === b[j]) {
      rows.push({ kind: 'equal', text: a[i], aIndex: i, bIndex: j })
      unchanged++
      i++
      j++
    } else if (dp[(i + 1) * w + j] >= dp[i * w + (j + 1)]) {
      rows.push({ kind: 'removed', text: a[i], aIndex: i, bIndex: null })
      removed++
      i++
    } else {
      rows.push({ kind: 'added', text: b[j], aIndex: null, bIndex: j })
      added++
      j++
    }
  }
  while (i < n) {
    rows.push({ kind: 'removed', text: a[i], aIndex: i, bIndex: null })
    removed++
    i++
  }
  while (j < m) {
    rows.push({ kind: 'added', text: b[j], aIndex: null, bIndex: j })
    added++
    j++
  }

  return {
    rows,
    aLineCount: aFull.length,
    bLineCount: bFull.length,
    truncated,
    added,
    removed,
    unchanged,
  }
}

function stripTrailingBlank(lines: readonly string[]): string[] {
  if (lines.length === 0) return []
  const out = lines.slice()
  while (out.length > 0 && out[out.length - 1] === '') out.pop()
  return out
}
