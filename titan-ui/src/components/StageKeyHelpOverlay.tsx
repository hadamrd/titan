/**
 * StageKeyHelpOverlay — calm keymap cheatsheet for /builds/$buildId (closes #688).
 *
 * <p>Opened by pressing {@code ?} on the build detail page (see
 * {@link useStageKeyNav}). Dismissable via Esc or by clicking the backdrop.
 * The visual language follows the v3 cmd-K sheet: oklch tokens, Geist body,
 * Geist Mono for the key glyphs. No marketing chrome, no animations beyond a
 * subtle fade (skipped under {@code prefers-reduced-motion}).
 */
import { useEffect, useRef } from 'react'

interface KeyRow {
  keys: readonly string[]
  label: string
}

const ROWS: readonly KeyRow[] = [
  { keys: ['j'], label: 'Next failed stage' },
  { keys: ['k'], label: 'Previous failed stage' },
  { keys: ['Shift', 'J'], label: 'Next stage (any status)' },
  { keys: ['Shift', 'K'], label: 'Previous stage (any status)' },
  { keys: ['g', 'g'], label: 'First stage' },
  { keys: ['Shift', 'G'], label: 'Last stage' },
  { keys: ['r'], label: 'Replay build' },
  { keys: ['c'], label: 'Cancel build' },
  { keys: ['t'], label: 'Retry selected stage' },
  { keys: ['?'], label: 'Toggle this help' },
  { keys: ['Esc'], label: 'Dismiss this help' },
]

interface Props {
  open: boolean
  onClose: () => void
}

export function StageKeyHelpOverlay({ open, onClose }: Props): React.ReactElement | null {
  const closeRef = useRef(onClose)
  closeRef.current = onClose

  useEffect(() => {
    if (!open) return
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        e.preventDefault()
        closeRef.current()
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [open])

  if (!open) return null

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-label="Keyboard shortcuts"
      data-testid="stage-key-help-overlay"
      onClick={onClose}
      style={{
        position: 'fixed',
        inset: 0,
        zIndex: 80,
        display: 'grid',
        placeItems: 'center',
        background: 'color-mix(in oklch, var(--background) 70%, transparent)',
        backdropFilter: 'blur(4px)',
      }}
    >
      <div
        onClick={(e) => e.stopPropagation()}
        style={{
          minWidth: 360,
          maxWidth: 480,
          background: 'var(--card)',
          color: 'var(--card-foreground)',
          border: '1px solid var(--border)',
          borderRadius: 12,
          padding: '20px 22px',
          boxShadow: '0 12px 40px color-mix(in oklch, var(--foreground) 12%, transparent)',
          fontFeatureSettings: "'cv11','ss01','ss02'",
        }}
      >
        <div
          style={{
            display: 'flex',
            justifyContent: 'space-between',
            alignItems: 'baseline',
            marginBottom: 14,
          }}
        >
          <h2
            style={{
              fontSize: 14,
              fontWeight: 600,
              letterSpacing: '0.01em',
              margin: 0,
            }}
          >
            Stage keyboard shortcuts
          </h2>
          <button
            type="button"
            onClick={onClose}
            aria-label="Close keyboard shortcuts"
            style={{
              background: 'transparent',
              border: 0,
              color: 'var(--muted-foreground)',
              fontSize: 12,
              cursor: 'pointer',
              padding: 4,
            }}
          >
            Esc
          </button>
        </div>
        <dl style={{ display: 'grid', gap: 8, margin: 0 }}>
          {ROWS.map((row) => (
            <div
              key={row.keys.join('+')}
              style={{
                display: 'flex',
                justifyContent: 'space-between',
                alignItems: 'center',
                gap: 16,
                fontSize: 13,
              }}
            >
              <dt style={{ color: 'var(--foreground)' }}>{row.label}</dt>
              <dd style={{ display: 'flex', gap: 4, margin: 0 }}>
                {row.keys.map((k, i) => (
                  <kbd
                    key={`${k}-${i}`}
                    style={{
                      fontFamily: 'var(--font-mono, ui-monospace, monospace)',
                      fontSize: 11,
                      padding: '2px 7px',
                      border: '1px solid var(--border)',
                      borderRadius: 6,
                      background: 'var(--muted)',
                      color: 'var(--foreground)',
                      lineHeight: 1.4,
                      minWidth: 18,
                      textAlign: 'center',
                    }}
                  >
                    {k}
                  </kbd>
                ))}
              </dd>
            </div>
          ))}
        </dl>
      </div>
    </div>
  )
}
