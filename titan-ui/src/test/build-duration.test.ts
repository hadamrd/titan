/**
 * Regression guard for the build-detail duration formatter (#35).
 *
 * Before this fix, opening a QUEUED build at /builds/<id> rendered
 * `Duration: NaNm NaNs` because the formatter ran arithmetic on null
 * timestamps. We now hard-reject null/non-finite/negative paths to `—`.
 */
import { describe, expect, it, vi, afterEach } from 'vitest'
import { formatBuildDuration, formatDuration } from '@/routes/builds/$buildId'

describe('formatDuration (explicit ms)', () => {
  it('returns em-dash for null', () => {
    expect(formatDuration(null)).toBe('—')
  })

  it('returns em-dash for undefined', () => {
    expect(formatDuration(undefined)).toBe('—')
  })

  it('returns em-dash for negative', () => {
    expect(formatDuration(-1)).toBe('—')
  })

  it('returns em-dash for NaN', () => {
    expect(formatDuration(NaN)).toBe('—')
  })

  it('formats sub-minute as Xs', () => {
    expect(formatDuration(45_000)).toBe('45s')
  })

  it('formats minutes + seconds', () => {
    expect(formatDuration(605_000)).toBe('10m 5s')
  })
})

describe('formatBuildDuration (from timestamps)', () => {
  afterEach(() => {
    vi.useRealTimers()
  })

  it('returns em-dash when never started (QUEUED build)', () => {
    expect(formatBuildDuration(null, null)).toBe('—')
  })

  it('returns em-dash when startedAt is undefined', () => {
    expect(formatBuildDuration(undefined, undefined)).toBe('—')
  })

  it('uses Date.now() as the end when build is still running', () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-05-23T10:10:30Z'))
    expect(formatBuildDuration('2026-05-23T10:10:00Z', null)).toBe('30s')
  })

  it('uses finishedAt for terminal builds', () => {
    expect(formatBuildDuration('2026-05-23T10:00:00Z', '2026-05-23T10:10:00Z')).toBe('10m 0s')
  })

  it('returns em-dash when startedAt is unparseable', () => {
    expect(formatBuildDuration('not-a-date', null)).toBe('—')
  })
})
