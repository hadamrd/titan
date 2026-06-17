/**
 * Shared status → variant mappings for the operator data-list pages
 * (workers / queue / approvals / audit). Extracted for #1190 so the four
 * pages agree on status semantics instead of each hand-rolling its own
 * colored text. One map, imported on every page (UX chart H2 + H8).
 *
 * The mapping is the cross-page discriminator — keep it a typed function,
 * never a string-literal comparison duplicated per page (manifesto: "No
 * stringly-typed cross-module discriminators").
 */
import type { StatusDotVariant } from '@/components/ui/StatusDot'
import type { BadgeVariant } from '@/components/ui/Badge'

// ── Severity (waiting pressure / expiry urgency) ──────────────────────────────

/** Three-level severity used for time-pressure cells (queue age, approval TTL). */
export type Severity = 'ok' | 'warn' | 'fail'

/** Default pressure thresholds (ms): >5m = fail, >1m = warn, else ok. */
export const WAIT_WARN_MS = 60_000
export const WAIT_FAIL_MS = 300_000

export function waitSeverity(
  ms: number,
  warnMs: number = WAIT_WARN_MS,
  failMs: number = WAIT_FAIL_MS,
): Severity {
  if (ms >= failMs) return 'fail'
  if (ms >= warnMs) return 'warn'
  return 'ok'
}

/** Urgency of an ISO expiry instant relative to now. Past = fail. */
export function expirySeverity(iso: string, now: number = Date.now()): Severity {
  const target = Date.parse(iso)
  if (!Number.isFinite(target)) return 'ok'
  const remaining = target - now
  if (remaining <= 0) return 'fail'
  if (remaining <= WAIT_WARN_MS) return 'warn'
  return 'ok'
}

export function severityToDot(sev: Severity): StatusDotVariant {
  switch (sev) {
    case 'fail':
      return 'fail'
    case 'warn':
      return 'warn'
    case 'ok':
      return 'success'
  }
}

export function severityToBadge(sev: Severity): BadgeVariant {
  switch (sev) {
    case 'fail':
      return 'fail'
    case 'warn':
      return 'warn'
    case 'ok':
      return 'success'
  }
}

// ── Worker lifecycle ──────────────────────────────────────────────────────────

export type WorkerStateLabel = 'ONLINE' | 'BUSY' | 'DRAINING' | 'OFFLINE'

export interface WorkerStatusView {
  variant: StatusDotVariant
  label: WorkerStateLabel
}

/**
 * Worker lifecycle → dot variant + display label. BUSY is a derived state
 * (ONLINE with at least one in-flight task) — it is not a server enum value,
 * so it is computed here rather than read off `state`.
 */
export function workerStatusView(state: string, currentTasks: number): WorkerStatusView {
  if (state === 'OFFLINE') return { variant: 'cancelled', label: 'OFFLINE' }
  if (state === 'DRAINING') return { variant: 'queued', label: 'DRAINING' }
  if (currentTasks > 0) return { variant: 'running', label: 'BUSY' }
  return { variant: 'success', label: 'ONLINE' }
}

// ── Queue priority ────────────────────────────────────────────────────────────

export type PriorityClass = 'high' | 'normal' | 'low'

/** Lower number = higher priority (P0 = highest). */
export function priorityClass(priority: number): PriorityClass {
  if (priority <= 0) return 'high'
  if (priority >= 5) return 'low'
  return 'normal'
}

export function priorityBadge(priority: number): BadgeVariant {
  switch (priorityClass(priority)) {
    case 'high':
      return 'fail'
    case 'normal':
      return 'default'
    case 'low':
      return 'default'
  }
}

// ── Audit action families ─────────────────────────────────────────────────────

/**
 * Audit action → badge variant, grouped by action family. Destructive actions
 * (abort / revoke / scope-denied) read warm; creative actions (create /
 * trigger) read informational; everything else is neutral.
 */
export function auditActionBadge(action: string): BadgeVariant {
  if (action.endsWith('_DENIED')) return 'fail'
  if (action.includes('REVOKE') || action.includes('ABORT')) return 'warn'
  if (action.includes('CREATE') || action.includes('TRIGGER')) return 'info'
  return 'default'
}
