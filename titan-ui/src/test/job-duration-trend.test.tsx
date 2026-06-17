/**
 * Render tests for the /pipelines duration-trend sparkline (issue #1096).
 *
 * The component derives its trend from the page-level bulk recent-builds list
 * (the `builds` prop — same source as the status sparkline, no per-row fetch),
 * so these tests pass `BuildDto[]` directly. Covers the states an operator sees
 * while scanning the fleet:
 *   1. mixed pass/fail history → an SVG line renders, coloured by trend.
 *   2. degrading job → the line is the red (--fail) token, not green.
 *   3. RUNNING / null-duration builds are filtered out of the trend.
 *   4. < 2 finished builds → the muted em-dash empty state, NOT a flat stub.
 */
import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { JobDurationTrend } from '../components/JobDurationTrend'
import type { BuildDto } from '../api/types'

let nextId = 1

/** Minimal BuildDto for the two fields the component reads (durationMs, status). */
function build(durationS: number | null, status: string): BuildDto {
  return {
    id: nextId++,
    jobId: 1,
    buildNumber: nextId,
    status,
    durationMs: durationS === null ? null : durationS * 1000,
  } as unknown as BuildDto
}

/** The bulk API returns builds newest-first; helper takes oldest→newest for readability. */
function newestFirst(...oldestToNewest: BuildDto[]): BuildDto[] {
  return [...oldestToNewest].reverse()
}

function renderTrend(jobId: number, builds: BuildDto[]) {
  return render(<JobDurationTrend jobId={jobId} builds={builds} />)
}

describe('JobDurationTrend (issue #1096)', () => {
  it('renders an SVG line for a job with mixed pass/fail history', () => {
    // oldest→newest durations 10,9,4,2 — the newer half is faster → improving.
    renderTrend(
      1,
      newestFirst(
        build(10, 'SUCCESS'),
        build(9, 'FAILED'),
        build(4, 'SUCCESS'),
        build(2, 'SUCCESS'),
      ),
    )

    const el = screen.getByTestId('job-row-1-duration-trend')
    expect(el.getAttribute('data-trend')).toBe('improving')
    const path = el.querySelector('svg path')
    expect(path).not.toBeNull()
    expect(path?.getAttribute('stroke')).toBe('var(--ok)')
  })

  it('colours a degrading job red, not green', () => {
    renderTrend(
      2,
      newestFirst(
        build(2, 'SUCCESS'),
        build(3, 'SUCCESS'),
        build(10, 'FAILED'),
        build(11, 'FAILED'),
      ),
    )

    const el = screen.getByTestId('job-row-2-duration-trend')
    expect(el.getAttribute('data-trend')).toBe('degrading')
    expect(el.querySelector('svg path')?.getAttribute('stroke')).toBe('var(--fail)')
  })

  it('ignores RUNNING / null-duration builds when computing the trend', () => {
    // A RUNNING build (no settled duration) and a QUEUED one must not count;
    // the trend is computed from the two finished builds only → degrading.
    renderTrend(
      3,
      newestFirst(
        build(2, 'SUCCESS'),
        build(null, 'RUNNING'),
        build(8, 'FAILED'),
        build(null, 'QUEUED'),
      ),
    )

    const el = screen.getByTestId('job-row-3-duration-trend')
    expect(el.getAttribute('data-trend')).toBe('degrading')
  })

  it('shows the muted em-dash when a job has fewer than 2 finished builds', () => {
    renderTrend(42, [build(5, 'SUCCESS'), build(null, 'RUNNING')])

    const empty = screen.getByTestId('job-row-42-duration-trend-empty')
    expect(empty.textContent).toBe('—')
  })

  it('shows the em-dash (not a crash) for a job with no builds', () => {
    renderTrend(7, [])

    const empty = screen.getByTestId('job-row-7-duration-trend-empty')
    expect(empty.textContent).toBe('—')
  })

  it('shows the em-dash (not a crash) when builds are still loading (undefined)', () => {
    render(<JobDurationTrend jobId={9} />)

    const empty = screen.getByTestId('job-row-9-duration-trend-empty')
    expect(empty.textContent).toBe('—')
  })
})
