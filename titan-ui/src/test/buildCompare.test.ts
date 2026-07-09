/**
 * Pure-helper unit tests for the build-comparison diff layer (#1077).
 *
 * These cover the diff functions in {@code @/lib/buildCompare} in isolation
 * from React — they're the algorithmic core of the comparison page and need
 * adversarial-grade coverage:
 *  - durationDelta: null inputs, zero, large minute-spanning deltas
 *  - artifactDiff: added / removed / changed / unchanged (sha + size matrix)
 *  - logPatternDiff: timestamps + UUIDs + numeric noise collapse to common
 *  - stageDiff: added / removed / regression / recovery / step-count delta
 *  - normaliseLogLine: each noise category is canonicalised
 */
import { describe, it, expect } from 'vitest'
import type { ArtifactDto, FlowNodeDto } from '../api/types'
import {
  artifactDiff,
  classifyStageStatus,
  durationDelta,
  formatBytes,
  formatSizeDelta,
  logPatternDiff,
  normaliseLogLine,
  stageDiff,
} from '../lib/buildCompare'

function art(name: string, size: number, sha = `sha-${name}`): ArtifactDto {
  return {
    id: name.charCodeAt(0),
    name,
    sizeBytes: size,
    sha256: sha,
    uploadedAt: '2026-05-24T11:00:00Z',
    downloadUrl: `/api/v1/artifacts/${name}/download`,
  }
}

function stage(
  name: string,
  opts: { status?: string; durationMs?: number | null; nodeId?: string } = {},
): FlowNodeDto {
  return {
    buildId: 1,
    nodeId: opts.nodeId ?? name,
    parentIds: null,
    nodeType: 'STAGE',
    displayName: name,
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

describe('durationDelta', () => {
  it('returns null/em-dash when either input is missing', () => {
    expect(durationDelta(null, 5_000)).toEqual({ deltaMs: null, label: '—' })
    expect(durationDelta(5_000, null)).toEqual({ deltaMs: null, label: '—' })
    expect(durationDelta(undefined, undefined)).toEqual({ deltaMs: null, label: '—' })
    expect(durationDelta(Number.NaN, 5)).toEqual({ deltaMs: null, label: '—' })
  })

  it('returns "=" for a zero delta', () => {
    expect(durationDelta(5_000, 5_000).label).toBe('=')
    expect(durationDelta(0, 0).label).toBe('=')
  })

  it('signs positive when B is slower than A', () => {
    expect(durationDelta(10_000, 22_500).label).toBe('+12.5s')
  })

  it('signs negative when A is slower than B', () => {
    expect(durationDelta(30_000, 18_000).label).toBe('-12s')
  })

  it('formats minute-spanning deltas as M:SS', () => {
    // 90s = 1:30
    expect(durationDelta(0, 90_000).label).toBe('+1:30')
    // 5m exactly → 5:00
    expect(durationDelta(0, 300_000).label).toBe('+5:00')
  })

  it('returns numeric deltaMs even when label is "="', () => {
    expect(durationDelta(7, 7).deltaMs).toBe(0)
  })
})

describe('artifactDiff', () => {
  it('marks shared artifacts with matching sha as unchanged', () => {
    const rows = artifactDiff([art('dist.tar.gz', 1024, 'sha-x')], [art('dist.tar.gz', 1024, 'sha-x')])
    expect(rows).toHaveLength(1)
    expect(rows[0]!.kind).toBe('unchanged')
    expect(rows[0]!.shaMatch).toBe(true)
    expect(rows[0]!.sizeDeltaBytes).toBe(0)
  })

  it('marks shared artifacts with differing sha as changed (regardless of size match)', () => {
    const rows = artifactDiff(
      [art('dist.tar.gz', 1024, 'sha-old')],
      [art('dist.tar.gz', 1024, 'sha-new')],
    )
    expect(rows[0]!.kind).toBe('changed')
    expect(rows[0]!.shaMatch).toBe(false)
    expect(rows[0]!.sizeDeltaBytes).toBe(0)
  })

  it('flags shaMatch=null (not false) when one side is missing', () => {
    const addedRows = artifactDiff([], [art('new.zip', 200)])
    expect(addedRows[0]!.kind).toBe('added')
    expect(addedRows[0]!.shaMatch).toBeNull()
    expect(addedRows[0]!.sizeDeltaBytes).toBeNull()

    const removedRows = artifactDiff([art('old.zip', 200)], [])
    expect(removedRows[0]!.kind).toBe('removed')
    expect(removedRows[0]!.shaMatch).toBeNull()
  })

  it('preserves A order then appends B-only artifacts', () => {
    const rows = artifactDiff(
      [art('a.txt', 1), art('shared.bin', 10)],
      [art('shared.bin', 10), art('z.txt', 1)],
    )
    expect(rows.map((r) => r.name)).toEqual(['a.txt', 'shared.bin', 'z.txt'])
  })

  it('returns empty array when both sides are empty', () => {
    expect(artifactDiff([], [])).toEqual([])
  })
})

describe('formatBytes / formatSizeDelta', () => {
  it('formats bytes across B/KB/MB ranges', () => {
    expect(formatBytes(0)).toBe('0 B')
    expect(formatBytes(999)).toBe('999 B')
    expect(formatBytes(1_500)).toBe('1.5 KB')
    expect(formatBytes(2_500_000)).toBe('2.5 MB')
  })

  it('signs and formats size deltas; "=" on zero; em-dash on null', () => {
    expect(formatSizeDelta(0)).toBe('=')
    expect(formatSizeDelta(null)).toBe('—')
    expect(formatSizeDelta(500)).toBe('+500 B')
    expect(formatSizeDelta(-1_500)).toBe('-1.5 KB')
    expect(formatSizeDelta(3_500_000)).toBe('+3.5 MB')
  })
})

describe('logPatternDiff + normaliseLogLine', () => {
  it('collapses ISO timestamps, UUIDs and hex blobs into <ts>/<uuid>/<hex>', () => {
    const a = normaliseLogLine(
      '2026-05-24T11:12:13.456Z task-1234567890abcdef started for 550e8400-e29b-41d4-a716-446655440000',
    )
    const b = normaliseLogLine(
      '2026-05-25T09:08:07.111Z task-fedcba9876543210 started for 11111111-2222-3333-4444-555555555555',
    )
    expect(a).toBe(b)
  })

  it('collapses bracket-prefixed times', () => {
    const a = normaliseLogLine('[12:34:56] step done')
    const b = normaliseLogLine('[01:02:03.456] step done')
    expect(a).toBe(b)
    expect(a).toContain('<ts>')
  })

  it('counts common lines as common, side-only lines correctly', () => {
    const aLines = [
      '2026-05-24T11:00:00Z compiling main.ts',
      '2026-05-24T11:00:01Z tests passed: 421',
      'only-in-a: noisy debug',
    ]
    const bLines = [
      '2026-05-25T08:30:00Z compiling main.ts',
      '2026-05-25T08:30:01Z tests passed: 995',
      'only-in-b: deploy summary',
    ]
    // The first two lines collapse to the same patterns ("compiling main.ts",
    // "tests passed: <num>") after normalisation; the last on each side is
    // unique. Note: normaliseLogLine deliberately only collapses 3+-digit
    // runs, so meaningful small integers ("1 file changed") survive.
    const { aOnly, bOnly, common } = logPatternDiff(aLines, bLines)
    expect(common).toBe(2)
    expect(aOnly).toBe(1)
    expect(bOnly).toBe(1)
  })

  it('de-duplicates within a side before counting', () => {
    const r = logPatternDiff(['x', 'x', 'x', 'y'], ['x', 'z', 'z'])
    expect(r.common).toBe(1) // 'x'
    expect(r.aOnly).toBe(1) // 'y'
    expect(r.bOnly).toBe(1) // 'z'
  })

  it('handles empty inputs', () => {
    expect(logPatternDiff([], [])).toEqual({ aOnly: 0, bOnly: 0, common: 0 })
    expect(logPatternDiff(['hello'], [])).toEqual({ aOnly: 1, bOnly: 0, common: 0 })
    expect(logPatternDiff([], ['hello'])).toEqual({ aOnly: 0, bOnly: 1, common: 0 })
  })
})

describe('classifyStageStatus', () => {
  it.each([
    ['SUCCESS', 'FAILED', 'regression'],
    ['SUCCESS', 'ABORTED', 'regression'],
    ['SUCCESS', 'UNSTABLE', 'regression'],
    ['FAILED', 'SUCCESS', 'recovery'],
    ['SUCCESS', 'SUCCESS', 'unchanged'],
    ['FAILED', 'FAILED', 'unchanged'],
    ['RUNNING', 'SUCCESS', 'unchanged'],
  ])('%s → %s = %s', (a, b, expected) => {
    expect(classifyStageStatus(a, b)).toBe(expected)
  })
})

describe('stageDiff', () => {
  it('emits added / removed kinds for one-sided stages', () => {
    const aN = [stage('build', { durationMs: 5_000 }), stage('lint', { durationMs: 2_000 })]
    const bN = [stage('build', { durationMs: 6_000 }), stage('deploy', { durationMs: 4_000 })]
    const rows = stageDiff(aN, bN)
    expect(rows.map((r) => r.name)).toEqual(['build', 'lint', 'deploy'])
    expect(rows.find((r) => r.name === 'build')!.kind).toBe('unchanged')
    expect(rows.find((r) => r.name === 'lint')!.kind).toBe('removed')
    expect(rows.find((r) => r.name === 'deploy')!.kind).toBe('added')
  })

  it('marks regression and recovery flips', () => {
    const aN = [stage('deploy', { status: 'SUCCESS', durationMs: 10_000 })]
    const bN = [stage('deploy', { status: 'FAILED', durationMs: 11_000 })]
    expect(stageDiff(aN, bN)[0]!.kind).toBe('regression')
    expect(stageDiff(bN, aN)[0]!.kind).toBe('recovery')
  })

  it('computes step-count delta from parentIds membership', () => {
    const aN: FlowNodeDto[] = [
      stage('build', { nodeId: 'st-a' }),
      // Two steps under build A.
      { ...stage('s1'), nodeType: 'STEP', nodeId: 's1-a', parentIds: 'st-a' },
      { ...stage('s2'), nodeType: 'STEP', nodeId: 's2-a', parentIds: 'st-a' },
    ]
    const bN: FlowNodeDto[] = [
      stage('build', { nodeId: 'st-b' }),
      // Four steps under build B.
      { ...stage('s1'), nodeType: 'STEP', nodeId: 's1-b', parentIds: 'st-b' },
      { ...stage('s2'), nodeType: 'STEP', nodeId: 's2-b', parentIds: 'st-b' },
      { ...stage('s3'), nodeType: 'STEP', nodeId: 's3-b', parentIds: 'st-b' },
      { ...stage('s4'), nodeType: 'STEP', nodeId: 's4-b', parentIds: 'st-b' },
    ]
    const row = stageDiff(aN, bN).find((r) => r.name === 'build')!
    expect(row.stepCountDelta).toBe(2)
  })

  it('carries durationDelta into the row', () => {
    const aN = [stage('test', { durationMs: 30_000 })]
    const bN = [stage('test', { durationMs: 42_500 })]
    expect(stageDiff(aN, bN)[0]!.duration.label).toBe('+12.5s')
  })
})
