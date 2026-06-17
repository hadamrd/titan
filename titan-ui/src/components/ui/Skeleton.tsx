import { cn } from '@/lib/utils'

/**
 * Shimmer block — fix #9 (loading states that match real geometry).
 *
 * Wraps the `.skeleton` class from tokens.css. Caller sets width/height
 * via `style` or tailwind utility; the keyframe is shared.
 *
 * Carries `role="presentation"` + a default `data-testid="skeleton"` so tests
 * can assert the loading state implementation-agnostically (without coupling to
 * the `.skeleton` CSS class, which is free to change). Callers may override
 * either via props.
 */
export function Skeleton({
  className,
  ...props
}: React.HTMLAttributes<HTMLDivElement>) {
  return (
    <div
      role="presentation"
      data-testid="skeleton"
      className={cn('skeleton', className)}
      {...props}
    />
  )
}
