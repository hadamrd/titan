/**
 * LogScrubber — §5.7 hi-fi moment from Claude Design v3.
 *
 * Wraps the existing SSE log stream (lines: string[]) with:
 *   - Right-side 16px minimap with severity-coloured bands + click-to-jump +
 *     hover-preview chip.
 *   - Inline search bar (`n` / `N` next/previous, match count, current-match
 *     highlight). Arrow keys on the minimap jump between failure events.
 *   - "Jump to failure" button — scrolls to first severity=err line.
 *   - Footer with err/warn/ok totals.
 *
 * Severity classifier is a naive regex pass that runs once per render via
 * useMemo. It is intentionally simple (the v1 contract) — a follow-up issue
 * will move classification server-side once the log stream emits structured
 * events.
 */
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { ArrowDown, Download, Search } from 'lucide-react'
import { StatusDot, type StatusDotVariant } from '@/components/ui/StatusDot'

export type LogSeverity = 'err' | 'warn' | 'ok' | 'cmd' | 'info' | ''

interface ClassifiedLine {
  n: number // 1-based line number
  severity: LogSeverity
  text: string
}

// ── Classifier ───────────────────────────────────────────────────────────────
// Order matters: err first, then warn, then ok, then cmd, then info, else ''.
// Patterns are intentionally permissive — false-positives degrade gracefully
// (a line tinted as warn is no worse than untinted).

const ERR_RE = /(^\[ERROR\b)|(^FAIL\b)|(\bException\b)|(^\s*✗ )|(\bERROR:)|(\bfailed\b.*\bexpected\b)/i
const WARN_RE = /(^\[WARN\b)|(\bWARN(ING)?:)|(^\s*⚠ )|(\bdeprecated\b)/i
const OK_RE = /(^\s*✓ )|(\bpassed\b)|(\bSUCCESS\b)/i
const CMD_RE = /^\$\s|^>\s|^\s*▶/
const INFO_RE = /^▸|^\[INFO\b/

function classify(text: string): LogSeverity {
  if (ERR_RE.test(text)) return 'err'
  if (WARN_RE.test(text)) return 'warn'
  if (OK_RE.test(text)) return 'ok'
  if (CMD_RE.test(text)) return 'cmd'
  if (INFO_RE.test(text)) return 'info'
  return ''
}

function severityColor(sev: LogSeverity): string {
  return (
    {
      err: 'var(--fail)',
      warn: 'var(--warn)',
      ok: 'var(--ok)',
      cmd: 'var(--accent)',
      info: 'var(--info)',
      '': 'transparent',
    } satisfies Record<LogSeverity, string>
  )[sev]
}

function severityOpacity(sev: LogSeverity): number {
  if (sev === 'err') return 1
  if (sev === 'warn') return 0.85
  if (sev === '') return 0.05
  return 0.45
}

// ── Search match highlighter ─────────────────────────────────────────────────

function HighlightedText({ text, query }: { text: string; query: string }) {
  if (!query) return <>{text}</>
  const lc = text.toLowerCase()
  const q = query.toLowerCase()
  const out: React.ReactNode[] = []
  let i = 0
  let k = 0
  while (i < text.length) {
    const next = lc.indexOf(q, i)
    if (next < 0) {
      out.push(text.slice(i))
      break
    }
    if (next > i) out.push(text.slice(i, next))
    out.push(<mark key={k++}>{text.slice(next, next + query.length)}</mark>)
    i = next + query.length
  }
  return <>{out}</>
}

// ── Component ────────────────────────────────────────────────────────────────

interface Props {
  lines: string[]
  sseState: 'connecting' | 'live' | 'reconnecting' | 'done' | 'error'
}

const SSE_LABEL = {
  connecting: 'Connecting…',
  live: 'Live',
  reconnecting: 'Reconnecting…',
  done: 'Done',
  error: 'Reconnecting…',
} satisfies Record<Props['sseState'], string>

const SSE_VARIANT = {
  connecting: 'queued',
  live: 'running',
  reconnecting: 'queued',
  done: 'success',
  error: 'fail',
} satisfies Record<Props['sseState'], StatusDotVariant>

export function LogScrubber({ lines, sseState }: Props) {
  const [search, setSearch] = useState('')
  const [matchIdx, setMatchIdx] = useState(0)
  const [hoverLine, setHoverLine] = useState<number | null>(null)
  const bodyRef = useRef<HTMLDivElement>(null)
  const minimapRef = useRef<HTMLDivElement>(null)
  const searchRef = useRef<HTMLInputElement>(null)

  // Classify lines lazily — pure transform, memoised on lines reference.
  const classified = useMemo<ClassifiedLine[]>(
    () => lines.map((text, i) => ({ n: i + 1, severity: classify(text), text })),
    [lines],
  )

  const matches = useMemo<number[]>(() => {
    if (!search) return []
    const lc = search.toLowerCase()
    const out: number[] = []
    for (let i = 0; i < classified.length; i++) {
      if (classified[i]!.text.toLowerCase().includes(lc)) out.push(i)
    }
    return out
  }, [classified, search])

  const firstFailure = useMemo(() => classified.findIndex((l) => l.severity === 'err'), [classified])

  const failureIndices = useMemo(
    () => classified.reduce<number[]>((acc, l, i) => (l.severity === 'err' ? acc.concat(i) : acc), []),
    [classified],
  )

  const scrollToIdx = useCallback((idx: number) => {
    const body = bodyRef.current
    if (!body) return
    const el = body.querySelector(`[data-ln="${idx}"]`) as HTMLElement | null
    if (!el) return
    const containerTop = body.getBoundingClientRect().top
    const lineTop = el.getBoundingClientRect().top
    body.scrollTop += lineTop - containerTop - 120
  }, [])

  // Scroll to the current match whenever the cursor changes.
  useEffect(() => {
    if (matches.length === 0) return
    const safe = matchIdx % matches.length
    const target = matches[safe]
    if (target !== undefined) scrollToIdx(target)
  }, [matchIdx, matches, scrollToIdx])

  // Global `n`/`N` next/previous (only when search has matches and the active
  // element is not an input/textarea).
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const tag = (document.activeElement as HTMLElement | null)?.tagName
      if (tag === 'INPUT' || tag === 'TEXTAREA') return
      if (e.key === '/' && matches.length === 0) {
        e.preventDefault()
        searchRef.current?.focus()
        return
      }
      if (matches.length === 0) return
      if (e.key === 'n') {
        e.preventDefault()
        setMatchIdx((i) => (i + 1) % matches.length)
      } else if (e.key === 'N') {
        e.preventDefault()
        setMatchIdx((i) => (i - 1 + matches.length) % matches.length)
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [matches.length])

  const jumpToFailure = useCallback(() => {
    if (firstFailure >= 0) scrollToIdx(firstFailure)
  }, [firstFailure, scrollToIdx])

  const handleMinimapClick = (e: React.MouseEvent) => {
    const map = minimapRef.current
    if (!map || classified.length === 0) return
    const rect = map.getBoundingClientRect()
    const ratio = (e.clientY - rect.top) / rect.height
    const target = Math.max(0, Math.min(classified.length - 1, Math.floor(ratio * classified.length)))
    scrollToIdx(target)
  }

  const handleMinimapMove = (e: React.MouseEvent) => {
    const map = minimapRef.current
    if (!map || classified.length === 0) return
    const rect = map.getBoundingClientRect()
    const ratio = (e.clientY - rect.top) / rect.height
    setHoverLine(Math.max(0, Math.min(classified.length - 1, Math.floor(ratio * classified.length))))
  }

  // Arrow-key navigation on the minimap — jumps between failure lines.
  const handleMinimapKey = (e: React.KeyboardEvent) => {
    if (failureIndices.length === 0) return
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      e.preventDefault()
      const dir = e.key === 'ArrowDown' ? 1 : -1
      // Find the next failure relative to the current hoverLine (or 0).
      const cur = hoverLine ?? 0
      const idxInList = failureIndices.findIndex((i) => (dir > 0 ? i > cur : i < cur))
      let target: number | undefined
      if (idxInList === -1) {
        target = dir > 0 ? failureIndices[0] : failureIndices[failureIndices.length - 1]
      } else if (dir > 0) {
        target = failureIndices[idxInList]
      } else {
        // Previous failure: take the last element before cur, which is
        // (idxInList - 1) when searching forward, but since findIndex returns
        // the first match for `< cur`, that IS the previous failure.
        target = failureIndices[idxInList]
      }
      if (target !== undefined) {
        setHoverLine(target)
        scrollToIdx(target)
      }
    }
  }

  const errCount = classified.filter((l) => l.severity === 'err').length
  const warnCount = classified.filter((l) => l.severity === 'warn').length
  const okCount = classified.filter((l) => l.severity === 'ok').length

  const currentMatch = matches.length > 0 ? matches[matchIdx % matches.length] : null

  return (
    <div className="terminal log-scrubber" style={{ height: '100%' }}>
      <div className="terminal-head" style={{ gap: 10, flexWrap: 'wrap' }}>
        <StatusDot variant={SSE_VARIANT[sseState]} />
        <span style={{ color: 'var(--fg-muted)' }}>{SSE_LABEL[sseState]}</span>

        <div className="log-search">
          <Search size={11} aria-hidden style={{ color: 'oklch(0.50 0.01 240)' }} />
          <input
            ref={searchRef}
            type="text"
            placeholder="Search logs · n / N to navigate"
            value={search}
            onChange={(e) => {
              setSearch(e.target.value)
              setMatchIdx(0)
            }}
            aria-label="Search logs"
          />
          {matches.length > 0 && (
            <span className="log-search-count">
              <span style={{ color: 'var(--accent)' }}>
                {(matchIdx % matches.length) + 1}
              </span>
              <span style={{ color: 'oklch(0.45 0.01 240)' }}> / {matches.length}</span>
            </span>
          )}
        </div>

        <div
          style={{
            marginLeft: 'auto',
            display: 'flex',
            gap: 6,
            alignItems: 'center',
          }}
        >
          <button
            type="button"
            className="btn btn-sm btn-ghost log-jump"
            onClick={jumpToFailure}
            disabled={firstFailure < 0}
            aria-label="Jump to first failure"
          >
            <ArrowDown size={11} aria-hidden /> Jump to failure
          </button>
          <button
            type="button"
            className="btn btn-sm btn-ghost"
            title="Download raw log"
            aria-label="Download raw log"
          >
            <Download size={11} aria-hidden />
          </button>
          <span style={{ marginLeft: 8, color: 'oklch(0.55 0.01 240)' }}>
            {classified.length} line{classified.length === 1 ? '' : 's'}
          </span>
        </div>
      </div>

      <div style={{ display: 'flex', flex: 1, minHeight: 0 }}>
        <div className="terminal-body" ref={bodyRef} style={{ flex: 1 }}>
          {classified.length === 0 ? (
            <div style={{ padding: 20, color: 'oklch(0.55 0.01 240)' }}>
              No log output yet.
            </div>
          ) : (
            classified.map((line) => {
              const isMatch = matches.includes(line.n - 1)
              const isCurrent = currentMatch === line.n - 1
              return (
                <div
                  key={line.n}
                  data-ln={line.n - 1}
                  className={`log-line no-ts ${line.severity} ${isMatch ? 'match' : ''} ${
                    isCurrent ? 'current-match' : ''
                  }`}
                >
                  <span className="ln">{line.n}</span>
                  <span className="msg">
                    <HighlightedText text={line.text} query={search} />
                  </span>
                </div>
              )
            })
          )}
        </div>

        <div
          className="log-minimap"
          ref={minimapRef}
          onClick={handleMinimapClick}
          onMouseMove={handleMinimapMove}
          onMouseLeave={() => setHoverLine(null)}
          onKeyDown={handleMinimapKey}
          role="slider"
          aria-label="Log minimap — arrow keys jump between failures"
          aria-valuemin={1}
          aria-valuemax={Math.max(1, classified.length)}
          aria-valuenow={(hoverLine ?? 0) + 1}
          tabIndex={0}
        >
          {classified.map((line, i) => (
            <div
              key={i}
              className="minimap-band"
              style={{
                height: `${100 / Math.max(1, classified.length)}%`,
                background: severityColor(line.severity),
                opacity: severityOpacity(line.severity),
              }}
            />
          ))}
          {hoverLine !== null && classified.length > 0 && (
            <div
              className="minimap-hover"
              style={{ top: `${(hoverLine / classified.length) * 100}%` }}
            >
              <span>line {hoverLine + 1}</span>
            </div>
          )}
        </div>
      </div>

      <div className="log-foot">
        <span>{classified.length} lines</span>
        <span style={{ color: 'var(--fg-faint)' }}>·</span>
        <span style={{ color: 'var(--fail)' }}>{errCount} err</span>
        <span style={{ color: 'var(--warn)' }}>{warnCount} warn</span>
        <span style={{ color: 'var(--ok)' }}>{okCount} ok</span>
        <span style={{ marginLeft: 'auto', color: 'var(--fg-faint)' }}>
          ↑↓ scroll · n/N match · / search
        </span>
      </div>
    </div>
  )
}
