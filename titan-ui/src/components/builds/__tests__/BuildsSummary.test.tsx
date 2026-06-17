/**
 * Render + adversarial test for BuildsSummary (#1187).
 *
 * The summary strip is the at-a-glance pass / fail / running row that #1187
 * pulled out of the tab labels. The load-bearing invariant (inherited from
 * #1071's BuildsStatsStrip guard): a Failed count of 0 must render NEUTRAL —
 * a clean failure column is GOOD news, never a red alert — and only goes
 * destructive when failed > 0.
 */
import { describe, it, expect } from 'vitest'
import { render, screen, cleanup } from '@testing-library/react'
import { afterEach } from 'vitest'
import { BuildsSummary } from '../BuildsSummary'

afterEach(() => cleanup())

describe('BuildsSummary', () => {
  it('renders passing / failed / running counts from props', () => {
    render(
      <BuildsSummary
        isLoading={false}
        counts={{ passing: 41, failed: 3, running: 2 }}
      />,
    )
    expect(screen.getByTestId('builds-summary-passing-value').textContent).toBe('41')
    expect(screen.getByTestId('builds-summary-failed-value').textContent).toBe('3')
    expect(screen.getByTestId('builds-summary-running-value').textContent).toBe('2')
  })

  it('marks the failed value destructive when > 0', () => {
    render(<BuildsSummary isLoading={false} counts={{ passing: 0, failed: 7, running: 0 }} />)
    expect(screen.getByTestId('builds-summary-failed-value').className).toContain(
      'builds-summary-value-danger',
    )
  })

  it('does NOT mark the failed value destructive when 0 (zero is good news)', () => {
    render(<BuildsSummary isLoading={false} counts={{ passing: 5, failed: 0, running: 0 }} />)
    const cell = screen.getByTestId('builds-summary-failed-value')
    expect(cell.textContent).toBe('0')
    expect(cell.className).not.toContain('builds-summary-value-danger')
  })

  it('renders a skeleton (not a misleading 0) while loading', () => {
    render(<BuildsSummary isLoading={true} counts={{}} />)
    // No resolved value cells while loading…
    expect(screen.queryByTestId('builds-summary-passing-value')).toBeNull()
    // …but the tiles + a skeleton placeholder are present.
    expect(screen.getByTestId('builds-summary-passing')).toBeInTheDocument()
    expect(screen.getAllByTestId('skeleton').length).toBeGreaterThan(0)
  })

  it('renders a skeleton for an unresolved count even when not loading', () => {
    // running count still in flight (undefined) — must not render as "0".
    render(<BuildsSummary isLoading={false} counts={{ passing: 1, failed: 0 }} />)
    expect(screen.queryByTestId('builds-summary-running-value')).toBeNull()
    expect(screen.getByTestId('builds-summary-running')).toBeInTheDocument()
  })

  it('renders a neutral dash (NOT an infinite skeleton) when a count query errored', () => {
    // failed-count query died (isError) and never resolved (value undefined).
    // The tile must NOT sit on a skeleton forever — it shows a terminal dash.
    render(
      <BuildsSummary
        isLoading={false}
        counts={{ passing: 5, running: 1 }}
        errors={{ failed: true }}
      />,
    )
    const dash = screen.getByTestId('builds-summary-failed-error')
    expect(dash.textContent).toBe('—')
    // No misleading 0, no value cell, and crucially no skeleton in this tile.
    expect(screen.queryByTestId('builds-summary-failed-value')).toBeNull()
    const failedTile = screen.getByTestId('builds-summary-failed')
    expect(failedTile.querySelector('[data-testid="skeleton"]')).toBeNull()
    // The dash is neutral, never the destructive token.
    expect(dash.className).not.toContain('builds-summary-value-danger')
  })

  it('prefers a resolved value over the error dash if the count did arrive', () => {
    // isError can be stale after a successful retry — a present value wins.
    render(
      <BuildsSummary
        isLoading={false}
        counts={{ passing: 5, failed: 2, running: 1 }}
        errors={{ failed: true }}
      />,
    )
    expect(screen.getByTestId('builds-summary-failed-value').textContent).toBe('2')
    expect(screen.queryByTestId('builds-summary-failed-error')).toBeNull()
  })
})
