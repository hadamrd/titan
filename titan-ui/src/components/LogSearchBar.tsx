/**
 * LogSearchBar — floating Ctrl+F search bar over the streamed build log
 * (#694).
 *
 * <p>Renders as a small floating chrome anchored top-right of its host
 * (the terminal pane). Owns the input, regex toggle, "X of Y" counter,
 * prev/next buttons, and close button. State (query / useRegex / matches
 * cursor) is owned by the caller and the hook {@link useLogSearch}.
 *
 * <h3>a11y</h3>
 * Input has {@code aria-label="Search in log"}. The match counter has
 * {@code aria-live="polite"} so a screen reader announces "5 of 42" as the
 * user types. {@code Escape} closes the bar via {@code onClose}; the caller
 * intercepts Ctrl+F to reopen.
 *
 * <h3>Regex error UX</h3>
 * When {@code regexError} is set the input border flips to {@code --fail}
 * and a tiny inline label appears next to the toggle chip. We deliberately
 * keep falling back to literal search under the hood (see useLogSearch) so
 * the user still sees something while they fix the pattern.
 */
import { useEffect, useRef } from 'react'
import { ChevronDown, ChevronUp, X } from 'lucide-react'

interface Props {
  query: string
  onQueryChange: (q: string) => void
  useRegex: boolean
  onUseRegexChange: (v: boolean) => void
  matchCount: number
  currentIdx: number
  onNext: () => void
  onPrev: () => void
  onClose: () => void
  regexError: string | null
  /** Optional test-id prefix; defaults to "log-search". */
  testId?: string
}

export function LogSearchBar({
  query,
  onQueryChange,
  useRegex,
  onUseRegexChange,
  matchCount,
  currentIdx,
  onNext,
  onPrev,
  onClose,
  regexError,
  testId = 'log-search',
}: Props) {
  const inputRef = useRef<HTMLInputElement>(null)

  // Focus + select on mount so re-opening the bar always starts a fresh edit.
  useEffect(() => {
    inputRef.current?.focus()
    inputRef.current?.select()
  }, [])

  const counterText =
    matchCount === 0
      ? '0 of 0'
      : `${currentIdx + 1} of ${matchCount}`

  const disabled = matchCount === 0
  const hasRegexError = regexError !== null

  return (
    <div
      role="search"
      data-testid={testId}
      style={{
        position: 'absolute',
        top: 8,
        right: 24,
        zIndex: 5,
        display: 'flex',
        alignItems: 'center',
        gap: 8,
        padding: '6px 8px',
        background: 'var(--surface-2)',
        border: '1px solid var(--border)',
        borderRadius: 'var(--r-md)',
        boxShadow: 'var(--shadow-md)',
        fontFamily: 'var(--font-mono)',
        fontSize: 11.5,
      }}
      onKeyDown={(e) => {
        if (e.key === 'Escape') {
          e.preventDefault()
          e.stopPropagation()
          onClose()
        }
      }}
    >
      <input
        ref={inputRef}
        type="text"
        value={query}
        onChange={(e) => onQueryChange(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === 'Enter') {
            e.preventDefault()
            if (e.shiftKey) onPrev()
            else onNext()
          }
        }}
        placeholder={useRegex ? 'regex' : 'search'}
        aria-label="Search in log"
        aria-invalid={hasRegexError}
        data-testid={`${testId}-input`}
        style={{
          width: 220,
          padding: '4px 8px',
          background: 'var(--bg)',
          color: 'var(--fg)',
          border: `1px solid ${hasRegexError ? 'var(--fail)' : 'var(--border)'}`,
          borderRadius: 'var(--r-sm)',
          outline: 'none',
          fontFamily: 'var(--font-mono)',
          fontSize: 11.5,
        }}
      />

      <button
        type="button"
        onClick={() => onUseRegexChange(!useRegex)}
        aria-pressed={useRegex}
        aria-label="Toggle regex mode"
        data-testid={`${testId}-regex-toggle`}
        title={hasRegexError ? regexError ?? 'Invalid regex' : 'Toggle regex'}
        style={{
          padding: '3px 7px',
          borderRadius: 'var(--r-sm)',
          background: useRegex ? 'var(--accent-soft)' : 'transparent',
          color: useRegex ? 'var(--accent)' : 'var(--fg-muted)',
          border: `1px solid ${useRegex ? 'var(--accent)' : 'var(--border)'}`,
          fontFamily: 'var(--font-mono)',
          fontSize: 10,
          fontWeight: 600,
          cursor: 'pointer',
          letterSpacing: '0.02em',
        }}
      >
        .*
      </button>

      <span
        aria-live="polite"
        aria-atomic="true"
        data-testid={`${testId}-counter`}
        style={{
          minWidth: 64,
          textAlign: 'center',
          fontVariantNumeric: 'tabular-nums',
          color: disabled ? 'var(--fg-dim)' : 'var(--fg-muted)',
          whiteSpace: 'nowrap',
        }}
      >
        {counterText}
      </span>

      <button
        type="button"
        onClick={onPrev}
        disabled={disabled}
        aria-label="Previous match"
        data-testid={`${testId}-prev`}
        style={iconBtn(disabled)}
      >
        <ChevronUp size={13} aria-hidden />
      </button>
      <button
        type="button"
        onClick={onNext}
        disabled={disabled}
        aria-label="Next match"
        data-testid={`${testId}-next`}
        style={iconBtn(disabled)}
      >
        <ChevronDown size={13} aria-hidden />
      </button>
      <button
        type="button"
        onClick={onClose}
        aria-label="Close search"
        data-testid={`${testId}-close`}
        style={iconBtn(false)}
      >
        <X size={13} aria-hidden />
      </button>
    </div>
  )
}

function iconBtn(disabled: boolean): React.CSSProperties {
  return {
    padding: '3px 5px',
    borderRadius: 'var(--r-sm)',
    background: 'transparent',
    color: disabled ? 'var(--fg-faint)' : 'var(--fg-muted)',
    border: '1px solid var(--border)',
    cursor: disabled ? 'not-allowed' : 'pointer',
    display: 'inline-flex',
    alignItems: 'center',
    justifyContent: 'center',
  }
}
