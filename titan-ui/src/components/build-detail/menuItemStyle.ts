import type { CSSProperties } from 'react'

/** Shared style for items inside the ReplayMenu popover. */
export function menuItemStyle(disabled: boolean): CSSProperties {
  return {
    display: 'block',
    width: '100%',
    textAlign: 'left',
    padding: '8px 10px',
    background: 'transparent',
    border: 'none',
    borderRadius: 4,
    color: disabled ? 'var(--fg-dim)' : 'var(--fg)',
    cursor: disabled ? 'not-allowed' : 'pointer',
    opacity: disabled ? 0.6 : 1,
  }
}
