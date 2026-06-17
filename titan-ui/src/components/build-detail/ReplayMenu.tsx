/**
 * ReplayMenu — header-level split button replacing the legacy single Replay
 * button (closes #618). Opens a small popover with two items:
 *   - "Replay entire build"        (always enabled — replays from first node)
 *   - "Replay from selected step"  (disabled when nothing is selected)
 *
 * The per-step-detail "Replay from here" button (next to the selected node's
 * header) is preserved unchanged — this menu adds a header-level entry point
 * alongside it so both choices are visible together up top.
 *
 * Menu items use a discriminated union so the click handler can't be wired
 * to a missing nodeId at compile time.
 */
import { useEffect, useRef, useState } from 'react'
import { Button } from '@/components/ui/Button'
import { menuItemStyle } from './menuItemStyle'

type ReplayChoice =
  | { kind: 'whole'; nodeId: string }
  | { kind: 'fromSelected'; nodeId: string }

export function ReplayMenu({
  firstNodeId,
  selectedNodeId,
  isPending,
  onReplay,
}: {
  firstNodeId: string
  selectedNodeId: string | null
  isPending: boolean
  onReplay: (nodeId: string) => void
}) {
  const [open, setOpen] = useState(false)
  const rootRef = useRef<HTMLDivElement | null>(null)

  // Close on outside click and on Escape — handlers attach only while open.
  useEffect(() => {
    if (!open) return
    const onDocClick = (e: MouseEvent) => {
      if (!rootRef.current) return
      if (!rootRef.current.contains(e.target as Node)) setOpen(false)
    }
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false)
    }
    document.addEventListener('mousedown', onDocClick)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('mousedown', onDocClick)
      document.removeEventListener('keydown', onKey)
    }
  }, [open])

  const pick = (choice: ReplayChoice) => {
    setOpen(false)
    onReplay(choice.nodeId)
  }

  const fromSelectedDisabled = selectedNodeId === null
  const fromSelectedTitle = fromSelectedDisabled
    ? 'Select a step in the Pipeline tab first'
    : 'Re-run starting from the currently selected step (re-uses its predecessors)'

  return (
    <div ref={rootRef} style={{ position: 'relative' }} data-testid="replay-menu-root">
      <Button
        variant="outline"
        size="sm"
        data-testid="replay-menu-trigger"
        aria-haspopup="menu"
        aria-expanded={open}
        disabled={isPending}
        onClick={() => setOpen((v) => !v)}
        title="Replay this build"
      >
        {isPending ? 'Replaying…' : 'Replay ▾'}
      </Button>
      {open && (
        <div
          role="menu"
          data-testid="replay-menu"
          aria-label="Replay options"
          style={{
            position: 'absolute',
            right: 0,
            top: 'calc(100% + 4px)',
            minWidth: 240,
            background: 'var(--bg-elevated, var(--bg-2))',
            border: '1px solid var(--border)',
            borderRadius: 6,
            boxShadow: '0 6px 20px rgba(0,0,0,0.18)',
            zIndex: 30,
            padding: 4,
            fontSize: 12,
          }}
        >
          <button
            type="button"
            role="menuitem"
            data-testid="replay-menu-whole"
            onClick={() => pick({ kind: 'whole', nodeId: firstNodeId })}
            title="Re-run this build end-to-end from the first node"
            style={menuItemStyle(false)}
          >
            <div style={{ fontWeight: 500 }}>Replay entire build</div>
            <div style={{ color: 'var(--fg-dim)', fontSize: 11, marginTop: 2 }}>
              Re-run end-to-end from the first node
            </div>
          </button>
          <button
            type="button"
            role="menuitem"
            data-testid="replay-menu-from-selected"
            disabled={fromSelectedDisabled}
            onClick={
              fromSelectedDisabled || selectedNodeId === null
                ? undefined
                : () => pick({ kind: 'fromSelected', nodeId: selectedNodeId })
            }
            title={fromSelectedTitle}
            aria-disabled={fromSelectedDisabled}
            style={menuItemStyle(fromSelectedDisabled)}
          >
            <div style={{ fontWeight: 500 }}>Replay from selected step</div>
            <div style={{ color: 'var(--fg-dim)', fontSize: 11, marginTop: 2 }}>
              {fromSelectedDisabled
                ? 'Select a step in the Pipeline tab first'
                : 'Re-run starting from the currently selected step'}
            </div>
          </button>
        </div>
      )}
    </div>
  )
}
