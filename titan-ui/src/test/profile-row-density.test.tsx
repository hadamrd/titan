/**
 * Adversarial regression for #586 — /profile setting-row density.
 *
 * Bug: PR #578 shipped the /profile redesign with `.setting-row` defined as
 * `grid-template-columns: 1fr 280px`. At wide viewports the 1fr label column
 * ballooned and the right control column felt pinned to the far edge with
 * acres of whitespace between the label and its input. The CLI install row
 * (a `<button>` containing an inline shell snippet) collapsed to a tiny strip
 * at the right edge for the same reason.
 *
 * Fix: bound the right column with `minmax(280px, 360px)`, cap
 * `.setting-control` at `max-width: 360px` and `justify-self: end` so labels
 * sit visually adjacent to their inputs instead of trailing into emptiness.
 *
 * This test is intentionally CSS-rule focused — jsdom's getComputedStyle does
 * not resolve external stylesheets, but it DOES honour an inline <style> tag
 * inserted into the document. We inject the production rules and assert the
 * shape we promised to ship.
 */
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'

const __filename = fileURLToPath(import.meta.url)
const __dirname = dirname(__filename)

const TOKENS_CSS = readFileSync(
  resolve(__dirname, '../styles/tokens.css'),
  'utf8',
)

let styleEl: HTMLStyleElement

beforeEach(() => {
  // jsdom doesn't load <link rel="stylesheet">; inject the production CSS
  // directly so getComputedStyle resolves real rules.
  styleEl = document.createElement('style')
  styleEl.textContent = TOKENS_CSS
  document.head.appendChild(styleEl)
})

afterEach(() => {
  styleEl.remove()
  document.body.innerHTML = ''
})

describe('/profile setting-row density (#586)', () => {
  it('declares a 2-column grid with the right column bounded (not 1fr 280px exactly, not auto/100%)', () => {
    // Source-of-truth assertion: the CSS rule itself, not a brittle
    // getComputedStyle round-trip (jsdom serialises grid-template-columns
    // inconsistently across versions).
    const rule = TOKENS_CSS.match(/\.setting-row\s*\{[^}]*\}/)?.[0] ?? ''
    expect(rule).toMatch(/display:\s*grid/)
    expect(rule).toMatch(/grid-template-columns:\s*minmax\(0,\s*1fr\)\s+minmax\(280px,\s*360px\)/)
    // Negative: the broken pre-#586 declaration must be gone.
    expect(rule).not.toMatch(/grid-template-columns:\s*1fr\s+280px\s*;/)
  })

  it('caps .setting-control width so small widgets do not pin to the far right edge', () => {
    const rule = TOKENS_CSS.match(/\.setting-control\s*\{[^}]*\}/)?.[0] ?? ''
    expect(rule).toMatch(/max-width:\s*360px/)
    expect(rule).toMatch(/justify-self:\s*end/)
  })

  it('renders a .setting-row with the bounded second column (DOM probe)', () => {
    document.body.innerHTML = `
      <div class="setting-row" data-testid="row">
        <div>
          <div class="setting-label">Display name</div>
          <div class="setting-desc">Shown on builds you trigger.</div>
        </div>
        <div class="setting-control" data-testid="control">
          <input class="field" value="Kira" />
        </div>
      </div>
    `
    const row = document.querySelector<HTMLElement>('[data-testid="row"]')!
    const style = getComputedStyle(row)
    expect(style.display).toBe('grid')
    // jsdom returns the literal token string; just sanity-check it mentions
    // a bounded right column (minmax with the 360px ceiling) and is NOT
    // the legacy "1fr 280px" or unbounded "1fr auto".
    const tpl = style.gridTemplateColumns || ''
    expect(tpl).not.toMatch(/^1fr\s+280px$/)
    expect(tpl).not.toMatch(/100%/)
    // Either jsdom resolves the minmax string, or it returns the raw rule —
    // either way it must mention 360px (the ceiling we shipped).
    expect(tpl).toContain('360px')
  })

  it('caps a code-snippet control width so the CLI install button does not collapse to a far-right strip', () => {
    document.body.innerHTML = `
      <div class="setting-row">
        <div>
          <div class="setting-label">Titan CLI</div>
        </div>
        <div class="setting-control" data-testid="cli-control">
          <pre>curl -fsSL https://get.titan.dev/install.sh | sh</pre>
        </div>
      </div>
    `
    const control = document.querySelector<HTMLElement>('[data-testid="cli-control"]')!
    const style = getComputedStyle(control)
    // The control must declare a max-width ceiling (360px) — never the full
    // viewport, and never auto/none which was the pre-fix behaviour.
    expect(style.maxWidth).toBe('360px')
    expect(style.justifySelf).toBe('end')
  })
})
