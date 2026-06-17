/**
 * Form-state + serializer round-trip tests for the trigger-editor (#439).
 *
 * Covers:
 *   - serializeTriggers — empty / cron / github+credentialsId / mixed
 *   - replaceTriggersBlock — preserves surrounding YAML, swaps the block
 *   - parse → serialize → parse round-trip — invariant under edit
 *   - blankTrigger — discriminated-union starters
 */
import { describe, expect, it } from 'vitest'
import {
  blankTrigger,
  parseTriggers,
  replaceTriggersBlock,
  serializeTriggers,
  type ParsedTrigger,
} from '@/lib/triggers'

describe('serializeTriggers', () => {
  it('empty list serialises as inline `triggers: []`', () => {
    expect(serializeTriggers([])).toBe('triggers: []')
  })

  it('serialises a cron trigger', () => {
    const out = serializeTriggers([
      { kind: 'cron', expr: '0 */6 * * *', humanized: 'every 6h' },
    ])
    expect(out).toBe('triggers:\n  - cron: "0 */6 * * *"')
  })

  it('serialises a github trigger with branches + credentialsId', () => {
    const out = serializeTriggers([
      { kind: 'github', branches: ['trunk', 'main'], credentialsId: 'gh-secret' },
    ])
    expect(out).toContain('- github:')
    expect(out).toContain('branches: ["trunk", "main"]')
    expect(out).toContain('credentialsId: "gh-secret"')
  })

  it('omits credentialsId when null', () => {
    const out = serializeTriggers([
      { kind: 'github', branches: ['trunk'], credentialsId: null },
    ])
    expect(out).not.toContain('credentialsId')
  })

  it('drops unknown entries on serialise (never writes back what we did not understand)', () => {
    const out = serializeTriggers([
      { kind: 'unknown', raw: 'bogus: thing' },
      { kind: 'cron', expr: '0 0 * * *', humanized: 'daily at 00:00' },
    ])
    expect(out).not.toContain('bogus')
    expect(out).toContain('cron')
  })
})

describe('replaceTriggersBlock', () => {
  it('swaps an existing block in place, preserving surrounding YAML', () => {
    const script = [
      'titan:',
      '  stages: []',
      'triggers:',
      '  - cron: "0 0 * * *"',
      'parameters: []',
    ].join('\n')
    const next = replaceTriggersBlock(script, [
      { kind: 'cron', expr: '*/5 * * * *', humanized: 'every 5m' },
    ])
    expect(next).toContain('parameters: []')
    expect(next).toContain('titan:')
    expect(next).toContain('*/5 * * * *')
    expect(next).not.toContain('0 0 * * *')
  })

  it('prepends a triggers block when none exists', () => {
    const script = 'titan:\n  stages: []\n'
    const next = replaceTriggersBlock(script, [
      { kind: 'cron', expr: '0 9 * * *', humanized: 'daily at 09:00' },
    ])
    expect(next.startsWith('triggers:')).toBe(true)
    expect(next).toContain('stages: []')
  })

  it('replaces with `triggers: []` when the list goes empty', () => {
    const script = 'triggers:\n  - cron: "0 0 * * *"\nstages: []\n'
    const next = replaceTriggersBlock(script, [])
    expect(next).toContain('triggers: []')
    expect(next).toContain('stages: []')
  })
})

describe('round-trip parse → serialize → parse', () => {
  it('is identity for a mixed-trigger list', () => {
    const original: ParsedTrigger[] = [
      { kind: 'cron', expr: '0 */6 * * *', humanized: 'every 6h' },
      { kind: 'github', branches: ['trunk', 'main'], credentialsId: 'gh-key' },
    ]
    const yaml = serializeTriggers(original)
    const parsed = parseTriggers(yaml)
    expect(parsed.kind).toBe('ok')
    if (parsed.kind !== 'ok') return
    expect(parsed.triggers).toHaveLength(2)
    expect(parsed.triggers[0]).toMatchObject({ kind: 'cron', expr: '0 */6 * * *' })
    expect(parsed.triggers[1]).toMatchObject({
      kind: 'github',
      branches: ['trunk', 'main'],
      credentialsId: 'gh-key',
    })
  })

  it('round-trips an empty list as on-demand', () => {
    const yaml = serializeTriggers([])
    const parsed = parseTriggers(yaml)
    expect(parsed.kind).toBe('ok')
    if (parsed.kind !== 'ok') return
    expect(parsed.triggers).toHaveLength(0)
  })
})

describe('blankTrigger', () => {
  it('produces a cron blank with a humanizable expression', () => {
    const t = blankTrigger('cron')
    expect(t.kind).toBe('cron')
    if (t.kind !== 'cron') return
    expect(t.expr).toMatch(/\*/)
  })

  it('produces a github blank with a default branch and no credential', () => {
    const t = blankTrigger('github')
    expect(t.kind).toBe('github')
    if (t.kind !== 'github') return
    expect(t.branches.length).toBeGreaterThan(0)
    expect(t.credentialsId).toBeNull()
  })
})
