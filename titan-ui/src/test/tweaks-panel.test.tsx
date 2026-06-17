/**
 * Adversarial tests for the TweaksPanel (#567).
 *
 * Pinned invariants:
 *   - The gear button opens a popover containing the Theme / Density /
 *     Animations sections (plus the existing Sidebar / Accent / Time-format
 *     sections — we don't assert those, only that the trio the ticket calls
 *     out is present).
 *   - Clicking "Compact" writes `density-compact` on <body>, mirrors the
 *     legacy `[data-density]` attr on <html>, and persists to localStorage.
 *   - Clicking "Reduce" sets `data-motion="reduce"` on <html> and persists.
 *   - A seed in localStorage applied by `initTweaks()` BEFORE React mounts
 *     reaches the DOM without any user interaction.
 *   - Light theme tokens DO exist in this codebase (tokens.css lines under
 *     [data-theme='light']), so we don't assert the "dark-only tooltip"
 *     fallback — the ticket marked it conditional on light tokens being
 *     missing. We do assert that picking Light flips the html attr to
 *     `light`, which is the visible contract.
 */
import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { act, fireEvent, render, screen, cleanup } from '@testing-library/react'
import { TweaksPanel, initTweaks } from '../components/TweaksPanel'

const STORAGE_KEY = 'titan.tweaks.v1'

function resetDom() {
  document.documentElement.removeAttribute('data-theme')
  document.documentElement.removeAttribute('data-density')
  document.documentElement.removeAttribute('data-sidebar')
  document.documentElement.removeAttribute('data-time-format')
  document.documentElement.removeAttribute('data-motion')
  document.documentElement.removeAttribute('style')
  document.body.className = ''
  localStorage.clear()
}

beforeEach(() => {
  resetDom()
})

afterEach(() => {
  cleanup()
  resetDom()
})

describe('TweaksPanel', () => {
  it('opens the popover with Theme / Density / Animations sections', () => {
    render(<TweaksPanel />)
    fireEvent.click(screen.getByLabelText('Open tweaks'))
    // Section labels are uppercase per Field styling but the textContent
    // preserves the source-case 'Theme' / 'Density' / 'Animations'.
    expect(screen.getByText('Theme')).toBeInTheDocument()
    expect(screen.getByText('Density')).toBeInTheDocument()
    expect(screen.getByText('Animations')).toBeInTheDocument()
    // The three "moments" segmented options must be reachable as buttons.
    expect(screen.getByRole('button', { name: 'Compact' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Auto' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Reduce' })).toBeInTheDocument()
  })

  it('clicking Compact applies density-compact on <body> and persists', () => {
    render(<TweaksPanel />)
    fireEvent.click(screen.getByLabelText('Open tweaks'))
    act(() => {
      fireEvent.click(screen.getByRole('button', { name: 'Compact' }))
    })
    expect(document.body.classList.contains('density-compact')).toBe(true)
    // Legacy hook still in place.
    expect(document.documentElement.getAttribute('data-density')).toBe('compact')
    const raw = localStorage.getItem(STORAGE_KEY)
    expect(raw).toBeTruthy()
    expect(JSON.parse(raw as string).density).toBe('compact')
  })

  it('clicking Reduce sets data-motion="reduce" on <html> and persists', () => {
    render(<TweaksPanel />)
    fireEvent.click(screen.getByLabelText('Open tweaks'))
    act(() => {
      fireEvent.click(screen.getByRole('button', { name: 'Reduce' }))
    })
    expect(document.documentElement.getAttribute('data-motion')).toBe('reduce')
    const raw = localStorage.getItem(STORAGE_KEY)
    expect(JSON.parse(raw as string).animations).toBe('reduce')
    // Flip back: 'On' must clear the attribute (otherwise the toggle is
    // one-way, which is the bug class we're guarding against).
    act(() => {
      fireEvent.click(screen.getByRole('button', { name: 'On' }))
    })
    expect(document.documentElement.hasAttribute('data-motion')).toBe(false)
  })

  it('initTweaks() applies persisted prefs before any user interaction', () => {
    localStorage.setItem(
      STORAGE_KEY,
      JSON.stringify({
        theme: 'light',
        density: 'compact',
        sidebar: 'expanded',
        accent: 'mint',
        timeFormat: 'relative',
        animations: 'reduce',
      }),
    )
    initTweaks()
    // No render yet — the seeded state must already be on the DOM.
    expect(document.documentElement.getAttribute('data-theme')).toBe('light')
    expect(document.documentElement.getAttribute('data-density')).toBe('compact')
    expect(document.body.classList.contains('density-compact')).toBe(true)
    expect(document.documentElement.getAttribute('data-motion')).toBe('reduce')
  })

  it('Auto theme resolves to the OS preference via matchMedia (#669)', () => {
    // jsdom setup defaults matchMedia.matches=false → prefers light.
    render(<TweaksPanel />)
    fireEvent.click(screen.getByLabelText('Open tweaks'))
    // Force Dark first so the attribute is concretely set, then flip to Auto.
    act(() => {
      fireEvent.click(screen.getByRole('button', { name: 'Dark' }))
    })
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark')
    act(() => {
      fireEvent.click(screen.getByRole('button', { name: 'Auto' }))
    })
    // Auto must resolve — the attribute is set to the resolved value, not
    // cleared. Previously "auto = no override" left :root dark stuck on for
    // light-OS users (the bug #669 closes).
    expect(document.documentElement.getAttribute('data-theme')).toBe('light')
  })
})
