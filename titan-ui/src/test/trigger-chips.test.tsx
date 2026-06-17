/**
 * Unit tests for the read-only trigger chips on /jobs/$id (ticket #438).
 *
 * Covers all four chip variants:
 *   - cron       → humanized cron chip
 *   - github     → branch-summary chip
 *   - on-demand  → empty-state chip (no triggers entry)
 *   - malformed  → graceful "triggers unparseable" chip + console.warn
 */
import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { TriggerChips } from '../components/TriggerChips'
import { humanizeCron, parseTriggers } from '../lib/triggers'

describe('TriggerChips component', () => {
  it('renders the cron chip with humanized expression for "0 */6 * * *"', () => {
    const yaml = [
      'stages:',
      '  - name: build',
      '    steps:',
      '      - script: echo hi',
      'triggers:',
      '  - cron: "0 */6 * * *"',
      '',
    ].join('\n')
    render(<TriggerChips pipelineScript={yaml} />)
    const chip = screen.getByTestId('trigger-chip-cron')
    expect(chip.textContent).toMatch(/cron · every 6h/)
  })

  it('renders the github chip with the branch summary', () => {
    const yaml = [
      'stages:',
      '  - name: build',
      'triggers:',
      '  - github:',
      '      branches: [trunk, main]',
      '',
    ].join('\n')
    render(<TriggerChips pipelineScript={yaml} />)
    const chip = screen.getByTestId('trigger-chip-github')
    expect(chip.textContent).toMatch(/github · trunk\|main/)
  })

  it('renders the on-demand chip when the YAML has no triggers block', () => {
    const yaml = 'stages:\n  - name: build\n    steps:\n      - script: echo hi\n'
    render(<TriggerChips pipelineScript={yaml} />)
    expect(screen.getByTestId('trigger-chip-ondemand').textContent).toBe('on demand')
  })

  it('renders the on-demand chip when pipelineScript is null/empty', () => {
    render(<TriggerChips pipelineScript={null} />)
    expect(screen.getByTestId('trigger-chip-ondemand')).toBeInTheDocument()
  })

  it('renders the on-demand chip for explicit empty list "triggers: []"', () => {
    render(<TriggerChips pipelineScript={'triggers: []\nstages: []\n'} />)
    expect(screen.getByTestId('trigger-chip-ondemand')).toBeInTheDocument()
  })

  it('gracefully degrades to "triggers unparseable" + console.warn on malformed YAML', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    // Inline flow-sequence (not supported) — extractor throws, the component
    // catches and renders the warn chip.
    render(<TriggerChips pipelineScript={'triggers: [oops not supported]\n'} />)
    expect(screen.getByTestId('trigger-chip-unparseable')).toBeInTheDocument()
    expect(warn).toHaveBeenCalled()
    warn.mockRestore()
  })

  it('opens an inline YAML snippet popover when the cron chip is clicked', () => {
    const yaml = 'triggers:\n  - cron: "0 */6 * * *"\nstages: []\n'
    render(<TriggerChips pipelineScript={yaml} />)
    fireEvent.click(screen.getByTestId('trigger-chip-cron'))
    const tip = screen.getByRole('tooltip')
    expect(tip.textContent).toContain('cron: "0 */6 * * *"')
  })
})

// ── Pure parser / humanizer ─────────────────────────────────────────────────

describe('parseTriggers — discriminated union shape', () => {
  it('returns an empty list for null input', () => {
    const r = parseTriggers(null)
    expect(r.kind).toBe('ok')
    if (r.kind === 'ok') expect(r.triggers).toEqual([])
  })

  it('classifies cron + github entries by `kind` (no string-sniffing)', () => {
    const r = parseTriggers(
      [
        'triggers:',
        '  - cron: "*/15 * * * *"',
        '  - github:',
        '      branches: ["trunk"]',
        '',
      ].join('\n'),
    )
    expect(r.kind).toBe('ok')
    if (r.kind !== 'ok') return
    expect(r.triggers).toHaveLength(2)
    expect(r.triggers[0]).toMatchObject({ kind: 'cron', expr: '*/15 * * * *' })
    expect(r.triggers[1]).toMatchObject({ kind: 'github', branches: ['trunk'] })
  })
})

describe('humanizeCron', () => {
  it('renders "every 6h" for "0 */6 * * *"', () => {
    expect(humanizeCron('0 */6 * * *')).toBe('every 6h')
  })
  it('renders "every 15m" for "*/15 * * * *"', () => {
    expect(humanizeCron('*/15 * * * *')).toBe('every 15m')
  })
  it('renders "daily at 09:30" for "30 9 * * *"', () => {
    expect(humanizeCron('30 9 * * *')).toBe('daily at 09:30')
  })
  it('falls back to the literal expression on uncommon patterns', () => {
    expect(humanizeCron('5 4 * * 1-5')).toBe('5 4 * * 1-5')
  })
})
