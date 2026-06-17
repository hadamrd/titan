/**
 * Status dot — the v2 dot+halo pattern.
 *
 * One <span> per dot, status-driven background + box-shadow halo. The
 * `running` variant pulses via the keyframe defined in tokens.css; the
 * `prefers-reduced-motion` query in the same file disables the pulse for
 * users who asked for less motion (fix #8 carries that constraint).
 */
import { cn } from '@/lib/utils'

export type StatusDotVariant =
  | 'success'
  | 'fail'
  | 'warn'
  | 'running'
  | 'queued'
  | 'cancelled'

interface StatusDotProps {
  variant: StatusDotVariant
  /** Use the loud accent hue instead of `--info` for the running pulse. */
  accent?: boolean
  className?: string
  'aria-label'?: string
}

export function StatusDot({
  variant,
  accent = false,
  className,
  ...rest
}: StatusDotProps) {
  return (
    <span
      role="img"
      aria-label={rest['aria-label'] ?? variant}
      className={cn(
        'status-dot',
        variant,
        variant === 'running' && accent && 'accent',
        className,
      )}
    />
  )
}
