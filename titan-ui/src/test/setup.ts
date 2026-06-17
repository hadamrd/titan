import '@testing-library/jest-dom'

// jsdom doesn't implement window.scrollTo — suppress the noise
Object.defineProperty(window, 'scrollTo', { value: () => undefined, writable: true })

// jsdom doesn't implement Element.scrollIntoView — TerminalConsole's
// auto-follow (#563) calls it on every chunk; without this stub, any test
// that mounts the console with non-empty `lines` throws on first effect.
if (!('scrollIntoView' in HTMLElement.prototype)) {
  Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', {
    configurable: true,
    writable: true,
    value: () => undefined,
  })
}

// jsdom doesn't implement ResizeObserver — required by @xyflow/react in the
// v3-stack DAG (#539). Minimal stub that records callbacks but never fires —
// xyflow falls back to its layout-fitness loop on first render.
class ResizeObserverStub {
  observe() {}
  unobserve() {}
  disconnect() {}
}
;(globalThis as unknown as { ResizeObserver: typeof ResizeObserverStub }).ResizeObserver =
  ResizeObserverStub

// jsdom lacks DOMMatrixReadOnly + getBoundingClientRect rich enough for
// xyflow's edge math. A no-op stub keeps the renderer from throwing during
// initial mount; production browsers ship the real APIs.
if (!('DOMMatrixReadOnly' in globalThis)) {
  class DOMMatrixReadOnlyStub {
    m22 = 1
    constructor(_init?: unknown) {}
  }
  ;(globalThis as unknown as { DOMMatrixReadOnly: typeof DOMMatrixReadOnlyStub })
    .DOMMatrixReadOnly = DOMMatrixReadOnlyStub
}

// jsdom doesn't implement matchMedia — required by the theme hook (#669)
// and any future media-query consumer. Default: prefers-color-scheme: light
// (matches=false). Tests that need a different value can replace
// window.matchMedia per-test (see theme.test.tsx for the helper).
if (typeof window !== 'undefined' && typeof window.matchMedia !== 'function') {
  Object.defineProperty(window, 'matchMedia', {
    configurable: true,
    writable: true,
    value: (query: string): MediaQueryList => ({
      matches: false,
      media: query,
      onchange: null,
      addListener: () => undefined,
      removeListener: () => undefined,
      addEventListener: () => undefined,
      removeEventListener: () => undefined,
      dispatchEvent: () => false,
    }),
  })
}
