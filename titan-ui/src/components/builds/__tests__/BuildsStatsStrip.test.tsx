/**
 * Render smoke-test for BuildsStatsStrip (#1071).
 *
 * Adversarial guard: a Failed count of 0 must NOT render with the
 * destructive style — a clean failure column is GOOD news.
 */
import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { BuildsStatsStrip } from '../BuildsStatsStrip'

describe('BuildsStatsStrip', () => {
  it('renders running / failed / all counts from props', () => {
    render(
      <BuildsStatsStrip
        activeTab="all"
        counts={{ all: 42, running: 3, failed: 5 }}
        showMine={false}
        onTabChange={() => {}}
      />,
    )
    expect(screen.getByTestId('filter-tab-all-count').textContent).toBe('42')
    expect(screen.getByTestId('filter-tab-running-count').textContent).toBe('3')
    expect(screen.getByTestId('filter-tab-failed-count').textContent).toBe('5')
  })

  it('marks the failed count destructive when > 0', () => {
    render(
      <BuildsStatsStrip
        activeTab="all"
        counts={{ failed: 7 }}
        showMine={false}
        onTabChange={() => {}}
      />,
    )
    expect(screen.getByTestId('filter-tab-failed-count').className).toContain(
      'cl-tab-count-danger',
    )
  })

  it('does NOT mark the failed count destructive when 0 (zero is good news)', () => {
    render(
      <BuildsStatsStrip
        activeTab="all"
        counts={{ failed: 0 }}
        showMine={false}
        onTabChange={() => {}}
      />,
    )
    const cell = screen.getByTestId('filter-tab-failed-count')
    expect(cell.className).not.toContain('cl-tab-count-danger')
  })

  it('hides the Mine tab when showMine=false', () => {
    render(
      <BuildsStatsStrip
        activeTab="all"
        counts={{}}
        showMine={false}
        onTabChange={() => {}}
      />,
    )
    expect(screen.queryByTestId('filter-tab-mine')).toBeNull()
  })

  it('fires onTabChange with the tab key when clicked', () => {
    const onTabChange = vi.fn()
    render(
      <BuildsStatsStrip
        activeTab="all"
        counts={{ running: 1 }}
        showMine={false}
        onTabChange={onTabChange}
      />,
    )
    fireEvent.click(screen.getByTestId('filter-tab-running'))
    expect(onTabChange).toHaveBeenCalledWith('running')
  })
})
