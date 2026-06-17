/**
 * /audit — admin-only audit log viewer (#517 / #727; consolidated to the
 * shared list pattern in #1190).
 *
 * <p>Reads {@code GET /api/v1/audit} via {@link useAuditEvents}. Frame: shared
 * PageContainer + PageHeader (UX chart H1). Grid: the shared DataTable
 * primitive (H2) with its optional inline row-expansion — preserves the #614
 * "one row open at a time" detail view without bespoke `<table>` markup.
 * Loading / empty / error / populated all flow through DataTable (H4); the
 * action chip uses the shared {@link Badge} + listStatus map (H8).
 *
 * <p>Filters live in the URL via TanStack Router `useSearch` (shareable links).
 * Discriminated-union typing on {@code action}: the Details cell branches on
 * the action code so a new server action surfaces as a tsc failure here.
 *
 * <p>The 403 case keeps its dedicated "Admin role required" panel — distinct
 * from a generic fetch error so the operator asks for ADMIN, not files a bug.
 */
import { createFileRoute, useNavigate, useSearch } from '@tanstack/react-router'
import { ScrollText } from 'lucide-react'
import { useEffect, useMemo, useState } from 'react'
import { useAuditEvents } from '@/api/hooks'
import {
  AUDIT_ACTIONS,
  type AuditAction,
  type AuditEventDto,
} from '@/api/types'
import { RelativeTime } from '@/components/StatusCell'
import { Badge } from '@/components/ui/Badge'
import { Button } from '@/components/ui/Button'
import { DataTable, type DataTableColumn } from '@/components/ui/DataTable'
import { PageContainer, PageHeader } from '@/components/ui/Page'
import { auditActionBadge } from '@/lib/listStatus'
import { useDocumentTitle } from '@/lib/useDocumentTitle'

const PAGE_SIZE = 50

// ── URL search-param schema (#727) ───────────────────────────────────────────

type SinceWindow = '24h' | '7d' | '30d' | 'all'

interface AuditSearch {
  action: readonly AuditAction[]
  actor: string
  resource: string
  since: SinceWindow
}

const DEFAULT_SEARCH: AuditSearch = {
  action: [],
  actor: '',
  resource: '',
  since: '7d',
}

function isAuditAction(s: unknown): s is AuditAction {
  return typeof s === 'string' && (AUDIT_ACTIONS as readonly string[]).includes(s)
}

function isSinceWindow(s: unknown): s is SinceWindow {
  return s === '24h' || s === '7d' || s === '30d' || s === 'all'
}

/** Coerce arbitrary URL search input to a typed {@link AuditSearch}. */
function validateSearch(raw: Record<string, unknown>): AuditSearch {
  let action: AuditAction[] = []
  const rawAction = raw.action
  if (Array.isArray(rawAction)) {
    action = rawAction.filter(isAuditAction)
  } else if (isAuditAction(rawAction)) {
    action = [rawAction]
  }
  action = Array.from(new Set(action))
  const actor = typeof raw.actor === 'string' ? raw.actor : ''
  const resource = typeof raw.resource === 'string' ? raw.resource : ''
  const since = isSinceWindow(raw.since) ? raw.since : DEFAULT_SEARCH.since
  return { action, actor, resource, since }
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

export const Route = createFileRoute('/audit')({
  component: AuditPage,
  validateSearch,
})

function AuditPage() {
  useDocumentTitle('Audit log')
  const search = useSearch({ from: '/audit' })
  const navigate = useNavigate({ from: '/audit' })

  const [actorInput, setActorInput] = useState(search.actor)
  const [resourceInput, setResourceInput] = useState(search.resource)

  useEffect(() => {
    setActorInput(search.actor)
  }, [search.actor])
  useEffect(() => {
    setResourceInput(search.resource)
  }, [search.resource])

  useEffect(() => {
    if (actorInput === search.actor) return
    const t = setTimeout(() => {
      void navigate({ search: (prev) => ({ ...prev, actor: actorInput }), replace: true })
    }, 300)
    return () => clearTimeout(t)
  }, [actorInput, search.actor, navigate])

  useEffect(() => {
    if (resourceInput === search.resource) return
    const t = setTimeout(() => {
      void navigate({ search: (prev) => ({ ...prev, resource: resourceInput }), replace: true })
    }, 300)
    return () => clearTimeout(t)
  }, [resourceInput, search.resource, navigate])

  const sinceIso = useMemo(() => sinceToIso(search.since), [search.since])

  const [offset, setOffset] = useState(0)

  useEffect(() => {
    setOffset(0)
  }, [search.action, search.actor, search.resource, search.since])

  const { data, isLoading, error, isPlaceholderData, refetch } = useAuditEvents({
    actor: search.actor || undefined,
    action: search.action,
    resource: search.resource || undefined,
    since: sinceIso ?? undefined,
    offset,
    limit: PAGE_SIZE,
  })

  const items = data?.items ?? []
  const total = data?.total ?? 0

  const filtersActive =
    search.action.length > 0 ||
    search.actor !== '' ||
    search.resource !== '' ||
    search.since !== DEFAULT_SEARCH.since

  function toggleAction(next: AuditAction, on: boolean) {
    void navigate({
      search: (prev) => {
        const cur = new Set(prev.action)
        if (on) cur.add(next)
        else cur.delete(next)
        return { ...prev, action: Array.from(cur) }
      },
      replace: true,
    })
  }
  function setSince(since: SinceWindow) {
    void navigate({ search: (prev) => ({ ...prev, since }), replace: true })
  }
  function clearFilters() {
    setActorInput('')
    setResourceInput('')
    void navigate({ search: () => ({ ...DEFAULT_SEARCH }), replace: true })
  }

  // `error` is typed `ApiError | null` (useQuery<AuditPage, ApiError>), so the
  // 403 role-gate and the generic message read straight off the problem detail.
  const is403 = error?.problem.status === 403
  const genericError = error && !is403 ? error.problem.detail ?? error.problem.title : null

  const columns = useAuditColumns()

  return (
    <PageContainer width="wide">
      <PageHeader
        title="Audit log"
        description="Every state-changing action, with the actor and target row. ADMIN-only."
      />

      <div className="filter-bar" data-testid="audit-filter-strip">
        <span style={{ fontSize: 12, color: 'var(--fg-dim)' }}>Action</span>
        {AUDIT_ACTIONS.map((a) => {
          const on = search.action.includes(a)
          return (
            <button
              key={a}
              type="button"
              data-testid={`filter-action-${a}`}
              aria-pressed={on}
              className={on ? 'filter-chip on' : 'filter-chip'}
              onClick={() => toggleAction(a, !on)}
            >
              {a}
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

        <span style={{ fontSize: 12, color: 'var(--fg-dim)', marginLeft: 12 }}>Resource</span>
        <input
          type="text"
          data-testid="filter-resource"
          placeholder="build / 42 / JOB"
          value={resourceInput}
          onChange={(e) => setResourceInput(e.target.value)}
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

      <div className="card list-table-wrap" style={{ opacity: isPlaceholderData ? 0.7 : 1 }}>
        {is403 ? (
          <Audit403 />
        ) : (
          <DataTable<AuditEventDto>
            rows={items}
            columns={columns}
            rowKey={(e) => e.id}
            rowTestId={(e) => `audit-row-${e.id}`}
            rowData={(e) => ({ 'data-action': e.action })}
            isLoading={isLoading}
            error={genericError ? { message: genericError, onRetry: () => void refetch() } : null}
            emptyMessage={
              <span>
                <ScrollText size={14} aria-hidden style={{ verticalAlign: 'middle', marginRight: 6 }} />
                {filtersActive
                  ? 'No audit events match these filters — try widening the actor / action / resource / window.'
                  : 'No audit events yet — state-changing actions (job create, build trigger, PAT mint) appear here.'}
              </span>
            }
            expandable={{
              render: (e) => <AuditDetail event={e} />,
              rowDetailTestId: (e) => `audit-row-details-${e.id}`,
            }}
            testId="audit"
          />
        )}

        {!is403 && (
          <div className="audit-pager">
            <span data-testid="audit-pager-summary">
              {data
                ? items.length === 0
                  ? `0 of ${total}`
                  : `${offset + 1}–${offset + items.length} of ${total}`
                : '—'}
            </span>
            <span style={{ display: 'flex', gap: 8 }}>
              <Button
                variant="outline"
                size="sm"
                data-testid="audit-pager-prev"
                disabled={offset === 0 || isLoading}
                onClick={() => setOffset(Math.max(0, offset - PAGE_SIZE))}
              >
                Previous
              </Button>
              <Button
                variant="outline"
                size="sm"
                data-testid="audit-pager-next"
                disabled={isLoading || offset + items.length >= total}
                onClick={() => setOffset(offset + PAGE_SIZE)}
              >
                Next
              </Button>
            </span>
          </div>
        )}
      </div>
    </PageContainer>
  )
}

function useAuditColumns(): DataTableColumn<AuditEventDto>[] {
  return useMemo(
    () => [
      {
        key: 'when',
        header: 'When',
        width: '120px',
        cell: (e) => <span className="cell-mono"><RelativeTime iso={e.occurredAt} /></span>,
      },
      {
        key: 'actor',
        header: 'Actor',
        width: '160px',
        cell: (e) => <span title={e.actor}>{e.actor}</span>,
      },
      {
        key: 'action',
        header: 'Action',
        width: '160px',
        cell: (e) => (
          <Badge variant={auditActionBadge(e.action)} data-testid={`audit-action-${e.id}`} title={e.action}>
            {e.action}
          </Badge>
        ),
      },
      {
        key: 'target',
        header: 'Target',
        width: '140px',
        cell: (e) => (
          <span className="cell-mono" title={`${e.targetType}${e.targetId !== null ? ` / ${e.targetId}` : ''}`}>
            {e.targetType}
            {e.targetId !== null ? ` / ${e.targetId}` : ''}
          </span>
        ),
      },
      {
        key: 'details',
        header: 'Details',
        cell: (e) => <span data-testid={`audit-row-toggle-${e.id}`}>{summariseAction(e)}</span>,
      },
    ],
    [],
  )
}

function Audit403() {
  return (
    <div className="tt-empty" data-testid="audit-error-403">
      <p style={{ fontSize: 13, color: 'var(--fail)', margin: 0 }}>
        Admin role required to view audit log.
      </p>
      <p style={{ fontSize: 12, color: 'var(--fg-dim)', marginTop: 4 }}>
        Ask a workspace administrator to grant the ADMIN role, then sign in again.
      </p>
    </div>
  )
}

function AuditDetail({ event }: { event: AuditEventDto }) {
  // detailsJson is typed string|null but Quarkus @JsonInclude(NON_NULL) strips
  // null keys, so it arrives as `undefined` on rows with no details — guard on
  // the type, not `!== null`, or `.trim()` throws (the /audit crash).
  const hasDetails =
    typeof event.detailsJson === 'string' && event.detailsJson.trim() !== ''
  if (!hasDetails) {
    return <span className="cell-mono cell-muted">(no details)</span>
  }
  return (
    <pre className="audit-detail-pre">
      <code>{formatJson(event.detailsJson)}</code>
    </pre>
  )
}

/**
 * Per-action one-line summary. Branches on the discriminated-union {@code action}
 * code; a new action forces tsc to flag the {@code default} branch.
 */
function summariseAction(event: AuditEventDto): string {
  const details = parseDetails(event.detailsJson)
  switch (event.action) {
    case 'JOB_CREATE':
      return (details.fullName as string | undefined) ?? 'created job'
    case 'JOB_UPDATE':
      return 'updated job script'
    case 'BUILD_TRIGGER': {
      const jobId = details.jobId
      return jobId != null ? `triggered build for job ${String(jobId)}` : 'triggered build'
    }
    case 'BUILD_ABORT':
      return 'aborted build'
    case 'PAT_CREATE': {
      const name = details.name as string | undefined
      return name ? `minted token "${name}"` : 'minted personal access token'
    }
    case 'PAT_REVOKE':
      return 'revoked personal access token'
    case 'PAT_SCOPE_DENIED': {
      const name = details.name as string | undefined
      return name ? `denied scope for token "${name}"` : 'denied token scope'
    }
    case 'TRANSITION_CAP_WARN': {
      const kind = details.kind as string | undefined
      const count = details.count
      return kind != null && count != null
        ? `transition soft-cap warning — ${kind} re-entered ${String(count)}×`
        : 'transition soft-cap warning'
    }
    case 'TRANSITION_CAP_HALT': {
      const kind = details.kind as string | undefined
      const count = details.count
      return kind != null && count != null
        ? `halted — ${kind} hit the transition spam guard (${String(count)})`
        : 'halted by transition spam guard'
    }
    default: {
      const _exhaustive: never = event.action
      return String(_exhaustive)
    }
  }
}

function parseDetails(json: string | null | undefined): Record<string, unknown> {
  // Loose `== null` catches both null AND undefined (NON_NULL-stripped keys).
  if (json == null || json.trim() === '') return {}
  try {
    const v = JSON.parse(json)
    return v !== null && typeof v === 'object' ? (v as Record<string, unknown>) : {}
  } catch {
    return {}
  }
}

function formatJson(json: string | null): string {
  if (json === null) return ''
  try {
    return JSON.stringify(JSON.parse(json), null, 2)
  } catch {
    return json
  }
}
