/**
 * GateDecision — the §5.1 hi-fi moment from Claude Design v3.
 *
 * Renders ONE pending gate: header (name + node id + waiting timer), approver
 * chips (current user gets a ring), stacked approve/reject buttons. Reject
 * opens a focus-trapped modal with an optional-reason textarea.
 *
 * Wired to the real backend (PR #299):
 *   GET    /api/v1/builds/{buildId}/gates                    → GateDto[]
 *   POST   /api/v1/builds/{buildId}/gates/{nodeId}/approve   body { reason? }
 *   POST   /api/v1/builds/{buildId}/gates/{nodeId}/reject    body { reason? }
 *
 * Server returns 200 on first decision, 409 if already-resolved, 403 if the
 * caller isn't an approver, 400 if reason exceeds 500 chars. We surface those
 * via ApiError and render the message inline.
 */
import { useEffect, useMemo, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { Check, Lock, X, ArrowRight } from 'lucide-react'
import { useApproveGate, useRejectGate } from '@/api/hooks'
import { ApiError, type GateDto } from '@/api/types'
import { useAuth } from '@/auth/AuthProvider'

interface Props {
  buildId: number
  gate: GateDto
}

// 5-colour avatar palette matched to .av-1..5 in components.css.
function avatarColor(name: string): string {
  let h = 0
  for (let i = 0; i < name.length; i++) h = (h * 31 + name.charCodeAt(i)) >>> 0
  return `av-${(h % 5) + 1}`
}

function initials(name: string): string {
  const parts = name.split(/[.\s_-]+/).filter(Boolean)
  if (parts.length === 0) return '?'
  if (parts.length === 1) return parts[0]!.slice(0, 2).toUpperCase()
  return (parts[0]![0]! + parts[1]![0]!).toUpperCase()
}

function formatWait(secs: number): string {
  const m = Math.floor(secs / 60)
  const s = secs % 60
  return `${m}m ${String(s).padStart(2, '0')}s`
}

function relativeSeconds(iso: string | undefined): number {
  if (!iso) return 0
  const t = Date.parse(iso)
  if (Number.isNaN(t)) return 0
  return Math.max(0, Math.floor((Date.now() - t) / 1000))
}

export function GateDecision({ buildId, gate }: Props) {
  const { user } = useAuth()
  const approve = useApproveGate(buildId)
  const reject = useRejectGate(buildId)
  const [showRejectDialog, setShowRejectDialog] = useState(false)
  const [reason, setReason] = useState('')
  const [waitedSec, setWaitedSec] = useState(() => relativeSeconds(gate.awaitingSince))

  // The current user's name as the backend will see it — prefer the OIDC
  // preferred_username (mirrors what GateService uses for its ACL check).
  const currentUser = useMemo<string>(() => {
    const p = user?.profile as Record<string, unknown> | undefined
    return (
      (p?.preferred_username as string | undefined) ??
      (p?.name as string | undefined) ??
      (p?.email as string | undefined) ??
      ''
    )
  }, [user])

  // Live waiting counter — 1 Hz. Reduced motion still updates the value; only
  // the .pulse-digits opacity animation is suppressed via CSS media query.
  useEffect(() => {
    const id = window.setInterval(() => {
      setWaitedSec(relativeSeconds(gate.awaitingSince))
    }, 1000)
    return () => window.clearInterval(id)
  }, [gate.awaitingSince])

  const onApprove = () => {
    approve.mutate({ nodeId: gate.nodeId })
  }
  const onReject = () => {
    reject.mutate(
      { nodeId: gate.nodeId, reason: reason.trim() || undefined },
      { onSuccess: () => setShowRejectDialog(false) },
    )
  }

  const decisionInFlight = approve.isPending || reject.isPending
  const decisionError =
    (approve.error as unknown) ?? (reject.error as unknown) ?? null

  const errorMessage = (() => {
    if (!decisionError) return null
    if (decisionError instanceof ApiError) {
      if (decisionError.status === 409) return 'This gate has already been resolved.'
      if (decisionError.status === 403) return 'You are not an approver for this gate.'
      if (decisionError.status === 404) return 'Gate not found — the pipeline may have moved on.'
      if (decisionError.status === 400) return decisionError.problem.detail ?? 'Invalid request.'
      return decisionError.problem.detail ?? decisionError.message
    }
    return 'Network error — please retry.'
  })()

  const state = approve.isPending ? 'approving' : reject.isPending ? 'rejecting' : 'pending'

  return (
    <>
      <div className={`gate-decision ${state}`}>
        <div className="gate-rail" />
        <div className="gate-body">
          <div className="gate-head">
            <div>
              <div className="gate-label">
                <Lock size={11} aria-hidden />
                Decision required · gate
              </div>
              <div className="gate-name">{gate.name}</div>
              <div className="gate-meta">
                <span className="mono">node:{gate.nodeId}</span>
              </div>
            </div>
            <div className="gate-waiting">
              <div className="gate-waiting-label">awaiting decision for</div>
              <div className="gate-waiting-time">
                <span className="pulse-digits">{formatWait(waitedSec)}</span>
              </div>
            </div>
          </div>

          <div className="gate-approvers">
            <div className="gate-section-label">
              {gate.approvers.length}{' '}
              {gate.approvers.length === 1 ? 'approver' : 'approvers'}
            </div>
            <div className="gate-approver-list">
              {gate.approvers.map((name) => {
                const you = name === currentUser
                return (
                  <div key={name} className={`gate-approver${you ? ' you' : ''}`}>
                    <div className={`avatar-sm ${avatarColor(name)}`} aria-hidden>
                      {initials(name)}
                    </div>
                    <div className="gate-approver-info">
                      <div className="gate-approver-name">{name}</div>
                      <div className="gate-approver-role">
                        {you ? 'you · approver' : 'approver'}
                      </div>
                    </div>
                  </div>
                )
              })}
            </div>
          </div>

          <div className="gate-actions">
            <button
              type="button"
              className="btn-gate-approve"
              onClick={onApprove}
              disabled={decisionInFlight}
              aria-label={`Approve gate ${gate.name}`}
            >
              {approve.isPending ? (
                <>
                  <span className="spinner-mini" aria-hidden /> Approving…
                </>
              ) : (
                <>
                  <Check size={13} aria-hidden /> Approve & resume
                </>
              )}
            </button>
            <button
              type="button"
              className="btn-gate-reject"
              onClick={() => setShowRejectDialog(true)}
              disabled={decisionInFlight}
              aria-label={`Reject gate ${gate.name}`}
            >
              <X size={13} aria-hidden /> Reject
            </button>
          </div>

          {errorMessage && (
            <div
              role="alert"
              style={{
                marginTop: 12,
                fontSize: 12,
                color: 'var(--fail)',
              }}
            >
              {errorMessage}
            </div>
          )}
        </div>
      </div>

      {showRejectDialog && (
        <RejectModal
          gateName={gate.name}
          reason={reason}
          setReason={setReason}
          onCancel={() => setShowRejectDialog(false)}
          onConfirm={onReject}
          loading={reject.isPending}
        />
      )}
    </>
  )
}

// ── Reject modal — focus-trapped + escape-closable ───────────────────────────

interface RejectModalProps {
  gateName: string
  reason: string
  setReason: (v: string) => void
  onCancel: () => void
  onConfirm: () => void
  loading: boolean
}

function RejectModal({
  gateName,
  reason,
  setReason,
  onCancel,
  onConfirm,
  loading,
}: RejectModalProps) {
  const textareaRef = useRef<HTMLTextAreaElement>(null)
  const cancelBtnRef = useRef<HTMLButtonElement>(null)
  const confirmBtnRef = useRef<HTMLButtonElement>(null)

  useEffect(() => {
    textareaRef.current?.focus()
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        e.preventDefault()
        onCancel()
      } else if (e.key === 'Tab') {
        // Cycle focus among the three interactive elements only.
        const candidates: (HTMLElement | null)[] = [
          textareaRef.current,
          cancelBtnRef.current,
          confirmBtnRef.current,
        ]
        const order = candidates.filter((el): el is HTMLElement => el !== null)
        if (order.length === 0) return
        const active = document.activeElement as HTMLElement | null
        const idx = active ? order.indexOf(active) : -1
        const next = e.shiftKey
          ? order[(idx - 1 + order.length) % order.length]
          : order[(idx + 1) % order.length]
        e.preventDefault()
        next?.focus()
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onCancel])

  // Portal to <body> (#113): keeps the fixed backdrop viewport-relative even
  // when the modal is mounted under a transformed/animated ancestor.
  return createPortal(
    <div className="gate-modal-backdrop" onClick={onCancel} role="presentation">
      <div
        className="gate-modal"
        onClick={(e) => e.stopPropagation()}
        role="dialog"
        aria-modal="true"
        aria-labelledby="gate-reject-title"
      >
        <div className="gate-modal-head">
          <div id="gate-reject-title" style={{ fontSize: 14, fontWeight: 600 }}>
            Reject {gateName}?
          </div>
          <div
            className="dim"
            style={{ fontSize: 12, marginTop: 2, color: 'var(--fg-dim)' }}
          >
            This will fail the build and stop downstream stages.
          </div>
        </div>
        <div style={{ padding: '14px 18px' }}>
          <label
            htmlFor="gate-reject-reason"
            style={{
              display: 'block',
              fontSize: 11,
              textTransform: 'uppercase',
              letterSpacing: '0.06em',
              color: 'var(--fg-faint)',
              marginBottom: 6,
            }}
          >
            Reason (optional)
          </label>
          <textarea
            id="gate-reject-reason"
            ref={textareaRef}
            className="field"
            style={{ minHeight: 68, fontFamily: 'inherit', resize: 'vertical' }}
            placeholder="e.g. waiting for QA signoff"
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            maxLength={500}
          />
        </div>
        <div className="gate-modal-foot">
          <button
            type="button"
            ref={cancelBtnRef}
            className="btn"
            onClick={onCancel}
            disabled={loading}
          >
            Cancel
          </button>
          <button
            type="button"
            ref={confirmBtnRef}
            className="btn-gate-reject-confirm"
            onClick={onConfirm}
            disabled={loading}
          >
            {loading ? (
              <>
                <span className="spinner-mini" aria-hidden /> Rejecting…
              </>
            ) : (
              <>
                <X size={12} aria-hidden /> Reject deploy
              </>
            )}
          </button>
        </div>
      </div>
    </div>,
    document.body,
  )
}

// ── Resolved one-liner — collapsed audit row for a decided gate ──────────────

export interface ResolvedGate {
  gateName: string
  decision: 'approved' | 'rejected'
  actor: string
  reason?: string
  resolvedAt?: string
}

export function GateResolved({ gate }: { gate: ResolvedGate }) {
  const ok = gate.decision === 'approved'
  return (
    <div className={`gate-resolved ${ok ? 'ok' : 'rej'}`}>
      <span className="gate-resolved-rail" />
      {ok ? <Check size={12} aria-hidden /> : <X size={12} aria-hidden />}
      <span>
        <strong>{ok ? 'Approved' : 'Rejected'}</strong> by{' '}
        <span className="mono">{gate.actor}</span>
        {ok && ' · pipeline resumed'}
      </span>
      {gate.reason && (
        <>
          <span style={{ color: 'var(--fg-faint)' }}>·</span>
          <span style={{ color: 'var(--fg-muted)' }}>&quot;{gate.reason}&quot;</span>
        </>
      )}
      <button
        type="button"
        className="btn btn-sm btn-ghost"
        style={{ marginLeft: 'auto' }}
      >
        View audit log <ArrowRight size={11} aria-hidden />
      </button>
    </div>
  )
}
