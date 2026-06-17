/**
 * Adversarial vitest for the #1095 stage-timing panel + chart.
 *
 * What an SRE relies on:
 *  1. A job with history renders one row per stage, each with a clickable bar
 *     per build and visible p50/p95/p99 read-outs.
 *  2. Clicking a bar targets that build's detail route (/builds/$buildId with
 *     the right buildId) — the "click a bar → navigate to build-detail"
 *     acceptance criterion.
 *  3. buildsConsidered === 0 (or zero stages) → the "not enough history yet"
 *     empty state, NOT a phantom all-zero chart.
 *  4. Loading → skeleton, never a premature empty flicker.
 *  5. Error → calm "unavailable" fallback, not the empty state.
 *  6. A single-sample stage still renders (p50 = p95 = p99) without NaN bars.
 *
 * The TanStack <Link> is mocked to a plain anchor that surfaces `to`/`params`
 * as data-* attributes — a lightweight check of the navigation target that
 * needs no router context (memory: Playwright/vitest lightweight checks).
 */
import { describe, it, expect, afterEach, vi } from 'vitest'
import { render, screen, cleanup } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { StageTimingsDto, StageTimingDto } from '../api/types'

const mockHook = vi.fn()
vi.mock('@/api/hooks', () => ({
  useJobStageTimings: (jobId: number | undefined, n: number) => mockHook(jobId, n),
}))

vi.mock('@tanstack/react-router', async () => {
  const actual = await vi.importActual<typeof import('@tanstack/react-router')>(
    '@tanstack/react-router',
  )
  return {
    ...actual,
    // Plain anchor that exposes the navigation target so the test can assert
    // the click destination without a live router. Forwards the data-* and
    // children so the bar's testid / attributes survive.
    Link: ({
      to,
      params,
      children,
      ...rest
    }: {
      to: string
      params?: Record<string, string>
      children?: React.ReactNode
      [k: string]: unknown
    }) => (
      <a
        href={to.replace('$buildId', params?.buildId ?? '')}
        data-to={to}
        data-param-buildid={params?.buildId}
        {...(rest as Record<string, unknown>)}
      >
        {children}
      </a>
    ),
  }
})

import { JobStageTimingsPanel } from '../components/JobStageTimingsPanel'

afterEach(() => {
  cleanup()
  mockHook.mockReset()
})

function renderPanel() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <JobStageTimingsPanel jobId={42} />
    </QueryClientProvider>,
  )
}

function stage(name: string, overrides: Partial<StageTimingDto> = {}): StageTimingDto {
  const samples = overrides.samples ?? [
    { buildId: 101, buildNumber: 1, durationMs: 1000, status: 'SUCCESS' },
    { buildId: 102, buildNumber: 2, durationMs: 2000, status: 'SUCCESS' },
    { buildId: 103, buildNumber: 3, durationMs: 3000, status: 'FAILED' },
  ]
  return {
    stageName: name,
    sampleCount: samples.length,
    p50Ms: 2000,
    p95Ms: 2900,
    p99Ms: 2980,
    minMs: 1000,
    maxMs: 3000,
    samples,
    ...overrides,
  }
}

function timings(overrides: Partial<StageTimingsDto> = {}): StageTimingsDto {
  return {
    n: 30,
    buildsConsidered: 3,
    stages: [stage('build'), stage('test')],
    ...overrides,
  }
}

describe('JobStageTimingsPanel / StageTimingChart', () => {
  it('renders one row per stage with bars + percentile read-outs', () => {
    mockHook.mockReturnValue({ data: timings(), isLoading: false, error: null })
    renderPanel()

    const rows = screen.getAllByTestId('job-stage-timing-row')
    expect(rows).toHaveLength(2)
    expect(rows[0].getAttribute('data-stage-name')).toBe('build')

    // Three bars per stage (3 samples each) → 6 bars total.
    expect(screen.getAllByTestId('job-stage-timing-bar')).toHaveLength(6)

    // p50/p95/p99 read-outs present (formatted, not raw ms).
    const p50 = screen.getAllByTestId('job-stage-timing-p50')[0].textContent ?? ''
    expect(p50).toMatch(/p50/)
    expect(p50).not.toContain('2000')

    // Percentile markers drawn (p50/p95/p99 per stage).
    expect(screen.getAllByTestId('job-stage-timing-marker-p95').length).toBeGreaterThan(0)
  })

  it('clicking a bar navigates to that build’s detail route', () => {
    mockHook.mockReturnValue({ data: timings(), isLoading: false, error: null })
    renderPanel()

    const bars = screen.getAllByTestId('job-stage-timing-bar')
    // First bar belongs to build #1 (buildId 101).
    const first = bars[0]
    expect(first.getAttribute('data-build-id')).toBe('101')
    expect(first.getAttribute('data-to')).toBe('/builds/$buildId')
    expect(first.getAttribute('data-param-buildid')).toBe('101')
    expect(first.getAttribute('href')).toBe('/builds/101')
  })

  it('buildsConsidered === 0 shows the "not enough history yet" empty state', () => {
    mockHook.mockReturnValue({
      data: timings({ buildsConsidered: 0, stages: [] }),
      isLoading: false,
      error: null,
    })
    renderPanel()
    expect(screen.getByTestId('job-stage-timing-empty')).toBeInTheDocument()
    expect(screen.getByTestId('job-stage-timing-empty').textContent).toMatch(
      /not enough history/i,
    )
    expect(screen.queryByTestId('job-stage-timing-chart')).toBeNull()
  })

  it('empty stages (but buildsConsidered > 0) still shows the empty state', () => {
    mockHook.mockReturnValue({
      data: timings({ buildsConsidered: 5, stages: [] }),
      isLoading: false,
      error: null,
    })
    renderPanel()
    expect(screen.getByTestId('job-stage-timing-empty')).toBeInTheDocument()
  })

  it('loading shows skeleton, not a premature empty flicker', () => {
    mockHook.mockReturnValue({ data: undefined, isLoading: true, error: null })
    renderPanel()
    expect(screen.getByTestId('job-stage-timings-loading')).toBeInTheDocument()
    expect(screen.queryByTestId('job-stage-timing-empty')).toBeNull()
  })

  it('error shows calm fallback, not the empty state', () => {
    mockHook.mockReturnValue({
      data: undefined,
      isLoading: false,
      error: new Error('boom'),
    })
    renderPanel()
    expect(screen.getByTestId('job-stage-timings-error')).toBeInTheDocument()
    expect(screen.queryByTestId('job-stage-timing-empty')).toBeNull()
  })

  it('single-sample stage renders one bar with p50 = p95 = p99 (no NaN)', () => {
    const single = stage('deploy', {
      sampleCount: 1,
      p50Ms: 12345,
      p95Ms: 12345,
      p99Ms: 12345,
      minMs: 12345,
      maxMs: 12345,
      samples: [{ buildId: 9, buildNumber: 7, durationMs: 12345, status: 'SUCCESS' }],
    })
    mockHook.mockReturnValue({
      data: timings({ buildsConsidered: 1, stages: [single] }),
      isLoading: false,
      error: null,
    })
    renderPanel()
    expect(screen.getAllByTestId('job-stage-timing-bar')).toHaveLength(1)
    const bar = screen.getByTestId('job-stage-timing-bar')
    // Height must be a real px value (no NaN leaking into the style).
    expect(bar.getAttribute('style') ?? '').not.toContain('NaN')
  })
})
