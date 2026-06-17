/**
 * Adversarial tests for the v3-stack polish landing (#551 — three follow-ups
 * deferred from PR #550):
 *   1. Live, draggable minimap viewport indicator.
 *   2. Console search wired through the terminal frame (`/`, n/N, mark count).
 *   3. Selection caret + selection pulse on the DAG card.
 *
 * Pinned invariants:
 *   - The viewport rectangle is rendered as a single overlay div that tracks
 *     the console's scrollTop / scrollHeight ratio. Mutating scrollTop and
 *     dispatching a scroll event MUST move the rectangle.
 *   - Pointerdown on the minimap MUST write a new scrollTop. We assert via
 *     a getter/setter spy so we don't depend on jsdom's layout engine.
 *   - The terminal search box highlights matches inline with <mark>, shows a
 *     counter `n / total` (or `— / 0` when no matches), and `n` cycles forward.
 *   - The DAG card renders the accent caret SVG path only when selected AND
 *     hides it when selection clears.
 *   - The selection pulse skips entirely under `prefers-reduced-motion`.
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { act, render, screen, fireEvent } from '@testing-library/react'
import { Minimap } from '../components/BuildDetail/Minimap'
import { TerminalConsole } from '../components/BuildDetail/TerminalConsole'
import { StackCardNode, type StackCardData } from '../components/BuildDetail/StackCardNode'
import { ReactFlowProvider, type NodeProps } from '@xyflow/react'
import type { ReactNode } from 'react'

function withFlow(children: ReactNode) {
  return <ReactFlowProvider>{children}</ReactFlowProvider>
}

// ── Helpers ────────────────────────────────────────────────────────────────

function makeFakeConsole(): {
  el: HTMLDivElement
  setScroll: (top: number) => void
  scrollSets: number[]
} {
  const el = document.createElement('div')
  document.body.appendChild(el)
  // jsdom doesn't lay out — we hand-stub the metrics the Minimap reads.
  Object.defineProperty(el, 'scrollHeight', { value: 1000, configurable: true })
  Object.defineProperty(el, 'clientHeight', { value: 200, configurable: true })
  let scrollTop = 0
  const scrollSets: number[] = []
  Object.defineProperty(el, 'scrollTop', {
    configurable: true,
    get() {
      return scrollTop
    },
    set(v: number) {
      scrollTop = v
      scrollSets.push(v)
    },
  })
  // Stub bounding rect so the pointer math is deterministic. The minimap
  // root reads its own getBoundingClientRect; we stub on prototypes elsewhere
  // as needed per-test.
  const setScroll = (top: number) => {
    scrollTop = top
    el.dispatchEvent(new Event('scroll'))
  }
  return { el, setScroll, scrollSets }
}

function mockMatchMedia(reduceMotion: boolean) {
  const mm = vi.fn().mockImplementation((q: string) => ({
    matches: q.includes('reduce') ? reduceMotion : false,
    media: q,
    addEventListener: () => {},
    removeEventListener: () => {},
    addListener: () => {},
    removeListener: () => {},
    onchange: null,
    dispatchEvent: () => false,
  }))
  Object.defineProperty(window, 'matchMedia', { configurable: true, writable: true, value: mm })
}

// Build a minimal NodeProps-like object — xyflow's NodeProps has many
// fields the component doesn't read; we only need `data` + `selected`.
function nodeProps(data: StackCardData, selected: boolean): NodeProps & { data: StackCardData } {
  return {
    id: data.nodeId,
    data,
    selected,
    type: 'stackCard',
    dragging: false,
    isConnectable: false,
    positionAbsoluteX: 0,
    positionAbsoluteY: 0,
    width: 180,
    height: 80,
    zIndex: 0,
    targetPosition: 'left',
    sourcePosition: 'right',
    selectable: true,
    deletable: false,
    draggable: false,
  } as unknown as NodeProps & { data: StackCardData }
}

const PROPER_CARD_DATA: StackCardData = {
  nodeId: 'test-it',
  name: 'test: it',
  descriptor: 'jest --runInBand',
  footer: 'step',
  durationMs: 116000,
  status: 'FAILED',
  variant: 'fail',
}

beforeEach(() => {
  mockMatchMedia(false)
})

afterEach(() => {
  document.body.innerHTML = ''
  vi.restoreAllMocks()
})

// ── 1. Minimap: live viewport + drag-to-scroll ────────────────────────────

describe('Minimap — live viewport indicator (#551)', () => {
  it('renders a viewport rectangle proportional to scrollTop / scrollHeight', () => {
    const { el, setScroll } = makeFakeConsole()
    const { rerender } = render(
      <Minimap
        severities={['ok', 'ok', 'err', 'warn']}
        totalLines={4}
        viewportRange={null}
        consoleEl={el}
      />,
    )

    const initial = screen.getByTestId('minimap-viewport') as HTMLElement
    // At scrollTop=0 the viewport sits at top:0%
    expect(initial.style.top).toBe('0%')
    // Height = clientHeight / scrollHeight = 200/1000 = 20%
    expect(initial.style.height).toBe('20%')

    // Move scroll → re-render path: Minimap subscribes to scroll events.
    act(() => {
      setScroll(500) // 50% through
    })
    // The component reads metrics on the scroll event and updates state;
    // re-render is implicit. Re-query and check top moved.
    rerender(
      <Minimap
        severities={['ok', 'ok', 'err', 'warn']}
        totalLines={4}
        viewportRange={null}
        consoleEl={el}
      />,
    )
    const moved = screen.getByTestId('minimap-viewport') as HTMLElement
    expect(moved.style.top).toBe('50%')
  })

  it('drag (pointerdown + pointermove) writes scrollTop on the console', () => {
    const { el, scrollSets } = makeFakeConsole()
    render(
      <Minimap
        severities={['ok', 'err']}
        totalLines={2}
        viewportRange={null}
        consoleEl={el}
      />,
    )
    const root = screen.getByTestId('log-minimap') as HTMLElement
    // Stub the root's rect so the pointer math is deterministic.
    vi.spyOn(root, 'getBoundingClientRect').mockReturnValue({
      top: 0,
      left: 0,
      bottom: 100,
      right: 16,
      width: 16,
      height: 100,
      x: 0,
      y: 0,
      toJSON: () => ({}),
    } as DOMRect)

    // jsdom: synthesize PointerEvents manually so clientY survives — fireEvent
    // doesn't always forward init props for pointer events.
    function pe(type: string, clientY: number): Event {
      const ev = new Event(type, { bubbles: true, cancelable: true }) as Event & {
        clientX: number
        clientY: number
        pointerId: number
      }
      ev.clientX = 8
      ev.clientY = clientY
      ev.pointerId = 1
      return ev
    }
    act(() => {
      root.dispatchEvent(pe('pointerdown', 50))
      root.dispatchEvent(pe('pointermove', 75))
      root.dispatchEvent(pe('pointerup', 75))
    })

    // pointerDown @ 50%  → scrollTop ≈ 500
    // pointerMove @ 75% → scrollTop ≈ 750 (then clamped to maxScroll=800)
    expect(scrollSets.length).toBeGreaterThanOrEqual(2)
    expect(scrollSets[0]).toBe(500)
    expect(scrollSets[1]).toBe(750)
  })
})

// ── 2. Console search inside the terminal frame ───────────────────────────

describe('TerminalConsole — inline search (#551)', () => {
  const LINES = [
    '$ pnpm test',
    '  ✓ POST /v2/auth/login → 200',
    '  ✓ POST /v2/auth/refresh → 200',
    '  ⚠ deprecated POST endpoint',
    '  ✗ POST /v2/workers/:id/labels → expected 200, got 409',
    'FAIL test/it/workers.spec.ts',
  ]

  it('typing a query highlights matches inline with <mark> + counter shows totals', () => {
    render(<TerminalConsole lines={LINES} sseState="done" />)

    const input = screen.getByTestId('terminal-search-input') as HTMLInputElement
    fireEvent.change(input, { target: { value: 'POST' } })

    // 4 lines contain 'POST' (lines 1, 2, 3, 4) — one <mark> each (case-insensitive).
    const marks = screen.getAllByTestId('search-mark')
    expect(marks.length).toBe(4)
    for (const m of marks) {
      expect(m.tagName.toLowerCase()).toBe('mark')
    }

    // Counter shows '1 / 4'
    const counter = screen.getByTestId('terminal-search-counter')
    expect(counter.textContent).toBe('1 / 4')
  })

  it('counter shows `— / 0` when no matches', () => {
    render(<TerminalConsole lines={LINES} sseState="done" />)
    const input = screen.getByTestId('terminal-search-input') as HTMLInputElement
    fireEvent.change(input, { target: { value: 'no-such-string-anywhere' } })
    const counter = screen.getByTestId('terminal-search-counter')
    expect(counter.textContent).toBe('— / 0')
    expect(screen.queryAllByTestId('search-mark').length).toBe(0)
  })

  it('empty query → no marks, no counter', () => {
    render(<TerminalConsole lines={LINES} sseState="done" />)
    expect(screen.queryAllByTestId('search-mark').length).toBe(0)
    expect(screen.queryByTestId('terminal-search-counter')).toBeNull()
  })

  it('pressing `n` advances current-match through results', () => {
    render(<TerminalConsole lines={LINES} sseState="done" />)
    const input = screen.getByTestId('terminal-search-input') as HTMLInputElement
    fireEvent.change(input, { target: { value: 'POST' } })

    // Initial: line 1 (first line containing POST) is the current match.
    const initialCurrent = document.querySelector('.log-line.current-match')
    expect(initialCurrent).not.toBeNull()
    const initialLn = initialCurrent!.getAttribute('data-ln')
    expect(initialLn).toBe('1')

    // Press `n` — dispatch on the terminal frame.
    const term = screen.getByTestId('terminal-console')
    act(() => {
      fireEvent.keyDown(term, { key: 'n' })
    })

    const nextCurrent = document.querySelector('.log-line.current-match')
    expect(nextCurrent).not.toBeNull()
    expect(nextCurrent!.getAttribute('data-ln')).toBe('2')

    // Counter advanced to '2 / 4'
    expect(screen.getByTestId('terminal-search-counter').textContent).toBe('2 / 4')
  })
})

// ── 3. Selection caret + pulse on the DAG card ────────────────────────────

describe('StackCardNode — selection caret + pulse (#551)', () => {
  it('renders the accent caret SVG when selected, hides it when not', () => {
    const data: StackCardData = { ...PROPER_CARD_DATA }
    const { rerender } = render(withFlow(<StackCardNode {...nodeProps(data, false)} />))
    expect(screen.queryByTestId('dag-node-caret-test-it')).toBeNull()

    rerender(withFlow(<StackCardNode {...nodeProps(data, true)} />))
    const caret = screen.getByTestId('dag-node-caret-test-it')
    // The caret IS the SVG path from design line 447.
    const path = caret.querySelector('path')
    expect(path).not.toBeNull()
    expect(path!.getAttribute('d')).toBe('M-14 36 L-2 42 L-14 48 Z')
    expect(path!.getAttribute('fill')).toBe('var(--accent)')

    // Clear selection → caret disappears.
    rerender(withFlow(<StackCardNode {...nodeProps(data, false)} />))
    expect(screen.queryByTestId('dag-node-caret-test-it')).toBeNull()
  })

  it('adds .pulse class on selection transition (motion-allowed env)', async () => {
    mockMatchMedia(false) // motion allowed
    vi.useFakeTimers()
    const data: StackCardData = { ...PROPER_CARD_DATA }
    const { rerender, container } = render(withFlow(<StackCardNode {...nodeProps(data, false)} />))

    act(() => {
      rerender(withFlow(<StackCardNode {...nodeProps(data, true)} />))
    })

    const card = container.querySelector('.bd-stack-card') as HTMLElement
    expect(card.className).toMatch(/\bpulse\b/)

    // After 600ms the class clears.
    act(() => {
      vi.advanceTimersByTime(650)
    })
    expect(card.className).not.toMatch(/\bpulse\b/)
    vi.useRealTimers()
  })

  it('SKIPS the pulse entirely when prefers-reduced-motion is set', () => {
    mockMatchMedia(true) // reduce motion
    const data: StackCardData = { ...PROPER_CARD_DATA }
    const { rerender, container } = render(withFlow(<StackCardNode {...nodeProps(data, false)} />))
    rerender(withFlow(<StackCardNode {...nodeProps(data, true)} />))
    const card = container.querySelector('.bd-stack-card') as HTMLElement
    expect(card.className).not.toMatch(/\bpulse\b/)
  })
})
