import { StatusDot, type StatusDotVariant } from '@/components/ui/StatusDot'

/**
 * Build / flow-node status badge.
 *
 * Visual: dot + halo + label. The old shadcn pill is gone; the new v2 pattern
 * pairs a coloured dot with the status text so the same component reads at
 * a glance in compact tables and detail headers alike.
 *
 * Accepts any string status — unknown values fall back to a neutral dot.
 */

// Map api → v2 dot variant. Names match the api/types.ts enum.
const STATUS_VARIANT: Record<string, StatusDotVariant> = {
  SUCCESS: 'success',
  FAILED: 'fail',
  FAILURE: 'fail',
  RUNNING: 'running',
  QUEUED: 'queued',
  ABORTED: 'cancelled',
  CANCELLED: 'cancelled',
  UNSTABLE: 'warn',
  NOT_BUILT: 'queued',
}

interface StatusBadgeProps {
  status: string
  /**
   * Optional stable hook for tests. When set it becomes the badge's
   * `data-testid` — used by the build-detail header to expose the build's
   * overall verdict as `build-verdict-badge` (golden-path e2e, #1166).
   * Non-visual; omitting it leaves the badge unchanged.
   */
  testId?: string
}

export function StatusBadge({ status, testId }: StatusBadgeProps) {
  const variant = STATUS_VARIANT[status] ?? 'queued'
  return (
    <span
      // `data-status` is the stable, machine-readable verdict (the raw API
      // status string) so e2e tools assert the verdict bucket without a pixel
      // diff; the colour comes from the StatusDot variant (fail → red). #1166.
      data-status={status}
      data-testid={testId}
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        gap: 8,
        fontSize: 12,
        fontFamily: 'var(--font-mono)',
        color: 'var(--fg-muted)',
      }}
    >
      <StatusDot variant={variant} />
      <span>{status}</span>
    </span>
  )
}
