import { Trash2 } from 'lucide-react'
import type { PersonalAccessTokenDto } from '@/api/types'
import { Badge } from '@/components/ui/Badge'
import { Button } from '@/components/ui/Button'
import { DataTable, type DataTableColumn } from '@/components/ui/DataTable'

/**
 * Access-tokens list, rebuilt on the shared `DataTable` (#1186, H2/H4).
 *
 * Replaces the hand-rolled `PatList` table. Two deliberate changes from the old
 * markup:
 *   1. The always-blank "Last used" column is GONE — the server never populates
 *      `lastUsedAt`, so it was a permanently-empty column (H4 relapse). When the
 *      backend starts tracking last-use we add the column back WITH data.
 *   2. All four data states (loading / empty / error / populated) are delegated
 *      to `DataTable`, so they match every other list in the product instead of
 *      being re-invented inline.
 *
 * The PAT data flow (scopes, job-pattern, reveal-once, revoke) is unchanged —
 * this is presentation only.
 */

function formatDate(iso: string | null | undefined): string {
  // Older server builds with @JsonInclude(NON_NULL) strip null keys; tolerate
  // missing fields without rendering "Invalid Date".
  if (iso === null || iso === undefined || iso === '') return '—'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return '—'
  // Compact date only (no time) — the full datetime was the widest column and
  // pushed the Actions/Revoke column off the card's right edge (H7 clip). The
  // exact timestamp lives in the title attr for anyone who needs it.
  return d.toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' })
}

function tokenStatus(t: PersonalAccessTokenDto): {
  label: string
  variant: 'success' | 'warn'
} {
  // Treat missing revokedAt (null OR undefined) as "not revoked".
  if (t.revokedAt !== null && t.revokedAt !== undefined) {
    return { label: 'Revoked', variant: 'warn' }
  }
  return { label: 'Active', variant: 'success' }
}

interface PatTableProps {
  tokens: PersonalAccessTokenDto[] | undefined
  isLoading: boolean
  error: unknown
  /** Retry affordance for the error state (H4) — re-runs the tokens query. */
  onRetry: () => void
  onRevoke: (t: PersonalAccessTokenDto) => void
  revokePending: boolean
}

export function PatTable({
  tokens,
  isLoading,
  error,
  onRetry,
  onRevoke,
  revokePending,
}: PatTableProps) {
  const rows = tokens ?? []

  const columns: DataTableColumn<PersonalAccessTokenDto>[] = [
    {
      key: 'name',
      header: 'Name',
      cell: (t) => t.name,
    },
    {
      key: 'scopes',
      header: 'Scopes',
      // Scopes as a compact comma-separated muted list — the standard PAT-table
      // convention (GitHub / GitLab / Stripe). The chunky info Badges were too
      // heavy and clipped at the column edge; plain text wraps and never clips.
      cell: (t) =>
        !Array.isArray(t.scopes) || t.scopes.length === 0 ? (
          <span style={{ color: 'var(--fg-faint)', fontSize: 11 }}>all (legacy)</span>
        ) : (
          <span
            data-testid="token-scopes"
            style={{
              fontFamily: 'var(--font-mono)',
              fontSize: 11,
              color: 'var(--fg-muted)',
              lineHeight: 1.5,
            }}
          >
            {t.scopes.join(', ')}
          </span>
        ),
    },
    {
      key: 'jobPattern',
      header: 'Job pattern',
      cell: (t) =>
        t.jobPattern === null || t.jobPattern === undefined || t.jobPattern === '' ? (
          <span style={{ color: 'var(--fg-faint)', fontSize: 11 }}>any</span>
        ) : (
          <Badge data-testid="token-job-pattern" variant="info">
            {t.jobPattern}
          </Badge>
        ),
    },
    {
      key: 'created',
      header: 'Created',
      cell: (t) => {
        const exact =
          t.createdAt !== null && t.createdAt !== undefined && t.createdAt !== ''
            ? new Date(t.createdAt)
            : null
        return (
          <span
            title={exact && !Number.isNaN(exact.getTime()) ? exact.toLocaleString() : undefined}
            style={{ fontSize: 11, fontFamily: 'var(--font-mono)', whiteSpace: 'nowrap' }}
          >
            {formatDate(t.createdAt)}
          </span>
        )
      },
    },
    {
      key: 'status',
      header: 'Status',
      cell: (t) => {
        const status = tokenStatus(t)
        return <Badge variant={status.variant}>{status.label}</Badge>
      },
    },
    {
      key: 'actions',
      header: '',
      align: 'right',
      cell: (t) =>
        t.revokedAt === null || t.revokedAt === undefined ? (
          <Button
            type="button"
            variant="ghost"
            size="sm"
            onClick={() => onRevoke(t)}
            disabled={revokePending}
            aria-label={`Revoke token ${t.name}`}
          >
            <Trash2 size={12} aria-hidden /> Revoke
          </Button>
        ) : null,
    },
  ]

  return (
    // The table scrolls INSIDE this container at narrow widths (H7) — the page
    // itself never gains a horizontal scrollbar.
    <div data-testid="pat-table-scroll" style={{ overflowX: 'auto' }}>
      <DataTable
        testId="pat-list"
        rows={rows}
        columns={columns}
        rowKey={(t) => t.id}
        rowTestId={() => 'token-row'}
        rowData={(t) => ({
          'data-token-name': t.name,
          'data-token-status': tokenStatus(t).label.toLowerCase(),
        })}
        isLoading={isLoading}
        error={
          error !== null && error !== undefined
            ? { message: 'Could not load tokens.', onRetry }
            : null
        }
        emptyMessage="No tokens yet — generate one above to authenticate the Titan CLI and CI scripts against your account."
      />
    </div>
  )
}
