import * as React from 'react'
import { cn } from '@/lib/utils'

/**
 * Table — v2 restyle. The new system favours `.row-list` divs over real
 * `<table>` markup, but keeping the existing Table* exports lets callers
 * (builds list, etc.) migrate later without breaking the data layer.
 *
 * Visuals: `border-bottom` rows, `var(--surface-2)` hover, uppercase
 * micro-caps header. No striping (v2 deliberately drops it).
 */

const Table = React.forwardRef<HTMLTableElement, React.HTMLAttributes<HTMLTableElement>>(
  ({ className, ...props }, ref) => (
    <div style={{ width: '100%', overflow: 'auto' }}>
      <table
        ref={ref}
        className={cn(className)}
        style={{
          width: '100%',
          borderCollapse: 'collapse',
          fontSize: 13,
          ...((props.style as React.CSSProperties | undefined) ?? {}),
        }}
        {...props}
      />
    </div>
  ),
)
Table.displayName = 'Table'

const TableHeader = React.forwardRef<
  HTMLTableSectionElement,
  React.HTMLAttributes<HTMLTableSectionElement>
>(({ className, ...props }, ref) => <thead ref={ref} className={cn(className)} {...props} />)
TableHeader.displayName = 'TableHeader'

const TableBody = React.forwardRef<
  HTMLTableSectionElement,
  React.HTMLAttributes<HTMLTableSectionElement>
>(({ className, ...props }, ref) => <tbody ref={ref} className={cn(className)} {...props} />)
TableBody.displayName = 'TableBody'

const TableFooter = React.forwardRef<
  HTMLTableSectionElement,
  React.HTMLAttributes<HTMLTableSectionElement>
>(({ className, ...props }, ref) => <tfoot ref={ref} className={cn(className)} {...props} />)
TableFooter.displayName = 'TableFooter'

const TableRow = React.forwardRef<HTMLTableRowElement, React.HTMLAttributes<HTMLTableRowElement>>(
  ({ className, style, ...props }, ref) => (
    <tr
      ref={ref}
      className={cn(className)}
      style={{
        borderBottom: '1px solid var(--border)',
        transition: 'background 0.08s',
        ...style,
      }}
      onMouseEnter={(e) => {
        ;(e.currentTarget as HTMLElement).style.background = 'var(--surface-2)'
      }}
      onMouseLeave={(e) => {
        ;(e.currentTarget as HTMLElement).style.background = ''
      }}
      {...props}
    />
  ),
)
TableRow.displayName = 'TableRow'

const TableHead = React.forwardRef<
  HTMLTableCellElement,
  React.ThHTMLAttributes<HTMLTableCellElement>
>(({ className, style, ...props }, ref) => (
  <th
    ref={ref}
    className={cn(className)}
    style={{
      textAlign: 'left',
      padding: '10px 12px',
      fontSize: 11,
      letterSpacing: '0.06em',
      textTransform: 'uppercase',
      color: 'var(--fg-faint)',
      fontWeight: 500,
      background: 'var(--bg-2)',
      ...style,
    }}
    {...props}
  />
))
TableHead.displayName = 'TableHead'

const TableCell = React.forwardRef<
  HTMLTableCellElement,
  React.TdHTMLAttributes<HTMLTableCellElement>
>(({ className, style, ...props }, ref) => (
  <td
    ref={ref}
    className={cn(className)}
    style={{
      padding: '12px',
      verticalAlign: 'middle',
      ...style,
    }}
    {...props}
  />
))
TableCell.displayName = 'TableCell'

const TableCaption = React.forwardRef<
  HTMLTableCaptionElement,
  React.HTMLAttributes<HTMLTableCaptionElement>
>(({ className, style, ...props }, ref) => (
  <caption
    ref={ref}
    className={cn(className)}
    style={{ color: 'var(--fg-dim)', fontSize: 12, marginTop: 8, ...style }}
    {...props}
  />
))
TableCaption.displayName = 'TableCaption'

export {
  Table,
  TableHeader,
  TableBody,
  TableFooter,
  TableHead,
  TableRow,
  TableCell,
  TableCaption,
}
