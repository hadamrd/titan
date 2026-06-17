/**
 * StatusCell + RelativeTime — shared list-cell renderers (#1190).
 *
 * <p>Both are thin compositions of existing `ui/` primitives, NOT new
 * primitives: {@link StatusCell} wraps {@link StatusDot} + a label so every
 * operator list (workers / queue / approvals / audit) treats status the same
 * way (UX chart H2/H8); {@link RelativeTime} renders a coarse relative string
 * with the absolute timestamp on hover (chart H6 column treatment).
 */
import type { ReactNode } from 'react'
import { StatusDot, type StatusDotVariant } from '@/components/ui/StatusDot'

interface StatusCellProps {
  variant: StatusDotVariant
  label: ReactNode
  /** Tooltip — defaults to the label when it is a plain string. */
  title?: string
  'data-testid'?: string
}

export function StatusCell({ variant, label, title, 'data-testid': testId }: StatusCellProps) {
  return (
    <span
      className="status-cell"
      data-testid={testId}
      title={title ?? (typeof label === 'string' ? label : undefined)}
    >
      <StatusDot variant={variant} aria-label={typeof label === 'string' ? label.toLowerCase() : variant} />
      <span className="status-cell-label">{label}</span>
    </span>
  )
}

/**
 * Coarse relative time ("5s", "2m", "3h", "4d") with the absolute timestamp
 * exposed via the native `title` tooltip + a machine-readable `dateTime`.
 * Anything older than 24h collapses to whole days. Unparseable / null input
 * degrades to `fallback` rather than throwing (adversarial: bad ISO).
 */
export function RelativeTime({
  iso,
  fallback = '—',
  now,
  'data-testid': testId,
}: {
  iso: string | null | undefined
  fallback?: string
  now?: number
  'data-testid'?: string
}) {
  if (iso == null || iso === '') return <span data-testid={testId}>{fallback}</span>
  const ts = Date.parse(iso)
  if (!Number.isFinite(ts)) return <span data-testid={testId}>{fallback}</span>
  return (
    <time dateTime={iso} title={new Date(ts).toLocaleString()} data-testid={testId}>
      {formatRelative(ts, now ?? Date.now())}
    </time>
  )
}

export function formatRelative(ts: number, now: number = Date.now()): string {
  const delta = now - ts
  if (delta < 0) return 'just now'
  const s = Math.floor(delta / 1000)
  if (s < 60) return `${s}s`
  const m = Math.floor(s / 60)
  if (m < 60) return `${m}m`
  const h = Math.floor(m / 60)
  if (h < 24) return `${h}h`
  const d = Math.floor(h / 24)
  return `${d}d`
}
