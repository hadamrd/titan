/**
 * Adversarial tests for AgentEventsPanel (closes #733).
 *
 * Covers:
 *  1. Empty events → muted "No worker events in recent history" placeholder,
 *     no crash.
 *  2. 5 mixed JOINED/LEFT events → 5 rows in newest-first order; the JOINED
 *     and LEFT chips render distinctly (different data-event-type + visible
 *     text), so a CSS regression flattening both into the same chip surfaces
 *     immediately.
 *  3. Event with no meta → row renders without a suffix; no crash. (DTO has
 *     no meta field in v1 — this asserts the renderer doesn't depend on one.)
 *  4. Clicking a row does NOT navigate anywhere (info-only rows — no Link).
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'

import { AgentEventsPanel } from '../components/AgentEventsPanel'
import type { AgentEventDto } from '../api/types'

// ── Fetch mock ──────────────────────────────────────────────────────────────

let fetchMock: ReturnType<typeof vi.fn>

function installFetch(events: AgentEventDto[]) {
  fetchMock = vi.fn(async (input: RequestInfo | URL) => {
    const url = typeof input === 'string' ? input : input.toString()
    if (url.includes('/api/v1/agents/events')) {
      return new Response(JSON.stringify(events), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      })
    }
    throw new Error(`Unexpected fetch: ${url}`)
  })
  vi.stubGlobal('fetch', fetchMock)
}

// ── Harness ────────────────────────────────────────────────────────────────

function mountPanel() {
  const qc = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return render(
    <QueryClientProvider client={qc}>
      <AgentEventsPanel />
    </QueryClientProvider>,
  )
}

// ── Lifecycle ──────────────────────────────────────────────────────────────

beforeEach(() => {
  vi.useFakeTimers({ shouldAdvanceTime: true })
  vi.setSystemTime(new Date('2026-05-24T10:00:00Z'))
})

afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
  cleanup()
})

// ── Tests ──────────────────────────────────────────────────────────────────

describe('AgentEventsPanel (#733)', () => {
  it('renders the muted empty-state when there are no events', async () => {
    installFetch([])
    mountPanel()
    await waitFor(() =>
      expect(
        screen.getByTestId('agent-events-panel-empty'),
      ).toBeInTheDocument(),
    )
    expect(
      screen.getByTestId('agent-events-panel-empty').textContent,
    ).toContain('No worker events in recent history')
  })

  it('renders 5 mixed JOINED/LEFT events in newest-first order with distinct chips', async () => {
    // Intentionally out-of-order on the wire — assert the renderer normalises.
    const events: AgentEventDto[] = [
      {
        id: 3,
        agentId: 'w-c',
        agentName: 'worker-c',
        type: 'LEFT',
        occurredAt: '2026-05-24T09:50:00Z',
      },
      {
        id: 5,
        agentId: 'w-e',
        agentName: 'worker-e',
        type: 'JOINED',
        occurredAt: '2026-05-24T09:58:00Z', // newest
      },
      {
        id: 1,
        agentId: 'w-a',
        agentName: 'worker-a',
        type: 'JOINED',
        occurredAt: '2026-05-24T09:40:00Z',
      },
      {
        id: 4,
        agentId: 'w-d',
        agentName: 'worker-d',
        type: 'LEFT',
        occurredAt: '2026-05-24T09:55:00Z',
      },
      {
        id: 2,
        agentId: 'w-b',
        agentName: 'worker-b',
        type: 'JOINED',
        occurredAt: '2026-05-24T09:45:00Z',
      },
    ]
    installFetch(events)
    mountPanel()

    await waitFor(() => {
      const rows = screen.queryAllByTestId(/^agent-event-row-/)
      expect(rows).toHaveLength(5)
    })

    const rows = screen.getAllByTestId(/^agent-event-row-/)
    const orderedIds = rows.map(
      (r) => r.getAttribute('data-testid')!.replace('agent-event-row-', ''),
    )
    // Newest first by occurredAt desc: 5 (9:58), 4 (9:55), 3 (9:50), 2 (9:45),
    // 1 (9:40).
    expect(orderedIds).toEqual(['5', '4', '3', '2', '1'])

    // Distinct chip rendering: each row carries data-event-type matching its
    // visible chip text. The two states must render under different chip
    // testids, so a CSS or branch regression collapsing both into one chip
    // shows up here.
    expect(rows[0].getAttribute('data-event-type')).toBe('JOINED')
    expect(rows[1].getAttribute('data-event-type')).toBe('LEFT')

    const joinedChips = screen.getAllByTestId('agent-event-chip-JOINED')
    const leftChips = screen.getAllByTestId('agent-event-chip-LEFT')
    expect(joinedChips).toHaveLength(3)
    expect(leftChips).toHaveLength(2)
    expect(joinedChips[0].textContent).toBe('JOINED')
    expect(leftChips[0].textContent).toBe('LEFT')

    // Agent names visible on every row.
    expect(screen.getByText('worker-e')).toBeInTheDocument()
    expect(screen.getByText('worker-a')).toBeInTheDocument()
  })

  it('renders an event with no extra metadata without a suffix or crash', async () => {
    const events: AgentEventDto[] = [
      {
        id: 42,
        agentId: 'lonely',
        agentName: 'lonely-worker',
        type: 'LEFT',
        occurredAt: '2026-05-24T09:59:00Z',
      },
    ]
    installFetch(events)
    mountPanel()
    const row = await screen.findByTestId('agent-event-row-42')
    // Row reads as "<name> LEFT <time>" with no trailing parenthetical
    // suffix — the DTO has no meta field in v1 so the renderer must not invent
    // one. Concretely: no "(" character anywhere in the row text.
    expect(row.textContent).toContain('lonely-worker')
    expect(row.textContent).toContain('LEFT')
    expect(row.textContent).not.toContain('(')
    expect(row.textContent).not.toContain('undefined')
    expect(row.textContent).not.toContain('null')
  })

  it('clicking a row does not navigate (info-only rows, no Link target)', async () => {
    const events: AgentEventDto[] = [
      {
        id: 7,
        agentId: 'w-7',
        agentName: 'worker-7',
        type: 'JOINED',
        occurredAt: '2026-05-24T09:55:00Z',
      },
    ]
    installFetch(events)
    mountPanel()
    const row = await screen.findByTestId('agent-event-row-7')

    // The row must NOT be an anchor — if it were, a click in JSDOM would try
    // to navigate and TanStack Router would throw outside a RouterProvider.
    // We assert structurally: not an <a>, and click is a no-op (no error,
    // no location change).
    expect(row.tagName.toLowerCase()).not.toBe('a')
    const before = window.location.pathname
    fireEvent.click(row)
    expect(window.location.pathname).toBe(before)
    // Row is still mounted post-click.
    expect(screen.getByTestId('agent-event-row-7')).toBeInTheDocument()
  })
})
