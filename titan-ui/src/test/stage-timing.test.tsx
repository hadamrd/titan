/**
 * Adversarial tests for StageTimingPanel (closes #670).
 *
 * Failure modes that matter for an SRE diagnosing a long failed build:
 *
 *  1. Three stages of mixed durations → three bars rendered in DECLARED
 *     YAML order (NOT sorted by duration); the longest bar consumes the
 *     full track width.
 *  2. RUNNING stage with no completedAt → bar uses muted colour, shows
 *     elapsed time relative to "now", and renders the pulse indicator.
 *  3. Instant / skipped stage (completedAt === startedAt) → bar still
 *     visible at 1px minimum, duration reads "0s" (regression guard
 *     against invisible rows).
 *  4. Empty stage list → renders the empty-state placeholder instead of
 *     a blank panel that looks like a render bug.
 *  5. Click on a stage row → invokes the onSelectStage callback with the
 *     stage's nodeId (the same handler the flow graph uses for selection).
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { render as rtlRender, screen, fireEvent, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactElement } from 'react'
import { StageTimingPanel } from '../components/StageTimingPanel'
import type { FlowNodeDto } from '../api/types'
import { AuthContext, type AuthState } from '../auth/AuthProvider'
import type { User } from 'oidc-client-ts'

// The StageTimingPanel now depends on AuthContext + a QueryClient (#748 wired
// the per-FAILED-stage Retry button + RBAC gate). All five legacy assertions
// still hold; we just need the provider scaffolding around the render.
const TEST_AUTH: AuthState = {
  user: {
    access_token: 'fake',
    expired: false,
    profile: { groups: [] },
  } as unknown as User,
  isLoading: false,
  isAuthenticated: true,
  signinRedirect: async () => {},
  signinRedirectCallback: async () => ({}) as never,
  signoutRedirect: async () => {},
}

function render(ui: ReactElement) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return rtlRender(
    <AuthContext.Provider value={TEST_AUTH}>
      <QueryClientProvider client={qc}>{ui}</QueryClientProvider>
    </AuthContext.Provider>,
  )
}

function stage(opts: {
  id: string
  name: string
  status?: string
  startedAt?: string | null
  completedAt?: string | null
  durationMs?: number | null
  nodeType?: string
}): FlowNodeDto {
  return {
    buildId: 1,
    nodeId: opts.id,
    parentIds: null,
    nodeType: opts.nodeType ?? 'STAGE',
    displayName: opts.name,
    stepDescriptor: null,
    status: opts.status ?? 'SUCCESS',
    agentLabel: null,
    startedAt: opts.startedAt ?? null,
    completedAt: opts.completedAt ?? null,
    durationMs: opts.durationMs ?? null,
    attempt: 1,
    maxAttempts: 1,
    failureCategory: null,
    failureReason: null,
    logTaskId: null,
  }
}

function widthPct(el: HTMLElement): number {
  // style.width is "max(1px, NN.NN%)" — pull the percentage out.
  const m = /([0-9.]+)%/.exec(el.style.width)
  return m ? Number(m[1]) : 0
}

describe('StageTimingPanel', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-05-24T12:00:00Z'))
  })
  afterEach(() => {
    vi.useRealTimers()
  })

  it('renders three bars in declared order; longest is full width', () => {
    // Declared order: build (5s) → test (60s) → deploy (15s). Longest is
    // 'test' (60s) — that bar should consume 100% of its track.
    const nodes: FlowNodeDto[] = [
      stage({
        id: 's-build',
        name: 'build',
        status: 'SUCCESS',
        startedAt: '2026-05-24T11:00:00Z',
        completedAt: '2026-05-24T11:00:05Z',
      }),
      stage({
        id: 's-test',
        name: 'test',
        status: 'SUCCESS',
        startedAt: '2026-05-24T11:00:05Z',
        completedAt: '2026-05-24T11:01:05Z',
      }),
      stage({
        id: 's-deploy',
        name: 'deploy',
        status: 'SUCCESS',
        startedAt: '2026-05-24T11:01:05Z',
        completedAt: '2026-05-24T11:01:20Z',
      }),
    ]
    render(<StageTimingPanel nodes={nodes} />)

    const panel = screen.getByTestId('stage-timing-panel')
    const rows = within(panel).getAllByRole('button')
    expect(rows).toHaveLength(3)

    // Declared YAML order preserved — NOT sorted longest-first.
    expect(rows[0]).toHaveAttribute('data-testid', 'stage-timing-row-s-build')
    expect(rows[1]).toHaveAttribute('data-testid', 'stage-timing-row-s-test')
    expect(rows[2]).toHaveAttribute('data-testid', 'stage-timing-row-s-deploy')

    // Longest stage's bar = 100% width.
    const longestBar = screen.getByTestId('stage-timing-bar-s-test')
    expect(widthPct(longestBar)).toBe(100)

    // Shorter stages are proportionally narrower.
    const buildBar = screen.getByTestId('stage-timing-bar-s-build')
    const deployBar = screen.getByTestId('stage-timing-bar-s-deploy')
    expect(widthPct(buildBar)).toBeLessThan(widthPct(deployBar))
    expect(widthPct(deployBar)).toBeLessThan(100)

    // Duration labels visible and human-formatted.
    expect(within(rows[0]!).getByText('5s')).toBeInTheDocument()
    expect(within(rows[1]!).getByText('1m')).toBeInTheDocument()
    expect(within(rows[2]!).getByText('15s')).toBeInTheDocument()
  })

  it('renders a RUNNING stage with muted bar, elapsed time, and pulse', () => {
    // System time set to 12:00:00Z; stage started 30s ago.
    const nodes: FlowNodeDto[] = [
      stage({
        id: 's-run',
        name: 'deploy',
        status: 'RUNNING',
        startedAt: '2026-05-24T11:59:30Z',
        completedAt: null,
      }),
    ]
    render(<StageTimingPanel nodes={nodes} />)

    const row = screen.getByTestId('stage-timing-row-s-run')
    expect(row).toHaveAttribute('data-running', 'true')

    const bar = screen.getByTestId('stage-timing-bar-s-run')
    // Muted token, NOT a status colour.
    expect(bar.style.background).toContain('var(--muted)')

    // Elapsed text reflects 30 seconds since startedAt.
    expect(within(row).getByText('30s')).toBeInTheDocument()

    // Pulse overlay is mounted.
    expect(screen.getByTestId('stage-timing-running-pulse')).toBeInTheDocument()
  })

  it('renders an instant / skipped stage as a 1px-min bar showing "0s"', () => {
    const nodes: FlowNodeDto[] = [
      stage({
        id: 's-skip',
        name: 'lint',
        status: 'SUCCESS',
        startedAt: '2026-05-24T11:00:00Z',
        completedAt: '2026-05-24T11:00:00Z',
      }),
      // A longer sibling so maxDuration > 0 and the normalisation path runs.
      stage({
        id: 's-long',
        name: 'build',
        status: 'SUCCESS',
        startedAt: '2026-05-24T11:00:00Z',
        completedAt: '2026-05-24T11:00:30Z',
      }),
    ]
    render(<StageTimingPanel nodes={nodes} />)

    const skipBar = screen.getByTestId('stage-timing-bar-s-skip')
    // The style is "max(1px, 0.00%)" — both halves must be present.
    expect(skipBar.style.width).toContain('1px')
    expect(skipBar.style.width).toContain('0.00%')

    const row = screen.getByTestId('stage-timing-row-s-skip')
    expect(within(row).getByText('0s')).toBeInTheDocument()
  })

  it('renders an empty-state placeholder when no stage nodes are present', () => {
    // Step-only nodes (no STAGE) should ALSO fall through to the empty state.
    const nodes: FlowNodeDto[] = [
      stage({
        id: 'sh-1',
        name: 'sh: echo',
        nodeType: 'sh',
        startedAt: '2026-05-24T11:00:00Z',
        completedAt: '2026-05-24T11:00:01Z',
      }),
    ]
    const { unmount } = render(<StageTimingPanel nodes={nodes} />)
    expect(screen.getByTestId('stage-timing-empty')).toBeInTheDocument()
    expect(screen.queryByTestId('stage-timing-panel')).not.toBeInTheDocument()
    unmount()

    // Truly empty array path — fresh mount keeps the provider scaffolding.
    render(<StageTimingPanel nodes={[]} />)
    expect(screen.getByTestId('stage-timing-empty')).toBeInTheDocument()
  })

  it('invokes onSelectStage with the stage nodeId when a row is clicked', () => {
    const onSelectStage = vi.fn()
    const nodes: FlowNodeDto[] = [
      stage({
        id: 's-build',
        name: 'build',
        startedAt: '2026-05-24T11:00:00Z',
        completedAt: '2026-05-24T11:00:05Z',
      }),
      stage({
        id: 's-test',
        name: 'test',
        startedAt: '2026-05-24T11:00:05Z',
        completedAt: '2026-05-24T11:01:05Z',
      }),
    ]
    render(<StageTimingPanel nodes={nodes} onSelectStage={onSelectStage} />)

    fireEvent.click(screen.getByTestId('stage-timing-row-s-test'))
    expect(onSelectStage).toHaveBeenCalledTimes(1)
    expect(onSelectStage).toHaveBeenCalledWith('s-test')
  })
})
