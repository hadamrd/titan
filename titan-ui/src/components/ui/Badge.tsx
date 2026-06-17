import { cn } from '@/lib/utils'

export type BadgeVariant = 'default' | 'success' | 'fail' | 'warn' | 'info' | 'accent'

interface BadgeProps extends React.HTMLAttributes<HTMLSpanElement> {
  variant?: BadgeVariant
}

export function Badge({ variant = 'default', className, ...props }: BadgeProps) {
  return (
    <span
      className={cn('badge', variant !== 'default' && variant, className)}
      {...props}
    />
  )
}
