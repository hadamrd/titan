import * as React from 'react'
import { cn } from '@/lib/utils'

export interface InputProps extends React.InputHTMLAttributes<HTMLInputElement> {}

/**
 * Input — uses `.field` (v2 system). Focus halo is `--accent-soft` so it
 * dials with the accent picker. :focus-visible (defined globally) renders
 * the accent outline on top of that — both states are intentional.
 */
const Input = React.forwardRef<HTMLInputElement, InputProps>(
  ({ className, type, ...props }, ref) => {
    return <input type={type} className={cn('field', className)} ref={ref} {...props} />
  },
)
Input.displayName = 'Input'

export { Input }
