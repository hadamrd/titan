/**
 * Adversarial tests for the build-comparison view (closes #716).
 *
 * Failure modes that matter:
 *  1. Same build vs itself → every delta is 0/'=', no NaN, no crashes
 *  2. Different jobs → muted '(different jobs — comparison may not be
 *     meaningful)' banner present, table still renders
 *  3. Stage in A absent in B → row shows A's value + '—' on B, delta '—'
 *  4. Stage in B absent in A → '—' on A side + B's value, delta '—'
 *  5. Empty stage list both sides → muted empty-state placeholder
 *
 * Plus a happy-path delta check (B slower than A → '+45s').
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import {
  BuildCompareView,
  classifyStatusFlip,
  pairStagesByName,
} from '../components/BuildCompareView'
import type { ArtifactDto, BuildDto, FlowNodeDto } from '../api/types'

function artifact(name: string, size: number, sha = `sha-${name}`): ArtifactDto {
  return {
    id: name.charCodeAt(0),
    name,
    sizeBytes: size,
    sha256: sha,
    uploadedAt: '2026-05-24T11:00:00Z',
    downloadUrl: `/api/v1/artifacts/${name}/download`,
  }
}

function build(opts: Partial<BuildDto> & { id: number; jobId: number }): BuildDto {
  return {
    buildNumber: opts.id,
    status: 'SUCCESS',
    triggeredBy: 'tester',
    triggerType: 'manual',
    queuedAt: '2026-05-24T11:00:00Z',
    startedAt: '2026-05-24T11:00:01Z',
    finishedAt: '2026-05-24T11:00:31Z',
    durationMs: 30_000,
    errorMessage: null,
    failureSummary: null,
    ...opts,
  }
}

function stage(opts: {
  id: string
  name: string
  status?: string
  durationMs?: number | null
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
    durationMs: opts.durationMs ?? null,
    attempt: 1,
    maxAttempts: 1,
    failureCategory: null,
    failureReason: null,
    logTaskId: null,
  }
}

describe('BuildCompareView', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-05-24T12:00:00Z'))
  })
  afterEach(() => {
    vi.useRealTimers()
  })

  it('same build vs itself → all deltas equal, no NaN, no crash', () => {
    const a = build({ id: 41, jobId: 1 })
    const nodes: FlowNodeDto[] = [
      stage({ id: 's1', name: 'build', durationMs: 5_000 }),
      stage({ id: 's2', name: 'test', durationMs: 60_000 }),
    ]
    render(
      <BuildCompareView buildA={a} buildB={a} nodesA={nodes} nodesB={nodes} />,
    )
    // Both deltas read '=' (zero delta).
    expect(screen.getByTestId('compare-delta-build')).toHaveTextContent('=')
    expect(screen.getByTestId('compare-delta-test')).toHaveTextContent('=')
    // No NaN anywhere on screen — guard against accidental Number(undefined) math.
    expect(screen.getByTestId('build-compare-view').textContent).not.toMatch(/NaN/)
    // Both rows render with no status-flip wash.
    expect(screen.getByTestId('compare-row-build')).toHaveAttribute('data-flip', 'none')
    expect(screen.getByTestId('compare-row-test')).toHaveAttribute('data-flip', 'none')
    // Same job → no cross-job warning.
    expect(screen.queryByTestId('compare-cross-job-warning')).not.toBeInTheDocument()
  })

  it('different jobs → warning banner present, table still renders', () => {
    const a = build({ id: 100, jobId: 1 })
    const b = build({ id: 200, jobId: 2 })
    const nodes: FlowNodeDto[] = [stage({ id: 's', name: 'build', durationMs: 5_000 })]
    render(
      <BuildCompareView buildA={a} buildB={b} nodesA={nodes} nodesB={nodes} />,
    )
    const banner = screen.getByTestId('compare-cross-job-warning')
    expect(banner).toBeInTheDocument()
    expect(banner.textContent).toMatch(/different jobs/i)
    // Table still rendered — the warning does not pre-empt the diff.
    expect(screen.getByTestId('compare-stages-table')).toBeInTheDocument()
    expect(screen.getByTestId('compare-row-build')).toBeInTheDocument()
  })

  it('stage in A absent in B (removed) → row shows A value + em-dash on B; delta em-dash', () => {
    const a = build({ id: 1, jobId: 1 })
    const b = build({ id: 2, jobId: 1 })
    const nodesA: FlowNodeDto[] = [
      stage({ id: 'a-1', name: 'lint', status: 'SUCCESS', durationMs: 3_000 }),
      stage({ id: 'a-2', name: 'build', status: 'SUCCESS', durationMs: 5_000 }),
    ]
    const nodesB: FlowNodeDto[] = [
      // 'lint' removed in B.
      stage({ id: 'b-2', name: 'build', status: 'SUCCESS', durationMs: 5_000 }),
    ]
    render(
      <BuildCompareView buildA={a} buildB={b} nodesA={nodesA} nodesB={nodesB} />,
    )
    // A-only stage row: B cell is em-dash, delta em-dash.
    const aCell = screen.getByTestId('compare-cell-a-lint')
    const bCell = screen.getByTestId('compare-cell-b-lint')
    const delta = screen.getByTestId('compare-delta-lint')
    expect(aCell.textContent).toMatch(/SUCCESS/)
    expect(bCell.textContent?.trim()).toBe('—')
    expect(delta.textContent?.trim()).toBe('—')
    // Sibling row still paired up.
    expect(screen.getByTestId('compare-delta-build').textContent?.trim()).toBe('=')
  })

  it('stage in B absent in A (added) → em-dash on A + B value', () => {
    const a = build({ id: 1, jobId: 1 })
    const b = build({ id: 2, jobId: 1 })
    const nodesA: FlowNodeDto[] = [
      stage({ id: 'a-1', name: 'build', durationMs: 5_000 }),
    ]
    const nodesB: FlowNodeDto[] = [
      stage({ id: 'b-1', name: 'build', durationMs: 5_000 }),
      stage({ id: 'b-2', name: 'deploy', status: 'SUCCESS', durationMs: 4_000 }),
    ]
    render(
      <BuildCompareView buildA={a} buildB={b} nodesA={nodesA} nodesB={nodesB} />,
    )
    const aCell = screen.getByTestId('compare-cell-a-deploy')
    const bCell = screen.getByTestId('compare-cell-b-deploy')
    const delta = screen.getByTestId('compare-delta-deploy')
    expect(aCell.textContent?.trim()).toBe('—')
    expect(bCell.textContent).toMatch(/SUCCESS/)
    expect(delta.textContent?.trim()).toBe('—')
  })

  it('empty stage list on both → muted empty-state placeholder', () => {
    const a = build({ id: 1, jobId: 1 })
    const b = build({ id: 2, jobId: 1 })
    render(<BuildCompareView buildA={a} buildB={b} nodesA={[]} nodesB={[]} />)
    expect(screen.getByTestId('compare-stages-empty')).toBeInTheDocument()
    expect(screen.queryByTestId('compare-stages-table')).not.toBeInTheDocument()
  })

  it('B slower than A → delta reads "+Ns"; A slower → "-Ns"', () => {
    const a = build({ id: 1, jobId: 1 })
    const b = build({ id: 2, jobId: 1 })
    const nodesA: FlowNodeDto[] = [
      stage({ id: 'a-s', name: 'slow', durationMs: 30_000 }),
      stage({ id: 'a-f', name: 'fast', durationMs: 50_000 }),
    ]
    const nodesB: FlowNodeDto[] = [
      stage({ id: 'b-s', name: 'slow', durationMs: 75_000 }),
      stage({ id: 'b-f', name: 'fast', durationMs: 38_000 }),
    ]
    render(
      <BuildCompareView buildA={a} buildB={b} nodesA={nodesA} nodesB={nodesB} />,
    )
    // B took 45s longer on 'slow'.
    expect(screen.getByTestId('compare-delta-slow')).toHaveTextContent('+45s')
    // B was 12s faster on 'fast'.
    expect(screen.getByTestId('compare-delta-fast')).toHaveTextContent('-12s')
  })

  it('status regression (✓→✗) gets the regression flip marker', () => {
    const a = build({ id: 1, jobId: 1, status: 'SUCCESS' })
    const b = build({ id: 2, jobId: 1, status: 'FAILED' })
    const nodesA: FlowNodeDto[] = [
      stage({ id: 'a', name: 'deploy', status: 'SUCCESS', durationMs: 10_000 }),
    ]
    const nodesB: FlowNodeDto[] = [
      stage({ id: 'b', name: 'deploy', status: 'FAILED', durationMs: 12_000 }),
    ]
    render(
      <BuildCompareView buildA={a} buildB={b} nodesA={nodesA} nodesB={nodesB} />,
    )
    expect(screen.getByTestId('compare-row-deploy')).toHaveAttribute(
      'data-flip',
      'regression',
    )
    expect(screen.getByTestId('compare-delta-deploy').textContent).toContain('✓→✗')
  })

  it('A-only stage labels the delta cell "Removed in B" with presence marker (#1077)', () => {
    const a = build({ id: 1, jobId: 1 })
    const b = build({ id: 2, jobId: 1 })
    const nodesA: FlowNodeDto[] = [
      stage({ id: 'a-lint', name: 'lint', durationMs: 3_000 }),
      stage({ id: 'a-build', name: 'build', durationMs: 5_000 }),
    ]
    const nodesB: FlowNodeDto[] = [
      stage({ id: 'b-build', name: 'build', durationMs: 5_000 }),
    ]
    render(<BuildCompareView buildA={a} buildB={b} nodesA={nodesA} nodesB={nodesB} />)
    const row = screen.getByTestId('compare-row-lint')
    expect(row).toHaveAttribute('data-presence', 'removed-in-b')
    expect(screen.getByTestId('compare-delta-lint').textContent).toMatch(/Removed in B/)
  })

  it('B-only stage labels the delta cell "Added in B" with presence marker (#1077)', () => {
    const a = build({ id: 1, jobId: 1 })
    const b = build({ id: 2, jobId: 1 })
    const nodesA: FlowNodeDto[] = [stage({ id: 'a-build', name: 'build', durationMs: 5_000 })]
    const nodesB: FlowNodeDto[] = [
      stage({ id: 'b-build', name: 'build', durationMs: 5_000 }),
      stage({ id: 'b-deploy', name: 'deploy', durationMs: 4_000 }),
    ]
    render(<BuildCompareView buildA={a} buildB={b} nodesA={nodesA} nodesB={nodesB} />)
    const row = screen.getByTestId('compare-row-deploy')
    expect(row).toHaveAttribute('data-presence', 'added-in-b')
    expect(screen.getByTestId('compare-delta-deploy').textContent).toMatch(/Added in B/)
  })

  it('same-build notice surfaces when sameBuild=true (#1077)', () => {
    const a = build({ id: 1, jobId: 1, buildNumber: 41 })
    render(
      <BuildCompareView
        buildA={a}
        buildB={a}
        nodesA={[]}
        nodesB={[]}
        sameBuild
      />,
    )
    const notice = screen.getByTestId('compare-same-build-notice')
    expect(notice).toBeInTheDocument()
    expect(notice.textContent).toMatch(/with itself/i)
    expect(notice.textContent).toMatch(/#41/)
  })

  it('artifact diff renders added/removed/changed/unchanged rows with sha indicators (#1077)', () => {
    const a = build({ id: 1, jobId: 1 })
    const b = build({ id: 2, jobId: 1 })
    const artsA = [
      artifact('dist.tar.gz', 1_000_000, 'sha-old'),
      artifact('shared.zip', 500, 'sha-shared'),
      artifact('removed-only.log', 200, 'sha-removed'),
    ]
    const artsB = [
      artifact('dist.tar.gz', 1_200_000, 'sha-new'),
      artifact('shared.zip', 500, 'sha-shared'),
      artifact('added-only.txt', 42, 'sha-added'),
    ]
    render(
      <BuildCompareView
        buildA={a}
        buildB={b}
        nodesA={[]}
        nodesB={[]}
        artifactsA={artsA}
        artifactsB={artsB}
      />,
    )
    // Section is present (open by default when there are rows).
    expect(screen.getByTestId('compare-artifacts-table')).toBeInTheDocument()
    // sha mismatch on dist (size grew).
    expect(screen.getByTestId('compare-artifact-row-dist.tar.gz')).toHaveAttribute(
      'data-kind',
      'changed',
    )
    expect(
      screen.getByTestId('compare-artifact-sha-mismatch-dist.tar.gz'),
    ).toBeInTheDocument()
    // sha match on shared.zip.
    expect(screen.getByTestId('compare-artifact-row-shared.zip')).toHaveAttribute(
      'data-kind',
      'unchanged',
    )
    expect(
      screen.getByTestId('compare-artifact-sha-match-shared.zip'),
    ).toBeInTheDocument()
    // Added + removed presence rows.
    expect(screen.getByTestId('compare-artifact-row-added-only.txt')).toHaveAttribute(
      'data-kind',
      'added',
    )
    expect(screen.getByTestId('compare-artifact-row-removed-only.log')).toHaveAttribute(
      'data-kind',
      'removed',
    )
  })

  it('artifact section shows friendly empty state when neither build published any (#1077)', () => {
    const a = build({ id: 1, jobId: 1 })
    const b = build({ id: 2, jobId: 1 })
    render(<BuildCompareView buildA={a} buildB={b} nodesA={[]} nodesB={[]} />)
    expect(screen.getByTestId('compare-artifacts-empty')).toBeInTheDocument()
  })

  it('per-side summary cards render build number, status, branch, commit', () => {
    const a = build({
      id: 1,
      jobId: 1,
      buildNumber: 41,
      status: 'SUCCESS',
      triggerMeta: { branch: 'main', commitSha: 'deadbeef1234', actor: 'alice' },
    })
    const b = build({
      id: 2,
      jobId: 1,
      buildNumber: 42,
      status: 'FAILED',
      triggerMeta: { branch: 'main', commitSha: 'cafef00d5678', actor: 'bob' },
    })
    render(<BuildCompareView buildA={a} buildB={b} nodesA={[]} nodesB={[]} />)
    const aCard = screen.getByTestId('compare-summary-a')
    const bCard = screen.getByTestId('compare-summary-b')
    expect(within(aCard).getByText('#41')).toBeInTheDocument()
    expect(within(bCard).getByText('#42')).toBeInTheDocument()
    // Short SHAs (7 chars).
    expect(within(aCard).getByText('deadbee')).toBeInTheDocument()
    expect(within(bCard).getByText('cafef00')).toBeInTheDocument()
  })
})

// ── Pure-function units (no DOM) ─────────────────────────────────────────────

describe('pairStagesByName', () => {
  it('preserves A declared order then appends B-only stages', () => {
    const aN = [
      stage({ id: 'a1', name: 'lint' }),
      stage({ id: 'a2', name: 'build' }),
    ]
    const bN = [
      stage({ id: 'b1', name: 'build' }),
      stage({ id: 'b2', name: 'deploy' }),
    ]
    const rows = pairStagesByName(aN, bN)
    expect(rows.map((r) => r.name)).toEqual(['lint', 'build', 'deploy'])
    expect(rows[0]!.b).toBeNull() // lint only in A
    expect(rows[2]!.a).toBeNull() // deploy only in B
  })

  it('ignores non-stage nodes', () => {
    const aN = [
      stage({ id: 'a1', name: 'sh: echo' }),
      { ...stage({ id: 'a2', name: 'step' }), nodeType: 'STEP' } as FlowNodeDto,
    ]
    const bN = [stage({ id: 'b1', name: 'sh: echo' })]
    const rows = pairStagesByName(aN, bN)
    expect(rows.map((r) => r.name)).toEqual(['sh: echo'])
  })
})

describe('classifyStatusFlip', () => {
  it.each([
    ['SUCCESS', 'FAILED', 'regression'],
    ['SUCCESS', 'ABORTED', 'regression'],
    ['SUCCESS', 'UNSTABLE', 'regression'],
    ['FAILED', 'SUCCESS', 'recovery'],
    ['ABORTED', 'SUCCESS', 'recovery'],
    ['SUCCESS', 'SUCCESS', 'none'],
    ['FAILED', 'FAILED', 'none'],
    ['RUNNING', 'SUCCESS', 'none'],
  ])('classifies %s→%s as %s', (a, b, expected) => {
    expect(classifyStatusFlip(a, b)).toBe(expected)
  })
})
