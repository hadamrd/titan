/**
 * useCmdK — global Cmd+K / Ctrl+K keyboard listener.
 *
 * <p>Owns the boolean open-state for the command palette and exposes the
 * setter so callers (e.g. the TopBar search chip) can open it without
 * re-implementing the hotkey contract. Extracted from {@code CommandPalette}
 * itself so the palette has a single source of truth for open/close while the
 * keyboard wiring stays trivially testable in isolation.
 *
 * <p>Bound on {@code window} so the hotkey works regardless of which element
 * has focus. {@code preventDefault} on the matching keystroke so the browser
 * doesn't surface its own ⌘K (Chrome's address-bar focus) underneath ours.
 *
 * <p>{@code defaultOpen} is a test seam — callers should never need it in
 * production. Internal toggle uses the functional updater form to stay
 * race-free against rapid double-press.
 */
import { useCallback, useEffect, useState } from 'react'

export interface UseCmdKResult {
  open: boolean
  setOpen: (next: boolean) => void
  toggle: () => void
}

export function useCmdK({ defaultOpen = false }: { defaultOpen?: boolean } = {}): UseCmdKResult {
  const [open, setOpenState] = useState(defaultOpen)

  const setOpen = useCallback((next: boolean) => setOpenState(next), [])
  const toggle = useCallback(() => setOpenState((v) => !v), [])

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'k' && (e.metaKey || e.ctrlKey)) {
        e.preventDefault()
        setOpenState((v) => !v)
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [])

  return { open, setOpen, toggle }
}
