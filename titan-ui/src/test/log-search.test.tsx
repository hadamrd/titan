/**
 * Adversarial tests for in-log search (#694) — both the {@link useLogSearch}
 * hook and the {@link LogSearchBar} component.
 *
 * <p>Sad-path matrix from the issue:
 * <ul>
 *   <li>empty log + query "foo" → "0 of 0", prev/next disabled
 *   <li>3 matches: counter "1 of 3" → next → "2 of 3" → next at last wraps
 *       to "1 of 3" (wrap = standard editor behaviour; documented choice)
 *   <li>catastrophic-backtracking regex (a+)+$ → input red border + no hang
 *       (validated under 50ms)
 *   <li>streaming: append 2 lines; counter grows; cursor preserved
 *   <li>Esc closes the bar; Ctrl+F reopens
 * </ul>
 */
import { describe, it, expect, vi } from 'vitest'
import { render, renderHook, act, screen, fireEvent } from '@testing-library/react'
import { useState } from 'react'
import { useLogSearch } from '@/lib/useLogSearch'
import { LogSearchBar } from '@/components/LogSearchBar'
import { TerminalConsole } from '@/components/BuildDetail/TerminalConsole'

describe('useLogSearch', () => {
  it('empty log + query "foo" → no matches', () => {
    const { result } = renderHook(() => useLogSearch([], 'foo', false))
    expect(result.current.matches).toHaveLength(0)
    expect(result.current.currentIdx).toBe(-1)
    expect(result.current.current).toBeNull()
  })

  it('3 matches across multiple lines; wrap on next at last match', () => {
    const lines = ['foo bar', 'baz foo qux', 'foo end']
    const { result } = renderHook(() => useLogSearch(lines, 'foo', false))
    expect(result.current.matches).toHaveLength(3)
    expect(result.current.currentIdx).toBe(0)

    act(() => result.current.next())
    expect(result.current.currentIdx).toBe(1)

    act(() => result.current.next())
    expect(result.current.currentIdx).toBe(2)

    // wrap
    act(() => result.current.next())
    expect(result.current.currentIdx).toBe(0)

    // prev from first wraps to last
    act(() => result.current.prev())
    expect(result.current.currentIdx).toBe(2)
  })

  it('catastrophic-backtracking regex is rejected without hang', () => {
    const lines = ['aaaaaaaaaaaaaaaaaaaaaaaaaaaaaa!'] // would chew (a+)+$
    const t0 = performance.now()
    const { result } = renderHook(() => useLogSearch(lines, '(a+)+$', true))
    const elapsed = performance.now() - t0
    expect(elapsed).toBeLessThan(50)
    expect(result.current.regexError).not.toBeNull()
    // Falls back to literal search — "(a+)+$" as a literal won't match.
    expect(result.current.matches).toHaveLength(0)
  })

  it('streaming: appending matching lines grows the counter and preserves cursor', () => {
    const initial = ['foo a', 'foo b', 'foo c']
    const { result, rerender } = renderHook(
      ({ lines }: { lines: string[] }) => useLogSearch(lines, 'foo', false),
      { initialProps: { lines: initial } },
    )
    expect(result.current.matches).toHaveLength(3)

    // Move cursor to match #2 (line index 1).
    act(() => result.current.next())
    expect(result.current.current?.line).toBe(1)

    // Append 2 more matching lines (streaming SSE chunk).
    const grown = [...initial, 'foo d', 'foo e']
    rerender({ lines: grown })

    expect(result.current.matches).toHaveLength(5)
    // Cursor preserved at line 1 (still matched).
    expect(result.current.current?.line).toBe(1)
  })

  it('literal mode escapes regex metachars', () => {
    const lines = ['price is $5.00', 'free']
    const { result } = renderHook(() => useLogSearch(lines, '$5.00', false))
    expect(result.current.matches).toHaveLength(1)
    expect(result.current.matches[0]!.line).toBe(0)
  })

  it('regex mode finds capture matches', () => {
    const lines = ['ERROR foo', 'WARN bar', 'ERROR baz']
    const { result } = renderHook(() => useLogSearch(lines, '^ERROR', true))
    expect(result.current.matches).toHaveLength(2)
    expect(result.current.regexError).toBeNull()
  })

  it('zero-width regex does not loop forever', () => {
    const lines = ['abc']
    const t0 = performance.now()
    renderHook(() => useLogSearch(lines, 'a*', true))
    expect(performance.now() - t0).toBeLessThan(50)
  })

  it('adversarial: query with regex special chars (.*?) is treated literally in non-regex mode', () => {
    // Acceptance criterion #1097: a query string with regex special chars
    // (.*?) must match literally, not as a wildcard. Otherwise SRE searching
    // for `pattern.*?match` in a log gets garbage hits.
    const lines = [
      'normal text here',          // would match `.*?` as regex
      'literal .*? sequence here', // the ONLY line that matches literally
      'also normal',
    ]
    const { result } = renderHook(() => useLogSearch(lines, '.*?', false))
    expect(result.current.matches).toHaveLength(1)
    expect(result.current.matches[0]!.line).toBe(1)
    // And the matched range is exactly the `.*?` substring.
    const m = result.current.matches[0]!
    expect(lines[m.line]!.slice(m.start, m.end)).toBe('.*?')
  })

  it('adversarial: literal mode escapes a bare `[` (would otherwise throw)', () => {
    // A lone `[` is an invalid regex source; if literal mode forwarded it
    // unescaped to new RegExp it would throw + return zero matches with no
    // explanation. We must escape and find the literal bracket.
    const lines = ['some text', 'has a [ bracket', 'plain']
    const { result } = renderHook(() => useLogSearch(lines, '[', false))
    expect(result.current.regexError).toBeNull()
    expect(result.current.matches).toHaveLength(1)
    expect(result.current.matches[0]!.line).toBe(1)
  })

  it('cycling: next from the last match wraps to the first (acceptance #1097)', () => {
    // Exact-shape test for the wrap acceptance criterion. 4 matches; next ×4
    // from idx 0 lands back on 0.
    const lines = ['a x', 'a y', 'a z', 'a w']
    const { result } = renderHook(() => useLogSearch(lines, 'a', false))
    expect(result.current.matches).toHaveLength(4)
    expect(result.current.currentIdx).toBe(0)
    act(() => result.current.next())
    act(() => result.current.next())
    act(() => result.current.next())
    expect(result.current.currentIdx).toBe(3)
    act(() => result.current.next()) // wraps
    expect(result.current.currentIdx).toBe(0)
  })
})

describe('TerminalConsole — highlight renderer XSS guard (#1097)', () => {
  // Acceptance criterion: the highlight renderer must escape special HTML
  // chars correctly. If we ever switched to dangerouslySetInnerHTML or
  // string-concat'd a <mark> wrapper, a log line containing `<script>` would
  // become a live DOM node. This test pins the safe-by-default contract.
  it('renders log lines containing HTML as TEXT, not as DOM nodes', () => {
    const malicious = '<script>alert("xss")</script>'
    const { container } = render(
      <TerminalConsole lines={[malicious]} sseState="done" />,
    )
    // The injected payload must NOT have spawned a real <script> child.
    expect(container.querySelector('script')).toBeNull()
    // And the literal angle-bracket text must be in the DOM as text.
    expect(container.textContent).toContain(malicious)
  })

  it('highlight wrapping does not allow tag injection via the query', () => {
    // The inline header search is the always-on highlighter. We type a query
    // that contains a closing-tag injection attempt; the matching line text
    // contains the same payload. Neither must produce live DOM.
    const malicious = '<img src=x onerror=alert(1)>'
    const lines = [`prefix ${malicious} suffix`]
    const { container } = render(<TerminalConsole lines={lines} sseState="done" />)
    const input = screen.getByTestId('terminal-search-input') as HTMLInputElement
    fireEvent.change(input, { target: { value: '<img' } })
    // No <img>, no <script>, no <iframe> got spawned by the match render.
    expect(container.querySelector('img')).toBeNull()
    expect(container.querySelector('script')).toBeNull()
    expect(container.querySelector('iframe')).toBeNull()
    // <mark> wrappers DID get spawned around the literal "<img" substring.
    const marks = container.querySelectorAll('mark')
    expect(marks.length).toBeGreaterThan(0)
    // And the marked text is the literal "<img" — not a parsed tag.
    expect(Array.from(marks).some((m) => m.textContent === '<img')).toBe(true)
  })
})

describe('LogSearchBar', () => {
  it('shows "0 of 0" with prev/next disabled when no matches', () => {
    render(
      <LogSearchBar
        query="missing"
        onQueryChange={() => undefined}
        useRegex={false}
        onUseRegexChange={() => undefined}
        matchCount={0}
        currentIdx={-1}
        onNext={() => undefined}
        onPrev={() => undefined}
        onClose={() => undefined}
        regexError={null}
      />,
    )
    expect(screen.getByTestId('log-search-counter').textContent).toBe('0 of 0')
    expect(screen.getByTestId('log-search-prev')).toBeDisabled()
    expect(screen.getByTestId('log-search-next')).toBeDisabled()
  })

  it('shows "2 of 3" and lets user click next/prev/close', () => {
    const onNext = vi.fn()
    const onPrev = vi.fn()
    const onClose = vi.fn()
    render(
      <LogSearchBar
        query="foo"
        onQueryChange={() => undefined}
        useRegex={false}
        onUseRegexChange={() => undefined}
        matchCount={3}
        currentIdx={1}
        onNext={onNext}
        onPrev={onPrev}
        onClose={onClose}
        regexError={null}
      />,
    )
    expect(screen.getByTestId('log-search-counter').textContent).toBe('2 of 3')
    fireEvent.click(screen.getByTestId('log-search-next'))
    fireEvent.click(screen.getByTestId('log-search-prev'))
    fireEvent.click(screen.getByTestId('log-search-close'))
    expect(onNext).toHaveBeenCalledOnce()
    expect(onPrev).toHaveBeenCalledOnce()
    expect(onClose).toHaveBeenCalledOnce()
  })

  it('regex error sets aria-invalid + red border on the input', () => {
    render(
      <LogSearchBar
        query="(a+)+$"
        onQueryChange={() => undefined}
        useRegex={true}
        onUseRegexChange={() => undefined}
        matchCount={0}
        currentIdx={-1}
        onNext={() => undefined}
        onPrev={() => undefined}
        onClose={() => undefined}
        regexError="Potentially catastrophic backtracking"
      />,
    )
    const input = screen.getByTestId('log-search-input') as HTMLInputElement
    expect(input.getAttribute('aria-invalid')).toBe('true')
    // The inline style reflects the --fail token when there's an error.
    expect(input.style.borderColor || input.getAttribute('style') || '').toContain('--fail')
  })

  it('Escape inside the bar calls onClose', () => {
    const onClose = vi.fn()
    render(
      <LogSearchBar
        query=""
        onQueryChange={() => undefined}
        useRegex={false}
        onUseRegexChange={() => undefined}
        matchCount={0}
        currentIdx={-1}
        onNext={() => undefined}
        onPrev={() => undefined}
        onClose={onClose}
        regexError={null}
      />,
    )
    fireEvent.keyDown(screen.getByRole('search'), { key: 'Escape' })
    expect(onClose).toHaveBeenCalledOnce()
  })

  it('Enter advances next; Shift+Enter advances prev', () => {
    const onNext = vi.fn()
    const onPrev = vi.fn()
    render(
      <LogSearchBar
        query="foo"
        onQueryChange={() => undefined}
        useRegex={false}
        onUseRegexChange={() => undefined}
        matchCount={3}
        currentIdx={0}
        onNext={onNext}
        onPrev={onPrev}
        onClose={() => undefined}
        regexError={null}
      />,
    )
    const input = screen.getByTestId('log-search-input')
    fireEvent.keyDown(input, { key: 'Enter' })
    fireEvent.keyDown(input, { key: 'Enter', shiftKey: true })
    expect(onNext).toHaveBeenCalledOnce()
    expect(onPrev).toHaveBeenCalledOnce()
  })

  it('a11y: counter has aria-live="polite"', () => {
    render(
      <LogSearchBar
        query="foo"
        onQueryChange={() => undefined}
        useRegex={false}
        onUseRegexChange={() => undefined}
        matchCount={1}
        currentIdx={0}
        onNext={() => undefined}
        onPrev={() => undefined}
        onClose={() => undefined}
        regexError={null}
      />,
    )
    const counter = screen.getByTestId('log-search-counter')
    expect(counter.getAttribute('aria-live')).toBe('polite')
  })

  it('a11y: input has aria-label="Search in log"', () => {
    render(
      <LogSearchBar
        query=""
        onQueryChange={() => undefined}
        useRegex={false}
        onUseRegexChange={() => undefined}
        matchCount={0}
        currentIdx={-1}
        onNext={() => undefined}
        onPrev={() => undefined}
        onClose={() => undefined}
        regexError={null}
      />,
    )
    expect(screen.getByLabelText('Search in log')).toBeInTheDocument()
  })
})

describe('LogSearchBar — open/close lifecycle integration', () => {
  // Mirrors what TerminalConsole does: a parent component owns `barOpen`,
  // intercepts Ctrl+F, renders the bar conditionally. Verifies that Esc
  // closes and Ctrl+F reopens — the round-trip from the issue.
  function Host() {
    const [open, setOpen] = useState(false)
    const [q, setQ] = useState('')
    if (typeof window !== 'undefined' && !(globalThis as { __hostBound?: boolean }).__hostBound) {
      // single window-level binding via React effect would be cleaner, but
      // for the test we attach inline once.
    }
    return (
      <div
        onKeyDown={(e) => {
          if ((e.ctrlKey || e.metaKey) && (e.key === 'f' || e.key === 'F')) {
            e.preventDefault()
            setOpen(true)
          }
        }}
        tabIndex={0}
        data-testid="host"
      >
        {open && (
          <LogSearchBar
            query={q}
            onQueryChange={setQ}
            useRegex={false}
            onUseRegexChange={() => undefined}
            matchCount={0}
            currentIdx={-1}
            onNext={() => undefined}
            onPrev={() => undefined}
            onClose={() => setOpen(false)}
            regexError={null}
          />
        )}
      </div>
    )
  }

  it('Ctrl+F opens, Esc closes, Ctrl+F reopens', () => {
    render(<Host />)
    const host = screen.getByTestId('host')
    expect(screen.queryByRole('search')).toBeNull()

    fireEvent.keyDown(host, { key: 'f', ctrlKey: true })
    expect(screen.getByRole('search')).toBeInTheDocument()

    fireEvent.keyDown(screen.getByRole('search'), { key: 'Escape' })
    expect(screen.queryByRole('search')).toBeNull()

    fireEvent.keyDown(host, { key: 'f', ctrlKey: true })
    expect(screen.getByRole('search')).toBeInTheDocument()
  })
})
