/**
 * Adversarial tests for TestResultsPanel v3-stack restyle (#559).
 *
 * Pinned invariants for SREs landing on the Tests tab after a build dies:
 *   1. FAILED rows render BEFORE passed/skipped — the SRE wants the bad news
 *      first, not buried at row 47.
 *   2. Clicking a failed row expands the failure body verbatim in a term-style
 *      block; the body must NOT render for non-failed rows.
 *   3. The "Failed" filter chip narrows the table to just FAILED rows — passes
 *      and skips must be gone, not merely dimmed.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { TestResultsPanel } from '../components/TestResultsPanel'
import * as hooks from '../api/hooks'
import type { TestResultsPage, TestRowDto } from '../api/types'

function row(
  id: number,
  status: TestRowDto['status'],
  name: string,
  opts: Partial<TestRowDto> = {},
): TestRowDto {
  return {
    id,
    suite: 'demo.Suite',
    className: 'demo.SuiteClass',
    name,
    status,
    durationMs: 12,
    failureMessage: status === 'FAILED' ? `AssertionError: ${name} blew up` : null,
    ...opts,
  }
}

function mockTests(items: TestRowDto[]) {
  const summary = {
    passed: items.filter((r) => r.status === 'PASSED').length,
    failed: items.filter((r) => r.status === 'FAILED').length,
    skipped: items.filter((r) => r.status === 'SKIPPED').length,
  }
  const page: TestResultsPage = { items, total: items.length, summary }
  vi.spyOn(hooks, 'useTests').mockReturnValue({
    data: page,
    isLoading: false,
    isError: false,
    error: null,
    // We only consume these 4 keys in the component — additional react-query
    // surface isn't exercised.
  } as unknown as ReturnType<typeof hooks.useTests>)
}

function renderPanel() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <TestResultsPanel buildId={1} />
    </QueryClientProvider>,
  )
}

describe('TestResultsPanel v3 — restyle (#559)', () => {
  beforeEach(() => {})
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('sorts FAILED rows before PASSED/SKIPPED — the SRE-default reading order', () => {
    // Wire-order intentionally puts the failure LAST so the sort has work to do.
    mockTests([
      row(1, 'PASSED', 'p1'),
      row(2, 'SKIPPED', 's1'),
      row(3, 'FAILED', 'boom'),
      row(4, 'PASSED', 'p2'),
    ])
    renderPanel()

    const table = screen.getByTestId('tests-table')
    const rows = within(table).getAllByRole('row')
    // First row must be the FAILED one; subsequent rows must NOT include the
    // failure later (i.e. the failure isn't merely duplicated).
    expect(rows[0].closest('[data-status]')?.getAttribute('data-status')).toBe('FAILED')
    expect(rows[0].textContent).toContain('boom')
    // No FAILED row should appear after a non-FAILED row.
    const statuses = rows.map(
      (r) => r.closest('[data-status]')?.getAttribute('data-status'),
    )
    let sawNonFailed = false
    for (const s of statuses) {
      if (s !== 'FAILED') sawNonFailed = true
      else if (sawNonFailed) throw new Error('FAILED row appeared after a non-FAILED row')
    }
  })

  it('expands a failed row inline to show the failure body in a term-style block', () => {
    mockTests([row(7, 'FAILED', 'crashy', { failureMessage: 'NullPointerException at L42' })])
    renderPanel()

    // Body should not render until the row is clicked.
    expect(screen.queryByTestId('test-fail-body-7')).toBeNull()

    const headerRow = screen.getByTestId('test-row-7').querySelector('[role="row"]')!
    fireEvent.click(headerRow)

    const body = screen.getByTestId('test-fail-body-7')
    expect(body.textContent).toBe('NullPointerException at L42')
    // The body must visually echo Console: oklch(0.12 0 0) dark background.
    expect(body.getAttribute('style') ?? '').toMatch(/oklch\(0\.12 0 0\)/)
    // Aria correctness: toggle button reports expanded.
    const toggle = within(screen.getByTestId('test-row-7')).getByRole('button', {
      name: /collapse|expand/i,
    })
    expect(toggle.getAttribute('aria-expanded')).toBe('true')
  })

  it('"Failed" filter chip narrows to FAILED rows only (passed/skipped disappear)', () => {
    mockTests([
      row(1, 'PASSED', 'p1'),
      row(2, 'FAILED', 'fail-me'),
      row(3, 'SKIPPED', 's1'),
      row(4, 'PASSED', 'p2'),
    ])
    renderPanel()

    // Pre-state: all 4 rows visible.
    expect(screen.getByTestId('test-row-1')).toBeInTheDocument()
    expect(screen.getByTestId('test-row-2')).toBeInTheDocument()
    expect(screen.getByTestId('test-row-3')).toBeInTheDocument()
    expect(screen.getByTestId('test-row-4')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: /^Failed/i, pressed: false }))

    // Only the FAILED row survives.
    expect(screen.getByTestId('test-row-2')).toBeInTheDocument()
    expect(screen.queryByTestId('test-row-1')).toBeNull()
    expect(screen.queryByTestId('test-row-3')).toBeNull()
    expect(screen.queryByTestId('test-row-4')).toBeNull()
  })

  it('renders the FAIL/PASS/SKIP summary header in mono with named counts', () => {
    mockTests([row(1, 'FAILED', 'a'), row(2, 'PASSED', 'b'), row(3, 'SKIPPED', 'c')])
    renderPanel()
    const summary = screen.getByTestId('tests-summary')
    expect(summary.textContent).toMatch(/1 failed/)
    expect(summary.textContent).toMatch(/1 passed/)
    expect(summary.textContent).toMatch(/1 skipped/)
  })

  it('shows the v3 empty-state copy when no test_result rows exist', () => {
    mockTests([])
    renderPanel()
    const empty = screen.getByTestId('tests-empty')
    expect(empty.textContent).toBe('No test results recorded for this build.')
  })
})
