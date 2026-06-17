/**
 * TerminalConsole — line-cap rendering (#1264).
 *
 * A 50k-line build must not mount 50k DOM rows (jank). The console keeps the
 * TAIL — the live edge SREs watch — and mounts only the last
 * `MAX_RENDERED_LINES`, exposing a "load older" affordance to mount the full
 * buffer on demand. Absolute line numbers (and the `done` SSE state) must be
 * preserved so search/auto-follow keep working.
 */
import { describe, it, expect } from 'vitest'
import { render, screen, cleanup, fireEvent } from '@testing-library/react'
import { afterEach } from 'vitest'
import { TerminalConsole, MAX_RENDERED_LINES } from '@/components/BuildDetail/TerminalConsole'

afterEach(cleanup)

function makeLines(n: number): string[] {
  return Array.from({ length: n }, (_, i) => `line ${i}`)
}

describe('TerminalConsole line cap', () => {
  it('renders all lines and no "load older" control when under the cap', () => {
    render(<TerminalConsole lines={makeLines(10)} sseState="live" />)
    expect(screen.getByTestId('log-line-0')).toBeInTheDocument()
    expect(screen.getByTestId('log-line-9')).toBeInTheDocument()
    expect(screen.queryByTestId('load-older-lines')).not.toBeInTheDocument()
  })

  it('mounts only the last MAX_RENDERED_LINES (tail) for a huge log, dropping older rows', () => {
    const total = MAX_RENDERED_LINES + 50
    render(<TerminalConsole lines={makeLines(total)} sseState="live" />)

    // Oldest lines are NOT mounted…
    expect(screen.queryByTestId('log-line-0')).not.toBeInTheDocument()
    expect(screen.queryByTestId(`log-line-${total - MAX_RENDERED_LINES - 1}`)).not.toBeInTheDocument()
    // …but the first kept line and the live tail ARE, with their ABSOLUTE index.
    expect(screen.getByTestId(`log-line-${total - MAX_RENDERED_LINES}`)).toBeInTheDocument()
    expect(screen.getByTestId(`log-line-${total - 1}`)).toBeInTheDocument()
  }, 20_000)

  it('shows a "load older" affordance naming the cap, and mounts the full buffer when clicked', () => {
    const total = MAX_RENDERED_LINES + 50
    render(<TerminalConsole lines={makeLines(total)} sseState="done" />)

    const loadOlder = screen.getByTestId('load-older-lines')
    expect(loadOlder).toHaveTextContent(
      `Showing last ${MAX_RENDERED_LINES.toLocaleString()} of ${total.toLocaleString()} lines`,
    )

    fireEvent.click(loadOlder)

    // Full buffer now mounted; the affordance is gone.
    expect(screen.getByTestId('log-line-0')).toBeInTheDocument()
    expect(screen.getByTestId(`log-line-${total - 1}`)).toBeInTheDocument()
    expect(screen.queryByTestId('load-older-lines')).not.toBeInTheDocument()
  }, 20_000)

  it('expands the full buffer when a search match lands in the dropped older region (#1264 sev2)', () => {
    // A unique needle on an OLD line that the line-cap drops from the DOM.
    const total = MAX_RENDERED_LINES + 50 // startIdx = 50 → lines 0..49 dropped
    const lines = makeLines(total)
    lines[5] = 'NEEDLE only here' // index 5 is in the dropped older region

    render(<TerminalConsole lines={lines} sseState="done" />)

    // Precondition: the old needle line is NOT mounted under the cap.
    expect(screen.queryByTestId('log-line-5')).not.toBeInTheDocument()

    // Searching for it must auto-expand so the row mounts (otherwise n/N would
    // silently no-op into the capped region — the sev2 bug).
    fireEvent.change(screen.getByTestId('terminal-search-input'), {
      target: { value: 'NEEDLE' },
    })

    const needleLine = screen.getByTestId('log-line-5')
    expect(needleLine).toBeInTheDocument()
    expect(needleLine).toHaveClass('current-match')
  }, 20_000)

  it('surfaces the connection state (reconnecting) in the header so the UI never lies about a drop', () => {
    render(<TerminalConsole lines={makeLines(3)} sseState="reconnecting" />)
    const indicator = screen.getByTestId('sse-state')
    expect(indicator).toHaveAttribute('data-sse-state', 'reconnecting')
    expect(indicator).toHaveTextContent('reconnecting')
  })
})
