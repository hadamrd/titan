/**
 * TerminalConsole — v3-stack right-pane "Console" rendering for the
 * /builds/$id Logs tab.
 *
 * Per the v3-stack design (lines 593–667), the terminal carries its own
 * header chrome with:
 *   - Inline search input (oklch-themed) with live match counter
 *     (`n / total` or `— / 0` when empty).
 *   - Per-severity line counts (1247 lines · 3 err · …)
 *   - Right-edge structure-banded Minimap with a live, draggable viewport
 *     indicator that scrubs the scroll proportionally.
 *
 * Keyboard contract (#551):
 *   `/` focuses the search input (when not already in an input/textarea)
 *   `n` / `N` advance / reverse through matches (when terminal-scoped)
 *
 * The classifier lives here AND in LogScrubber.tsx — we keep the duplication
 * narrow so the minimap can show banding without coupling to LogScrubber.
 * A follow-up will move classification server-side.
 */
import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { Download, Search } from 'lucide-react'
import { Minimap, type LineSeverity } from './Minimap'
import { LogSearchBar } from '@/components/LogSearchBar'
import { useLogSearch, type LogMatch } from '@/lib/useLogSearch'
import { isTypingTarget } from '@/lib/useStageKeyNav'

const ERR_RE = /(^\[ERROR\b)|(^FAIL\b)|(\bException\b)|(^\s*✗ )|(\bERROR:)|(\bfailed\b.*\bexpected\b)/i
const WARN_RE = /(^\[WARN\b)|(\bWARN(ING)?:)|(^\s*⚠ )|(\bdeprecated\b)/i
const OK_RE = /(^\s*✓ )|(\bpassed\b)|(\bSUCCESS\b)/i
const CMD_RE = /^\$\s|^>\s|^\s*▶/
const INFO_RE = /^▸|^\[INFO\b/

function classify(text: string): LineSeverity {
  if (ERR_RE.test(text)) return 'err'
  if (WARN_RE.test(text)) return 'warn'
  if (OK_RE.test(text)) return 'ok'
  if (CMD_RE.test(text)) return 'cmd'
  if (INFO_RE.test(text)) return 'info'
  return ''
}

/**
 * Render a line with the given pre-computed match ranges wrapped in <mark>
 * spans. The cursor's current match (matched by line+start identity) gets
 * the `current-match` class for the stronger highlight.
 */
function highlightRanges(
  text: string,
  ranges: ReadonlyArray<{ start: number; end: number }>,
  currentRange: { line: number; start: number; end: number } | null,
  lineIdx: number,
): ReactNode {
  if (ranges.length === 0) return text
  const out: ReactNode[] = []
  let i = 0
  let k = 0
  for (const r of ranges) {
    if (r.start > i) out.push(text.slice(i, r.start))
    const isCur =
      currentRange !== null &&
      currentRange.line === lineIdx &&
      currentRange.start === r.start &&
      currentRange.end === r.end
    out.push(
      <mark
        key={k++}
        className={isCur ? 'log-mark current-match' : 'log-mark'}
        data-testid={isCur ? 'log-search-mark-current' : 'log-search-mark'}
        style={{
          background: isCur ? 'var(--mark-bg-active)' : 'var(--mark-bg)',
          color: isCur ? 'var(--mark-fg-active)' : 'inherit',
          padding: '0 1px',
          borderRadius: 2,
        }}
      >
        {text.slice(r.start, r.end)}
      </mark>,
    )
    i = r.end
  }
  if (i < text.length) out.push(text.slice(i))
  return <>{out}</>
}

function highlight(text: string, query: string, isCurrentLine: boolean): ReactNode {
  if (!query) return text
  const lc = text.toLowerCase()
  const q = query.toLowerCase()
  const out: ReactNode[] = []
  let i = 0
  let k = 0
  while (i < text.length) {
    const next = lc.indexOf(q, i)
    if (next < 0) {
      out.push(text.slice(i))
      break
    }
    if (next > i) out.push(text.slice(i, next))
    out.push(
      <mark
        key={k++}
        className={isCurrentLine ? 'current-match' : undefined}
        data-testid="search-mark"
      >
        {text.slice(next, next + query.length)}
      </mark>,
    )
    i = next + query.length
  }
  return <>{out}</>
}

/**
 * Cap on how many log lines we keep mounted in the DOM. A 50k-line build would
 * otherwise mount 50k flex rows and jank the tab (#1264). We always keep the
 * TAIL (the live edge SREs watch), render the last `MAX_RENDERED_LINES`, and
 * expose a "load older" affordance to mount the full buffer on demand.
 */
export const MAX_RENDERED_LINES = 5000

interface Props {
  lines: string[]
  sseState: 'connecting' | 'live' | 'reconnecting' | 'done' | 'error'
  /**
   * Used to name the downloaded log file: `build-<N>-<iso>.log` (#705).
   * Optional so existing tests that don't care about the download chip
   * still render. When omitted the button is hidden.
   */
  buildNumber?: number
}

export function TerminalConsole({ lines, sseState, buildNumber }: Props) {
  const severities = useMemo<LineSeverity[]>(() => lines.map(classify), [lines])

  // ── Auto-follow state (#563) ───────────────────────────────────────────────
  // For live builds the SREs want the last line glued to the bottom; opting
  // out happens by scrolling up. A small floating chip lets them re-engage.
  const [autoFollow, setAutoFollow] = useState(true)

  // ── Line cap (#1264) ───────────────────────────────────────────────────────
  // Keep the DOM bounded for huge logs by mounting only the last
  // MAX_RENDERED_LINES rows. `showAll` opts into the full buffer when the
  // operator clicks "load older". `startIdx` is the absolute index of the first
  // mounted line — every render below keeps using ABSOLUTE indices (data-ln,
  // severities[i], matchesByLine) so search/minimap/auto-follow are unaffected.
  const [showAll, setShowAll] = useState(false)
  const capped = !showAll && lines.length > MAX_RENDERED_LINES
  const startIdx = capped ? lines.length - MAX_RENDERED_LINES : 0
  // Last seen scrollTop — used to detect a scroll-up delta from the user.
  // Initialized lazily on the first scroll event so the programmatic
  // "glue to bottom" tick that follows the initial render doesn't register
  // as a phantom delta on its own.
  const lastScrollTopRef = useRef<number | null>(null)

  const counts = useMemo(() => {
    let err = 0, warn = 0, ok = 0
    for (const s of severities) {
      if (s === 'err') err++
      else if (s === 'warn') warn++
      else if (s === 'ok') ok++
    }
    return { err, warn, ok }
  }, [severities])

  // ── Search state ───────────────────────────────────────────────────────────
  const [search, setSearch] = useState('')
  const [matchIdx, setMatchIdx] = useState(0)
  const searchRef = useRef<HTMLInputElement>(null)
  const bodyRef = useRef<HTMLDivElement>(null)
  const rootRef = useRef<HTMLDivElement>(null)

  // ── Floating Ctrl+F bar (#694) ─────────────────────────────────────────────
  const [barOpen, setBarOpen] = useState(false)
  const [barQuery, setBarQuery] = useState('')
  const [barRegex, setBarRegex] = useState(false)
  const logSearch = useLogSearch(lines, barOpen ? barQuery : '', barRegex)
  const barMatches = logSearch.matches
  const barCurrent = logSearch.current

  // Group matches by line for O(1) per-line render lookup. Built only when the
  // bar is driving the highlight (`barOpen`); otherwise the original inline
  // header `search` state owns rendering.
  const matchesByLine = useMemo(() => {
    if (!barOpen) return null
    const map = new Map<number, LogMatch[]>()
    for (const m of barMatches) {
      const arr = map.get(m.line)
      if (arr) arr.push(m)
      else map.set(m.line, [m])
    }
    return map
  }, [barOpen, barMatches])

  // Scroll the current bar match into view whenever the cursor changes.
  useEffect(() => {
    if (!barOpen || !barCurrent) return
    const body = bodyRef.current
    if (!body) return
    const el = body.querySelector(`[data-ln="${barCurrent.line}"]`) as HTMLElement | null
    if (!el) return
    el.scrollIntoView({ block: 'nearest', behavior: 'auto' })
  }, [barOpen, barCurrent, startIdx])

  const matchLines = useMemo<number[]>(() => {
    if (!search) return []
    const lc = search.toLowerCase()
    const out: number[] = []
    for (let i = 0; i < lines.length; i++) {
      if (lines[i]!.toLowerCase().includes(lc)) out.push(i)
    }
    return out
  }, [lines, search])

  const totalMatches = matchLines.length
  const safeIdx = totalMatches > 0 ? matchIdx % totalMatches : 0
  const currentLine = totalMatches > 0 ? matchLines[safeIdx]! : -1

  // ── Navigate-into-capped-region (#1264 review, sev2) ───────────────────────
  // matchLines / barMatches scan ALL lines, so a navigation target can point at
  // an absolute index below `startIdx` that the line-cap dropped from the DOM.
  // When that happens, expand the full buffer first so the row's [data-ln] node
  // exists — otherwise scrollToLine / the bar scroll effect querySelector misses
  // and n/N + the Ctrl+F bar silently no-op for older matches.
  const navTarget = barOpen ? (barCurrent?.line ?? -1) : currentLine
  useEffect(() => {
    if (navTarget >= 0 && capped && navTarget < startIdx) setShowAll(true)
  }, [navTarget, capped, startIdx])

  // Minimap scrubbing bands the FULL buffer; if the user scrubs into the older
  // region while capped, expand so the body scrolls the real target (not the
  // tail's compressed scroll range).
  const onMinimapJump = useCallback(
    (pct: number) => {
      if (capped && pct * lines.length < startIdx) setShowAll(true)
    },
    [capped, lines.length, startIdx],
  )

  const scrollToLine = useCallback((lineIdx: number) => {
    const body = bodyRef.current
    if (!body) return
    const el = body.querySelector(`[data-ln="${lineIdx}"]`) as HTMLElement | null
    if (!el) return
    const containerTop = body.getBoundingClientRect().top
    const lineTop = el.getBoundingClientRect().top
    body.scrollTop += lineTop - containerTop - 120
  }, [])

  // Whenever the cursor changes, scroll the current match into view.
  useEffect(() => {
    if (currentLine >= 0) scrollToLine(currentLine)
  }, [currentLine, scrollToLine, startIdx])

  // ── Auto-follow: glue last line on every new chunk ─────────────────────────
  // Runs after each render where `lines.length` changes. We scroll the very
  // last `.log-line` (queried by its data-ln) into view so search highlights
  // and severity classes stay legible during streaming.
  const scrollToBottom = useCallback(() => {
    const body = bodyRef.current
    if (!body) return
    const last = body.querySelector<HTMLElement>(`[data-ln="${lines.length - 1}"]`)
    if (!last) return
    last.scrollIntoView({ behavior: 'auto', block: 'end' })
    // Reset baseline so the smooth-scroll the browser performs after
    // scrollIntoView doesn't read as a user "scroll up" event.
    lastScrollTopRef.current = null
  }, [lines.length])

  useEffect(() => {
    if (autoFollow && lines.length > 0) scrollToBottom()
  }, [lines.length, autoFollow, scrollToBottom])

  // Manual-scroll detection. Any scrollTop decrease > 40px from the last
  // auto-scroll position is treated as opt-out; threshold absorbs jitter
  // from inertial trackpads and short overscrolls.
  const onBodyScroll = useCallback(() => {
    const body = bodyRef.current
    if (!body) return
    const prev = lastScrollTopRef.current
    const now = body.scrollTop
    lastScrollTopRef.current = now
    if (prev === null) return
    const delta = now - prev
    if (delta < -40 && autoFollow) {
      setAutoFollow(false)
    }
  }, [autoFollow])

  const resumeFollow = useCallback(() => {
    setAutoFollow(true)
    // scrollToBottom runs in the autoFollow effect once state flips, but we
    // also call it eagerly so click feels instant even if no new line lands.
    scrollToBottom()
  }, [scrollToBottom])

  // Keyboard: `/` focus, `n`/`N` navigate. Scoped to events bubbling from the
  // terminal frame (so it doesn't fight global handlers on other panels).
  useEffect(() => {
    const root = rootRef.current
    if (!root) return
    const onKey = (e: KeyboardEvent) => {
      const tag = (document.activeElement as HTMLElement | null)?.tagName
      const inForeignInput =
        (tag === 'INPUT' || tag === 'TEXTAREA') &&
        document.activeElement !== searchRef.current
      if (e.key === '/') {
        if (inForeignInput) return
        e.preventDefault()
        searchRef.current?.focus()
        searchRef.current?.select()
        return
      }
      // `End` re-enables auto-follow when the console (or any of its
      // descendants) holds focus. Works independent of search state.
      if (e.key === 'End' && !inForeignInput) {
        e.preventDefault()
        setAutoFollow(true)
        scrollToBottom()
        return
      }
      if (totalMatches === 0) return
      // `n`/`N` work when the search input is focused OR when focus is
      // inside the terminal frame.
      if (e.key === 'n') {
        e.preventDefault()
        setMatchIdx((i) => (i + 1) % totalMatches)
      } else if (e.key === 'N') {
        e.preventDefault()
        setMatchIdx((i) => (i - 1 + totalMatches) % totalMatches)
      }
    }
    root.addEventListener('keydown', onKey)
    return () => root.removeEventListener('keydown', onKey)
  }, [totalMatches, scrollToBottom])

  // ── Ctrl+F / Cmd+F intercept (#694) ────────────────────────────────────────
  // Window-scoped so it works regardless of focus — but we DO NOT swallow
  // when focus is inside an INPUT/TEXTAREA/contenteditable, so the OS shortcut
  // still works on (e.g.) the inline header search box and the YAML editor.
  // Since this component only mounts on the build-detail Logs tab, scoping to
  // window here is naturally route-scoped.
  useEffect(() => {
    if (typeof window === 'undefined') return
    const onKey = (e: KeyboardEvent) => {
      const isFind = (e.ctrlKey || e.metaKey) && (e.key === 'f' || e.key === 'F')
      if (!isFind) return
      if (isTypingTarget(document.activeElement)) return
      e.preventDefault()
      setBarOpen(true)
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [])

  const closeBar = useCallback(() => {
    setBarOpen(false)
  }, [])

  // ── Download full log as .txt (#705) ──────────────────────────────────────
  // Snapshot `lines` at click time so a download triggered mid-stream contains
  // exactly what the user saw. We construct the Blob synchronously inside the
  // handler (capturing the closure value) so a later mutation of the array
  // can't bleed into the file.
  const downloadDisabled = lines.length === 0
  const onDownload = useCallback(() => {
    if (lines.length === 0) return
    const snapshot = lines.slice()
    const body = snapshot.join('\n') + '\n'
    const blob = new Blob([body], { type: 'text/plain;charset=utf-8' })
    const url = URL.createObjectURL(blob)
    const ts = new Date().toISOString()
    const name = `build-${buildNumber ?? 'log'}-${ts}.log`
    const a = document.createElement('a')
    a.href = url
    a.download = name
    a.rel = 'noopener'
    a.style.display = 'none'
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
    // Give the browser a tick to start the download before revoking; revoking
    // synchronously is fine in Chromium but Firefox occasionally races.
    setTimeout(() => URL.revokeObjectURL(url), 0)
  }, [lines, buildNumber])

  const counterText =
    !search ? null : totalMatches === 0 ? '— / 0' : `${safeIdx + 1} / ${totalMatches}`

  return (
    <div
      ref={rootRef}
      data-testid="terminal-console"
      tabIndex={-1}
      style={{
        display: 'flex',
        flexDirection: 'column',
        flex: 1,
        minHeight: 0,
        background: 'oklch(0.12 0.018 250)',
        border: '1px solid var(--border)',
        borderRadius: 'var(--r-md)',
        overflow: 'hidden',
        outline: 'none',
      }}
    >
      {/* Header: search + counts (design lines 594–612) */}
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 10,
          padding: '6px 12px',
          borderBottom: '1px solid oklch(0.22 0 0)',
          background: 'oklch(0.10 0 0)',
          fontFamily: 'var(--font-mono)',
          fontSize: 10.5,
          color: 'oklch(0.50 0 0)',
        }}
        data-testid="terminal-counts"
      >
        {/* Search box */}
        <div
          className="bd-term-search"
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: 6,
            padding: '2px 8px',
            borderRadius: 'var(--r-sm)',
            background: 'oklch(0.16 0 0)',
            border: '1px solid oklch(0.22 0 0)',
            width: 280,
          }}
        >
          <Search size={11} aria-hidden style={{ color: 'oklch(0.45 0 0)' }} />
          <input
            ref={searchRef}
            type="text"
            value={search}
            onChange={(e) => {
              setSearch(e.target.value)
              setMatchIdx(0)
            }}
            placeholder="Search · n / N to navigate · / focus"
            aria-label="Search console"
            data-testid="terminal-search-input"
            style={{
              background: 'transparent',
              border: 'none',
              outline: 'none',
              flex: 1,
              color: 'oklch(0.92 0 0)',
              fontFamily: 'var(--font-mono)',
              fontSize: 11.5,
              minWidth: 0,
            }}
          />
          {counterText !== null && (
            <span
              data-testid="terminal-search-counter"
              style={{
                fontSize: 10,
                fontFamily: 'var(--font-mono)',
                color:
                  totalMatches === 0
                    ? 'oklch(0.45 0 0)'
                    : 'var(--accent)',
                whiteSpace: 'nowrap',
              }}
            >
              {counterText}
            </span>
          )}
        </div>

        <span>
          {lines.length.toLocaleString()} line{lines.length !== 1 ? 's' : ''}
        </span>
        <span style={{ color: 'oklch(0.30 0 0)' }}>·</span>
        <span style={{ color: 'var(--fail)' }} data-testid="count-err">
          {counts.err} err
        </span>
        <span style={{ color: 'var(--warn)' }} data-testid="count-warn">
          {counts.warn} warn
        </span>
        <span style={{ color: 'var(--ok)' }} data-testid="count-ok">
          {counts.ok} ok
        </span>
        <span
          data-testid="sse-state"
          data-sse-state={sseState}
          style={{ marginLeft: 'auto', color: 'oklch(0.45 0 0)' }}
        >
          {sseState}
        </span>
        <button
          type="button"
          onClick={onDownload}
          disabled={downloadDisabled}
          aria-disabled={downloadDisabled}
          aria-label="Download log as text"
          title="Download log as text"
          data-testid="terminal-download-log"
          style={{
            display: 'inline-flex',
            alignItems: 'center',
            gap: 4,
            padding: '2px 8px',
            borderRadius: 'var(--r-sm)',
            background: 'oklch(0.16 0 0)',
            border: '1px solid oklch(0.22 0 0)',
            color: downloadDisabled ? 'oklch(0.35 0 0)' : 'oklch(0.85 0 0)',
            fontFamily: 'var(--font-mono)',
            fontSize: 10.5,
            cursor: downloadDisabled ? 'not-allowed' : 'pointer',
            opacity: downloadDisabled ? 0.5 : 1,
          }}
        >
          <Download size={11} aria-hidden />
          Download
        </button>
      </div>

      {/* Body: log lines on the left + Minimap on the right */}
      <div style={{ flex: 1, minHeight: 0, display: 'flex', position: 'relative' }}>
        {barOpen && (
          <LogSearchBar
            query={barQuery}
            onQueryChange={setBarQuery}
            useRegex={barRegex}
            onUseRegexChange={setBarRegex}
            matchCount={barMatches.length}
            currentIdx={logSearch.currentIdx}
            onNext={logSearch.next}
            onPrev={logSearch.prev}
            onClose={closeBar}
            regexError={logSearch.regexError}
          />
        )}
        <div
          ref={bodyRef}
          data-testid="terminal-body"
          onScroll={onBodyScroll}
          style={{
            flex: 1,
            minWidth: 0,
            overflow: 'auto',
            padding: '6px 0',
            fontFamily: 'var(--font-mono)',
            fontSize: 11.5,
            color: 'oklch(0.92 0 0)',
          }}
        >
          {lines.length === 0 ? (
            <div style={{ padding: 12, color: 'oklch(0.55 0 0)' }}>
              No log output yet.
            </div>
          ) : (
            <>
            {capped && (
              <button
                type="button"
                onClick={() => setShowAll(true)}
                data-testid="load-older-lines"
                style={{
                  display: 'block',
                  width: '100%',
                  padding: '6px 12px',
                  textAlign: 'left',
                  background: 'oklch(0.16 0 0)',
                  border: 'none',
                  borderBottom: '1px solid oklch(0.22 0 0)',
                  color: 'oklch(0.70 0 0)',
                  fontFamily: 'var(--font-mono)',
                  fontSize: 10.5,
                  cursor: 'pointer',
                }}
              >
                Showing last {MAX_RENDERED_LINES.toLocaleString()} of{' '}
                {lines.length.toLocaleString()} lines · load older
              </button>
            )}
            {lines.slice(startIdx).map((text, j) => {
              const i = startIdx + j
              const sev = severities[i]!
              const isInlineMatch = currentLine === i
              const lineBarMatches = matchesByLine?.get(i)
              const isBarCurrentLine =
                barOpen && barCurrent !== null && barCurrent.line === i
              const lineClass = barOpen
                ? `log-line no-ts ${sev} ${isBarCurrentLine ? 'current-match' : ''}`
                : `log-line no-ts ${sev} ${isInlineMatch ? 'current-match' : ''}`
              return (
                <div
                  key={i}
                  data-ln={i}
                  data-testid={`log-line-${i}`}
                  className={lineClass}
                  style={{
                    display: 'flex',
                    gap: 10,
                    padding: '0 12px',
                  }}
                >
                  <span
                    className="ln"
                    style={{
                      color: 'oklch(0.40 0 0)',
                      minWidth: 38,
                      textAlign: 'right',
                      flexShrink: 0,
                    }}
                  >
                    {i + 1}
                  </span>
                  <span className="msg" style={{ whiteSpace: 'pre-wrap', flex: 1, minWidth: 0 }}>
                    {barOpen
                      ? lineBarMatches && lineBarMatches.length > 0
                        ? highlightRanges(text, lineBarMatches, barCurrent, i)
                        : text
                      : highlight(text, search, isInlineMatch)}
                  </span>
                </div>
              )
            })}
            </>
          )}
        </div>
        <Minimap
          severities={severities}
          totalLines={lines.length}
          viewportRange={null}
          consoleEl={bodyRef.current}
          onJump={onMinimapJump}
        />
        {!autoFollow && (
          <button
            type="button"
            onClick={resumeFollow}
            data-testid="resume-follow-chip"
            aria-label="Resume follow"
            style={{
              position: 'absolute',
              bottom: 12,
              right: 24,
              padding: '6px 12px',
              borderRadius: 999,
              background: 'var(--accent)',
              color: 'oklch(0.12 0.018 250)',
              fontFamily: 'var(--font-mono)',
              fontSize: 11,
              fontWeight: 600,
              border: '1px solid oklch(0.22 0 0)',
              cursor: 'pointer',
              boxShadow: '0 4px 12px oklch(0 0 0 / 0.45)',
              zIndex: 2,
            }}
          >
            Resume follow ↓
          </button>
        )}
      </div>

      <div
        style={{
          padding: '6px 12px',
          borderTop: '1px solid oklch(0.22 0 0)',
          background: 'oklch(0.10 0 0)',
          fontFamily: 'var(--font-mono)',
          fontSize: 10.5,
          color: 'oklch(0.50 0 0)',
          display: 'flex',
        }}
      >
        <span>
          {sseState === 'live' ? 'Streaming' : 'Static'} · n / N to navigate · / to search ·{' '}
          <span data-testid="auto-follow-status">
            auto-follow {autoFollow ? 'on' : 'off'} · last line {lines.length}
          </span>
        </span>
      </div>
    </div>
  )
}
