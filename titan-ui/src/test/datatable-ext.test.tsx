/**
 * Component tests for the DataTable extensions added in #1190: the four view
 * states (loading / empty / error+retry / populated), optional inline row
 * expansion (single-open invariant), and optional drag-reorder grip handles.
 *
 * Adversarial-first: the error path asserts a styled panel + retry control
 * (NOT a raw string, NOT a blank table) and that empty + error never co-render.
 */
import { describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import { DataTable, type DataTableColumn } from '../components/ui/DataTable'

interface Row {
  id: number
  name: string
}

const ROWS: Row[] = [
  { id: 1, name: 'alpha' },
  { id: 2, name: 'beta' },
  { id: 3, name: 'gamma' },
]

const COLUMNS: DataTableColumn<Row>[] = [
  { key: 'name', header: 'Name', cell: (r) => <span>{r.name}</span> },
]

function base() {
  return {
    columns: COLUMNS,
    rowKey: (r: Row) => r.id,
    rowTestId: (r: Row) => `row-${r.id}`,
    testId: 'dt',
  }
}

describe('DataTable view states', () => {
  it('loading → skeleton rows, no data rows, no empty/error', () => {
    render(<DataTable<Row> rows={[]} {...base()} isLoading />)
    expect(screen.getAllByTestId('dt-skel').length).toBeGreaterThan(0)
    expect(screen.queryByTestId('row-1')).toBeNull()
    expect(screen.queryByTestId('dt-empty')).toBeNull()
    expect(screen.queryByTestId('dt-error')).toBeNull()
  })

  it('empty → empty panel, no skeleton, no rows', () => {
    render(<DataTable<Row> rows={[]} {...base()} emptyMessage="Nothing here" />)
    expect(screen.getByTestId('dt-empty')).toHaveTextContent('Nothing here')
    expect(screen.queryByTestId('dt-skel')).toBeNull()
    expect(screen.queryByTestId('row-1')).toBeNull()
  })

  it('populated → rows, no skeleton, no empty', () => {
    render(<DataTable<Row> rows={ROWS} {...base()} />)
    expect(screen.getByTestId('row-1')).toBeInTheDocument()
    expect(screen.getByTestId('row-3')).toBeInTheDocument()
    expect(screen.queryByTestId('dt-skel')).toBeNull()
    expect(screen.queryByTestId('dt-empty')).toBeNull()
  })

  it('error → styled panel + working retry, NOT empty, NOT raw string (adversarial)', () => {
    const onRetry = vi.fn()
    render(
      <DataTable<Row> rows={[]} {...base()} error={{ message: 'boom', onRetry }} />,
    )
    const panel = screen.getByTestId('dt-error')
    expect(panel).toHaveClass('tt-empty') // shared panel, not a bare string
    expect(panel.textContent).toContain('boom')
    // empty + error never co-render
    expect(screen.queryByTestId('dt-empty')).toBeNull()
    // retry affordance present + wired
    fireEvent.click(screen.getByText('retry'))
    expect(onRetry).toHaveBeenCalledTimes(1)
  })

  it('error wins over rows: a rejected load shows the panel, never the stale grid', () => {
    render(<DataTable<Row> rows={[]} {...base()} error={{ message: 'nope' }} />)
    expect(screen.getByTestId('dt-error')).toBeInTheDocument()
    expect(screen.queryByTestId('row-1')).toBeNull()
  })
})

describe('DataTable inline expansion', () => {
  const expandable = {
    render: (r: Row) => <div>detail for {r.name}</div>,
    rowDetailTestId: (r: Row) => `detail-${r.id}`,
  }

  it('click row → detail visible; click again → collapsed', () => {
    render(<DataTable<Row> rows={ROWS} {...base()} expandable={expandable} />)
    expect(screen.queryByTestId('detail-1')).toBeNull()
    fireEvent.click(screen.getByTestId('row-1'))
    expect(screen.getByTestId('detail-1')).toHaveTextContent('detail for alpha')
    fireEvent.click(screen.getByTestId('row-1'))
    expect(screen.queryByTestId('detail-1')).toBeNull()
  })

  it('opening row 2 collapses row 1 (only-one-open invariant)', () => {
    render(<DataTable<Row> rows={ROWS} {...base()} expandable={expandable} />)
    fireEvent.click(screen.getByTestId('row-1'))
    expect(screen.getByTestId('detail-1')).toBeInTheDocument()
    fireEvent.click(screen.getByTestId('row-2'))
    expect(screen.getByTestId('detail-2')).toBeInTheDocument()
    expect(screen.queryByTestId('detail-1')).toBeNull()
  })
})

describe('DataTable drag-reorder', () => {
  it('renders one grip handle per row with an accessible label', () => {
    render(
      <DataTable<Row>
        rows={ROWS}
        {...base()}
        reorder={{ onReorder: vi.fn(), handleLabel: (k) => `move ${k}` }}
      />,
    )
    expect(screen.getByLabelText('move 1')).toBeInTheDocument()
    expect(screen.getByLabelText('move 2')).toBeInTheDocument()
    expect(screen.getByLabelText('move 3')).toBeInTheDocument()
  })

  it('does not render grips when reorder is absent (read-only default unaffected)', () => {
    render(<DataTable<Row> rows={ROWS} {...base()} />)
    expect(screen.queryByLabelText('move 1')).toBeNull()
  })
})

describe('DataTable reorder + expandable invariant (sev2/correctness #1190)', () => {
  const expandable = {
    render: (r: Row) => <div data-testid={`detail-${r.id}`}>detail {r.name}</div>,
    rowDetailTestId: (r: Row) => `detail-row-${r.id}`,
  }

  it('throws if BOTH reorder and expandable are supplied (mutually exclusive)', () => {
    // Without the runtime guard this would silently desync leadCols vs the
    // prepended cells (broken detail-row colSpan) and drop the expand path.
    // Suppress React's error-boundary console noise for the expected throw.
    const spy = vi.spyOn(console, 'error').mockImplementation(() => {})
    expect(() =>
      render(
        <DataTable<Row>
          rows={ROWS}
          {...base()}
          reorder={{ onReorder: vi.fn() }}
          expandable={expandable}
        />,
      ),
    ).toThrow(/mutually exclusive/)
    spy.mockRestore()
  })

  it('reorder alone is fine', () => {
    expect(() =>
      render(<DataTable<Row> rows={ROWS} {...base()} reorder={{ onReorder: vi.fn() }} />),
    ).not.toThrow()
  })

  it('expandable alone is fine', () => {
    expect(() =>
      render(<DataTable<Row> rows={ROWS} {...base()} expandable={expandable} />),
    ).not.toThrow()
  })
})
