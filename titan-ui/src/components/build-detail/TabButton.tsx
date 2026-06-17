/**
 * TabButton — accessible tab in the build-detail right pane.
 * Extracted from `/builds/$buildId.tsx` (ticket #851).
 */
import type { ReactNode } from 'react'
import { cn } from '@/lib/utils'

export function TabButton({
  active,
  onClick,
  children,
}: {
  active: boolean
  onClick: () => void
  children: ReactNode
}) {
  return (
    <button
      type="button"
      role="tab"
      aria-selected={active}
      className={cn('tab', active && 'active')}
      onClick={onClick}
    >
      {children}
    </button>
  )
}
