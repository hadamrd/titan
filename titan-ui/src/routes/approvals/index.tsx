/**
 * /approvals — pending-approval inbox (#721; consolidated to the shared list
 * pattern in #1190).
 *
 * <p>Reads {@code GET /api/v1/approvals?status=PENDING} via {@link useApprovals}.
 * Frame: shared PageContainer + PageHeader (UX chart H1). Grid: the shared
 * DataTable primitive (H2) — build · prompt · approvers · expires · actions.
 * All four states (loading / empty / error / populated) flow through DataTable
 * so they match /workers, /queue and /audit (H4). Expiry urgency uses the
 * shared listStatus map + <StatusCell> (H8).
 *
 * <p>RBAC: the server enforces 403 on the decide endpoints. Per-row
 * Approve/Reject (and the row checkbox) are disabled with a tooltip when the
 * caller lacks {@code APPROVE_BUILD} or isn't in the row's approvers list.
 *
 * <p>Bulk actions (#734): a checkbox column + "select all visible" lets an
 * approver decide N rows in one round-trip. Selection is gated by the same
 * {@code mayDecide} predicate as the single endpoints.
 */
import { createFileRoute, Link } from '@tanstack/react-router'
import { Check, ShieldCheck, X } from 'lucide-react'
import { useMemo, useState } from 'react'
import {
  summarizeBulkOutcomes,
  useApprovals,
  useApproveApproval,
  useBulkApprove,
  useBulkReject,
  useRejectApproval,
} from '@/api/hooks'
import { ApiError, type ApprovalDto } from '@/api/types'
import { useAuth } from '@/auth/AuthProvider'
import { hasRole, useAuthRoles } from '@/lib/auth'
import { StatusCell } from '@/components/StatusCell'
import { Badge } from '@/components/ui/Badge'
import { DataTable, type DataTableColumn } from '@/components/ui/DataTable'
import { PageContainer, PageHeader } from '@/components/ui/Page'
import { expirySeverity, severityToDot } from '@/lib/listStatus'
import { useDocumentTitle } from '@/lib/useDocumentTitle'

export const Route = createFileRoute('/approvals/')({
  component: ApprovalsPage,
})

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
  if (!Number.isFinite(target)) return '—'
  const delta = target - now
  if (delta <= 0) return 'expired'
  const s = Math.floor(delta / 1000)
  if (s < 60) return `${s}s`
  const m = Math.floor(s / 60)
  if (m < 60) return `${m}m`
  const h = Math.floor(m / 60)
  if (h < 24) return `${h}h ${m % 60}m`
  const d = Math.floor(h / 24)
  return `${d}d ${h % 24}h`
}

function ApprovalsPage() {
  useDocumentTitle('Approvals')
  const roles = useAuthRoles()
  const { user } = useAuth()
  const me = currentUsername(user?.profile)
  const hasApproveRole = hasRole(roles, 'APPROVE_BUILD') || hasRole(roles, 'ADMIN')

  const { data, isLoading, error, refetch } = useApprovals({ status: 'PENDING' })
  const approve = useApproveApproval()
  const reject = useRejectApproval()
  const bulkApprove = useBulkApprove()
  const bulkReject = useBulkReject()

  const [selected, setSelected] = useState<ReadonlySet<number>>(() => new Set())
  const [banner, setBanner] = useState<{ kind: 'ok' | 'mixed' | 'err'; text: string } | null>(null)

  const items: ApprovalDto[] = data?.items ?? []

  // Per-row "can decide" predicate — gates both the per-row buttons AND the
  // checkbox so selection cannot include rows that always come back forbidden.
  const canDecideFor = (a: ApprovalDto): boolean => {
    if (!hasApproveRole) return false
    if (hasRole(roles, 'ADMIN')) return true
    return a.approvers.length === 0 || a.approvers.includes(me)
  }

  const selectableIds = useMemo(
    () => items.filter(canDecideFor).map((a) => a.id),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [items, hasApproveRole, me, roles],
  )

  const allSelected = selectableIds.length > 0 && selectableIds.every((id) => selected.has(id))
  const someSelected = selected.size > 0 && !allSelected

  const toggleOne = (id: number) => {
    setSelected((prev) => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })
  }

  const toggleAll = () => {
    setSelected(allSelected ? new Set<number>() : new Set(selectableIds))
  }

  const onBulk = async (kind: 'approve' | 'reject') => {
    const ids = Array.from(selected)
    if (ids.length === 0) return
    const verb = kind === 'approve' ? 'approved' : 'rejected'
    const mut = kind === 'approve' ? bulkApprove : bulkReject
    try {
      const resp = await mut.mutateAsync({ ids })
      const text = summarizeBulkOutcomes(resp, verb)
      const anyApplied = resp.outcomes.some((o) => o.applied)
      const anySkipped = resp.outcomes.some((o) => !o.applied)
      const kindBanner: 'ok' | 'mixed' | 'err' = anyApplied ? (anySkipped ? 'mixed' : 'ok') : 'err'
      setBanner({ kind: kindBanner, text })
      setSelected(new Set())
    } catch (e) {
      const msg = e instanceof ApiError ? e.problem.detail ?? e.problem.title : `Bulk ${verb} failed`
      setBanner({ kind: 'err', text: msg })
    }
  }

  const bulkInFlight = bulkApprove.isPending || bulkReject.isPending
  const errMsg = error
    ? error instanceof ApiError
      ? error.problem.detail ?? error.problem.title
      : 'Failed to load approvals.'
    : null

  const columns: DataTableColumn<ApprovalDto>[] = [
    {
      key: 'select',
      width: '36px',
      // Render the select-all control only once rows exist — during the
      // loading/empty states a header checkbox would be a no-op (nothing to
      // select) and would mislead consumers waiting on it.
      header:
        items.length > 0 ? (
          <input
            type="checkbox"
            data-testid="approval-select-all"
            aria-label="Select all visible approvals"
            checked={allSelected}
            ref={(el) => {
              if (el) el.indeterminate = someSelected
            }}
            disabled={selectableIds.length === 0}
            onChange={toggleAll}
          />
        ) : null,
      cell: (a) => {
        const mayDecide = canDecideFor(a)
        return (
          <input
            type="checkbox"
            data-testid={`approval-select-${a.id}`}
            aria-label={`Select approval ${a.id}`}
            checked={selected.has(a.id)}
            disabled={!mayDecide || bulkInFlight}
            onChange={() => toggleOne(a.id)}
          />
        )
      },
    },
    {
      key: 'build',
      header: 'Build',
      width: '90px',
      cell: (a) => (
        <Link to="/builds/$buildId" params={{ buildId: String(a.buildId) }} className="cell-link cell-mono">
          #{a.buildId}
        </Link>
      ),
    },
    {
      key: 'prompt',
      header: 'Prompt',
      cell: (a) => <span title={a.prompt}>{a.prompt}</span>,
    },
    {
      key: 'approvers',
      header: 'Approvers',
      width: '220px',
      cell: (a) => (
        <span className="cell-badges">
          {a.approvers.length === 0 ? (
            <span className="cell-muted">any approver</span>
          ) : (
            a.approvers.map((name) => (
              <Badge
                key={name}
                variant={name === me ? 'accent' : 'default'}
                data-testid={`approval-${a.id}-approver-${name}`}
              >
                {name}
              </Badge>
            ))
          )}
        </span>
      ),
    },
    {
      key: 'expires',
      header: 'Expires',
      width: '120px',
      cell: (a) => (
        <StatusCell variant={severityToDot(expirySeverity(a.expiresAt))} label={relativeExpires(a.expiresAt)} />
      ),
    },
    {
      key: 'action',
      header: '',
      width: '170px',
      align: 'right',
      cell: (a) => {
        const inApprovers = a.approvers.length === 0 || a.approvers.includes(me)
        const mayDecide = canDecideFor(a)
        const disabledHint = !hasApproveRole
          ? 'You lack the APPROVE_BUILD role'
          : !inApprovers
            ? 'You are not an approver for this'
            : ''
        const inFlight =
          (approve.isPending && approve.variables?.id === a.id) ||
          (reject.isPending && reject.variables?.id === a.id)
        return (
          <span className="cell-actions">
            <button
              type="button"
              className="btn-gate-approve"
              data-testid={`approval-approve-${a.id}`}
              disabled={inFlight || !mayDecide}
              title={!mayDecide ? disabledHint : undefined}
              onClick={() => approve.mutate({ id: a.id, buildId: a.buildId })}
            >
              <Check size={12} aria-hidden /> Approve
            </button>
            <button
              type="button"
              className="btn-gate-reject"
              data-testid={`approval-reject-${a.id}`}
              disabled={inFlight || !mayDecide}
              title={!mayDecide ? disabledHint : undefined}
              onClick={() => reject.mutate({ id: a.id, buildId: a.buildId })}
            >
              <X size={12} aria-hidden /> Reject
            </button>
          </span>
        )
      },
    },
  ]

  return (
    <PageContainer>
      <PageHeader
        title="Approvals"
        description={data ? `${data.total} pending sign-off${data.total === 1 ? '' : 's'}` : 'Pending sign-offs'}
      />

      {banner !== null ? (
        <div
          role="status"
          data-testid="approvals-bulk-banner"
          data-kind={banner.kind}
          className="approvals-banner"
          data-bannerkind={banner.kind}
        >
          {banner.text}{' '}
          <button
            type="button"
            data-testid="approvals-bulk-banner-dismiss"
            onClick={() => setBanner(null)}
            className="btn btn-sm btn-ghost"
          >
            dismiss
          </button>
        </div>
      ) : null}

      {selected.size > 0 ? (
        <div data-testid="approvals-bulk-actionbar" className="approvals-bulkbar">
          <span data-testid="approvals-bulk-selected-count" className="cell-muted">
            {selected.size} selected
          </span>
          <span style={{ flex: 1 }} />
          <button
            type="button"
            className="btn-gate-approve"
            data-testid="bulk-approve-btn"
            disabled={bulkInFlight}
            onClick={() => void onBulk('approve')}
          >
            <Check size={12} aria-hidden /> Approve {selected.size}
          </button>
          <button
            type="button"
            className="btn-gate-reject"
            data-testid="bulk-reject-btn"
            disabled={bulkInFlight}
            onClick={() => void onBulk('reject')}
          >
            <X size={12} aria-hidden /> Reject {selected.size}
          </button>
          <button
            type="button"
            data-testid="bulk-clear-btn"
            onClick={() => setSelected(new Set())}
            className="btn btn-sm btn-ghost"
          >
            clear
          </button>
        </div>
      ) : null}

      <div className="card list-table-wrap">
        <DataTable<ApprovalDto>
          rows={items}
          columns={columns}
          rowKey={(a) => a.id}
          rowTestId={(a) => `approval-row-${a.id}`}
          isLoading={isLoading}
          error={errMsg ? { message: errMsg, onRetry: () => void refetch() } : null}
          emptyMessage={
            <span className="empty-stack">
              <span className="empty-title">
                <ShieldCheck size={14} aria-hidden style={{ verticalAlign: 'middle', marginRight: 6 }} />
                No pending approvals
              </span>
              <span className="empty-sub">When a pipeline pauses for sign-off it appears here.</span>
            </span>
          }
          testId="approvals"
        />
      </div>
    </PageContainer>
  )
}
