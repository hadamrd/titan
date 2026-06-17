/**
 * Minimap — 16px-wide structure-banded mini-bar that sits to the right of
 * the console. Each band's color = a severity bucket and height = the
 * fraction of total lines in that bucket. The viewport indicator overlays
 * the band that contains the currently-scrolled-to range AND is draggable
 * to scrub the terminal proportionally (issue #551 polish).
 *
 * Visual spec: docs/design/build-detail-v3-mockups/project/build-detail/
 * v3-stack.html lines 649–670.
 */
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'

export type LineSeverity = 'err' | 'warn' | 'ok' | 'info' | 'cmd' | ''

interface Band {
  severity: LineSeverity
  height: number // 0..1
}

interface Props {
  severities: LineSeverity[]
  // viewportRange = [startLine, endLine] (1-based, inclusive). Pass null
  // when the console hasn't scrolled / measured yet.
  viewportRange: [number, number] | null
  totalLines: number
  onJump?: (linePct: number) => void
  // When set, the minimap tracks the console scroll element directly and
  // renders a draggable viewport rectangle proportional to its scroll metrics.
  // This is the "live viewport" polish from #551 — it supersedes viewportRange
  // when both are provided.
  consoleEl?: HTMLElement | null
}

const COLOR: Record<LineSeverity, string> = {
  err: 'var(--fail)',
  warn: 'var(--warn)',
  ok: 'var(--ok)',
  info: 'var(--info)',
  cmd: 'var(--accent)',
  '': 'transparent',
}

const OPACITY: Record<LineSeverity, number> = {
  err: 1,
  warn: 0.85,
  ok: 0.55,
  info: 0.5,
  cmd: 0.5,
  '': 0.05,
}

function bandFor(severities: LineSeverity[]): Band[] {
  if (severities.length === 0) return []
  // Collapse runs of the same severity into a single band proportional to
  // its length — preserves the "structure" look of the mockup without
  // rendering 1247 individual divs.
  const out: Band[] = []
  let cur: LineSeverity = severities[0]!
  let runStart = 0
  for (let i = 1; i <= severities.length; i++) {
    if (i === severities.length || severities[i] !== cur) {
      out.push({ severity: cur, height: (i - runStart) / severities.length })
      if (i < severities.length) {
        cur = severities[i]!
        runStart = i
      }
    }
  }
  return out
}

interface LiveMetrics {
  topPct: number
  heightPct: number
}

function readMetrics(el: HTMLElement | null): LiveMetrics | null {
  if (!el) return null
  const sh = el.scrollHeight
  if (sh <= 0) return null
  const ch = el.clientHeight
  if (ch <= 0) return null
  const topPct = el.scrollTop / sh
  const heightPct = ch / sh
  return { topPct, heightPct }
}

export function Minimap({
  severities,
  viewportRange,
  totalLines,
  onJump,
  consoleEl,
}: Props) {
  const bands = useMemo(() => bandFor(severities), [severities])
  const rootRef = useRef<HTMLDivElement>(null)
  const [live, setLive] = useState<LiveMetrics | null>(() => readMetrics(consoleEl ?? null))
  const draggingRef = useRef(false)

  // Subscribe to the console's scroll + resize events so the viewport
  // rectangle stays in sync as new SSE lines stream in.
  useEffect(() => {
    if (!consoleEl) {
      setLive(null)
      return
    }
    const update = () => setLive(readMetrics(consoleEl))
    update()
    consoleEl.addEventListener('scroll', update, { passive: true })
    const ro = typeof ResizeObserver !== 'undefined' ? new ResizeObserver(update) : null
    if (ro) ro.observe(consoleEl)
    return () => {
      consoleEl.removeEventListener('scroll', update)
      if (ro) ro.disconnect()
    }
  }, [consoleEl])

  // Drag handlers — translate clientY → consoleEl.scrollTop proportionally.
  const scrollToPct = useCallback(
    (pct: number) => {
      const clamped = Math.max(0, Math.min(1, pct))
      if (consoleEl) {
        const sh = consoleEl.scrollHeight
        const ch = consoleEl.clientHeight
        const maxScroll = Math.max(0, sh - ch)
        // Anchor the viewport so the drag point is the TOP of the indicator.
        consoleEl.scrollTop = clamped * sh
        // Re-clamp via real metric to handle ranges where maxScroll < sh*pct.
        if (consoleEl.scrollTop > maxScroll) consoleEl.scrollTop = maxScroll
      }
      if (onJump) onJump(clamped)
    },
    [consoleEl, onJump],
  )

  const handlePointerDown = (e: React.PointerEvent) => {
    const root = rootRef.current
    if (!root) return
    draggingRef.current = true
    try {
      root.setPointerCapture(e.pointerId)
    } catch {
      /* not all environments support pointer capture (e.g. jsdom) */
    }
    const rect = root.getBoundingClientRect()
    scrollToPct((e.clientY - rect.top) / rect.height)
  }

  const handlePointerMove = (e: React.PointerEvent) => {
    if (!draggingRef.current) return
    const root = rootRef.current
    if (!root) return
    const rect = root.getBoundingClientRect()
    scrollToPct((e.clientY - rect.top) / rect.height)
  }

  const handlePointerUp = (e: React.PointerEvent) => {
    draggingRef.current = false
    const root = rootRef.current
    if (root) {
      try {
        root.releasePointerCapture(e.pointerId)
      } catch {
        /* ignore */
      }
    }
  }

  // Resolve the viewport rectangle. Live metrics take precedence; fall back
  // to the static `viewportRange` (the pre-#551 contract).
  let viewportPctTop = 0
  let viewportPctHeight = 0
  let showViewport = false
  if (live) {
    viewportPctTop = live.topPct
    viewportPctHeight = live.heightPct
    showViewport = true
  } else if (viewportRange && totalLines > 0) {
    viewportPctTop = (viewportRange[0] - 1) / totalLines
    viewportPctHeight = (viewportRange[1] - viewportRange[0] + 1) / totalLines
    showViewport = true
  }

  const interactive = Boolean(consoleEl) || Boolean(onJump)

  return (
    <div
      ref={rootRef}
      className="bd-minimap"
      data-testid="log-minimap"
      role="presentation"
      onPointerDown={interactive ? handlePointerDown : undefined}
      onPointerMove={interactive ? handlePointerMove : undefined}
      onPointerUp={interactive ? handlePointerUp : undefined}
      onPointerCancel={interactive ? handlePointerUp : undefined}
      style={{
        width: 16,
        background: 'oklch(0.10 0 0)',
        borderLeft: '1px solid var(--border)',
        position: 'relative',
        flexShrink: 0,
        cursor: interactive ? (consoleEl ? 'ns-resize' : 'pointer') : 'default',
        touchAction: 'none',
      }}
    >
      <div style={{ position: 'absolute', inset: 0, display: 'flex', flexDirection: 'column' }}>
        {bands.map((b, i) => (
          <div
            key={i}
            data-testid="minimap-band"
            style={{
              flex: `${b.height} 0 0`,
              background: COLOR[b.severity],
              opacity: OPACITY[b.severity],
              minHeight: 1,
            }}
          />
        ))}
      </div>
      {showViewport && (
        <div
          data-testid="minimap-viewport"
          style={{
            position: 'absolute',
            left: -2,
            right: -2,
            top: `${viewportPctTop * 100}%`,
            height: `${Math.max(0.04, viewportPctHeight) * 100}%`,
            border: '1px solid var(--accent)',
            borderRadius: 2,
            background: 'var(--accent-soft)',
            pointerEvents: 'none',
          }}
        />
      )}
    </div>
  )
}
