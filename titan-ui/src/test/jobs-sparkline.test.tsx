/**
 * Adversarial tests for the /jobs per-row sparkline (issue #648).
 *
 * Covers the three failure modes that matter:
 *   1. 20 builds → 20 <rect> elements with the correct per-status fill (no
 *      "all builds are red" rendering regression).
 *   2. 0 builds → renders the muted em-dash, NOT an empty SVG (silent empty
 *      state would look like a render bug to an SRE).
 *   3. Click on a bar navigates to /builds/<id> AND stops propagation, so a
 *      row-level click handler (e.g. View link) cannot hijack the navigation.
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { JobSparkline } from '../components/JobSparkline'
import { setupFetchMock, resetFetchMock } from './msw-handlers'
import { setAccessToken } from '../auth/tokenStore'
import type { BuildDto, BuildsPage } from '../api/types'

const navigateMock = vi.fn()

vi.mock('@tanstack/react-router', async () => {
  const actual = await vi.importActual<typeof import('@tanstack/react-router')>(
    '@tanstack/react-router',
  )
  return {
    ...actual,
    useNavigate: () => navigateMock,
  }
})

function build(id: number, status: string): BuildDto {
  return {
    id,
    jobId: 1,
    buildNumber: id,
    status,
    triggeredBy: null,
    triggerType: null,
    queuedAt: '2026-05-24T10:00:00Z',
    startedAt: '2026-05-24T10:00:01Z',
    finishedAt: '2026-05-24T10:00:10Z',
    durationMs: 9000,
    errorMessage: null,
    failureSummary: null,
  }
}

function renderSparkline(jobId: number) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <JobSparkline jobId={jobId} />
    </QueryClientProvider>,
  )
}

beforeEach(() => {
  navigateMock.mockReset()
  setAccessToken('fake')
})

afterEach(() => {
  resetFetchMock()
  setAccessToken(null)
  vi.restoreAllMocks()
})

describe('/jobs sparkline (issue #648)', () => {
  it('renders 20 bars with correct fills per status', async () => {
    // 7 SUCCESS, 7 FAILED, 6 RUNNING — exercises all three color buckets.
    const items: BuildDto[] = []
    for (let i = 0; i < 7; i++) items.push(build(100 + i, 'SUCCESS'))
    for (let i = 0; i < 7; i++) items.push(build(200 + i, 'FAILED'))
    for (let i = 0; i < 6; i++) items.push(build(300 + i, 'RUNNING'))
    const page: BuildsPage = { items, total: 20, offset: 0, limit: 20 }

    setupFetchMock([
      (url, method) => {
        if (method !== 'GET') return null
        if (url.pathname !== '/api/v1/jobs/1/builds') return null
        return { status: 200, body: page }
      },
    ])

    renderSparkline(1)

    const svg = await screen.findByTestId('job-row-1-sparkline')
    const rects = svg.querySelectorAll('rect')
    expect(rects.length).toBe(20)

    // Count per-fill — verifies the status→token mapping survives any future
    // token rename without smuggling in hex literals.
    const fills: Record<string, number> = {}
    rects.forEach((r) => {
      const f = r.getAttribute('fill') ?? '?'
      fills[f] = (fills[f] ?? 0) + 1
    })
    expect(fills['var(--ok)']).toBe(7)
    expect(fills['var(--fail)']).toBe(7)
    expect(fills['var(--muted)']).toBe(6)
  })

  it('shows muted em-dash on a job with zero builds', async () => {
    const page: BuildsPage = { items: [], total: 0, offset: 0, limit: 20 }
    setupFetchMock([
      (url, method) => {
        if (method !== 'GET') return null
        if (url.pathname !== '/api/v1/jobs/42/builds') return null
        return { status: 200, body: page }
      },
    ])

    renderSparkline(42)

    const empty = await screen.findByTestId('job-row-42-sparkline-empty')
    expect(empty.textContent).toBe('—')
  })

  it('clicking a bar navigates to /builds/$buildId and stops propagation', async () => {
    const items: BuildDto[] = [build(9001, 'SUCCESS'), build(9002, 'FAILED')]
    const page: BuildsPage = { items, total: 2, offset: 0, limit: 20 }
    setupFetchMock([
      (url, method) => {
        if (method !== 'GET') return null
        if (url.pathname !== '/api/v1/jobs/7/builds') return null
        return { status: 200, body: page }
      },
    ])

    // Wrap the sparkline in a row-level click spy so we can prove
    // stopPropagation works (the bar handler must not bubble).
    const rowClick = vi.fn()
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(
      <QueryClientProvider client={qc}>
        <div onClick={rowClick} data-testid="row">
          <JobSparkline jobId={7} />
        </div>
      </QueryClientProvider>,
    )

    const svg = await screen.findByTestId('job-row-7-sparkline')
    const rects = svg.querySelectorAll('rect')
    expect(rects.length).toBe(2)

    // API returns newest-first ([9001, 9002] as mocked). Component reverses
    // for left=oldest / right=newest, so 9002 ends up in slot 0 and 9001 in
    // slot 1 (rightmost).
    expect(rects[0].getAttribute('data-build-id')).toBe('9002')
    expect(rects[1].getAttribute('data-build-id')).toBe('9001')

    fireEvent.click(rects[0])

    await waitFor(() => expect(navigateMock).toHaveBeenCalledTimes(1))
    expect(navigateMock).toHaveBeenCalledWith({
      to: '/builds/$buildId',
      params: { buildId: '9002' },
    })
    expect(rowClick).not.toHaveBeenCalled()
  })
})
