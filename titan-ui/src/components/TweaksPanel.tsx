/**
 * Tweaks panel — top-right popover for visual prefs.
 *
 * Controls: theme (dark/light), density (compact/balanced/comfy), sidebar
 * (expanded/rail), accent (5 presets), time-format (relative/absolute).
 *
 * Persistence: localStorage under `titan.tweaks.v1`. State is applied by writing
 * `data-theme` / `data-density` / `data-sidebar` on <html>, plus the accent CSS
 * vars (`--accent`, `--accent-2`, `--accent-loud`, `--accent-ink`,
 * `--accent-soft`) on the same root. That means no React context: any consumer
 * that wants to *read* the current tweak imports `useTweaks()`, but the visual
 * effect is purely CSS — components don't have to re-render.
 *
 * `initTweaks()` is called from main.tsx BEFORE React mounts so the first
 * paint already matches the saved prefs (no FOUC).
 */
import { Sliders, X } from 'lucide-react'
import { useEffect, useState, useSyncExternalStore } from 'react'

// ── Accent presets — fix #3 (per-accent ink) ────────────────────────────────
// Each preset declares the 4 accent vars + the ink that should sit on top of
// btn-primary backgrounds. Light accents (mint/amber) get a near-black ink;
// darker accents (sky/violet/red) get near-white. Hand-tuned, not computed.
export type AccentKey = 'mint' | 'sky' | 'violet' | 'amber' | 'red'

interface AccentPreset {
  accent: string
  accent2: string
  accentLoud: string
  accentInk: string
  accentSoft: string
}

const ACCENTS: Record<AccentKey, AccentPreset> = {
  mint: {
    // v3: warmer mint, hue 155 (was 165)
    accent: 'oklch(0.78 0.11 155)',
    accent2: 'oklch(0.86 0.13 155)',
    accentLoud: 'oklch(0.82 0.15 155)',
    accentInk: 'oklch(0.20 0.04 155)',
    accentSoft: 'oklch(0.30 0.08 155 / 0.18)',
  },
  sky: {
    accent: 'oklch(0.74 0.12 235)',
    accent2: 'oklch(0.82 0.14 235)',
    accentLoud: 'oklch(0.78 0.16 235)',
    accentInk: 'oklch(0.98 0.005 235)',
    accentSoft: 'oklch(0.40 0.10 235 / 0.20)',
  },
  violet: {
    accent: 'oklch(0.68 0.14 295)',
    accent2: 'oklch(0.76 0.16 295)',
    accentLoud: 'oklch(0.72 0.18 295)',
    accentInk: 'oklch(0.98 0.005 295)',
    accentSoft: 'oklch(0.40 0.12 295 / 0.22)',
  },
  amber: {
    accent: 'oklch(0.82 0.13 75)',
    accent2: 'oklch(0.88 0.15 75)',
    accentLoud: 'oklch(0.85 0.17 75)',
    accentInk: 'oklch(0.22 0.05 75)',
    accentSoft: 'oklch(0.50 0.10 75 / 0.20)',
  },
  red: {
    accent: 'oklch(0.70 0.16 25)',
    accent2: 'oklch(0.78 0.18 25)',
    accentLoud: 'oklch(0.74 0.20 25)',
    accentInk: 'oklch(0.98 0.005 25)',
    accentSoft: 'oklch(0.45 0.12 25 / 0.22)',
  },
}

export type ThemeKey = 'auto' | 'dark' | 'light'
export type DensityKey = 'compact' | 'balanced' | 'comfy'
export type SidebarKey = 'expanded' | 'rail'
export type TimeFormatKey = 'relative' | 'absolute'
export type AnimationsKey = 'on' | 'reduce'

export interface Tweaks {
  theme: ThemeKey
  density: DensityKey
  sidebar: SidebarKey
  accent: AccentKey
  timeFormat: TimeFormatKey
  animations: AnimationsKey
}

const STORAGE_KEY = 'titan.tweaks.v1'
const DEFAULTS: Tweaks = {
  theme: 'dark',
  density: 'balanced',
  sidebar: 'expanded',
  accent: 'mint',
  timeFormat: 'relative',
  animations: 'on',
}

// ── Subscriber store (no Context needed) ─────────────────────────────────────
const listeners = new Set<() => void>()
let current: Tweaks = DEFAULTS

function load(): Tweaks {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return DEFAULTS
    const parsed = JSON.parse(raw) as Partial<Tweaks>
    return { ...DEFAULTS, ...parsed }
  } catch {
    return DEFAULTS
  }
}

function save(t: Tweaks) {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(t))
  } catch {
    /* ignore quota / private-mode errors */
  }
}

/** Resolve theme='auto' against the OS prefers-color-scheme media query.
 *  Returns 'dark' or 'light' — never 'auto'. SSR / no-matchMedia falls
 *  back to 'dark' (existing :root default, no contrast regression). */
function resolveAuto(): 'dark' | 'light' {
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') {
    return 'dark'
  }
  return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
}

function apply(t: Tweaks) {
  const html = document.documentElement
  // theme='auto' resolves live against prefers-color-scheme. We always set
  // the data-theme attribute to the *resolved* value (dark|light) so the
  // CSS tokens block matches deterministically — relying on the implicit
  // :root default broke light-mode users on Auto (#669).
  const resolved = t.theme === 'auto' ? resolveAuto() : t.theme
  html.dataset.theme = resolved
  // CSS file uses `compact` and `comfy`; balanced = no attr (use defaults).
  if (t.density === 'balanced') delete html.dataset.density
  else html.dataset.density = t.density
  // Mirror density onto <body> as a class for callers that key off body
  // (e.g. the v3 mockup convention: `.density-compact`).
  if (typeof document.body !== 'undefined' && document.body) {
    document.body.classList.toggle('density-compact', t.density === 'compact')
    document.body.classList.toggle('density-comfy', t.density === 'comfy')
  }
  html.dataset.sidebar = t.sidebar
  // density="balanced" is the unset state, but we still want the attribute
  // present for tests/styling hooks → set it as well.
  html.dataset.timeFormat = t.timeFormat
  // animations='reduce' is mirrored on <html> as data-motion="reduce"; the
  // CSS layer promotes it to the same animation-killing rules that fire on
  // prefers-reduced-motion. 'on' clears the attribute so the system pref
  // wins (a user with OS-level reduce-motion still gets reduced motion).
  if (t.animations === 'reduce') html.dataset.motion = 'reduce'
  else delete html.dataset.motion
  const a = ACCENTS[t.accent]
  html.style.setProperty('--accent', a.accent)
  html.style.setProperty('--accent-2', a.accent2)
  html.style.setProperty('--accent-loud', a.accentLoud)
  html.style.setProperty('--accent-ink', a.accentInk)
  html.style.setProperty('--accent-soft', a.accentSoft)
}

function setTweaks(next: Partial<Tweaks>) {
  current = { ...current, ...next }
  apply(current)
  save(current)
  listeners.forEach((l) => l())
}

// Live OS-scheme listener state — when the user has theme='auto', a change
// to prefers-color-scheme must repaint without a reload (#669). We track
// the attached (MediaQueryList, callback) pair so repeated initTweaks()
// calls (HMR / tests that re-stub matchMedia) detach the old listener
// before re-attaching — avoids duplicate fires AND stale-stub leaks.
let attachedMq: MediaQueryList | null = null
let attachedCb: ((e: MediaQueryListEvent) => void) | null = null

function attachMqListener() {
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return
  // Detach previous binding first (idempotent).
  if (attachedMq && attachedCb) {
    if (typeof attachedMq.removeEventListener === 'function') {
      attachedMq.removeEventListener('change', attachedCb)
    } else {
      attachedMq.removeListener(attachedCb)
    }
  }
  const m = window.matchMedia('(prefers-color-scheme: dark)')
  const onChange = () => {
    if (current.theme === 'auto') {
      apply(current)
      listeners.forEach((l) => l())
    }
  }
  attachedMq = m
  attachedCb = onChange
  if (typeof m.addEventListener === 'function') m.addEventListener('change', onChange)
  else m.addListener(onChange)
}

/** Called once from main.tsx before React mounts. */
export function initTweaks() {
  current = load()
  apply(current)
  attachMqListener()
}

function subscribe(l: () => void) {
  listeners.add(l)
  return () => listeners.delete(l)
}

export function useTweaks() {
  const value = useSyncExternalStore(
    subscribe,
    () => current,
    () => DEFAULTS,
  )
  return [value, setTweaks] as const
}

// ── UI ────────────────────────────────────────────────────────────────────────

function Seg<T extends string>({
  value,
  options,
  onChange,
}: {
  value: T
  options: { key: T; label: string }[]
  onChange: (v: T) => void
}) {
  return (
    <div className="seg">
      {options.map((o) => (
        <button
          key={o.key}
          type="button"
          className={o.key === value ? 'on' : ''}
          onClick={() => onChange(o.key)}
        >
          {o.label}
        </button>
      ))}
    </div>
  )
}

export function TweaksPanel() {
  const [t, set] = useTweaks()
  const [open, setOpen] = useState(false)

  // ESC closes the panel.
  useEffect(() => {
    if (!open) return
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false)
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [open])

  return (
    <>
      <button
        type="button"
        className="icon-btn"
        title="Tweaks"
        aria-label="Open tweaks"
        aria-expanded={open}
        onClick={() => setOpen((v) => !v)}
      >
        <Sliders size={16} />
      </button>
      {open && (
        <>
          <div
            role="presentation"
            onClick={() => setOpen(false)}
            style={{
              position: 'fixed',
              inset: 0,
              zIndex: 49,
            }}
          />
          <div
            role="dialog"
            aria-label="Tweaks"
            style={{
              position: 'fixed',
              top: 'calc(var(--topbar-h) + 8px)',
              right: 16,
              width: 280,
              background: 'var(--surface)',
              border: '1px solid var(--border)',
              borderRadius: 'var(--r-lg)',
              boxShadow: 'var(--shadow-pop)',
              padding: 14,
              zIndex: 50,
              display: 'flex',
              flexDirection: 'column',
              gap: 14,
            }}
          >
            <div
              style={{
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'space-between',
              }}
            >
              <strong style={{ fontSize: 13, letterSpacing: '-0.01em' }}>Tweaks</strong>
              <button
                type="button"
                className="icon-btn"
                aria-label="Close tweaks"
                onClick={() => setOpen(false)}
                style={{ width: 24, height: 24 }}
              >
                <X size={14} />
              </button>
            </div>

            <Field label="Theme">
              <Seg
                value={t.theme}
                options={[
                  { key: 'auto', label: 'Auto' },
                  { key: 'dark', label: 'Dark' },
                  { key: 'light', label: 'Light' },
                ]}
                onChange={(v) => set({ theme: v })}
              />
            </Field>

            <Field label="Density">
              <Seg
                value={t.density}
                options={[
                  { key: 'compact', label: 'Compact' },
                  { key: 'balanced', label: 'Balanced' },
                  { key: 'comfy', label: 'Comfy' },
                ]}
                onChange={(v) => set({ density: v })}
              />
            </Field>

            <Field label="Sidebar">
              <Seg
                value={t.sidebar}
                options={[
                  { key: 'expanded', label: 'Expanded' },
                  { key: 'rail', label: 'Rail' },
                ]}
                onChange={(v) => set({ sidebar: v })}
              />
            </Field>

            <Field label="Animations">
              <Seg
                value={t.animations}
                options={[
                  { key: 'on', label: 'On' },
                  { key: 'reduce', label: 'Reduce' },
                ]}
                onChange={(v) => set({ animations: v })}
              />
            </Field>

            <Field label="Time format">
              <Seg
                value={t.timeFormat}
                options={[
                  { key: 'relative', label: 'Relative' },
                  { key: 'absolute', label: 'Absolute' },
                ]}
                onChange={(v) => set({ timeFormat: v })}
              />
            </Field>

            <Field label="Accent">
              <div style={{ display: 'flex', gap: 8 }}>
                {(Object.keys(ACCENTS) as AccentKey[]).map((k) => (
                  <button
                    key={k}
                    type="button"
                    aria-label={`Accent ${k}`}
                    aria-pressed={t.accent === k}
                    onClick={() => set({ accent: k })}
                    style={{
                      width: 26,
                      height: 26,
                      borderRadius: 999,
                      border:
                        t.accent === k
                          ? '2px solid var(--fg)'
                          : '1px solid var(--border)',
                      background: ACCENTS[k].accent,
                      cursor: 'pointer',
                      padding: 0,
                    }}
                  />
                ))}
              </div>
            </Field>
          </div>
        </>
      )}
    </>
  )
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
      <div
        style={{
          fontSize: 11,
          textTransform: 'uppercase',
          letterSpacing: '0.08em',
          color: 'var(--fg-faint)',
          fontWeight: 500,
        }}
      >
        {label}
      </div>
      {children}
    </div>
  )
}
