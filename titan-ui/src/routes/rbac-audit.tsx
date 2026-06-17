/**
 * /rbac-audit — admin/READ_AUDIT view of the typed RBAC allow/deny trail (#1167).
 *
 * <p>Reads {@code GET /api/v1/rbac-audit} via {@link useRbacAuditEvents}. This is the operator's
 * "who-did-what / who-was-denied" surface over {@code titan.rbac_audit}. It is deliberately a
 * SEPARATE route from {@code /audit}: that page's {@code AuditAction} union is coupled to {@code
 * audit_log}; the rbac trail's verdict/scope/role shape is its own typing and mixing the two would
 * bloat both. Filter-strip / table / URL-{@code useSearch} / debounced-input / pagination patterns
 * are copied from {@code audit.tsx}.
 *
 * <p>The headline column is {@code Verdict}: ALLOW vs DENY are visually distinct (a denied check is
 * what an admin scans for during an incident). {@code verdict} is a closed union — the cell branches
 * on it with an exhaustiveness check, so a new server-side verdict is a tsc failure, not a silent
 * generic render.
 *
 * <p>States:
 * <ul>
 *   <li>Loading — skeleton rows.</li>
 *   <li>403 — "Admin / READ_AUDIT role required".</li>
 *   <li>Other error — generic banner with the problem detail.</li>
 *   <li>Empty + filters active — "No RBAC events match these filters" (filter-too-narrow).</li>
 *   <li>Empty + no filters — "No RBAC checks recorded yet" (system-quiet). The two empties are
 *       distinct so the operator can tell "the gate is silent" from "I over-narrowed".</li>
 * </ul>
 */
import { createFileRoute, useNavigate, useSearch } from '@tanstack/react-router'
import { ShieldCheck } from 'lucide-react'
import { useEffect, useMemo, useState } from 'react'
import { useRbacAuditEvents } from '@/api/hooks'
import {
  ApiError,
  RBAC_VERDICTS,
  type RbacAuditEventDto,
  type RbacVerdict,
  isRbacVerdict,
} from '@/api/types'
import { EmptyState } from '@/components/EmptyState'
import { PageContainer, PageHeader } from '@/components/ui/Page'
import { Skeleton } from '@/components/ui/Skeleton'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/Table'
import { useDocumentTitle } from '@/lib/useDocumentTitle'

const PAGE_SIZE = 50

// ── URL search-param schema ──────────────────────────────────────────────────

type SinceWindow = '24h' | '7d' | '30d' | 'all'

interface RbacAuditSearch {
  verdict: readonly RbacVerdict[]
  actor: string
  since: SinceWindow
}

const DEFAULT_SEARCH: RbacAuditSearch = {
  verdict: [],
  actor: '',
  since: '7d',
}

function isSinceWindow(s: unknown): s is SinceWindow {
  return s === '24h' || s === '7d' || s === '30d' || s === 'all'
}

/**
 * Coerce arbitrary URL search input to a typed {@link RbacAuditSearch}. URLs are the wild west;
 * this is the only place that touches `unknown`. Stale links with an unknown verdict are sanitised
 * (dropped) so the rest of the page still renders — a half-typed filter never blanks the trail.
 */
function validateSearch(raw: Record<string, unknown>): RbacAuditSearch {
  let verdict: RbacVerdict[] = []
  const rawVerdict = raw.verdict
  if (Array.isArray(rawVerdict)) {
    verdict = rawVerdict.filter(isRbacVerdict)
  } else if (isRbacVerdict(rawVerdict)) {
    verdict = [rawVerdict]
  }
  verdict = Array.from(new Set(verdict))
  const actor = typeof raw.actor === 'string' ? raw.actor : ''
  const since = isSinceWindow(raw.since) ? raw.since : DEFAULT_SEARCH.since
  return { verdict, actor, since }
}

function sinceToIso(window: SinceWindow): string | null {
  if (window === 'all') return null
  const now = Date.now()
  const ms =
    window === '24h'
      ? 24 * 60 * 60 * 1000
      : window === '7d'
        ? 7 * 24 * 60 * 60 * 1000
        : 30 * 24 * 60 * 60 * 1000
  return new Date(now - ms).toISOString()
}

export const Route = createFileRoute('/rbac-audit')({
  component: RbacAuditPage,
  validateSearch,
})

function RbacAuditPage() {
  useDocumentTitle('RBAC audit')
  const search = useSearch({ from: '/rbac-audit' })
  const navigate = useNavigate({ from: '/rbac-audit' })

  // Debounced text input (300ms) — typing N chars must land exactly 1 fetch.
  const [actorInput, setActorInput] = useState(search.actor)
  useEffect(() => {
    setActorInput(search.actor)
  }, [search.actor])
  useEffect(() => {
    if (actorInput === search.actor) return
    const t = setTimeout(() => {
      void navigate({ search: (prev) => ({ ...prev, actor: actorInput }), replace: true })
    }, 300)
    return () => clearTimeout(t)
  }, [actorInput, search.actor, navigate])

  const sinceIso = useMemo(() => sinceToIso(search.since), [search.since])

  const [offset, setOffset] = useState(0)
  // Reset pagination when any filter changes — paginating into an empty tail after narrowing is
  // the worst "is the system broken?" trap.
  useEffect(() => {
    setOffset(0)
  }, [search.verdict, search.actor, search.since])

  const { data, isLoading, error, isPlaceholderData } = useRbacAuditEvents({
    actor: search.actor || undefined,
    verdict: search.verdict,
    since: sinceIso ?? undefined,
    offset,
    limit: PAGE_SIZE,
  })

  const items = data?.items ?? []
  const total = data?.total ?? 0

  const filtersActive =
    search.verdict.length > 0 || search.actor !== '' || search.since !== DEFAULT_SEARCH.since

  function toggleVerdict(next: RbacVerdict, on: boolean) {
    void navigate({
      search: (prev) => {
        const cur = new Set(prev.verdict)
        if (on) cur.add(next)
        else cur.delete(next)
        return { ...prev, verdict: Array.from(cur) }
      },
      replace: true,
    })
  }
  function setSince(since: SinceWindow) {
    void navigate({ search: (prev) => ({ ...prev, since }), replace: true })
  }
  function clearFilters() {
    setActorInput('')
    void navigate({ search: () => ({ ...DEFAULT_SEARCH }), replace: true })
  }

  return (
    <PageContainer width="wide">
      <PageHeader
        title="RBAC audit"
        description={
          <>
            Every role-gated check — who was allowed or <strong>denied</strong>, on which scope, and
            why. Admin / READ_AUDIT only.
          </>
        }
      />

      <div className="filter-bar" data-testid="rbac-audit-filter-strip">
        <span style={{ fontSize: 12, color: 'var(--fg-dim)' }}>Verdict</span>
        {RBAC_VERDICTS.map((v) => {
          const on = search.verdict.includes(v)
          return (
            <button
              key={v}
              type="button"
              data-testid={`filter-verdict-${v}`}
              aria-pressed={on}
              className={on ? 'filter-chip on' : 'filter-chip'}
              onClick={() => toggleVerdict(v, !on)}
            >
              {v}
            </button>
          )
        })}

        <span style={{ fontSize: 12, color: 'var(--fg-dim)', marginLeft: 12 }}>Actor</span>
        <input
          type="text"
          data-testid="filter-actor"
          placeholder="alice / svc-cd"
          value={actorInput}
          onChange={(e) => setActorInput(e.target.value)}
          style={{ width: 160 }}
        />

        <span style={{ fontSize: 12, color: 'var(--fg-dim)', marginLeft: 12 }}>Window</span>
        <select
          data-testid="filter-since"
          value={search.since}
          onChange={(e) => setSince(e.target.value as SinceWindow)}
        >
          <option value="24h">Last 24h</option>
          <option value="7d">Last 7d</option>
          <option value="30d">Last 30d</option>
          <option value="all">All time</option>
        </select>

        {filtersActive && (
          <button
            type="button"
            data-testid="filter-clear"
            className="filter-chip"
            onClick={clearFilters}
            style={{ marginLeft: 'auto' }}
          >
            Clear filters
          </button>
        )}
      </div>

      <div className="card" style={{ opacity: isPlaceholderData ? 0.7 : 1 }}>
        {error ? (
          <RbacAuditError error={error} />
        ) : isLoading ? (
          <RbacAuditSkeleton />
        ) : items.length === 0 ? (
          <EmptyState
            data-testid="rbac-audit-empty"
            icon={<ShieldCheck size={28} aria-hidden />}
            title={
              filtersActive
                ? 'No RBAC events match these filters'
                : 'No RBAC checks recorded yet'
            }
            message={
              filtersActive
                ? 'Try widening the verdict / actor / window selection.'
                : 'Allow and deny decisions on role-gated endpoints will appear here once RBAC is exercised.'
            }
          />
        ) : (
          <RbacAuditTable items={items} />
        )}

        <div
          style={{
            padding: '10px 14px',
            display: 'flex',
            justifyContent: 'space-between',
            alignItems: 'center',
            borderTop: '1px solid var(--border)',
            fontSize: 12,
            color: 'var(--fg-dim)',
          }}
        >
          <span data-testid="rbac-audit-pager-summary">
            {data
              ? items.length === 0
                ? `0 of ${total}`
                : `${offset + 1}–${offset + items.length} of ${total}`
              : '—'}
          </span>
          <span style={{ display: 'flex', gap: 8 }}>
            <button
              type="button"
              className="btn btn-sm"
              data-testid="rbac-audit-pager-prev"
              disabled={offset === 0 || isLoading}
              onClick={() => setOffset(Math.max(0, offset - PAGE_SIZE))}
            >
              Previous
            </button>
            <button
              type="button"
              className="btn btn-sm"
              data-testid="rbac-audit-pager-next"
              disabled={isLoading || offset + items.length >= total}
              onClick={() => setOffset(offset + PAGE_SIZE)}
            >
              Next
            </button>
          </span>
        </div>
      </div>
    </PageContainer>
  )
}

function RbacAuditError({ error }: { error: unknown }) {
  const status = error instanceof ApiError ? error.problem.status : 0
  if (status === 403) {
    return (
      <div className="empty" style={{ padding: 30 }} data-testid="rbac-audit-error-403">
        <p style={{ fontSize: 13, color: 'var(--fail)' }}>
          Admin / READ_AUDIT role required to view the RBAC audit trail.
        </p>
        <p style={{ fontSize: 12, color: 'var(--fg-dim)', marginTop: 4 }}>
          Ask a workspace administrator to grant the ADMIN or READ_AUDIT role, then sign in again.
        </p>
      </div>
    )
  }
  return (
    <div className="empty" style={{ padding: 30 }} data-testid="rbac-audit-error-generic">
      <p style={{ fontSize: 13, color: 'var(--fail)' }}>Failed to load RBAC audit events</p>
      <p style={{ fontSize: 12, color: 'var(--fg-dim)', marginTop: 4 }}>
        {error instanceof ApiError
          ? (error.problem.detail ?? error.problem.title)
          : error instanceof Error
            ? error.message
            : String(error)}
      </p>
    </div>
  )
}

function RbacAuditSkeleton() {
  return (
    <div style={{ padding: 12 }} data-testid="rbac-audit-skeleton">
      {Array.from({ length: 6 }).map((_, i) => (
        <div
          key={i}
          style={{ display: 'flex', gap: 12, alignItems: 'center', padding: '10px 4px' }}
        >
          <Skeleton style={{ height: 12, width: 150 }} />
          <Skeleton style={{ height: 12, width: 100 }} />
          <Skeleton style={{ height: 12, width: 140 }} />
          <Skeleton style={{ height: 12, width: 110 }} />
          <Skeleton style={{ height: 12, width: 70 }} />
        </div>
      ))}
    </div>
  )
}

function RbacAuditTable({ items }: { items: RbacAuditEventDto[] }) {
  return (
    <Table>
      <TableHeader>
        <TableRow>
          <TableHead style={{ width: 190 }}>When</TableHead>
          <TableHead style={{ width: 170 }}>Actor</TableHead>
          <TableHead style={{ width: 200 }}>Endpoint</TableHead>
          <TableHead style={{ width: 170 }}>Scope</TableHead>
          <TableHead style={{ width: 180 }}>Roles (req → eff)</TableHead>
          <TableHead style={{ width: 90 }}>Verdict</TableHead>
        </TableRow>
      </TableHeader>
      <TableBody data-testid="rbac-audit-table-body">
        {items.map((evt) => (
          <RbacAuditRow key={evt.id} event={evt} />
        ))}
      </TableBody>
    </Table>
  )
}

function RbacAuditRow({ event }: { event: RbacAuditEventDto }) {
  return (
    <TableRow data-testid={`rbac-audit-row-${event.id}`}>
      <TableCell
        className="tabnum"
        style={{ fontFamily: 'var(--font-mono)', fontSize: 12, color: 'var(--fg-muted)' }}
      >
        {formatWhen(event.occurredAt)}
      </TableCell>
      <TableCell style={{ fontSize: 12.5 }} data-testid={`rbac-audit-actor-${event.id}`}>
        {/* Anonymous deny → null actor. Render an explicit marker, never blank-crash. */}
        {event.actor ?? <span style={{ color: 'var(--fg-dim)' }}>(anonymous)</span>}
      </TableCell>
      <TableCell
        style={{ fontFamily: 'var(--font-mono)', fontSize: 12, color: 'var(--fg-muted)' }}
        title={event.endpoint}
      >
        {event.endpoint}
      </TableCell>
      <TableCell
        style={{ fontFamily: 'var(--font-mono)', fontSize: 12, color: 'var(--fg-muted)' }}
      >
        {event.scopeKind}
        {event.scopeId ? ` / ${event.scopeId}` : ''}
      </TableCell>
      <TableCell
        style={{ fontFamily: 'var(--font-mono)', fontSize: 11.5, color: 'var(--fg-muted)' }}
      >
        {event.requiredRole}
        {' → '}
        {event.effectiveRole ?? <span style={{ color: 'var(--fg-dim)' }}>—</span>}
      </TableCell>
      <TableCell>
        <VerdictBadge verdict={event.verdict} id={event.id} />
      </TableCell>
    </TableRow>
  )
}

/**
 * First-class, visually-distinct verdict badge. Branches on the closed union with an exhaustiveness
 * check — a new server-side verdict forces a tsc failure here rather than rendering generically.
 */
function VerdictBadge({ verdict, id }: { verdict: RbacVerdict; id: number }) {
  switch (verdict) {
    case 'ALLOW':
      return (
        <span
          className="priority-chip"
          data-testid={`rbac-audit-verdict-${id}`}
          data-verdict="ALLOW"
          style={{
            fontFamily: 'var(--font-mono)',
            fontSize: 11,
            color: 'var(--ok, #16a34a)',
            borderColor: 'var(--ok, #16a34a)',
          }}
        >
          ALLOW
        </span>
      )
    case 'DENY':
      return (
        <span
          className="priority-chip"
          data-testid={`rbac-audit-verdict-${id}`}
          data-verdict="DENY"
          style={{
            fontFamily: 'var(--font-mono)',
            fontSize: 11,
            color: 'var(--fail)',
            borderColor: 'var(--fail)',
            fontWeight: 600,
          }}
        >
          DENY
        </span>
      )
    default: {
      const _exhaustive: never = verdict
      return <span>{String(_exhaustive)}</span>
    }
  }
}

function formatWhen(iso: string): string {
  try {
    const d = new Date(iso)
    return d.toLocaleString(undefined, {
      year: 'numeric',
      month: 'short',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
    })
  } catch {
    return iso
  }
}
