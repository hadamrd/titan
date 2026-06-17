import * as React from 'react'
import { cva, type VariantProps } from 'class-variance-authority'
import { cn } from '@/lib/utils'

/**
 * Button — v2 system. Uses the `.btn`, `.btn-primary`, `.btn-ghost`,
 * `.btn-danger`, `.btn-sm` classes from tokens.css. Keeps the existing CVA
 * variant/size API for source-compat (`variant="default" | "outline" |
 * "destructive" | "secondary" | "ghost" | "link"`, `size="default" | "sm" |
 * "lg" | "icon"`).
 *
 * shadcn callers still type-check; the visual mapping is:
 *   default     → btn-primary
 *   destructive → btn-danger
 *   outline     → btn (default)
 *   secondary   → btn (default)
 *   ghost / link→ btn-ghost
 */
const buttonVariants = cva('btn', {
  variants: {
    variant: {
      default: 'btn-primary',
      destructive: 'btn-danger',
      outline: '',
      secondary: '',
      ghost: 'btn-ghost',
      link: 'btn-ghost',
    },
    size: {
      default: '',
      sm: 'btn-sm',
      lg: '',
      icon: 'btn-sm',
    },
  },
  defaultVariants: {
    variant: 'default',
    size: 'default',
  },
})

export interface ButtonProps
  extends React.ButtonHTMLAttributes<HTMLButtonElement>,
    VariantProps<typeof buttonVariants> {}

const Button = React.forwardRef<HTMLButtonElement, ButtonProps>(
  ({ className, variant, size, ...props }, ref) => {
    return (
      <button className={cn(buttonVariants({ variant, size }), className)} ref={ref} {...props} />
    )
  },
)
Button.displayName = 'Button'

export { Button, buttonVariants }
