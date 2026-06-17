/**
 * Adversarial tests for LogDiffPanel (closes #770).
 *
 * Failure modes that matter:
 *  1. 0-line logs on both sides → muted "No log content to compare"
 *  2. Identical logs → 0 removed / 0 added / N unchanged
 *  3. B has same lines + 1 trailing extra → exactly 1 added on right
 *  4. Stage in A only / in B only → "(no counterpart …)" + solo logs render
 *  5. Huge log (1000 lines) → diff completes synchronously, truncation
 *     notice shows; rendered line count is capped
 *
 * We never touch the network: the panel exposes a `fetchLogs` test seam.
 */
import { describe, it, expect } from 'vitest'
import { render, screen, fireEvent, waitFor, act } from '@testing-library/react'
import type { FlowNodeDto } from '../api/types'
import { LogDiffPanel, pickDefaultStage, unionStages } from '../components/LogDiffPanel'
import { lineDiff, MAX_DIFF_LINES } from '../lib/lineDiff'

function stage(opts: {
  id: string
  name: string
  status?: string
  logTaskId?: string | null
  buildId?: number
}): FlowNodeDto {
  return {
    buildId: opts.buildId ?? 1,
    nodeId: opts.id,
    parentIds: null,
    nodeType: 'STAGE',
    displayName: opts.name,
    stepDescriptor: null,
    status: opts.status ?? 'SUCCESS',
    agentLabel: null,
    startedAt: null,
    completedAt: null,
    durationMs: null,
    attempt: 1,
    maxAttempts: 1,
    failureCategory: null,
    failureReason: null,
    logTaskId: opts.logTaskId ?? `task-${opts.id}`,
  }
}

// ── Pure helpers ────────────────────────────────────────────────────────────

describe('pickDefaultStage heuristic', () => {
  it('returns null on no stages', () => {
    expect(pickDefaultStage([])).toBeNull()
  })

  it('prefers the first stage that is FAILED on the B side and present on both', () => {
    const sA = stage({ id: 'a-build', name: 'build', buildId: 1 })
    const sB = stage({ id: 'b-build', name: 'build', buildId: 2 })
    const sA2 = stage({ id: 'a-test', name: 'test', buildId: 1 })
    const sB2 = stage({ id: 'b-test', name: 'test', status: 'FAILED', buildId: 2 })
    const u = unionStages([sA, sA2], [sB, sB2])
    expect(pickDefaultStage(u)).toBe('test')
  })

  it('falls back to last common stage when nothing in B failed', () => {
    const sA1 = stage({ id: 'a1', name: 'build' })
    const sB1 = stage({ id: 'b1', name: 'build' })
    const sA2 = stage({ id: 'a2', name: 'test' })
    const sB2 = stage({ id: 'b2', name: 'test' })
    const u = unionStages([sA1, sA2], [sB1, sB2])
    expect(pickDefaultStage(u)).toBe('test')
  })

  it('falls back to first stage when none are common', () => {
    const u = unionStages(
      [stage({ id: 'a1', name: 'only-a' })],
      [stage({ id: 'b1', name: 'only-b' })],
    )
    const picked = pickDefaultStage(u)
    expect(picked === 'only-a' || picked === 'only-b').toBe(true)
  })
})

// ── lineDiff() — pure ───────────────────────────────────────────────────────

describe('lineDiff', () => {
  it('handles two empty inputs', () => {
    const r = lineDiff([], [])
    expect(r.rows).toEqual([])
    expect(r.added).toBe(0)
    expect(r.removed).toBe(0)
    expect(r.unchanged).toBe(0)
  })

  it('identical logs → all equal, no added/removed', () => {
    const lines = ['one', 'two', 'three', 'four', 'five']
    const r = lineDiff(lines, lines)
    expect(r.removed).toBe(0)
    expect(r.added).toBe(0)
    expect(r.unchanged).toBe(5)
  })

  it('B has one extra trailing line → exactly +1 added', () => {
    const a = ['one', 'two', 'three', 'four', 'five']
    const b = [...a, 'six']
    const r = lineDiff(a, b)
    expect(r.removed).toBe(0)
    expect(r.added).toBe(1)
    expect(r.unchanged).toBe(5)
    expect(r.rows[r.rows.length - 1]).toEqual({
      kind: 'added',
      text: 'six',
      aIndex: null,
      bIndex: 5,
    })
  })

  it('caps a 1000-line input at MAX_DIFF_LINES per side', () => {
    const big = Array.from({ length: 1000 }, (_, i) => `line ${i}`)
    const r = lineDiff(big, big)
    expect(r.truncated).toBe(true)
    expect(r.rows.length).toBe(MAX_DIFF_LINES)
    expect(r.unchanged).toBe(MAX_DIFF_LINES)
  })

  it('strips a single trailing blank line on each side', () => {
    const r = lineDiff(['one', 'two', ''], ['one', 'two', ''])
    expect(r.unchanged).toBe(2)
    expect(r.added + r.removed).toBe(0)
  })
})

// ── Component ───────────────────────────────────────────────────────────────

describe('<LogDiffPanel />', () => {
  it('renders "No log content to compare" when both stages have 0 lines', async () => {
    const sA = stage({ id: 'a1', name: 'build', buildId: 1 })
    const sB = stage({ id: 'b1', name: 'build', buildId: 2 })
    const fetchLogs = async () => []

    render(
      <LogDiffPanel
        buildIdA={1}
        buildIdB={2}
        nodesA={[sA]}
        nodesB={[sB]}
        fetchLogs={fetchLogs}
      />,
    )
    await waitFor(() =>
      expect(screen.getByTestId('log-diff-empty')).toBeInTheDocument(),
    )
    expect(screen.getByTestId('log-diff-empty').textContent).toMatch(
      /No log content to compare/,
    )
  })

  it('renders identical logs with 0 added / 0 removed', async () => {
    const sA = stage({ id: 'a1', name: 'build', buildId: 1 })
    const sB = stage({ id: 'b1', name: 'build', buildId: 2 })
    const same = ['line a', 'line b', 'line c', 'line d', 'line e']
    const fetchLogs = async () => same

    render(
      <LogDiffPanel
        buildIdA={1}
        buildIdB={2}
        nodesA={[sA]}
        nodesB={[sB]}
        fetchLogs={fetchLogs}
      />,
    )
    await waitFor(() =>
      expect(screen.getByTestId('log-diff-summary')).toBeInTheDocument(),
    )
    expect(screen.getByTestId('log-diff-removed-count').textContent).toMatch(
      /−0 removed/,
    )
    expect(screen.getByTestId('log-diff-added-count').textContent).toMatch(
      /\+0 added/,
    )
  })

  it('B has one extra trailing line → reports +1 added', async () => {
    const sA = stage({ id: 'a1', name: 'build', buildId: 1 })
    const sB = stage({ id: 'b1', name: 'build', buildId: 2 })
    const a = ['x', 'y', 'z', 'q', 'r']
    const b = [...a, 'extra']
    const fetchLogs = async (buildId: number) => (buildId === 1 ? a : b)

    render(
      <LogDiffPanel
        buildIdA={1}
        buildIdB={2}
        nodesA={[sA]}
        nodesB={[sB]}
        fetchLogs={fetchLogs}
      />,
    )
    await waitFor(() =>
      expect(screen.getByTestId('log-diff-summary')).toBeInTheDocument(),
    )
    expect(screen.getByTestId('log-diff-added-count').textContent).toMatch(
      /\+1 added/,
    )
    expect(screen.getByTestId('log-diff-removed-count').textContent).toMatch(
      /−0 removed/,
    )
  })

  it('stage in A but not in B → muted "no counterpart" note + solo logs', async () => {
    const sA = stage({ id: 'a1', name: 'lonely', buildId: 1 })
    const fetchLogs = async () => ['only', 'in', 'A']

    render(
      <LogDiffPanel
        buildIdA={1}
        buildIdB={2}
        nodesA={[sA]}
        nodesB={[]}
        fetchLogs={fetchLogs}
      />,
    )
    await waitFor(() =>
      expect(
        screen.getByTestId('log-diff-missing-counterpart'),
      ).toBeInTheDocument(),
    )
    const note = screen.getByTestId('log-diff-missing-counterpart')
    expect(note.textContent).toMatch(/no counterpart in build B/)
    await waitFor(() =>
      expect(screen.getByTestId('log-diff-solo')).toBeInTheDocument(),
    )
  })

  it('stage in B but not in A → muted "no counterpart" pointing to A', async () => {
    const sB = stage({ id: 'b1', name: 'lonely', buildId: 2 })
    const fetchLogs = async () => ['only', 'in', 'B']

    render(
      <LogDiffPanel
        buildIdA={1}
        buildIdB={2}
        nodesA={[]}
        nodesB={[sB]}
        fetchLogs={fetchLogs}
      />,
    )
    await waitFor(() =>
      expect(
        screen.getByTestId('log-diff-missing-counterpart'),
      ).toBeInTheDocument(),
    )
    expect(
      screen.getByTestId('log-diff-missing-counterpart').textContent,
    ).toMatch(/no counterpart in build A/)
  })

  it('huge log (1000 lines both sides) → renders within a synchronous tick and truncates', async () => {
    const sA = stage({ id: 'a1', name: 'huge', buildId: 1 })
    const sB = stage({ id: 'b1', name: 'huge', buildId: 2 })
    const big = Array.from({ length: 1000 }, (_, i) => `line ${i}`)
    const fetchLogs = async () => big

    const t0 = performance.now()
    render(
      <LogDiffPanel
        buildIdA={1}
        buildIdB={2}
        nodesA={[sA]}
        nodesB={[sB]}
        fetchLogs={fetchLogs}
      />,
    )
    await waitFor(() =>
      expect(screen.getByTestId('log-diff-truncated')).toBeInTheDocument(),
    )
    const elapsed = performance.now() - t0
    // Generous bound: jsdom synchronous diff + render of 500 lines should be
    // well under 2.5s even on a slow CI box.
    expect(elapsed).toBeLessThan(2500)
    expect(screen.getByTestId('log-diff-truncated').textContent).toMatch(
      /showing first 500 lines per side/,
    )
  })

  it('unified toggle switches to single-column view with +/- prefixes', async () => {
    const sA = stage({ id: 'a1', name: 'build', buildId: 1 })
    const sB = stage({ id: 'b1', name: 'build', buildId: 2 })
    const fetchLogs = async (buildId: number) =>
      buildId === 1 ? ['same', 'removed-line'] : ['same', 'added-line']

    render(
      <LogDiffPanel
        buildIdA={1}
        buildIdB={2}
        nodesA={[sA]}
        nodesB={[sB]}
        fetchLogs={fetchLogs}
      />,
    )
    await waitFor(() =>
      expect(screen.getByTestId('log-diff-summary')).toBeInTheDocument(),
    )
    expect(screen.queryByTestId('log-diff-unified')).toBeNull()

    const toggle = screen.getByTestId('log-diff-unified-toggle') as HTMLInputElement
    await act(async () => {
      fireEvent.click(toggle)
    })
    expect(toggle.checked).toBe(true)
    expect(screen.getByTestId('log-diff-unified')).toBeInTheDocument()
  })
})
