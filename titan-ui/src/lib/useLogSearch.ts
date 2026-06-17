/**
 * useLogSearch — Ctrl+F in-log search over the streamed build log (#694).
 *
 * <p>Given the array of log lines currently held in client state plus a query
 * string (literal substring or regex), this hook computes the list of matches
 * (line index + char range), exposes a cursor over them, and gives the caller
 * {@code next()} / {@code prev()} / {@code reset()} controls.
 *
 * <h3>Regex safety</h3>
 * Catastrophic backtracking on a streamed 10k-line log is a denial-of-service
 * in disguise. We don't ship a heavyweight safe-regex library; instead we:
 * <ul>
 *   <li>Reject the regex if {@code new RegExp(q, 'gi')} throws — surfaced as
 *       {@link UseLogSearchResult#regexError}.</li>
 *   <li>Reject obviously dangerous shapes: nested quantifiers like
 *       {@code (a+)+}, {@code (a*)*}, {@code (a+)*}, or end-anchored variants
 *       of the same. These are the textbook ReDoS patterns.</li>
 *   <li>Cap the source string at 200 chars — operators don't paste essays.</li>
 * </ul>
 * The caller should render a red input border when {@code regexError} is set
 * and (optionally) fall back to literal substring search by flipping the
 * {@code useRegex} flag to false.
 *
 * <h3>Streaming behaviour</h3>
 * When the {@code logLines} array grows (SSE chunks land) we recompute matches
 * but try to preserve the user's current cursor position by mapping the
 * previous {@code currentIdx}'s match line back onto the new array. If the
 * previous match still exists at the same line, the cursor stays there; if
 * not, we clamp to {@code Math.min(prevIdx, totalMatches - 1)}.
 *
 * <h3>Wrap behaviour</h3>
 * {@code next()} at the last match wraps to the first; {@code prev()} at the
 * first match wraps to the last. Wrap is the editor default (vim, vscode,
 * intellij all wrap) so SREs muscle-memory it.
 */
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'

/** A single match span. {@code line} is the index into the input array; the
 * {@code [start, end)} pair is the character range within that line. */
export interface LogMatch {
  line: number
  start: number
  end: number
}

export interface UseLogSearchResult {
  matches: LogMatch[]
  /** Cursor index into {@link #matches}. {@code -1} when empty. */
  currentIdx: number
  /** Current match (convenience), or {@code null} when empty. */
  current: LogMatch | null
  /** Same shape as {@code regex.exec} error. Null when input is valid. */
  regexError: string | null
  next(): void
  prev(): void
  reset(): void
}

/** Common ReDoS shapes: nested quantifiers on a captured group. We err on
 *  the side of "too eager to reject" — false positives just send the user
 *  to literal mode, false negatives can hang the renderer. */
const REDOS_SHAPES = [
  /\([^)]*[+*][^)]*\)[+*]/, // (a+)+, (a*)*, (a+)*, etc.
  /\([^)]*[+*]\)\{\d+,?\d*\}/, // (a+){10,} style
]

/** Maximum query length we'll even attempt to compile as a regex. */
const MAX_REGEX_LEN = 200

/** Returns a non-null error string if the source looks dangerous OR fails to
 *  compile; null if it's safe to use. */
function validateRegex(source: string): string | null {
  if (source.length > MAX_REGEX_LEN) return 'Pattern too long'
  for (const shape of REDOS_SHAPES) {
    if (shape.test(source)) return 'Potentially catastrophic backtracking'
  }
  try {
    // eslint-disable-next-line no-new
    new RegExp(source, 'gi')
  } catch (e) {
    return (e as Error).message || 'Invalid regex'
  }
  return null
}

/** Escape user input for safe substring-as-regex use. */
function escapeForLiteral(s: string): string {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

export function useLogSearch(
  logLines: readonly string[],
  query: string,
  useRegex: boolean,
): UseLogSearchResult {
  const trimmed = query
  const regexError = useMemo<string | null>(() => {
    if (!trimmed) return null
    if (!useRegex) return null
    return validateRegex(trimmed)
  }, [trimmed, useRegex])

  const matches = useMemo<LogMatch[]>(() => {
    if (!trimmed) return []
    // If regex mode failed validation, fall back to literal substring so the
    // user still sees something while they fix the pattern.
    const pattern = useRegex && !regexError ? trimmed : escapeForLiteral(trimmed)
    let re: RegExp
    try {
      re = new RegExp(pattern, 'gi')
    } catch {
      return []
    }
    const out: LogMatch[] = []
    for (let i = 0; i < logLines.length; i++) {
      const text = logLines[i]!
      re.lastIndex = 0
      let m: RegExpExecArray | null
      // Guard against zero-width matches looping forever.
      while ((m = re.exec(text)) !== null) {
        const start = m.index
        const end = start + m[0].length
        if (end === start) {
          re.lastIndex = start + 1
          continue
        }
        out.push({ line: i, start, end })
      }
    }
    return out
  }, [logLines, trimmed, useRegex, regexError])

  const [currentIdx, setCurrentIdx] = useState(0)

  // Preserve cursor across streaming updates: snapshot the current match line,
  // and after recomputation, snap to the first match on (or after) that line.
  const prevMatchLineRef = useRef<number | null>(null)
  useEffect(() => {
    if (matches.length === 0) {
      setCurrentIdx(0)
      prevMatchLineRef.current = null
      return
    }
    const targetLine = prevMatchLineRef.current
    if (targetLine === null) {
      setCurrentIdx((i) => (i >= matches.length ? 0 : i))
      return
    }
    // Find the first match at or after the previously focused line.
    const idx = matches.findIndex((m) => m.line >= targetLine)
    setCurrentIdx(idx >= 0 ? idx : matches.length - 1)
  }, [matches])

  // Track which line the current match is on so the next recompute can
  // restore it.
  useEffect(() => {
    const cur = matches[currentIdx]
    prevMatchLineRef.current = cur ? cur.line : null
  }, [matches, currentIdx])

  const next = useCallback(() => {
    setCurrentIdx((i) => {
      if (matches.length === 0) return 0
      return (i + 1) % matches.length
    })
  }, [matches.length])

  const prev = useCallback(() => {
    setCurrentIdx((i) => {
      if (matches.length === 0) return 0
      return (i - 1 + matches.length) % matches.length
    })
  }, [matches.length])

  const reset = useCallback(() => {
    setCurrentIdx(0)
    prevMatchLineRef.current = null
  }, [])

  const safeIdx = matches.length === 0 ? -1 : Math.min(currentIdx, matches.length - 1)
  const current = safeIdx >= 0 ? matches[safeIdx]! : null

  return {
    matches,
    currentIdx: safeIdx,
    current,
    regexError,
    next,
    prev,
    reset,
  }
}
