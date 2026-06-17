/**
 * Access-tokens tab — four-state + H4/H5 guards for the #1186 redesign.
 *
 * The tokens list is now built on the shared `DataTable`. These tests lock in:
 *  - loading  → Skeleton rows (not a bare header, not a spinner-forever)
 *  - empty    → centred "no tokens yet" message
 *  - error    → an explicit retry affordance that calls back (sad path: a
 *               forced query error must NOT render a blank header / infinite spin)
 *  - populated→ a real table with NO permanently-blank column (the dropped
 *               "Last used" relapse guard, H4)
 *  - H5       → exactly one dominant primary action on the tab ("Generate
 *               token"), and the table itself buries no primary in a row.
 */
import { afterEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'

import { PatTable } from '../components/profile/PatTable'
import { PersonalAccessTokensCard } from '../components/profile/PersonalAccessTokensCard'
import { setAccessToken } from '../auth/tokenStore'
import type { PersonalAccessTokenDto } from '../api/types'
import { resetFetchMock, setupFetchMock } from './msw-handlers'

const ACTIVE_TOKEN: PersonalAccessTokenDto = {
  id: 7,
  name: 'ci-bot',
  prefix: 'tt_live_AbCd',
  scopes: ['READ_JOB'],
  jobPattern: 'acme/web-*',
  createdAt: '2026-05-24T10:00:00Z',
  lastUsedAt: null,
  revokedAt: null,
}

afterEach(() => {
  resetFetchMock()
})

describe('PatTable — four data states', () => {
  it('loading state renders DataTable skeleton rows, not a bare header', () => {
    render(
      <PatTable
        tokens={undefined}
        isLoading
        error={null}
        onRetry={() => {}}
        onRevoke={() => {}}
        revokePending={false}
      />,
    )
    expect(screen.getAllByTestId('pat-list-skel').length).toBeGreaterThan(0)
    // not the empty / error terminal states
    expect(screen.queryByTestId('pat-list-empty')).toBeNull()
    expect(screen.queryByTestId('pat-list-error')).toBeNull()
  })

  it('empty state renders a centred "no tokens yet" message', () => {
    render(
      <PatTable
        tokens={[]}
        isLoading={false}
        error={null}
        onRetry={() => {}}
        onRevoke={() => {}}
        revokePending={false}
      />,
    )
    const empty = screen.getByTestId('pat-list-empty')
    expect(empty.textContent).toMatch(/no tokens yet/i)
  })

  it('error state renders a retry affordance that calls onRetry (sad path)', () => {
    const onRetry = vi.fn()
    render(
      <PatTable
        tokens={undefined}
        isLoading={false}
        error={new Error('boom 500')}
        onRetry={onRetry}
        onRevoke={() => {}}
        revokePending={false}
      />,
    )
    const errBox = screen.getByTestId('pat-list-error')
    // not an infinite spinner / blank header
    expect(screen.queryByTestId('pat-list-skel')).toBeNull()
    const retry = within(errBox).getByText(/retry/i)
    fireEvent.click(retry)
    expect(onRetry).toHaveBeenCalledTimes(1)
  })

  it('populated state renders rows and ships NO permanently-blank column (H4)', () => {
    render(
      <PatTable
        tokens={[ACTIVE_TOKEN]}
        isLoading={false}
        error={null}
        onRetry={() => {}}
        onRevoke={() => {}}
        revokePending={false}
      />,
    )
    const table = screen.getByTestId('pat-list').querySelector('table')!
    // the relapse guard: the always-empty "Last used" column must be gone.
    expect(within(table).queryByText('Last used')).toBeNull()

    // generic no-blank-column assertion: for every column index, at least one
    // body cell must carry content (text or a control). Given a populated row,
    // a column that is 100% blank is an H4 violation.
    const headerCells = Array.from(table.querySelectorAll('thead th'))
    const bodyRows = Array.from(table.querySelectorAll('tbody tr'))
    headerCells.forEach((_th, colIdx) => {
      const anyFilled = bodyRows.some((row) => {
        const cell = row.querySelectorAll('td')[colIdx]
        if (!cell) return false
        return (cell.textContent ?? '').trim().length > 0 || cell.querySelector('button') !== null
      })
      expect(anyFilled).toBe(true)
    })
  })

  it('buries no primary-button treatment inside a table row (H5)', () => {
    const { container } = render(
      <PatTable
        tokens={[ACTIVE_TOKEN]}
        isLoading={false}
        error={null}
        onRetry={() => {}}
        onRevoke={() => {}}
        revokePending={false}
      />,
    )
    // the revoke action is a ghost button; no .btn-primary may live in the table.
    expect(container.querySelectorAll('.btn-primary').length).toBe(0)
  })
})

// ── Card-level: H5 one-primary + empty-state primary action ──────────────────

function tokensHandler(state: { tokens: PersonalAccessTokenDto[] }) {
  return (url: URL, method: string) => {
    if (method === 'GET' && url.pathname === '/api/v1/me/tokens') {
      return { status: 200, body: state.tokens }
    }
    return null
  }
}

function mountCard(state: { tokens: PersonalAccessTokenDto[] }) {
  setupFetchMock([tokensHandler(state)])
  setAccessToken('test-token')
  const qc = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return render(
    <QueryClientProvider client={qc}>
      <PersonalAccessTokensCard />
    </QueryClientProvider>,
  )
}

describe('PersonalAccessTokensCard — H5 one primary action', () => {
  it('empty state surfaces the "Generate token" primary, and it is the ONLY primary', async () => {
    const { container } = mountCard({ tokens: [] })

    // empty state present (no rows) — findBy* flushes the query inside act()
    await screen.findByTestId('pat-list-empty')

    // the one dominant primary action is the generate button (.btn-primary)
    const generate = screen.getByTestId('pat-generate-btn')
    expect(generate.classList.contains('btn-primary')).toBe(true)

    // exactly one primary-button treatment on the whole tab (H5)
    await waitFor(() => {
      expect(container.querySelectorAll('.btn-primary').length).toBe(1)
    })
  })
})
