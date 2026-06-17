/**
 * Adversarial tests for the /builds list trigger-source glyph (closes #600).
 *
 * The resolver is a discriminated-union derivation from BuildDto's
 * (triggerType, triggerMeta) tuple — no string sniffing downstream. These
 * tests pin every branch of the truth table:
 *
 *   1. GITHUB_WEBHOOK         → GitFork
 *   2. CRON                   → Clock
 *   3. MANUAL                 → Play
 *   4. DOGFOOD                → Zap
 *   5. null type w/ commitSha → GitFork (legacy rows pre-#589)
 *   6. null type, no meta     → Zap (DOGFOOD fallback)
 */
import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import {
  TriggerSourceIcon,
  resolveTriggerSource,
} from '../components/TriggerSourceIcon'

describe('resolveTriggerSource', () => {
  it('GITHUB_WEBHOOK → GITHUB', () => {
    expect(resolveTriggerSource('GITHUB_WEBHOOK', null)).toBe('GITHUB')
  })
  it('triggerMeta.commitSha present → GITHUB even when type is null', () => {
    expect(resolveTriggerSource(null, { commitSha: 'a3f9c12' })).toBe('GITHUB')
  })
  it('CRON → CRON', () => {
    expect(resolveTriggerSource('CRON', null)).toBe('CRON')
  })
  it('MANUAL → MANUAL', () => {
    expect(resolveTriggerSource('MANUAL', null)).toBe('MANUAL')
  })
  it('DOGFOOD → DOGFOOD', () => {
    expect(resolveTriggerSource('DOGFOOD', null)).toBe('DOGFOOD')
  })
  it('null trigger + no meta → DOGFOOD fallback (no crash)', () => {
    expect(resolveTriggerSource(null, null)).toBe('DOGFOOD')
    expect(resolveTriggerSource(undefined, undefined)).toBe('DOGFOOD')
  })
})

describe('TriggerSourceIcon', () => {
  it('renders GitFork glyph + GitHub tooltip for GITHUB_WEBHOOK', () => {
    render(<TriggerSourceIcon triggerType="GITHUB_WEBHOOK" triggerMeta={null} />)
    const el = screen.getByTestId('trigger-source-github')
    expect(el.getAttribute('title')).toBe('Triggered via GitHub webhook')
    // lucide adds class names containing the icon kebab name.
    expect(el.querySelector('svg')?.getAttribute('class') ?? '').toMatch(
      /lucide-git-fork/i,
    )
  })

  it('renders Clock glyph + cron tooltip for CRON', () => {
    render(<TriggerSourceIcon triggerType="CRON" triggerMeta={null} />)
    const el = screen.getByTestId('trigger-source-cron')
    expect(el.getAttribute('title')).toBe('Triggered via cron schedule')
    expect(el.querySelector('svg')?.getAttribute('class') ?? '').toMatch(
      /lucide-clock/i,
    )
  })

  it('renders Play glyph + manual tooltip for MANUAL', () => {
    render(<TriggerSourceIcon triggerType="MANUAL" triggerMeta={null} />)
    const el = screen.getByTestId('trigger-source-manual')
    expect(el.getAttribute('title')).toBe('Triggered manually')
    expect(el.querySelector('svg')?.getAttribute('class') ?? '').toMatch(
      /lucide-play/i,
    )
  })

  it('renders Zap glyph + dogfood tooltip for DOGFOOD (and null fallback)', () => {
    const { unmount } = render(
      <TriggerSourceIcon triggerType="DOGFOOD" triggerMeta={null} />,
    )
    const el = screen.getByTestId('trigger-source-dogfood')
    expect(el.getAttribute('title')).toBe('Triggered via dogfood')
    expect(el.querySelector('svg')?.getAttribute('class') ?? '').toMatch(
      /lucide-zap/i,
    )
    unmount()

    // null triggerType must NOT crash and must fall back to DOGFOOD.
    render(<TriggerSourceIcon triggerType={null} triggerMeta={null} />)
    expect(screen.getByTestId('trigger-source-dogfood')).toBeTruthy()
  })
})
