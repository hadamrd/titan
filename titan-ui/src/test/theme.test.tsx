/**
 * Adversarial tests for the theme system (#669).
 *
 * Pinned invariants:
 *   - setTheme('dark') sets data-theme="dark" on <html>
 *   - setTheme('light') sets data-theme="light"
 *   - setTheme('auto') with matchMedia.matches=true (OS prefers dark) →
 *     data-theme="dark"
 *   - setTheme('auto') with matchMedia.matches=false (OS prefers light) →
 *     data-theme="light"
 *   - A live OS-scheme flip while on 'auto' updates the attribute without
 *     any user action
 *   - localStorage persists across re-mounts
 *   - resolvedTheme on the hook reflects the same resolution rule
 */
import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { act, cleanup, render, screen } from '@testing-library/react'
import { ThemeToggle } from '../components/ThemeToggle'
import { useTheme } from '../lib/theme'
import { initTweaks } from '../components/TweaksPanel'

const STORAGE_KEY = 'titan.tweaks.v1'

interface MqStub {
  matches: boolean
  listeners: Set<(e: { matches: boolean }) => void>
  fire(matches: boolean): void
}

function installMatchMedia(initialMatches: boolean): MqStub {
  const stub: MqStub = {
    matches: initialMatches,
    listeners: new Set(),
    fire(matches: boolean) {
      stub.matches = matches
      const evt = { matches } as { matches: boolean }
      stub.listeners.forEach((l) => l(evt))
    },
  }
  Object.defineProperty(window, 'matchMedia', {
    configurable: true,
    writable: true,
    value: (query: string): MediaQueryList =>
      ({
        media: query,
        get matches() {
          return stub.matches
        },
        onchange: null,
        addListener: (cb: (e: { matches: boolean }) => void) => stub.listeners.add(cb),
        removeListener: (cb: (e: { matches: boolean }) => void) =>
          stub.listeners.delete(cb),
        addEventListener: (
          _evt: string,
          cb: (e: { matches: boolean }) => void,
        ) => stub.listeners.add(cb),
        removeEventListener: (
          _evt: string,
          cb: (e: { matches: boolean }) => void,
        ) => stub.listeners.delete(cb),
        dispatchEvent: () => false,
      }) as unknown as MediaQueryList,
  })
  return stub
}

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

/** Test harness: renders ThemeToggle alongside a probe component that
 *  surfaces the hook's resolvedTheme as data attrs for assertions. */
function Probe() {
  const { theme, resolvedTheme } = useTheme()
  return (
    <div data-testid="probe" data-theme-value={theme} data-resolved={resolvedTheme} />
  )
}

beforeEach(() => {
  resetDom()
})

afterEach(() => {
  cleanup()
  resetDom()
})

describe('theme system (#669)', () => {
  it('setTheme("dark") applies data-theme="dark" on <html>', () => {
    installMatchMedia(false)
    render(
      <>
        <ThemeToggle />
        <Probe />
      </>,
    )
    act(() => {
      screen.getByRole('button', { name: 'Theme: Dark' }).click()
    })
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark')
    expect(screen.getByTestId('probe').getAttribute('data-resolved')).toBe('dark')
  })

  it('setTheme("auto") with matchMedia=dark → data-theme="dark"', () => {
    installMatchMedia(true)
    render(
      <>
        <ThemeToggle />
        <Probe />
      </>,
    )
    act(() => {
      // Force light first so we observe the flip when Auto is chosen.
      screen.getByRole('button', { name: 'Theme: Light' }).click()
    })
    expect(document.documentElement.getAttribute('data-theme')).toBe('light')
    act(() => {
      screen.getByRole('button', { name: 'Theme: Auto' }).click()
    })
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark')
    expect(screen.getByTestId('probe').getAttribute('data-resolved')).toBe('dark')
  })

  it('setTheme("auto") with matchMedia=light → data-theme="light"', () => {
    installMatchMedia(false)
    render(
      <>
        <ThemeToggle />
        <Probe />
      </>,
    )
    act(() => {
      screen.getByRole('button', { name: 'Theme: Dark' }).click()
    })
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark')
    act(() => {
      screen.getByRole('button', { name: 'Theme: Auto' }).click()
    })
    expect(document.documentElement.getAttribute('data-theme')).toBe('light')
    expect(screen.getByTestId('probe').getAttribute('data-resolved')).toBe('light')
  })

  it('live matchMedia change while on Auto updates data-theme without user action', () => {
    const mq = installMatchMedia(false)
    // initTweaks() installs the live OS-scheme listener. It must run AFTER
    // matchMedia is stubbed so the listener attaches to the controllable mq.
    initTweaks()
    render(
      <>
        <ThemeToggle />
        <Probe />
      </>,
    )
    act(() => {
      screen.getByRole('button', { name: 'Theme: Auto' }).click()
    })
    expect(document.documentElement.getAttribute('data-theme')).toBe('light')
    // OS flips to dark mode — listener fires, apply() repaints, attribute
    // updates with no React event or user click.
    act(() => {
      mq.fire(true)
    })
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark')
    // And back to light — the listener stays live (not one-shot).
    act(() => {
      mq.fire(false)
    })
    expect(document.documentElement.getAttribute('data-theme')).toBe('light')
  })

  it('localStorage persists theme across hook re-mount', () => {
    installMatchMedia(false)
    const { unmount } = render(
      <>
        <ThemeToggle />
        <Probe />
      </>,
    )
    act(() => {
      screen.getByRole('button', { name: 'Theme: Dark' }).click()
    })
    const raw = localStorage.getItem(STORAGE_KEY)
    expect(raw).toBeTruthy()
    expect(JSON.parse(raw as string).theme).toBe('dark')

    unmount()
    resetDom()
    // Re-seed the persisted value (resetDom cleared it; in real life it
    // survives a reload). The contract under test: initTweaks() reads
    // localStorage and re-applies before React touches the DOM.
    localStorage.setItem(STORAGE_KEY, JSON.stringify({ theme: 'dark' }))
    initTweaks()
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark')

    // Remount and assert the hook reads back the persisted value.
    render(<Probe />)
    expect(screen.getByTestId('probe').getAttribute('data-theme-value')).toBe('dark')
  })
})
