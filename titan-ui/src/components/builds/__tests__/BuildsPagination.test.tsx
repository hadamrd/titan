/**
 * Render smoke-test for BuildsPagination (#1071).
 *
 * Pinned behaviour:
 *  - Renders "{shown} of {total} shown" footer always.
 *  - Prev/next controls hidden when total fits in one page (preserves the
 *    pre-#1071 behaviour where the surface had no pagination at all).
 *  - Prev disabled on page 0; next disabled on the last page.
 *  - Click fires onPageChange with the correct delta (page±1).
 */
import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { BuildsPagination } from '../BuildsPagination'

describe('BuildsPagination', () => {
  it('renders the shown/total footer', () => {
    render(
      <BuildsPagination
        shown={7}
        total={42}
        page={0}
        pageSize={100}
        onPageChange={() => {}}
      />,
    )
    expect(screen.getByTestId('builds-count-footer').textContent).toMatch(/7 of 42 shown/)
  })

  it('omits the prev/next controls when the whole set fits in one page', () => {
    render(
      <BuildsPagination
        shown={5}
        total={5}
        page={0}
        pageSize={100}
        onPageChange={() => {}}
      />,
    )
    expect(screen.queryByTestId('builds-page-prev')).toBeNull()
    expect(screen.queryByTestId('builds-page-next')).toBeNull()
  })

  it('disables "Previous" on page 0', () => {
    render(
      <BuildsPagination
        shown={100}
        total={250}
        page={0}
        pageSize={100}
        onPageChange={() => {}}
      />,
    )
    expect((screen.getByTestId('builds-page-prev') as HTMLButtonElement).disabled).toBe(true)
    expect((screen.getByTestId('builds-page-next') as HTMLButtonElement).disabled).toBe(false)
  })

  it('disables "Next" on the last page', () => {
    // total=250, pageSize=100 → 3 pages (indices 0..2). page=2 is last.
    render(
      <BuildsPagination
        shown={50}
        total={250}
        page={2}
        pageSize={100}
        onPageChange={() => {}}
      />,
    )
    expect((screen.getByTestId('builds-page-prev') as HTMLButtonElement).disabled).toBe(false)
    expect((screen.getByTestId('builds-page-next') as HTMLButtonElement).disabled).toBe(true)
  })

  it('fires onPageChange(page+1) when Next is clicked', () => {
    const onPageChange = vi.fn()
    render(
      <BuildsPagination
        shown={100}
        total={300}
        page={1}
        pageSize={100}
        onPageChange={onPageChange}
      />,
    )
    fireEvent.click(screen.getByTestId('builds-page-next'))
    expect(onPageChange).toHaveBeenCalledWith(2)
  })

  it('fires onPageChange(page-1) when Previous is clicked', () => {
    const onPageChange = vi.fn()
    render(
      <BuildsPagination
        shown={100}
        total={300}
        page={2}
        pageSize={100}
        onPageChange={onPageChange}
      />,
    )
    fireEvent.click(screen.getByTestId('builds-page-prev'))
    expect(onPageChange).toHaveBeenCalledWith(1)
  })
})
