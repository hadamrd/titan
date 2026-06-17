/**
 * Adversarial tests for BuildDurationTrend (issue #663).
 *
 * Covers the four failure modes that matter for an SRE inspecting whether
 * THIS build's duration is anomalous:
 *   1. 20 builds with varied durations → 20 bars, the bar matching the max
 *      duration is the tallest (regression guard for divide-by-zero / inverse
 *      normalisation).
 *   2. 1 build only → renders that single bar without crashing and without
 *      NaN heights (so a brand-new job's first build page does not blow up).
 *   3. currentBuildId not in the response (race: trend hasn't seen this
 *      build yet) → trend still renders, no highlight ring is drawn, no
 *      crash.
 *   4. Empty response → renders a muted placeholder, NOT a blank SVG that
 *      looks like a render bug.
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { BuildDurationTrend } from '../components/BuildDurationTrend'
import { setupFetchMock, resetFetchMock } from './msw-handlers'
import { setAccessToken } from '../auth/tokenStore'
import type { BuildDto } from '../api/types'

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

function build(id: number, durationMs: number | null, status = 'SUCCESS'): BuildDto {
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
    durationMs,
    errorMessage: null,
    failureSummary: null,
  }
}

function mockRecentBuilds(jobId: number, items: BuildDto[]) {
  setupFetchMock([
    (url, method) => {
      if (method !== 'GET') return null
      if (url.pathname !== '/api/v1/jobs/recent-builds') return null
      // Endpoint shape: { "<jobId>": BuildDto[] }
      return { status: 200, body: { [String(jobId)]: items } }
    },
  ])
}

function renderTrend(jobId: number, currentBuildId: number) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <BuildDurationTrend jobId={jobId} currentBuildId={currentBuildId} />
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

describe('BuildDurationTrend (issue #663)', () => {
  it('renders 20 bars and the max-duration bar is the tallest', async () => {
    // Varied durations; max sits in the middle of the window so we know the
    // height-ranking comes from the value, not from list position.
    const items: BuildDto[] = []
    for (let i = 0; i < 20; i++) {
      // Slot 10 is the tallest (60_000 ms = 1 min); others scale around it.
      const d = i === 10 ? 60_000 : 1_000 + i * 500
      items.push(build(1000 + i, d))
    }
    mockRecentBuilds(1, items)

    renderTrend(1, 1010)

    const svg = await screen.findByTestId('build-duration-trend')
    const bars = svg.querySelectorAll('rect[data-build-id]')
    expect(bars.length).toBe(20)

    // Find the tallest bar by SVG height attribute — must correspond to id 1010.
    let tallest: Element | null = null
    let tallestH = -1
    bars.forEach((r) => {
      const h = Number(r.getAttribute('height') ?? '0')
      expect(Number.isFinite(h)).toBe(true)
      expect(h).toBeGreaterThan(0)
      if (h > tallestH) {
        tallestH = h
        tallest = r
      }
    })
    expect(tallest).not.toBeNull()
    expect(tallest!.getAttribute('data-build-id')).toBe('1010')

    // Current ring is drawn for the current build id (1010).
    expect(screen.getByTestId('build-duration-trend-current-ring')).toBeTruthy()
  })

  it('renders a single bar gracefully when only one build exists (no NaN)', async () => {
    const items: BuildDto[] = [build(7777, 12_345, 'SUCCESS')]
    mockRecentBuilds(1, items)

    renderTrend(1, 7777)

    const svg = await screen.findByTestId('build-duration-trend')
    const bars = svg.querySelectorAll('rect[data-build-id]')
    expect(bars.length).toBe(1)
    const h = Number(bars[0].getAttribute('height'))
    expect(Number.isFinite(h)).toBe(true)
    expect(h).toBeGreaterThan(0)
    // Width/x must also be finite (no NaN from divide-by-zero).
    expect(Number.isFinite(Number(bars[0].getAttribute('width')))).toBe(true)
    expect(Number.isFinite(Number(bars[0].getAttribute('x')))).toBe(true)
    expect(bars[0].getAttribute('data-build-id')).toBe('7777')
    // Current ring present because 7777 IS the current build.
    expect(screen.getByTestId('build-duration-trend-current-ring')).toBeTruthy()
  })

  it('renders without highlight when currentBuildId is absent from the window', async () => {
    const items: BuildDto[] = [
      build(101, 5_000, 'SUCCESS'),
      build(102, 7_000, 'FAILED'),
      build(103, 3_000, 'SUCCESS'),
    ]
    mockRecentBuilds(1, items)

    // currentBuildId 999 is not in the response (race / older build).
    renderTrend(1, 999)

    const svg = await screen.findByTestId('build-duration-trend')
    const bars = svg.querySelectorAll('rect[data-build-id]')
    expect(bars.length).toBe(3)
    // No ring drawn.
    expect(screen.queryByTestId('build-duration-trend-current-ring')).toBeNull()
    // Sanity: no bar carries data-current="true".
    bars.forEach((r) => {
      expect(r.getAttribute('data-current')).not.toBe('true')
    })
  })

  it('renders muted placeholder (not blank SVG) on an empty window', async () => {
    mockRecentBuilds(1, [])

    renderTrend(1, 555)

    await waitFor(() => {
      expect(screen.getByTestId('build-duration-trend-empty')).toBeTruthy()
    })
    // No SVG trend rendered.
    expect(screen.queryByTestId('build-duration-trend')).toBeNull()
    expect(screen.getByTestId('build-duration-trend-empty').textContent).toBe('—')
  })
})
