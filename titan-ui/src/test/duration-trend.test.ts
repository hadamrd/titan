/**
 * Unit tests for the pure duration-trend math (issue #1096).
 *
 * Covers the test-matrix "trend color logic — improving / degrading / flat",
 * plus the adversarial windows (empty, single-sample, odd-length middle drop).
 */
import { describe, it, expect } from 'vitest'
import {
  finishedDurationsSeconds,
  trendDirection,
  trendColor,
} from '../lib/durationTrend'

describe('trendDirection (issue #1096)', () => {
  it('improving — newer half faster than older half', () => {
    // older half avg = (10+10)/2 = 10, newer half avg = (4+2)/2 = 3 → improving
    expect(trendDirection([10, 10, 4, 2])).toBe('improving')
  })

  it('degrading — newer half slower than older half', () => {
    // older half avg = 3, newer half avg = 10 → degrading
    expect(trendDirection([2, 4, 10, 10])).toBe('degrading')
  })

  it('flat — both halves have equal average', () => {
    expect(trendDirection([5, 5, 5, 5])).toBe('flat')
    // Equal averages from different values must still read flat.
    expect(trendDirection([2, 8, 8, 2])).toBe('flat')
  })

  it('odd length drops the middle sample so halves stay equal-sized', () => {
    // [10, 10, <999 ignored>, 1, 1] → older avg 10, newer avg 1 → improving.
    // If the middle 999 leaked into either half the verdict would flip.
    expect(trendDirection([10, 10, 999, 1, 1])).toBe('improving')
  })

  it('adversarial: empty series is flat (no prior window)', () => {
    expect(trendDirection([])).toBe('flat')
  })

  it('adversarial: single sample is flat (nothing to compare)', () => {
    expect(trendDirection([42])).toBe('flat')
  })
})

describe('finishedDurationsSeconds (issue #1096)', () => {
  const b = (status: string, durationMs: number | null) => ({ status, durationMs })

  it('keeps only finished builds with a settled duration, ms→s, oldest→newest', () => {
    // Server order is newest-first; helper must reverse to oldest→newest.
    const builds = [
      b('SUCCESS', 2000), // newest
      b('RUNNING', null), // dropped — not finished
      b('FAILED', 4000),
      b('UNSTABLE', 1000),
      b('QUEUED', null), // dropped — not finished
      b('SUCCESS', null), // dropped — no settled duration
    ]
    expect(finishedDurationsSeconds(builds)).toEqual([1, 4, 2])
  })

  it('mixed pass/fail window — both SUCCESS and FAILED contribute points', () => {
    const builds = [b('FAILED', 10_000), b('SUCCESS', 5000)]
    expect(finishedDurationsSeconds(builds)).toEqual([5, 10])
  })

  it('adversarial: undefined / empty / all-unfinished → empty series', () => {
    expect(finishedDurationsSeconds(undefined)).toEqual([])
    expect(finishedDurationsSeconds([])).toEqual([])
    expect(finishedDurationsSeconds([b('RUNNING', null), b('QUEUED', 0)])).toEqual([])
  })

  it('zero-duration finished build is plotted (0 is a settled value, not null)', () => {
    expect(finishedDurationsSeconds([b('SUCCESS', 0)])).toEqual([0])
  })
})

describe('trendColor (issue #1096)', () => {
  it('improving → green token', () => {
    expect(trendColor([10, 10, 1, 1])).toBe('var(--ok)')
  })

  it('degrading → red token', () => {
    expect(trendColor([1, 1, 10, 10])).toBe('var(--fail)')
  })

  it('flat → red token ("red otherwise" in the acceptance criterion)', () => {
    expect(trendColor([5, 5, 5, 5])).toBe('var(--fail)')
  })
})
