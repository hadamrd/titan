/**
 * Unit tests for {@link partitionBuilds} — the /builds in-flight / history
 * split predicate. Pinned by #917's acceptance criteria: every non-terminal
 * status (QUEUED, RUNNING, PAUSED, anything not in {SUCCESS, FAILED,
 * ABORTED, UNSTABLE}) lands in `inFlight`; every terminal lands in
 * `history`. Order MUST be preserved within each bucket so the page keeps
 * the server's "newest-first" ordering inside each section.
 *
 * Adversarial cases covered:
 *   - empty input → both buckets empty (no header rendered downstream)
 *   - all-terminal input → inFlight is `[]` (no header rendered downstream)
 *   - all-in-flight input → history is `[]` (no separator rendered)
 *   - mixed input → original ordering preserved in each bucket
 *   - an unknown / future status string is treated as in-flight (fail-safe
 *     surface bias: if we don't know it's terminal, keep it visible).
 */
import { describe, it, expect } from 'vitest'
import { partitionBuilds } from '../partitionBuilds'
import type { BuildStatus } from '@/api/types'

interface Row {
  id: number
  status: BuildStatus
}

const r = (id: number, status: BuildStatus): Row => ({ id, status })

describe('partitionBuilds', () => {
  it('returns empty buckets for an empty list', () => {
    const out = partitionBuilds<Row>([])
    expect(out.inFlight).toEqual([])
    expect(out.history).toEqual([])
  })

  it('sends QUEUED and RUNNING into inFlight; terminal into history', () => {
    const input: Row[] = [
      r(1, 'RUNNING'),
      r(2, 'SUCCESS'),
      r(3, 'QUEUED'),
      r(4, 'FAILED'),
      r(5, 'ABORTED'),
      r(6, 'UNSTABLE'),
    ]
    const out = partitionBuilds(input)
    expect(out.inFlight.map((b) => b.id)).toEqual([1, 3])
    expect(out.history.map((b) => b.id)).toEqual([2, 4, 5, 6])
  })

  it('preserves the input order within each bucket', () => {
    const input: Row[] = [
      r(10, 'SUCCESS'),
      r(9, 'RUNNING'),
      r(8, 'SUCCESS'),
      r(7, 'RUNNING'),
      r(6, 'QUEUED'),
    ]
    const out = partitionBuilds(input)
    expect(out.inFlight.map((b) => b.id)).toEqual([9, 7, 6])
    expect(out.history.map((b) => b.id)).toEqual([10, 8])
  })

  it('all-terminal input leaves inFlight empty', () => {
    const input: Row[] = [r(1, 'SUCCESS'), r(2, 'FAILED'), r(3, 'ABORTED')]
    const out = partitionBuilds(input)
    expect(out.inFlight).toEqual([])
    expect(out.history.length).toBe(3)
  })

  it('all-in-flight input leaves history empty (no separator rendered)', () => {
    const input: Row[] = [r(1, 'RUNNING'), r(2, 'QUEUED')]
    const out = partitionBuilds(input)
    expect(out.history).toEqual([])
    expect(out.inFlight.length).toBe(2)
  })

  it('treats an unknown future status as in-flight (fail-safe surface bias)', () => {
    // A backend that one day adds PAUSED before the UI knows about it should
    // still surface the build in the sticky top section — losing visibility
    // is the worse failure mode than over-pinning.
    const input = [
      { id: 1, status: 'PAUSED' as unknown as BuildStatus },
      { id: 2, status: 'SUCCESS' as BuildStatus },
    ]
    const out = partitionBuilds(input)
    expect(out.inFlight.map((b) => b.id)).toEqual([1])
    expect(out.history.map((b) => b.id)).toEqual([2])
  })
})
