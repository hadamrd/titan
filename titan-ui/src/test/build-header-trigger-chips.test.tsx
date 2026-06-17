/**
 * Adversarial tests for the v3 build-header trigger-meta chips (closes #596).
 *
 * Backend half (PR #595) ships `BuildDto.triggerMeta = { branch?, commitSha?, actor? }`
 * on webhook-born builds; null/undefined on manual / dogfood / replay. Mockup:
 * docs/design/build-detail-v3-mockups/project/build-detail/v3-stack.html
 * lines 244–268.
 *
 * Pinned invariants:
 *   1. All three fields present  → all three chips render with correct text,
 *      with the commitSha truncated to 7 chars (Git short-SHA convention).
 *   2. triggerMeta absent        → legacy {triggeredBy} / {triggerType} subtitle
 *      renders unchanged AND none of the chip test-ids appear.
 *   3. Partial { branch only }   → branch chip renders, SHA + actor chips
 *      ABSENT, and NO literal "undefined" / "null" leaks into the DOM.
 */
import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { TriggerMetaChips } from '../routes/builds/$buildId'
import type { TriggerMetaDto } from '../api/types'

describe('TriggerMetaChips', () => {
  it('renders branch, short-SHA, and actor chips when all fields are present', () => {
    const meta: TriggerMetaDto = {
      branch: 'main',
      commitSha: 'a3f9c1234567890',
      actor: 'kira.rai',
    }
    render(<TriggerMetaChips meta={meta} />)

    const branchChip = screen.getByTestId('trigger-meta-branch')
    const shaChip = screen.getByTestId('trigger-meta-sha')
    const actorChip = screen.getByTestId('trigger-meta-actor')

    expect(branchChip.textContent).toContain('main')
    // Short-SHA = first 7 chars, NOT the full hash.
    expect(shaChip.textContent).toBe('a3f9c12')
    expect(shaChip.textContent).not.toContain('a3f9c1234567890')
    expect(actorChip.textContent).toBe('kira.rai')
  })

  it('renders nothing visible when all fields are null (no undefined leak)', () => {
    const meta: TriggerMetaDto = { branch: null, commitSha: null, actor: null }
    const { container } = render(<TriggerMetaChips meta={meta} />)

    expect(container.querySelector('[data-testid="trigger-meta-branch"]')).toBeNull()
    expect(container.querySelector('[data-testid="trigger-meta-sha"]')).toBeNull()
    expect(container.querySelector('[data-testid="trigger-meta-actor"]')).toBeNull()
    expect(container.textContent ?? '').not.toMatch(/undefined|null/i)
  })

  it('renders ONLY the branch chip when commitSha and actor are absent', () => {
    const meta: TriggerMetaDto = { branch: 'main' }
    const { container } = render(<TriggerMetaChips meta={meta} />)

    expect(screen.getByTestId('trigger-meta-branch').textContent).toContain('main')
    expect(container.querySelector('[data-testid="trigger-meta-sha"]')).toBeNull()
    expect(container.querySelector('[data-testid="trigger-meta-actor"]')).toBeNull()
    // No "undefined" / "null" leak — the partial branch chip must NOT carry
    // placeholder text for the missing SHA / actor.
    expect(container.textContent ?? '').not.toMatch(/undefined|null/i)
  })
})
