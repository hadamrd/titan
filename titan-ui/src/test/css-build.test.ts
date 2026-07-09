/**
 * css-build — assertions on the design-token CSS pipeline.
 *
 * Guards the entire class of "tokens.css silently dropped" bugs (PR #343):
 * if globals.css stops @importing tokens.css, or tokens.css stops defining
 * --bg / --accent, or the Geist font-family declaration disappears, the
 * production bundle starts shipping a white-on-default page. These tests
 * make that failure mode load-bearing.
 *
 * Strategy: read the CSS source files directly from disk via Node `fs`.
 * Vite's `?inline` / `?raw` for `.css` files both run through the CSS
 * plugin pipeline (which can yield an empty string in the vitest jsdom
 * environment without a tailwind context), so they're unreliable here.
 * On-disk bytes is exactly what we want: we assert on the text the
 * developer checked in, not on the bundler output.
 */
import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

const STYLES_DIR = resolve(__dirname, '../styles')
const globalsCss = readFileSync(resolve(STYLES_DIR, 'globals.css'), 'utf8')
const tokensCss = readFileSync(resolve(STYLES_DIR, 'tokens.css'), 'utf8')
const componentsCss = readFileSync(resolve(STYLES_DIR, 'components.css'), 'utf8')

describe('design-token CSS bundle', () => {
  it('tokens.css defines the oklch token --bg', () => {
    expect(tokensCss).toMatch(/--bg\s*:\s*oklch\(/)
  })

  it('tokens.css defines the oklch token --accent', () => {
    expect(tokensCss).toMatch(/--accent\s*:\s*oklch\(/)
  })

  it('tokens.css declares Geist as the sans font (--font-sans)', () => {
    // tokens.css drives typography via --font-sans, consumed by Tailwind's
    // font-sans class and by body{font-family:var(--font-sans)} elsewhere.
    expect(tokensCss).toMatch(/--font-sans\s*:[^;]*Geist/i)
  })

  it('globals.css imports tokens.css (the bug-prone wiring step)', () => {
    expect(globalsCss).toMatch(/@import\s+['"]\.\/tokens\.css['"]/)
  })

  it('globals.css imports components.css', () => {
    expect(globalsCss).toMatch(/@import\s+['"]\.\/components\.css['"]/)
  })

  it('globals.css references --bg on body so the token is actually consumed', () => {
    expect(globalsCss).toMatch(/body[\s\S]*var\(--bg\)/)
  })

  it('modal buttons neutralize the .btn:active translate (#113)', () => {
    // tokens.css presses buttons down by 0.5px on :active. Inside a modal
    // that micro-shift lets the click hit-test resolve to the backdrop and
    // dismiss the dialog instead of firing the action. components.css must
    // keep the higher-specificity override in place.
    expect(componentsCss).toMatch(
      /\.gate-modal\s+\.btn:active\s*\{[^}]*transform\s*:\s*none/,
    )
  })
})
