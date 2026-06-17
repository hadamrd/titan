/**
 * DataTable — Titan list primitive (#850 + design 64). All visual tokens come
 * from src/styles/tokens.css `/* tables (#850 / design 64) *\/`; no magic
 * numbers here. NOT in V1 (deliberate): virtualization, multi-column sort,
 * column resize, bulk selection, inline row expansion — see design 64.
 */
import {
  type CSSProperties,
  Fragment,
  type KeyboardEvent as ReactKeyboardEvent,
  type ReactElement,
  type ReactNode,
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react'
import { ChevronDown, ChevronRight } from 'lucide-react'
import {
  closestCenter,
  DndContext,
  KeyboardSensor,
  PointerSensor,
  useSensor,
  useSensors,
  type DragEndEvent,
} from '@dnd-kit/core'
import {
  arrayMove,
  SortableContext,
  sortableKeyboardCoordinates,
  useSortable,
  verticalListSortingStrategy,
} from '@dnd-kit/sortable'
import { CSS } from '@dnd-kit/utilities'
import { GripVertical } from 'lucide-react'

export type SortDir = 'asc' | 'desc'

export interface DataTableColumn<T> {
  key: string
  header: ReactNode
  cell: (row: T) => ReactNode
  width?: string
  numeric?: boolean
  sortable?: boolean
  align?: 'left' | 'right'
}

export interface DataTableJumpTo {
  number?: (raw: string) => void
  sha?: (raw: string) => void
  branch?: (raw: string) => void
}

/**
 * Optional drag-to-reorder. When supplied, a grip-handle column is prepended,
 * rows become sortable, and `onReorder` fires with the new ordering (the
 * row keys, in display order) after a drop. Sorting is disabled while reorder
 * is active — the manual order is the source of truth. Gated behind this prop
 * so existing read-only tables (the common case) are completely unaffected.
 */
export interface DataTableReorder {
  onReorder: (orderedKeys: Array<string | number>) => void
  disabled?: boolean
  /** Accessible verb for the grip handle, e.g. "reorder task". */
  handleLabel?: (rowKey: string | number) => string
}

/**
 * Optional inline row expansion. When supplied, a leading chevron column is
 * prepended, rows toggle a detail panel on click, and only one row is open at
 * a time. Mutually exclusive with `reorder`. Gated behind this prop so
 * read-only tables are unaffected.
 */
export interface DataTableExpandable<T> {
  render: (row: T) => ReactNode
  /** data-testid for the detail <tr> (e.g. `audit-row-details-42`). */
  rowDetailTestId?: (row: T) => string | undefined
}

export interface DataTableProps<T> {
  rows: readonly T[]
  columns: readonly DataTableColumn<T>[]
  rowKey: (row: T) => string | number
  /** Per-row data-testid. Stable handle for Playwright + adversarial tests. */
  rowTestId?: (row: T) => string | undefined
  /** Extra per-row data-* (e.g. data-event-type) — merged into the <tr>. */
  rowData?: (row: T) => Record<string, string | undefined>
  onRowClick?: (row: T) => void
  isInflight?: (row: T) => boolean
  isFailed?: (row: T) => boolean
  isLoading?: boolean
  error?: { message: string; onRetry?: () => void } | null
  emptyMessage?: ReactNode
  emptyCta?: { label: string; to?: string; onClick?: () => void }
  total?: number
  pageSize?: number
  onNewer?: () => void
  onOlder?: () => void
  hasNewer?: boolean
  hasOlder?: boolean
  /** URL key for `?<key>_after=…&<key>_sort=col:dir`. */
  routeSearchKey?: string
  jumpTo?: DataTableJumpTo
  testId?: string
  onSortChange?: (sort: { key: string; dir: SortDir } | null) => void
  reorder?: DataTableReorder
  expandable?: DataTableExpandable<T>
}

interface UrlSort { key: string; dir: SortDir }

function parseSort(raw: unknown): UrlSort | null {
  if (typeof raw !== 'string' || raw.length === 0) return null
  const i = raw.lastIndexOf(':')
  if (i <= 0 || i === raw.length - 1) return null
  const dir = raw.slice(i + 1)
  if (dir !== 'asc' && dir !== 'desc') return null
  return { key: raw.slice(0, i), dir }
}

export function DataTable<T>(props: DataTableProps<T>): ReactElement {
  const {
    rows, columns, rowKey, rowTestId, rowData, onRowClick, isInflight, isFailed,
    isLoading = false, error = null, emptyMessage, emptyCta,
    total, pageSize: _pageSize, onNewer, onOlder, hasNewer = false, hasOlder = false,
    routeSearchKey, jumpTo, testId, onSortChange, reorder, expandable,
  } = props

  // Invariant: `reorder` and `expandable` each prepend a distinct lead column
  // and take over the row-render branch in incompatible ways (reorder routes
  // through SortableContext + a 1-col grip; expandable adds a chevron col +
  // click-to-toggle detail rows). Supplying both desyncs leadCols from the
  // actual prepended cells (broken colSpan on detail rows) AND silently drops
  // the expand path. The JSDoc says they're mutually exclusive — enforce it at
  // runtime instead of letting the layout break go unnoticed.
  if (reorder !== undefined && expandable !== undefined) {
    throw new Error(
      'DataTable: `reorder` and `expandable` are mutually exclusive — pass at most one.',
    )
  }

  const [expandedKey, setExpandedKey] = useState<string | number | null>(null)
  const leadCols = (reorder ? 1 : 0) + (expandable ? 1 : 0)
  const reorderActive = reorder !== undefined && !isLoading && !error && rows.length > 0
  const dndSensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 4 } }),
    useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates }),
  )
  const onDragEnd = useCallback(
    (event: DragEndEvent) => {
      if (!reorder) return
      const { active, over } = event
      if (!over || active.id === over.id) return
      const keys = rows.map((r) => rowKey(r))
      const from = keys.findIndex((k) => String(k) === String(active.id))
      const to = keys.findIndex((k) => String(k) === String(over.id))
      if (from < 0 || to < 0) return
      reorder.onReorder(arrayMove(keys, from, to))
    },
    [reorder, rows, rowKey],
  )

  // URL state via window.location so the primitive works inside or outside a
  // RouterProvider. TanStack Router observes history.pushState normally.
  const [localSort, setLocalSort] = useState<UrlSort | null>(null)
  const urlSort = useUrlSort(routeSearchKey)
  const activeSort = routeSearchKey ? urlSort : localSort

  const setSort = useCallback((next: UrlSort | null) => {
    onSortChange?.(next)
    if (routeSearchKey) {
      writeUrlSort(routeSearchKey, next)
    } else {
      setLocalSort(next)
    }
  }, [routeSearchKey, onSortChange])

  const toggleSort = useCallback((key: string) => {
    if (activeSort?.key !== key) return setSort({ key, dir: 'desc' })
    if (activeSort.dir === 'desc') return setSort({ key, dir: 'asc' })
    return setSort(null)
  }, [activeSort, setSort])

  // ── Sticky-collapse on scroll ────────────────────────────────────────────
  const rootRef = useRef<HTMLDivElement | null>(null)
  const [collapsed, setCollapsed] = useState(false)
  useEffect(() => {
    const node = rootRef.current
    if (!node) return
    const scroller: HTMLElement | Window = findScrollParent(node) ?? window
    const onScroll = () => {
      const y = scroller instanceof Window ? window.scrollY : (scroller as HTMLElement).scrollTop
      const threshold = readCssNumber(node, '--header-collapse-y', 56)
      setCollapsed(y > threshold)
    }
    scroller.addEventListener('scroll', onScroll, { passive: true })
    onScroll()
    return () => scroller.removeEventListener('scroll', onScroll)
  }, [])

  // ── Keyboard nav (j/k/J/K/gg/G/Enter) ────────────────────────────────────
  const tbodyRef = useRef<HTMLTableSectionElement | null>(null)
  const [focusIdx, setFocusIdx] = useState(0)
  const lastGRef = useRef<number>(0)
  const onKeyDown = useCallback((e: ReactKeyboardEvent<HTMLTableElement>) => {
    const tag = (e.target as HTMLElement).tagName
    if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') return
    const n = rows.length
    if (n === 0) return
    const k = e.key
    if (k === 'j' && !e.shiftKey) { e.preventDefault(); setFocusIdx((i) => Math.min(n - 1, i + 1)) }
    else if (k === 'k' && !e.shiftKey) { e.preventDefault(); setFocusIdx((i) => Math.max(0, i - 1)) }
    else if (k === 'J' || (k === 'j' && e.shiftKey)) { e.preventDefault(); if (onOlder && hasOlder) onOlder() }
    else if (k === 'K' || (k === 'k' && e.shiftKey)) { e.preventDefault(); if (onNewer && hasNewer) onNewer() }
    else if (k === 'G') { e.preventDefault(); setFocusIdx(n - 1) }
    else if (k === 'g') {
      const now = Date.now()
      if (now - lastGRef.current < 500) { e.preventDefault(); setFocusIdx(0); lastGRef.current = 0 }
      else lastGRef.current = now
    } else if (k === 'Enter') {
      const r = rows[focusIdx]
      if (!r) return
      if (expandable) {
        e.preventDefault()
        const key = rowKey(r)
        setExpandedKey((cur) => (cur === key ? null : key))
      } else if (onRowClick) {
        e.preventDefault()
        onRowClick(r)
      }
    }
  }, [rows, focusIdx, onRowClick, onNewer, onOlder, hasNewer, hasOlder, expandable, rowKey])

  useEffect(() => {
    tbodyRef.current?.querySelector<HTMLElement>(`tr[data-row-idx="${focusIdx}"]`)?.focus({ preventScroll: false })
  }, [focusIdx, rows])

  const effectiveTotal = total ?? rows.length
  const countLabel = rows.length === 0 ? `0 of ${effectiveTotal}` : `1–${rows.length} of ${effectiveTotal}`

  const sortedRows = useMemo(() => {
    if (reorder) return rows
    if (!activeSort || onNewer || onOlder) return rows
    const col = columns.find((c) => c.key === activeSort.key)
    if (!col) return rows
    const copy = rows.slice()
    copy.sort((a, b) => {
      const av = col.cell(a), bv = col.cell(b)
      const as = String(typeof av === 'string' || typeof av === 'number' ? av : '')
      const bs = String(typeof bv === 'string' || typeof bv === 'number' ? bv : '')
      return as < bs ? -1 : as > bs ? 1 : 0
    })
    if (activeSort.dir === 'desc') copy.reverse()
    return copy
  }, [rows, activeSort, columns, onNewer, onOlder])

  const content = (
    <div ref={rootRef} data-testid={testId}>
      {collapsed && (
        <div className="tt-collapsed-bar" data-testid={testId ? `${testId}-collapsed` : undefined}>
          {activeSort && (
            <span>{activeSort.dir === 'asc' ? '↑' : '↓'} sort: {activeSort.key} · {activeSort.dir}</span>
          )}
          <span className="tt-count">{countLabel}</span>
        </div>
      )}
      {!isLoading && !error && rows.length > 0 && (
        <Pager countLabel={`showing ${countLabel}`} onNewer={onNewer} onOlder={onOlder}
          hasNewer={hasNewer} hasOlder={hasOlder} jumpTo={jumpTo} testId={testId} />
      )}
      <table className="tt" data-collapsed={collapsed} onKeyDown={onKeyDown} tabIndex={-1}>
        <colgroup>
          {reorder && <col key="__grip__" style={{ width: '36px' }} />}
          {expandable && <col key="__expand__" style={{ width: '32px' }} />}
          {columns.map((c) => <col key={c.key} style={c.width ? { width: c.width } : undefined} />)}
        </colgroup>
        <thead><tr>
          {reorder && <th key="__grip__" aria-label="reorder" />}
          {expandable && <th key="__expand__" aria-label="expand" />}
          {columns.map((c) => {
          const sortable = c.sortable === true
          const isSorted = activeSort?.key === c.key
          const styl = c.align === 'right' ? ({ textAlign: 'right' } as CSSProperties) : undefined
          return (
            <th key={c.key} data-sortable={sortable} style={styl}
              aria-sort={isSorted ? (activeSort.dir === 'asc' ? 'ascending' : 'descending') : 'none'}
              onClick={sortable ? () => toggleSort(c.key) : undefined}>
              {c.header}
              {isSorted && <span className="tt-sort" aria-hidden>{activeSort.dir === 'asc' ? '↑' : '↓'}</span>}
            </th>
          )
        })}</tr></thead>
        <tbody ref={tbodyRef}>
          {isLoading ? (
            Array.from({ length: 5 }).map((_, i) => (
              <tr key={`skel-${i}`} className="tt-skel-row" data-testid={testId ? `${testId}-skel` : undefined}>
                <td colSpan={columns.length + leadCols} aria-hidden />
              </tr>
            ))
          ) : reorderActive && reorder ? (
            // DndContext is hoisted to wrap the whole table (below) — it emits
            // helper <div>s that must not nest inside <tbody>. SortableContext
            // renders no extra DOM, so it is safe here.
            <SortableContext items={sortedRows.map((r) => String(rowKey(r)))} strategy={verticalListSortingStrategy}>
              {sortedRows.map((r, idx) => (
                <SortableDataRow
                  key={rowKey(r)}
                  id={String(rowKey(r))}
                  idx={idx}
                  row={r}
                  columns={columns}
                  rowTestId={rowTestId}
                  rowData={rowData}
                  onRowClick={onRowClick}
                  isInflight={isInflight}
                  isFailed={isFailed}
                  disabled={reorder.disabled ?? false}
                  handleLabel={reorder.handleLabel?.(rowKey(r)) ?? `Drag to reorder ${rowKey(r)}`}
                />
              ))}
            </SortableContext>
          ) : (
            sortedRows.map((r, idx) => {
              const key = rowKey(r)
              const isExpanded = expandable !== undefined && expandedKey === key
              const clickable = onRowClick !== undefined || expandable !== undefined
              const handleClick = expandable
                ? () => setExpandedKey((cur) => (cur === key ? null : key))
                : onRowClick
                  ? () => onRowClick(r)
                  : undefined
              return (
                <Fragment key={key}>
                  <tr data-row-idx={idx} data-clickable={clickable}
                    data-testid={rowTestId?.(r)}
                    data-inflight={isInflight?.(r) ?? false} data-failed={isFailed?.(r) ?? false}
                    aria-expanded={expandable ? isExpanded : undefined}
                    tabIndex={clickable ? 0 : -1}
                    onClick={handleClick}
                    {...(rowData ? rowData(r) : {})}>
                    {expandable && (
                      <td className="tt-expand-cell" aria-hidden>
                        {isExpanded ? <ChevronDown size={12} /> : <ChevronRight size={12} />}
                      </td>
                    )}
                    {columns.map((c) => (
                      <td key={c.key} className={c.numeric ? 'num' : undefined}
                        style={c.align === 'right' ? ({ textAlign: 'right' } as CSSProperties) : undefined}>
                        {c.cell(r)}
                      </td>
                    ))}
                  </tr>
                  {isExpanded && expandable && (
                    <tr className="tt-detail-row" data-testid={expandable.rowDetailTestId?.(r)}>
                      <td colSpan={columns.length + leadCols}>{expandable.render(r)}</td>
                    </tr>
                  )}
                </Fragment>
              )
            })
          )}
        </tbody>
      </table>

      {!isLoading && error && (
        <div className="tt-empty" data-testid={testId ? `${testId}-error` : undefined}>
          <span className="tt-err-prefix" aria-hidden />
          <span>━━━ failed to load — {error.message}
            {error.onRetry && (<>{' · '}<a href="#" onClick={(e) => { e.preventDefault(); error.onRetry?.() }}>retry</a></>)}
          </span>
        </div>
      )}
      {!isLoading && !error && rows.length === 0 && (
        <div className="tt-empty" data-testid={testId ? `${testId}-empty` : undefined}>
          <span>{emptyMessage ?? 'No data.'}</span>
          {emptyCta && (<>{' '}<a href={emptyCta.to ?? '#'}
            onClick={(e) => { if (emptyCta.onClick) { e.preventDefault(); emptyCta.onClick() } }}>{emptyCta.label}</a></>)}
        </div>
      )}
    </div>
  )

  if (reorderActive && reorder) {
    return (
      <DndContext sensors={dndSensors} collisionDetection={closestCenter} onDragEnd={onDragEnd}>
        {content}
      </DndContext>
    )
  }
  return content
}

interface SortableDataRowProps<T> {
  id: string
  idx: number
  row: T
  columns: readonly DataTableColumn<T>[]
  rowTestId?: (row: T) => string | undefined
  rowData?: (row: T) => Record<string, string | undefined>
  onRowClick?: (row: T) => void
  isInflight?: (row: T) => boolean
  isFailed?: (row: T) => boolean
  disabled: boolean
  handleLabel: string
}

function SortableDataRow<T>({
  id, idx, row, columns, rowTestId, rowData, onRowClick, isInflight, isFailed, disabled, handleLabel,
}: SortableDataRowProps<T>): ReactElement {
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } = useSortable({ id, disabled })
  const clickable = onRowClick !== undefined
  const style: CSSProperties = {
    transform: CSS.Transform.toString(transform),
    transition,
    opacity: isDragging ? 0.6 : undefined,
    background: isDragging ? 'var(--surface-2)' : undefined,
  }
  return (
    <tr ref={setNodeRef} style={style} data-row-idx={idx} data-clickable={clickable}
      data-testid={rowTestId?.(row)}
      data-inflight={isInflight?.(row) ?? false} data-failed={isFailed?.(row) ?? false}
      tabIndex={clickable ? 0 : -1}
      onClick={clickable ? () => onRowClick(row) : undefined}
      {...(rowData ? rowData(row) : {})}>
      <td className="tt-grip-cell">
        <button type="button" className="tt-grip" aria-label={handleLabel}
          title={disabled ? 'Saving…' : 'Drag to reorder'} disabled={disabled}
          onClick={(e) => e.stopPropagation()} {...attributes} {...listeners}>
          <GripVertical size={14} aria-hidden />
        </button>
      </td>
      {columns.map((c) => (
        <td key={c.key} className={c.numeric ? 'num' : undefined}
          style={c.align === 'right' ? ({ textAlign: 'right' } as CSSProperties) : undefined}>
          {c.cell(row)}
        </td>
      ))}
    </tr>
  )
}

interface PagerProps {
  countLabel: string; onNewer?: () => void; onOlder?: () => void
  hasNewer: boolean; hasOlder: boolean; jumpTo?: DataTableJumpTo; testId?: string
}
function Pager({ countLabel, onNewer, onOlder, hasNewer, hasOlder, jumpTo, testId }: PagerProps): ReactElement {
  const showCursor = onNewer !== undefined || onOlder !== undefined
  return (
    <div className="tt-pager" data-testid={testId ? `${testId}-pager` : undefined}>
      <span className="tt-pager-count">{countLabel}</span>
      {showCursor && (<>
        <button type="button" className="tt-cursor-btn" disabled={!hasNewer || !onNewer}
          onClick={onNewer} data-testid={testId ? `${testId}-newer` : undefined}>↑ newer</button>
        <button type="button" className="tt-cursor-btn" disabled={!hasOlder || !onOlder}
          onClick={onOlder} data-testid={testId ? `${testId}-older` : undefined}>older ↓</button>
      </>)}
      {jumpTo && <JumpToInputs jumpTo={jumpTo} testId={testId} />}
    </div>
  )
}

function JumpToInputs({ jumpTo, testId }: { jumpTo: DataTableJumpTo; testId?: string }): ReactElement {
  const [vals, setVals] = useState({ num: '', sha: '', branch: '' })
  const set = (k: 'num' | 'sha' | 'branch') => (e: React.ChangeEvent<HTMLInputElement>) =>
    setVals((s) => ({ ...s, [k]: e.target.value }))
  const fire = (k: 'num' | 'sha' | 'branch', h?: (s: string) => void) =>
    (e: ReactKeyboardEvent<HTMLInputElement>) => {
      if (e.key !== 'Enter') return
      const v = vals[k].trim()
      if (v !== '' && h) { h(v); setVals((s) => ({ ...s, [k]: '' })) }
    }
  const slots: Array<['num' | 'sha' | 'branch', string, string, ((s: string) => void) | undefined]> = [
    ['num', '#', 'jump to build number', jumpTo.number],
    ['sha', 'sha', 'jump to commit sha', jumpTo.sha],
    ['branch', 'branch', 'jump to branch', jumpTo.branch],
  ]
  return (
    <div className="tt-jump" data-testid={testId ? `${testId}-jump` : undefined}>
      <label>jump to:</label>
      {slots.map(([k, ph, aria, h]) => h && (
        <input key={k} type="text" placeholder={ph} aria-label={aria}
          value={vals[k]} onChange={set(k)} onKeyDown={fire(k, h)} />
      ))}
    </div>
  )
}

function useUrlSort(routeSearchKey: string | undefined): UrlSort | null {
  const [v, setV] = useState<UrlSort | null>(() => readUrlSort(routeSearchKey))
  useEffect(() => {
    if (!routeSearchKey) return
    const sync = () => setV(readUrlSort(routeSearchKey))
    window.addEventListener('popstate', sync)
    // TanStack Router pushes via history.pushState which doesn't fire
    // popstate — patch pushState to fire a synthetic event once.
    const orig = window.history.pushState
    window.history.pushState = function patched(...args: Parameters<typeof orig>) {
      const r = orig.apply(this, args)
      window.dispatchEvent(new Event('tt-urlchange'))
      return r
    }
    window.addEventListener('tt-urlchange', sync)
    sync()
    return () => {
      window.removeEventListener('popstate', sync)
      window.removeEventListener('tt-urlchange', sync)
      window.history.pushState = orig
    }
  }, [routeSearchKey])
  return v
}

function readUrlSort(routeSearchKey: string | undefined): UrlSort | null {
  if (!routeSearchKey || typeof window === 'undefined') return null
  const sp = new URLSearchParams(window.location.search)
  return parseSort(sp.get(`${routeSearchKey}_sort`))
}

function writeUrlSort(routeSearchKey: string, next: UrlSort | null): void {
  if (typeof window === 'undefined') return
  const url = new URL(window.location.href)
  const field = `${routeSearchKey}_sort`
  if (next === null) url.searchParams.delete(field)
  else url.searchParams.set(field, `${next.key}:${next.dir}`)
  window.history.pushState({}, '', url.toString())
}

function findScrollParent(node: HTMLElement): HTMLElement | null {
  let cur: HTMLElement | null = node.parentElement
  while (cur) {
    const s = window.getComputedStyle(cur)
    if (/(auto|scroll|overlay)/.test(s.overflowY)) return cur
    cur = cur.parentElement
  }
  return null
}

function readCssNumber(node: HTMLElement, prop: string, fallback: number): number {
  const raw = window.getComputedStyle(node).getPropertyValue(prop).trim()
  if (raw === '') return fallback
  const n = parseInt(raw, 10)
  return Number.isFinite(n) ? n : fallback
}
