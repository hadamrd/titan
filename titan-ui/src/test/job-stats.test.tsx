/**
 * Adversarial vitest for JobStatsPanel (closes #775).
 *
 * What SREs rely on:
 *  1. 0 builds → muted placeholder + em-dashes on rate / p95 tiles (NOT "0%"
 *     and NOT "0 ms" — both would lie about the engine).
 *  2. 30d data renders exactly 30 sparkline bars (server returns dense; the UI
 *     must NOT collapse zero-bucket days).
 *  3. Window-chip click switches the React-Query key (and so the hook receives
 *     a fresh window arg — guard against the "we changed the chip but the
 *     query never refetched" regression).
 *  4. p95 nullable: null → em-dash, not "0 ms".
 *  5. Failure-rate tint flips on once {@code > 10%} — not at 10% exactly.
 */
import { describe, it, expect, vi, afterEach } from 'vitest'
import { render, screen, cleanup, fireEvent } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { JobStatsDto, JobStatsWindow } from '../api/types'

const mockHook = vi.fn()
vi.mock('@/api/hooks', () => ({
  useJobStats: (jobId: number | undefined, window: JobStatsWindow) =>
    mockHook(jobId, window),
}))

import { JobStatsPanel } from '../components/JobStatsPanel'

afterEach(() => {
  cleanup()
  mockHook.mockReset()
})

function renderPanel() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <JobStatsPanel jobId={42} />
    </QueryClientProvider>,
  )
}

function denseBuckets(days: number, failedAt: number[] = []): JobStatsDto['dailyBuckets'] {
  // Generate calendar-walk dates starting from a fixed anchor so every bucket
  // is unique (real server output never repeats a day; the React key warning
  // would mask a genuine bug).
  const anchor = new Date(Date.UTC(2026, 0, 1)) // 2026-01-01 UTC
  const out: JobStatsDto['dailyBuckets'] = []
  for (let i = 0; i < days; i++) {
    const failed = failedAt.includes(i) ? 1 : 0
    const d = new Date(anchor)
    d.setUTCDate(d.getUTCDate() + i)
    const day = d.toISOString().slice(0, 10)
    out.push({
      day,
      totalBuilds: failedAt.includes(i) ? 1 : 0,
      failedBuilds: failed,
    })
  }
  return out
}

function stats(overrides: Partial<JobStatsDto> = {}): JobStatsDto {
  return {
    totalBuilds: 10,
    failedBuilds: 3,
    failureRate: 0.3,
    p50DurationMs: 12_000,
    p95DurationMs: 45_000,
    dailyBuckets: denseBuckets(30, [1, 5, 10]),
    window: '30d',
    ...overrides,
  }
}

describe('JobStatsPanel', () => {
  it('renders 3 KPI tiles + sparkline for non-empty data', () => {
    mockHook.mockReturnValue({ data: stats(), isLoading: false, error: null })
    renderPanel()
    expect(screen.getByTestId('job-stats-total').textContent).toContain('10')
    expect(screen.getByTestId('job-stats-failure-rate').textContent).toContain('30%')
    // p95 = 45_000ms → "45s" via formatDuration; assert it doesn't render as raw ms.
    const p95 = screen.getByTestId('job-stats-p95').textContent ?? ''
    expect(p95).not.toContain('45000')
    expect(p95).toMatch(/45/)
    const spark = screen.getByTestId('job-stats-sparkline')
    expect(spark.getAttribute('data-bars')).toBe('30')
  })

  it('zero builds renders muted placeholder (no misleading 0%) + em-dash p95', () => {
    mockHook.mockReturnValue({
      data: stats({
        totalBuilds: 0,
        failedBuilds: 0,
        failureRate: 0,
        p50DurationMs: null,
        p95DurationMs: null,
        dailyBuckets: denseBuckets(30, []),
      }),
      isLoading: false,
      error: null,
    })
    renderPanel()
    expect(screen.getByTestId('job-stats-empty')).toBeInTheDocument()
    // Failure-rate tile is em-dash, NOT "0%".
    const rate = screen.getByTestId('job-stats-failure-rate').textContent ?? ''
    expect(rate).toContain('—')
    expect(rate).not.toContain('0%')
    // p95 null → em-dash, NOT a number. Strip the "p95 duration" label before
    // asserting on digits — the label itself legitimately contains "95".
    const p95Tile = screen.getByTestId('job-stats-p95')
    const p95Value = p95Tile.querySelector('.metric-value')?.textContent ?? ''
    expect(p95Value).toContain('—')
    expect(p95Value).not.toMatch(/\d/)
    // No failure-tint when empty.
    expect(
      screen.getByTestId('job-stats-failure-rate').getAttribute('data-tinted'),
    ).toBeNull()
  })

  it('renders exactly windowDays bars in the sparkline (7d → 7, 30d → 30)', () => {
    mockHook.mockReturnValue({
      data: stats({ window: '7d', dailyBuckets: denseBuckets(7, [2]) }),
      isLoading: false,
      error: null,
    })
    renderPanel()
    expect(screen.getByTestId('job-stats-sparkline').getAttribute('data-bars')).toBe('7')
    cleanup()
    mockHook.mockReset()
    mockHook.mockReturnValue({ data: stats(), isLoading: false, error: null })
    renderPanel()
    expect(screen.getByTestId('job-stats-sparkline').getAttribute('data-bars')).toBe('30')
  })

  it('clicking a window chip calls the hook with the new window arg', () => {
    mockHook.mockReturnValue({ data: stats(), isLoading: false, error: null })
    renderPanel()
    // Initial call: default 30d.
    const initialWindows = mockHook.mock.calls.map((c) => c[1])
    expect(initialWindows).toContain('30d')
    // Click 7d.
    fireEvent.click(screen.getByTestId('job-stats-window-7d'))
    const seenWindows = mockHook.mock.calls.map((c) => c[1])
    expect(seenWindows).toContain('7d')
    // Click 90d.
    fireEvent.click(screen.getByTestId('job-stats-window-90d'))
    const seen2 = mockHook.mock.calls.map((c) => c[1])
    expect(seen2).toContain('90d')
  })

  it('failure rate > 10% turns the tile red (data-tinted attr)', () => {
    mockHook.mockReturnValue({
      data: stats({ failureRate: 0.11, totalBuilds: 100, failedBuilds: 11 }),
      isLoading: false,
      error: null,
    })
    renderPanel()
    expect(
      screen.getByTestId('job-stats-failure-rate').getAttribute('data-tinted'),
    ).toBe('true')
  })

  it('failure rate 10% exactly is NOT tinted (strict > threshold)', () => {
    mockHook.mockReturnValue({
      data: stats({ failureRate: 0.1, totalBuilds: 10, failedBuilds: 1 }),
      isLoading: false,
      error: null,
    })
    renderPanel()
    expect(
      screen.getByTestId('job-stats-failure-rate').getAttribute('data-tinted'),
    ).toBeNull()
  })

  it('error state shows a calm fallback, not the empty placeholder', () => {
    mockHook.mockReturnValue({
      data: undefined,
      isLoading: false,
      error: new Error('boom'),
    })
    renderPanel()
    expect(screen.getByTestId('job-stats-error')).toBeInTheDocument()
    expect(screen.queryByTestId('job-stats-empty')).toBeNull()
  })

  it('shows skeleton while loading (no premature "no builds" flicker)', () => {
    mockHook.mockReturnValue({ data: undefined, isLoading: true, error: null })
    renderPanel()
    expect(screen.getByTestId('job-stats-loading')).toBeInTheDocument()
    expect(screen.queryByTestId('job-stats-empty')).toBeNull()
  })
})
