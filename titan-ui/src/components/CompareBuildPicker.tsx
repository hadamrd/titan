/**
 * Header-level entry point for the side-by-side build comparison view
 * (closes #716).
 *
 * <p>UX choice: a small inline popover (NOT a modal). Modals are too heavy
 * for a one-tap "pick the previous build" gesture — the SRE is already
 * mid-flow on /builds/{id} and doesn't want their context dimmed. The
 * popover lists up to 10 same-job builds, defaulting focus to the previous
 * build so Enter immediately navigates.
 *
 * <p>Navigation rule: clicking a peer build navigates to
 * {@code /builds/compare?a=<peer>&b=<current>} — current build is B (right
 * side), peer is A (left side). That matches the issue's "build #41 vs #42"
 * mental model where the older build sits on the left.
 *
 * <p>Same-job-only by construction: we filter the job's recent builds
 * client-side and never offer the current build as a peer (an A==B
 * comparison is a sanity-test surface, not a user-driven flow).
 */
import { useEffect, useRef, useState } from 'react'
import { useNavigate } from '@tanstack/react-router'
import { Button } from '@/components/ui/Button'
import { StatusBadge } from '@/components/StatusBadge'
import { useJobBuilds } from '@/api/hooks'
import { formatDate } from '@/lib/format'

const PICKER_MAX_BUILDS = 10

export interface CompareBuildPickerProps {
  jobId: number
  currentBuildId: number
}

export function CompareBuildPicker({ jobId, currentBuildId }: CompareBuildPickerProps) {
  const [open, setOpen] = useState(false)
  const rootRef = useRef<HTMLDivElement | null>(null)
  const navigate = useNavigate()
  // Pull a slightly larger window so we have headroom after filtering out
  // the current build — server orders newest-first so the slice we want is
  // the leading PICKER_MAX_BUILDS after filter.
  const { data } = useJobBuilds(open ? jobId : undefined, 0, PICKER_MAX_BUILDS + 1)

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

  const peers = (data?.items ?? [])
    .filter((b) => b.id !== currentBuildId)
    .slice(0, PICKER_MAX_BUILDS)
  // Default-target: the most-recent peer (typically the immediately-previous
  // build in chronological order — useJobBuilds returns newest-first).
  const defaultPeer = peers[0] ?? null

  const goCompare = (peerId: number) => {
    setOpen(false)
    // Path-form (#1077): /builds/<peer>/compare/<current>. Peer = A (left side,
    // older build); current = B (right side, the build the SRE was looking at).
    void navigate({
      to: '/builds/$buildId/compare/$other',
      params: { buildId: String(peerId), other: String(currentBuildId) },
    })
  }

  return (
    <div
      ref={rootRef}
      style={{ position: 'relative' }}
      data-testid="compare-picker-root"
    >
      <Button
        variant="outline"
        size="sm"
        data-testid="compare-picker-trigger"
        aria-haspopup="menu"
        aria-expanded={open}
        onClick={() => setOpen((v) => !v)}
        title="Compare this build with another"
      >
        Compare ▾
      </Button>
      {open && (
        <div
          role="menu"
          data-testid="compare-picker-menu"
          aria-label="Pick a build to compare against"
          style={{
            position: 'absolute',
            right: 0,
            top: 'calc(100% + 4px)',
            minWidth: 280,
            maxHeight: 360,
            overflow: 'auto',
            background: 'var(--bg-elevated, var(--bg-2))',
            border: '1px solid var(--border)',
            borderRadius: 6,
            boxShadow: '0 6px 20px rgba(0,0,0,0.18)',
            zIndex: 30,
            padding: 4,
            fontSize: 12,
          }}
        >
          {peers.length === 0 ? (
            <div
              data-testid="compare-picker-empty"
              style={{
                padding: '8px 10px',
                color: 'var(--fg-dim)',
              }}
            >
              No other builds for this job yet.
            </div>
          ) : (
            peers.map((b) => {
              const isDefault = defaultPeer !== null && b.id === defaultPeer.id
              return (
                <button
                  key={b.id}
                  type="button"
                  role="menuitem"
                  data-testid={`compare-picker-item-${b.id}`}
                  data-default={isDefault ? 'true' : undefined}
                  autoFocus={isDefault}
                  onClick={() => goCompare(b.id)}
                  title={`Compare against #${b.buildNumber}`}
                  style={{
                    display: 'flex',
                    width: '100%',
                    alignItems: 'center',
                    gap: 10,
                    padding: '6px 8px',
                    background: 'transparent',
                    border: 'none',
                    borderRadius: 4,
                    color: 'var(--fg)',
                    cursor: 'pointer',
                    textAlign: 'left',
                  }}
                >
                  <span
                    style={{
                      fontFamily: 'var(--font-mono)',
                      minWidth: 38,
                      color: 'var(--fg-muted)',
                    }}
                  >
                    #{b.buildNumber}
                  </span>
                  <StatusBadge status={b.status} />
                  <span
                    style={{
                      marginLeft: 'auto',
                      color: 'var(--fg-dim)',
                      fontFamily: 'var(--font-mono)',
                    }}
                  >
                    {formatDate(b.startedAt ?? b.queuedAt, 'relative')}
                  </span>
                </button>
              )
            })
          )}
        </div>
      )}
    </div>
  )
}
