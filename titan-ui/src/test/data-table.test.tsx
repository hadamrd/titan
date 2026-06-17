/**
 * Adversarial tests for the DataTable primitive (#850 + design 64).
 *
 * Every assertion ties back to a checklist item in the dispatched brief
 * (which mirrors docs/design/64-titan-tables.md). Each `it(...)` names which
 * differentiator it guards so a future regression points at the right doc
 * section.
 */
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import {
  createMemoryHistory,
  createRootRoute,
  createRoute,
  createRouter,
  Outlet,
  RouterProvider,
} from '@tanstack/react-router'

import { DataTable, type DataTableColumn } from '../components/ui/DataTable'

interface Row {
  id: number
  status: 'RUNNING' | 'SUCCESS' | 'FAILED'
  name: string
}

const ROWS: Row[] = [
  { id: 1, status: 'RUNNING', name: 'alpha' },
  { id: 2, status: 'FAILED', name: 'bravo' },
  { id: 3, status: 'SUCCESS', name: 'charlie' },
]

const COLUMNS: DataTableColumn<Row>[] = [
  { key: 'id', header: '#', cell: (r) => r.id, numeric: true, sortable: true, width: '60px' },
  { key: 'status', header: 'Status', cell: (r) => r.status, sortable: true },
  { key: 'name', header: 'Name', cell: (r) => r.name, sortable: true },
]

function mountAt(
  search: Record<string, unknown>,
  ui: (search: Record<string, unknown>) => React.ReactNode,
) {
  // Seed window.location with the query string — DataTable's URL state
  // reads/writes via window.history.pushState, not the memory router.
  const qs = new URLSearchParams(search as Record<string, string>).toString()
  window.history.replaceState({}, '', `/t${qs ? `?${qs}` : ''}`)
  const root = createRootRoute({ component: () => <Outlet /> })
  const page = createRoute({
    getParentRoute: () => root,
    path: '/t',
    component: () => <>{ui(search)}</>,
    validateSearch: (raw: Record<string, unknown>) => raw,
  })
  const history = createMemoryHistory({ initialEntries: [`/t${qs ? `?${qs}` : ''}`] })
  const router = createRouter({ routeTree: root.addChildren([page]), history })
  return { router, history, view: render(<RouterProvider router={router} />) }
}

afterEach(() => {
  cleanup()
  window.history.replaceState({}, '', '/')
})

describe('DataTable — design 64 differentiators', () => {
  it('renders one tr per data row with the correct row count (n=3)', async () => {
    mountAt({}, () => (
      <DataTable rows={ROWS} columns={COLUMNS} rowKey={(r) => r.id} testId="dt" />
    ))
    await waitFor(() => {
      expect(screen.getAllByRole('row')).toHaveLength(1 + 3) // header + body
    })
  })

  it('row hover signals via box-shadow inset (left-edge accent), never a bg fill', async () => {
    mountAt({}, () => (
      <DataTable
        rows={ROWS}
        columns={COLUMNS}
        rowKey={(r) => r.id}
        onRowClick={() => undefined}
        testId="dt"
      />
    ))
    await waitFor(() => screen.getAllByRole('row'))
    const rows = screen.getAllByRole('row').slice(1)
    // Hover affordance is opt-in via data-clickable=true; the structural
    // contract (rather than computed style) is what we assert — the CSS
    // selector `.tt tbody tr[data-clickable='true']:hover` is the only
    // place a hover affordance is applied, and it uses box-shadow inset,
    // not background.
    for (const r of rows) {
      expect(r.getAttribute('data-clickable')).toBe('true')
      // belt-and-suspenders: no inline background-color set
      expect((r as HTMLElement).style.backgroundColor).toBe('')
    }
  })

  it('in-flight rows carry data-inflight=true; terminal rows do not (status is structure)', async () => {
    mountAt({}, () => (
      <DataTable
        rows={ROWS}
        columns={COLUMNS}
        rowKey={(r) => r.id}
        isInflight={(r) => r.status === 'RUNNING'}
        isFailed={(r) => r.status === 'FAILED'}
        testId="dt"
      />
    ))
    await waitFor(() => screen.getAllByRole('row'))
    const rows = screen.getAllByRole('row').slice(1)
    expect(rows[0].getAttribute('data-inflight')).toBe('true')
    expect(rows[0].getAttribute('data-failed')).toBe('false')
    expect(rows[1].getAttribute('data-failed')).toBe('true')
    expect(rows[1].getAttribute('data-inflight')).toBe('false')
    expect(rows[2].getAttribute('data-inflight')).toBe('false')
    expect(rows[2].getAttribute('data-failed')).toBe('false')
  })

  it('header is editorial (uppercase via CSS) and uses a single ↑/↓ glyph in accent — no chevron icon', async () => {
    mountAt({}, () => (
      <DataTable rows={ROWS} columns={COLUMNS} rowKey={(r) => r.id} testId="dt" />
    ))
    await waitFor(() => screen.getAllByRole('row'))
    const headers = screen.getAllByRole('columnheader')
    // All three are sortable → `data-sortable=true`.
    for (const h of headers) expect(h.getAttribute('data-sortable')).toBe('true')
    // Click the # header → sort glyph appears, single character.
    fireEvent.click(headers[0])
    await waitFor(() => {
      expect(headers[0].querySelector('.tt-sort')?.textContent).toBe('↓')
    })
    // No svg or chevron icon snuck in.
    expect(headers[0].querySelector('svg')).toBeNull()
  })

  it('toggles sort dir desc → asc → cleared on repeat clicks', async () => {
    mountAt({}, () => (
      <DataTable rows={ROWS} columns={COLUMNS} rowKey={(r) => r.id} testId="dt" />
    ))
    await waitFor(() => screen.getAllByRole('columnheader'))
    const hdr = screen.getAllByRole('columnheader')[0]
    fireEvent.click(hdr)
    expect(hdr.querySelector('.tt-sort')?.textContent).toBe('↓')
    fireEvent.click(hdr)
    expect(hdr.querySelector('.tt-sort')?.textContent).toBe('↑')
    fireEvent.click(hdr)
    expect(hdr.querySelector('.tt-sort')).toBeNull()
  })

  it('URL state — routeSearchKey writes ?<key>_sort=col:dir on header click', async () => {
    mountAt({}, () => (
      <DataTable
        rows={ROWS}
        columns={COLUMNS}
        rowKey={(r) => r.id}
        routeSearchKey="builds"
        testId="dt"
      />
    ))
    await waitFor(() => screen.getAllByRole('columnheader'))
    fireEvent.click(screen.getAllByRole('columnheader')[1]) // Status
    await waitFor(() => {
      expect(window.location.search).toContain('builds_sort=status%3Adesc')
    })
  })

  it('URL state — initial sort is read from ?<key>_sort and rendered as the active glyph', async () => {
    mountAt({ builds_sort: 'name:asc' }, () => (
      <DataTable
        rows={ROWS}
        columns={COLUMNS}
        rowKey={(r) => r.id}
        routeSearchKey="builds"
        testId="dt"
      />
    ))
    await waitFor(() => screen.getAllByRole('columnheader'))
    const headers = screen.getAllByRole('columnheader')
    // 3rd column is `name`.
    expect(headers[2].querySelector('.tt-sort')?.textContent).toBe('↑')
    expect(headers[0].querySelector('.tt-sort')).toBeNull()
  })

  it('pager — cursor-style "↑ newer" / "older ↓" buttons; never "Page X of Y"', async () => {
    const onOlder = vi.fn()
    const onNewer = vi.fn()
    mountAt({}, () => (
      <DataTable
        rows={ROWS}
        columns={COLUMNS}
        rowKey={(r) => r.id}
        total={312}
        onNewer={onNewer}
        onOlder={onOlder}
        hasNewer
        hasOlder
        testId="dt"
      />
    ))
    const pager = await screen.findByTestId('dt-pager')
    expect(pager.textContent).toContain('showing 1–3 of 312')
    expect(pager.textContent).toContain('↑ newer')
    expect(pager.textContent).toContain('older ↓')
    // Critical: the forbidden literal must never appear.
    expect(pager.textContent).not.toMatch(/Page\s+\d+\s+of\s+\d+/i)
    expect(pager.textContent).not.toMatch(/Page size/i)
    fireEvent.click(screen.getByTestId('dt-older'))
    fireEvent.click(screen.getByTestId('dt-newer'))
    expect(onOlder).toHaveBeenCalledTimes(1)
    expect(onNewer).toHaveBeenCalledTimes(1)
  })

  it('pager — hasNewer=false disables the newer button (no false affordance)', async () => {
    mountAt({}, () => (
      <DataTable
        rows={ROWS}
        columns={COLUMNS}
        rowKey={(r) => r.id}
        total={3}
        onNewer={() => undefined}
        onOlder={() => undefined}
        hasNewer={false}
        hasOlder={false}
        testId="dt"
      />
    ))
    await screen.findByTestId('dt-pager')
    expect(screen.getByTestId('dt-newer')).toBeDisabled()
    expect(screen.getByTestId('dt-older')).toBeDisabled()
  })

  it('jump-to inputs — Enter fires the matching handler with the trimmed raw', async () => {
    const number = vi.fn()
    const sha = vi.fn()
    mountAt({}, () => (
      <DataTable
        rows={ROWS}
        columns={COLUMNS}
        rowKey={(r) => r.id}
        onNewer={() => undefined}
        onOlder={() => undefined}
        hasNewer
        hasOlder
        jumpTo={{ number, sha }}
        testId="dt"
      />
    ))
    const numInput = await screen.findByLabelText('jump to build number')
    fireEvent.change(numInput, { target: { value: ' 42 ' } })
    fireEvent.keyDown(numInput, { key: 'Enter' })
    expect(number).toHaveBeenCalledWith('42')
    const shaInput = screen.getByLabelText('jump to commit sha')
    fireEvent.change(shaInput, { target: { value: '7f32a54' } })
    fireEvent.keyDown(shaInput, { key: 'Enter' })
    expect(sha).toHaveBeenCalledWith('7f32a54')
  })

  it('empty state — hairline rule + muted line + optional inline CTA; no illustration', async () => {
    mountAt({}, () => (
      <DataTable
        rows={[] as Row[]}
        columns={COLUMNS}
        rowKey={(r) => r.id}
        emptyMessage="No builds yet — trigger one from the ↗ Jobs page"
        emptyCta={{ label: 'Go to Jobs', to: '/jobs' }}
        testId="dt"
      />
    ))
    const empty = await screen.findByTestId('dt-empty')
    expect(empty.textContent).toContain('No builds yet')
    expect(empty.querySelector('a')?.getAttribute('href')).toBe('/jobs')
    // Hairline-only — the .tt-empty rule uses border-top/border-bottom hairline,
    // never a card or padded hero. Structurally: no svg, no illustration.
    expect(empty.querySelector('svg')).toBeNull()
    expect(empty.querySelector('img')).toBeNull()
  })

  it('error state — table renders + `━━━ failed to load…` hairline-prefix line, no banner/modal', async () => {
    const onRetry = vi.fn()
    mountAt({}, () => (
      <DataTable
        rows={[] as Row[]}
        columns={COLUMNS}
        rowKey={(r) => r.id}
        error={{ message: 'connection refused', onRetry }}
        testId="dt"
      />
    ))
    const err = await screen.findByTestId('dt-error')
    expect(err.textContent).toContain('━━━ failed to load — connection refused')
    // Hairline prefix is a span, never an `alert` role.
    expect(err.querySelector('.tt-err-prefix')).not.toBeNull()
    // Retry is an inline link, never a primary button.
    const retry = err.querySelector('a')
    expect(retry?.textContent).toBe('retry')
    fireEvent.click(retry!)
    expect(onRetry).toHaveBeenCalledTimes(1)
  })

  it('loading state — skeleton rows render with .tt-skel-row (titan-breathe), not block shimmer', async () => {
    mountAt({}, () => (
      <DataTable rows={[] as Row[]} columns={COLUMNS} rowKey={(r) => r.id} isLoading testId="dt" />
    ))
    await waitFor(() => {
      expect(screen.queryAllByTestId('dt-skel').length).toBeGreaterThan(0)
    })
    const skels = screen.queryAllByTestId('dt-skel')
    // Structurally a <tr> with .tt-skel-row — not a div.skeleton (block shimmer).
    for (const s of skels) {
      expect(s.tagName.toLowerCase()).toBe('tr')
      expect(s.className).toContain('tt-skel-row')
      expect(s.className).not.toContain('skeleton')
    }
  })

  it('keyboard nav — j moves focus down, k moves focus up, Enter triggers onRowClick', async () => {
    const onRowClick = vi.fn()
    mountAt({}, () => (
      <DataTable
        rows={ROWS}
        columns={COLUMNS}
        rowKey={(r) => r.id}
        onRowClick={onRowClick}
        testId="dt"
      />
    ))
    await waitFor(() => screen.getAllByRole('row'))
    const table = document.querySelector('table.tt')!
    fireEvent.keyDown(table, { key: 'j' })
    fireEvent.keyDown(table, { key: 'j' })
    fireEvent.keyDown(table, { key: 'k' })
    // After +1 +1 -1 we should be at idx 1 (row id=2 / bravo).
    fireEvent.keyDown(table, { key: 'Enter' })
    expect(onRowClick).toHaveBeenCalledTimes(1)
    expect(onRowClick.mock.calls[0][0].id).toBe(2)
  })

  it('keyboard nav — J/K page through cursor handlers; gg goes to top, G goes to bottom', async () => {
    const onOlder = vi.fn()
    const onNewer = vi.fn()
    const onRowClick = vi.fn()
    mountAt({}, () => (
      <DataTable
        rows={ROWS}
        columns={COLUMNS}
        rowKey={(r) => r.id}
        onRowClick={onRowClick}
        onNewer={onNewer}
        onOlder={onOlder}
        hasNewer
        hasOlder
        testId="dt"
      />
    ))
    await waitFor(() => screen.getAllByRole('row'))
    const table = document.querySelector('table.tt')!
    fireEvent.keyDown(table, { key: 'J', shiftKey: true })
    fireEvent.keyDown(table, { key: 'K', shiftKey: true })
    expect(onOlder).toHaveBeenCalledTimes(1)
    expect(onNewer).toHaveBeenCalledTimes(1)
    // G → bottom (idx 2 → id 3); Enter to fire onRowClick on it.
    fireEvent.keyDown(table, { key: 'G', shiftKey: true })
    fireEvent.keyDown(table, { key: 'Enter' })
    expect(onRowClick.mock.calls.at(-1)?.[0].id).toBe(3)
    // gg → top (idx 0 → id 1).
    fireEvent.keyDown(table, { key: 'g' })
    fireEvent.keyDown(table, { key: 'g' })
    fireEvent.keyDown(table, { key: 'Enter' })
    expect(onRowClick.mock.calls.at(-1)?.[0].id).toBe(1)
  })

  it('numeric columns get `num` class → tabular-nums + mono via CSS', async () => {
    mountAt({}, () => (
      <DataTable rows={ROWS} columns={COLUMNS} rowKey={(r) => r.id} testId="dt" />
    ))
    await waitFor(() => screen.getAllByRole('row'))
    const firstRow = screen.getAllByRole('row')[1]
    const cells = firstRow.querySelectorAll('td')
    expect(cells[0].className).toContain('num')
    expect(cells[1].className).not.toContain('num')
  })
})
