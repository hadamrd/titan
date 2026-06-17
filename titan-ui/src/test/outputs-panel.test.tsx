/**
 * Adversarial tests for OutputsPanel (#782).
 *
 * Pinned invariants:
 *   1. Empty / missing outputs render the muted "(no outputs)" placeholder
 *      and NO table chrome — a step that published nothing must not look
 *      like a step with broken telemetry.
 *   2. N outputs render exactly N rows — no header row, no off-by-one.
 *   3. A value containing newlines renders inside a <pre> with pre-wrap
 *      whitespace, so a 50-line PEM is scrollable not truncated.
 *   4. Copy button calls navigator.clipboard.writeText with the exact value
 *      (not the key, not a JSON wrapper) — the SRE pastes the literal string.
 *   5. Copy label flips to "Copied" after a successful write — a silent
 *      clipboard failure must not look like a silent clipboard success.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { OutputsPanel } from '../components/OutputsPanel'

describe('OutputsPanel', () => {
  beforeEach(() => {
    Object.assign(navigator, {
      clipboard: { writeText: vi.fn(() => Promise.resolve()) },
    })
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('renders the empty placeholder when outputs is undefined', () => {
    render(<OutputsPanel outputs={undefined} />)
    expect(screen.getByTestId('outputs-empty')).toHaveTextContent('(no outputs)')
    expect(screen.queryByTestId('outputs-table')).toBeNull()
  })

  it('renders the empty placeholder when outputs is an empty object', () => {
    render(<OutputsPanel outputs={{}} />)
    expect(screen.getByTestId('outputs-empty')).toBeInTheDocument()
    expect(screen.queryByTestId('outputs-table')).toBeNull()
  })

  it('renders one row per output entry — five entries → five rows', () => {
    const outputs = {
      image: 'ghcr.io/acme/api:v1.2.3',
      digest: 'sha256:deadbeef',
      tag: 'v1.2.3',
      registry: 'ghcr.io',
      pushed: 'true',
    }
    render(<OutputsPanel outputs={outputs} />)
    const table = screen.getByTestId('outputs-table')
    // tbody rows only — no header row.
    const rows = table.querySelectorAll('tbody tr')
    expect(rows.length).toBe(5)
    for (const k of Object.keys(outputs)) {
      expect(screen.getByTestId(`outputs-row-${k}`)).toBeInTheDocument()
    }
  })

  it('renders a multi-line value inside a <pre> with pre-wrap whitespace', () => {
    const pem = '-----BEGIN-----\nLINE1\nLINE2\n-----END-----'
    render(<OutputsPanel outputs={{ cert: pem }} />)
    const cell = screen.getByTestId('outputs-value-cert')
    expect(cell.tagName).toBe('PRE')
    // pre-wrap so the value wraps inside its column instead of overflowing
    // horizontally and pushing the copy button off-screen.
    expect(cell).toHaveStyle({ whiteSpace: 'pre-wrap' })
    expect(cell.textContent).toBe(pem)
  })

  it('renders a single-line value inline (not as a <pre>) so the row stays compact', () => {
    render(<OutputsPanel outputs={{ image: 'ghcr.io/acme/api:v1' }} />)
    const cell = screen.getByTestId('outputs-value-image')
    expect(cell.tagName).toBe('SPAN')
  })

  it('copy button calls navigator.clipboard.writeText with the literal value', async () => {
    const writeText = navigator.clipboard.writeText as ReturnType<typeof vi.fn>
    render(<OutputsPanel outputs={{ image: 'ghcr.io/acme/api:v1.2.3' }} />)
    fireEvent.click(screen.getByTestId('outputs-copy-image'))
    expect(writeText).toHaveBeenCalledTimes(1)
    expect(writeText).toHaveBeenCalledWith('ghcr.io/acme/api:v1.2.3')
    // Label flips to "Copied" after the resolved promise.
    await waitFor(() =>
      expect(screen.getByTestId('outputs-copy-image')).toHaveTextContent('Copied'),
    )
  })

  it('copy failure is a silent no-op — label stays as "Copy"', async () => {
    Object.assign(navigator, {
      clipboard: { writeText: vi.fn(() => Promise.reject(new Error('denied'))) },
    })
    render(<OutputsPanel outputs={{ image: 'x' }} />)
    fireEvent.click(screen.getByTestId('outputs-copy-image'))
    // Microtask drain — label must not have flipped.
    await Promise.resolve()
    await Promise.resolve()
    expect(screen.getByTestId('outputs-copy-image')).toHaveTextContent('Copy')
  })
})
