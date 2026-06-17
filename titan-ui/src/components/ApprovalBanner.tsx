/**
 * ApprovalBanner — inline approval surface on /builds/$buildId (#721).
 *
 * <p>Renders ONE pending approval inline at the top of the build detail. Mirrors
 * the {@link GateDecision} idiom (gate-decision banner) but tuned for the
 * lower-key "approval:" parked step: the building block is the approval row
 * from {@code titan.approvals}, not the stage-level gate node.
 *
 * <p>v3 design floor: calm yellow-muted oklch background, no jarring strobe.
 * {@code prefers-reduced-motion} respected — the only effect is a static
 * border colour. Approve/Reject buttons are disabled with a tooltip when the
 * caller lacks {@code APPROVE_BUILD} or isn't in the approvers list. The
 * server is still the source of truth (403 on bad caller).
 */
import { Check, Lock, X } from 'lucide-react'
import { useEffect, useState } from 'react'
import { useApproveApproval, useRejectApproval } from '@/api/hooks'
import { ApiError, type ApprovalDto } from '@/api/types'
import { useAuth } from '@/auth/AuthProvider'
import { hasRole, useAuthRoles } from '@/lib/auth'

interface Props {
  approval: ApprovalDto
}

function currentUsername(profile: unknown): string {
  if (profile === null || typeof profile !== 'object') return ''
  const p = profile as Record<string, unknown>
  return (
    (p.preferred_username as string | undefined) ??
    (p.name as string | undefined) ??
    (p.email as string | undefined) ??
    ''
  )
}

function relativeExpires(iso: string): string {
  const now = Date.now()
  const target = Date.parse(iso)
  if (!Number.isFinite(target)) return ''
  const deltaMs = target - now
  if (deltaMs <= 0) return 'expired'
  const s = Math.floor(deltaMs / 1000)
  if (s < 60) return `expires in ${s}s`
  const m = Math.floor(s / 60)
  if (m < 60) return `expires in ${m}m`
  const h = Math.floor(m / 60)
  if (h < 24) return `expires in ${h}h ${m % 60}m`
  const d = Math.floor(h / 24)
  return `expires in ${d}d ${h % 24}h`
}

export function ApprovalBanner({ approval }: Props) {
  const { user } = useAuth()
  const roles = useAuthRoles()
  const approve = useApproveApproval()
  const reject = useRejectApproval()
  const [expiresLabel, setExpiresLabel] = useState(() => relativeExpires(approval.expiresAt))

  // Tick the expires-at countdown once a minute. Reduced motion still updates
  // the value — we only suppress animation, not numerical state.
  useEffect(() => {
    const id = window.setInterval(() => {
      setExpiresLabel(relativeExpires(approval.expiresAt))
    }, 30_000)
    return () => window.clearInterval(id)
  }, [approval.expiresAt])

  const me = currentUsername(user?.profile)
  // Client-side hint only — server enforces. Match the server rule: empty
  // approvers list = anyone with APPROVE_BUILD may decide.
  const hasApproveRole = hasRole(roles, 'APPROVE_BUILD') || hasRole(roles, 'ADMIN')
  const inApproversList =
    approval.approvers.length === 0 || approval.approvers.includes(me)
  const mayDecide = hasApproveRole && (inApproversList || hasRole(roles, 'ADMIN'))
  const disabledHint = !hasApproveRole
    ? 'You lack the APPROVE_BUILD role'
    : !inApproversList
      ? 'You are not an approver for this'
      : ''

  const inFlight = approve.isPending || reject.isPending
  const decisionError = (approve.error as unknown) ?? (reject.error as unknown) ?? null
  const errorMessage = (() => {
    if (!decisionError) return null
    if (decisionError instanceof ApiError) {
      if (decisionError.status === 409) return 'This approval has already been resolved.'
      if (decisionError.status === 403) return 'You are not an approver for this.'
      if (decisionError.status === 404) return 'Approval not found — the pipeline may have moved on.'
      return decisionError.problem.detail ?? decisionError.message
    }
    return 'Network error — please retry.'
  })()

  const isTerminal = approval.status !== 'PENDING'
  const timedOut = approval.status === 'TIMED_OUT'

  return (
    <div
      data-testid={`approval-banner-${approval.id}`}
      role="region"
      aria-label={`Approval required: ${approval.prompt}`}
      style={{
        display: 'flex',
        alignItems: 'flex-start',
        gap: 12,
        padding: '12px 14px',
        margin: '8px 16px',
        borderRadius: 8,
        // Doc 64: theme-aware. Subtle bg + warn-tinted left rule carries the
        // "needs attention" signal without strobing in dark mode (previous
        // hardcoded cream-on-amber blew up on the dark theme).
        background: 'var(--bg-2)',
        border: '1px solid var(--border)',
        borderLeft: '3px solid var(--warn)',
        color: 'var(--fg)',
        fontSize: 13,
      }}
    >
      <Lock size={14} aria-hidden style={{ marginTop: 2 }} />
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ display: 'flex', alignItems: 'baseline', gap: 8, flexWrap: 'wrap' }}>
          <strong style={{ fontSize: 13 }}>Approval required</strong>
          <span style={{ color: 'var(--fg-muted)' }}>{approval.prompt}</span>
        </div>
        <div
          className="tabnum"
          style={{
            fontSize: 11,
            color: 'var(--fg-faint)',
            fontFamily: 'var(--font-mono)',
            marginTop: 4,
          }}
        >
          node:{approval.flowNodeId}
          {!isTerminal && (
            <>
              {' · '}
              <span data-testid={`approval-expires-${approval.id}`}>{expiresLabel}</span>
            </>
          )}
          {approval.approvers.length > 0 && (
            <>
              {' · '}approvers: {approval.approvers.join(', ')}
            </>
          )}
        </div>
        {timedOut && (
          <div
            data-testid={`approval-timed-out-${approval.id}`}
            style={{ marginTop: 6, fontSize: 12, color: 'var(--fail)' }}
          >
            auto-rejected: timeout
          </div>
        )}
        {errorMessage && (
          <div role="alert" style={{ marginTop: 6, fontSize: 12, color: 'var(--fail)' }}>
            {errorMessage}
          </div>
        )}
      </div>
      {!isTerminal && (
        <div style={{ display: 'flex', gap: 6 }}>
          <button
            type="button"
            className="btn-gate-approve"
            data-testid={`approval-approve-${approval.id}`}
            disabled={inFlight || !mayDecide}
            title={!mayDecide ? disabledHint : undefined}
            aria-label={`Approve approval ${approval.id}`}
            onClick={() => approve.mutate({ id: approval.id, buildId: approval.buildId })}
          >
            <Check size={13} aria-hidden /> Approve
          </button>
          <button
            type="button"
            className="btn-gate-reject"
            data-testid={`approval-reject-${approval.id}`}
            disabled={inFlight || !mayDecide}
            title={!mayDecide ? disabledHint : undefined}
            aria-label={`Reject approval ${approval.id}`}
            onClick={() => reject.mutate({ id: approval.id, buildId: approval.buildId })}
          >
            <X size={13} aria-hidden /> Reject
          </button>
        </div>
      )}
    </div>
  )
}
