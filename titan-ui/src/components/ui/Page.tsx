import * as React from 'react'
import { cn } from '@/lib/utils'

/**
 * Page frame — the shared container every route renders inside (UX chart H1).
 *
 * Before this existed, only 2/16 routes set a max-width, so content floated
 * marooned in the top-left of a 1440px canvas (the /profile#tokens failure).
 * `<PageContainer>` gives a centred, max-width, consistently-padded column;
 * `<PageHeader>` gives a consistent title / description / actions row.
 *
 * Usage:
 *   <PageContainer>
 *     <PageHeader title="Builds" description="Every run, newest first" actions={<Button>New</Button>} />
 *     ...content...
 *   </PageContainer>
 *
 * Widths: `default` (max-w-screen-xl) for lists/dashboards; `narrow`
 * (max-w-3xl) for forms/detail/settings; `wide` (max-w-screen-2xl) for
 * data-dense tables that genuinely need the room.
 */

type Width = 'default' | 'narrow' | 'wide'

const WIDTH: Record<Width, string> = {
  narrow: 'max-w-3xl',
  default: 'max-w-screen-xl',
  wide: 'max-w-screen-2xl',
}

export interface PageContainerProps extends React.HTMLAttributes<HTMLDivElement> {
  width?: Width
}

export const PageContainer = React.forwardRef<HTMLDivElement, PageContainerProps>(
  ({ width = 'default', className, ...props }, ref) => (
    <div
      ref={ref}
      className={cn('mx-auto w-full px-6 py-6 md:px-8 md:py-8', WIDTH[width], className)}
      {...props}
    />
  ),
)
PageContainer.displayName = 'PageContainer'

// Omit the native `title` attribute (a `string` tooltip attr) so our richer
// `title: React.ReactNode` prop does not collide with it (TS2430). Same fix
// landed independently on trunk in #1194.
export interface PageHeaderProps extends Omit<React.HTMLAttributes<HTMLElement>, 'title'> {
  title: React.ReactNode
  description?: React.ReactNode
  /** Right-aligned actions — keep to ONE primary action (UX chart H5). */
  actions?: React.ReactNode
}

export const PageHeader = React.forwardRef<HTMLElement, PageHeaderProps>(
  ({ title, description, actions, className, ...props }, ref) => (
    <header
      ref={ref}
      className={cn(
        'mb-6 flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between',
        className,
      )}
      {...props}
    >
      <div className="min-w-0">
        <h1 className="truncate text-xl font-semibold tracking-tight text-foreground">{title}</h1>
        {description ? (
          // Stable hook for e2e header-copy oracles (e.g. the /builds
          // jobs-vs-builds-count guard in 21-builds-page-no-rot.spec.ts).
          <p className="mt-1 text-sm text-muted-foreground" data-testid="page-description">
            {description}
          </p>
        ) : null}
      </div>
      {actions ? <div className="flex shrink-0 items-center gap-2">{actions}</div> : null}
    </header>
  ),
)
PageHeader.displayName = 'PageHeader'
