/**
 * Theme toggle (#669) — Auto / Light / Dark segmented control for the
 * topbar. Persists via the existing TweaksPanel store (single source of
 * truth for visual prefs) so the gear-popover and the topbar pill stay in
 * sync without prop drilling.
 *
 * Visual: icon-only segmented pill matching `.seg` / `.icon-btn` v3 chrome.
 * Three buttons: Monitor (Auto), Sun (Light), Moon (Dark). aria-pressed
 * marks the active mode; the title attribute spells it out for hover.
 */
import { Monitor, Moon, Sun } from 'lucide-react'
import { useTheme } from '@/lib/theme'
import type { ThemeKey } from '@/components/TweaksPanel'

const OPTIONS: Array<{ key: ThemeKey; label: string; Icon: typeof Monitor }> = [
  { key: 'auto', label: 'Auto', Icon: Monitor },
  { key: 'light', label: 'Light', Icon: Sun },
  { key: 'dark', label: 'Dark', Icon: Moon },
]

export function ThemeToggle() {
  const { theme, setTheme, resolvedTheme } = useTheme()
  return (
    <div
      className="seg"
      role="group"
      aria-label="Theme"
      data-resolved-theme={resolvedTheme}
    >
      {OPTIONS.map(({ key, label, Icon }) => {
        const active = theme === key
        return (
          <button
            key={key}
            type="button"
            className={active ? 'on' : ''}
            aria-pressed={active}
            aria-label={`Theme: ${label}`}
            title={label}
            onClick={() => setTheme(key)}
            style={{
              display: 'inline-flex',
              alignItems: 'center',
              justifyContent: 'center',
              padding: '4px 8px',
            }}
          >
            <Icon size={14} aria-hidden />
          </button>
        )
      })}
    </div>
  )
}
