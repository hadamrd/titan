/**
 * Unit tests for the shared list status→variant map (#1190).
 *
 * The map is the cross-page discriminator: workers / queue / approvals / audit
 * all read status colour from these functions. Adversarial-first — every
 * threshold boundary + the graceful-degrade (bad ISO) case is asserted, not
 * just the happy middle.
 */
import { describe, expect, it } from 'vitest'
import {
  auditActionBadge,
  expirySeverity,
  priorityBadge,
  severityToBadge,
  severityToDot,
  waitSeverity,
  workerStatusView,
  WAIT_FAIL_MS,
  WAIT_WARN_MS,
} from '../lib/listStatus'

describe('waitSeverity — pressure thresholds', () => {
  it('is ok below the warn threshold', () => {
    expect(waitSeverity(0)).toBe('ok')
    expect(waitSeverity(WAIT_WARN_MS - 1)).toBe('ok')
  })
  it('flips to warn exactly at the warn threshold (boundary)', () => {
    expect(waitSeverity(WAIT_WARN_MS)).toBe('warn')
    expect(waitSeverity(WAIT_FAIL_MS - 1)).toBe('warn')
  })
  it('flips to fail exactly at the fail threshold (boundary)', () => {
    expect(waitSeverity(WAIT_FAIL_MS)).toBe('fail')
    expect(waitSeverity(WAIT_FAIL_MS * 10)).toBe('fail')
  })
})

describe('expirySeverity — relative to a fixed now', () => {
  const NOW = Date.parse('2026-06-04T12:00:00Z')
  it('is fail when already expired (sad path: past instant)', () => {
    expect(expirySeverity('2026-06-04T11:59:59Z', NOW)).toBe('fail')
  })
  it('is warn when expiring within a minute', () => {
    expect(expirySeverity('2026-06-04T12:00:30Z', NOW)).toBe('warn')
  })
  it('is ok when comfortably in the future', () => {
    expect(expirySeverity('2026-06-04T13:00:00Z', NOW)).toBe('ok')
  })
  it('degrades to ok on an unparseable ISO instead of throwing (adversarial)', () => {
    expect(expirySeverity('not-a-date', NOW)).toBe('ok')
  })
})

describe('severity → variant maps', () => {
  it('maps dot variants', () => {
    expect(severityToDot('ok')).toBe('success')
    expect(severityToDot('warn')).toBe('warn')
    expect(severityToDot('fail')).toBe('fail')
  })
  it('maps badge variants', () => {
    expect(severityToBadge('ok')).toBe('success')
    expect(severityToBadge('warn')).toBe('warn')
    expect(severityToBadge('fail')).toBe('fail')
  })
})

describe('workerStatusView — lifecycle mapping', () => {
  it('OFFLINE → cancelled', () => {
    expect(workerStatusView('OFFLINE', 0)).toEqual({ variant: 'cancelled', label: 'OFFLINE' })
  })
  it('DRAINING → queued even with in-flight tasks (order matters, adversarial)', () => {
    expect(workerStatusView('DRAINING', 3)).toEqual({ variant: 'queued', label: 'DRAINING' })
  })
  it('ONLINE with tasks → BUSY/running', () => {
    expect(workerStatusView('ONLINE', 1)).toEqual({ variant: 'running', label: 'BUSY' })
  })
  it('ONLINE idle → ONLINE/success', () => {
    expect(workerStatusView('ONLINE', 0)).toEqual({ variant: 'success', label: 'ONLINE' })
  })
})

describe('priorityBadge — lower number = higher priority', () => {
  it('P0 and below is high (fail)', () => {
    expect(priorityBadge(0)).toBe('fail')
    expect(priorityBadge(-1)).toBe('fail')
  })
  it('mid range is neutral', () => {
    expect(priorityBadge(3)).toBe('default')
  })
  it('P5 and above is low (neutral)', () => {
    expect(priorityBadge(5)).toBe('default')
  })
})

describe('auditActionBadge — action families', () => {
  it('scope-denied is fail', () => {
    expect(auditActionBadge('PAT_SCOPE_DENIED')).toBe('fail')
  })
  it('revoke / abort are warn', () => {
    expect(auditActionBadge('PAT_REVOKE')).toBe('warn')
    expect(auditActionBadge('BUILD_ABORT')).toBe('warn')
  })
  it('create / trigger are info', () => {
    expect(auditActionBadge('JOB_CREATE')).toBe('info')
    expect(auditActionBadge('BUILD_TRIGGER')).toBe('info')
  })
  it('everything else is neutral default', () => {
    expect(auditActionBadge('JOB_UPDATE')).toBe('default')
  })
})
