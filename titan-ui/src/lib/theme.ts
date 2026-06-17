/**
 * Theme hook — issue #669.
 *
 * Thin facade over the existing TweaksPanel store. The single source of
 * truth for `theme` lives in `titan.tweaks.v1` (see TweaksPanel.tsx); this
 * module exposes a tighter `useTheme()` API for callers that only care
 * about light/dark and want a resolved value (when theme === 'auto').
 *
 * Resolution rule:
 *   - theme === 'light' → resolvedTheme = 'light'
 *   - theme === 'dark'  → resolvedTheme = 'dark'
 *   - theme === 'auto'  → resolvedTheme = matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
 *
 * Live updates: when `theme === 'auto'` and the OS scheme flips, the hook
 * re-renders and `apply()` re-runs (via the matchMedia listener registered
 * in TweaksPanel.initTweaks). The DOM `data-theme` attribute is kept in
 * sync — every other component sees the change via CSS variables.
 */
import { useSyncExternalStore } from 'react'
import { useTweaks, type ThemeKey } from '@/components/TweaksPanel'

export type ResolvedTheme = 'light' | 'dark'

const mq = (): MediaQueryList | null => {
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') {
    return null
  }
  return window.matchMedia('(prefers-color-scheme: dark)')
}

export function resolveTheme(theme: ThemeKey): ResolvedTheme {
  if (theme === 'light') return 'light'
  if (theme === 'dark') return 'dark'
  const m = mq()
  return m && m.matches ? 'dark' : 'light'
}

/** Subscribe to OS-level prefers-color-scheme changes. */
function subscribeMq(cb: () => void): () => void {
  const m = mq()
  if (!m) return () => {}
  // Safari < 14 used addListener; modern browsers use addEventListener.
  if (typeof m.addEventListener === 'function') {
    m.addEventListener('change', cb)
    return () => m.removeEventListener('change', cb)
  }
  m.addListener(cb)
  return () => m.removeListener(cb)
}

function getMqSnapshot(): boolean {
  const m = mq()
  return m ? m.matches : false
}

function getServerSnapshot(): boolean {
  return false
}

export interface UseTheme {
  theme: ThemeKey
  setTheme: (theme: ThemeKey) => void
  resolvedTheme: ResolvedTheme
}

export function useTheme(): UseTheme {
  const [tweaks, setTweaks] = useTweaks()
  // useSyncExternalStore on matchMedia so 'auto' callers re-render when the
  // OS scheme flips, even though `tweaks.theme` itself didn't change.
  const isDark = useSyncExternalStore(subscribeMq, getMqSnapshot, getServerSnapshot)
  const resolvedTheme: ResolvedTheme =
    tweaks.theme === 'auto' ? (isDark ? 'dark' : 'light') : tweaks.theme
  return {
    theme: tweaks.theme,
    setTheme: (t) => setTweaks({ theme: t }),
    resolvedTheme,
  }
}
