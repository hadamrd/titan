/**
 * Adversarial tests for the console auto-follow behavior (#563).
 *
 * The live build console glues itself to the last log line by default so an
 * SRE staring at a streaming run sees the tail without manual scroll. The
 * opt-out is intentional: any meaningful scroll-up disengages follow, and a
 * persistent "Resume follow" chip is the only way back (besides the `End`
 * keystroke).
 *
 * Pinned invariants:
 *   1. Every new chunk while autoFollow is on calls scrollIntoView on the
 *      last log line — not the first, not "near" the bottom.
 *   2. A manual scrollTop reduction beyond the dead-band (40px) disengages
 *      autoFollow AND surfaces the chip.
 *   3. While disengaged, no new chunk re-engages follow on its own — the
 *      user is in control until they click the chip or press End.
 *   4. The chip click + End key both re-engage AND fire scrollIntoView.
 */
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, fireEvent, act } from '@testing-library/react'
import { TerminalConsole } from '../components/BuildDetail/TerminalConsole'

// jsdom doesn't implement scrollIntoView; we stub a spy so the test asserts
// the production code's intent (glue last line) without depending on layout.
const scrollSpy = vi.fn()

beforeEach(() => {
  scrollSpy.mockReset()
  Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', {
    configurable: true,
    writable: true,
    value: scrollSpy,
  })
})

function renderWithLines(initial: string[]) {
  return render(<TerminalConsole lines={initial} sseState="live" />)
}

describe('TerminalConsole — auto-follow (#563)', () => {
  it('scrolls last line into view on every new SSE chunk by default', () => {
    const { rerender } = renderWithLines(['line 1'])
    // Initial mount counts as the first auto-follow tick.
    expect(scrollSpy).toHaveBeenCalledTimes(1)

    // Simulate 4 additional chunks landing — total 5.
    const lines = ['line 1']
    for (let i = 2; i <= 5; i++) {
      lines.push(`line ${i}`)
      rerender(<TerminalConsole lines={[...lines]} sseState="live" />)
    }
    expect(scrollSpy).toHaveBeenCalledTimes(5)
  })

  it('manual scroll-up beyond the dead-band disengages follow and shows the chip', () => {
    const lines = ['a', 'b', 'c']
    const { rerender } = renderWithLines(lines)
    expect(scrollSpy).toHaveBeenCalledTimes(1)
    scrollSpy.mockClear()

    const body = screen.getByTestId('terminal-body')
    // Set a believable "we were scrolled near the bottom" baseline, then jump
    // to the top. The component reads scrollTop directly to detect the delta.
    Object.defineProperty(body, 'scrollTop', { configurable: true, writable: true, value: 500 })
    fireEvent.scroll(body)
    // Simulate user scrolling up >40px.
    Object.defineProperty(body, 'scrollTop', { configurable: true, writable: true, value: 0 })
    fireEvent.scroll(body)

    expect(screen.getByTestId('resume-follow-chip')).toBeInTheDocument()
    expect(screen.getByTestId('auto-follow-status')).toHaveTextContent('auto-follow off')

    // A subsequent chunk MUST NOT scroll — the user is in control.
    scrollSpy.mockClear()
    rerender(<TerminalConsole lines={[...lines, 'd']} sseState="live" />)
    expect(scrollSpy).not.toHaveBeenCalled()
  })

  it('clicking the chip hides it and resumes follow', async () => {
    const lines = ['a', 'b', 'c']
    const { rerender } = renderWithLines(lines)

    const body = screen.getByTestId('terminal-body')
    Object.defineProperty(body, 'scrollTop', { configurable: true, writable: true, value: 500 })
    fireEvent.scroll(body)
    Object.defineProperty(body, 'scrollTop', { configurable: true, writable: true, value: 0 })
    fireEvent.scroll(body)

    const chip = screen.getByTestId('resume-follow-chip')
    expect(chip).toBeInTheDocument()
    scrollSpy.mockClear()

    await act(async () => {
      fireEvent.click(chip)
    })

    expect(screen.queryByTestId('resume-follow-chip')).not.toBeInTheDocument()
    expect(scrollSpy).toHaveBeenCalled()
    expect(screen.getByTestId('auto-follow-status')).toHaveTextContent('auto-follow on')

    // A subsequent chunk scrolls again — follow is fully re-engaged.
    scrollSpy.mockClear()
    rerender(<TerminalConsole lines={[...lines, 'd']} sseState="live" />)
    expect(scrollSpy).toHaveBeenCalled()
  })

  it('pressing End while console is focused hides the chip and resumes follow', () => {
    renderWithLines(['a', 'b', 'c'])

    const body = screen.getByTestId('terminal-body')
    Object.defineProperty(body, 'scrollTop', { configurable: true, writable: true, value: 500 })
    fireEvent.scroll(body)
    Object.defineProperty(body, 'scrollTop', { configurable: true, writable: true, value: 0 })
    fireEvent.scroll(body)

    expect(screen.getByTestId('resume-follow-chip')).toBeInTheDocument()
    scrollSpy.mockClear()

    const root = screen.getByTestId('terminal-console')
    fireEvent.keyDown(root, { key: 'End' })

    expect(screen.queryByTestId('resume-follow-chip')).not.toBeInTheDocument()
    expect(screen.getByTestId('auto-follow-status')).toHaveTextContent('auto-follow on')
  })
})
