/**
 * Tests for the GitHub-App provenance badge on the build-detail header (issue
 * #892, finishing the UX half of #835).
 *
 * Backend half (this PR's Java side) extends `BuildDto.TriggerMetaDto` with
 * `{ provenance, repoFullName, commitUrl }`, populated only for
 * `triggerType` starting with `github-app` AND a resolvable job→repo linkage.
 *
 * Pinned invariants (from the ticket's acceptance criteria + test matrix):
 *   1. provenance === "github-app" with repo + commitUrl → badge renders with
 *      "via GitHub App · {org}/{repo} · view on github.com"; the link points at
 *      the commit on github.com and carries target=_blank + rel=noopener.
 *   2. manual build (no provenance) → NO badge (byte-identical to today).
 *   3. Adversarial: provenance set but repoFullName / commitUrl null/empty →
 *      badge suppressed (no broken link, no "via GitHub App · undefined").
 */
import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { GithubProvenanceBadge } from './GithubProvenanceBadge'
import type { TriggerMetaDto } from '../../api/types'

const SHA = 'abc123def456abc123def456abc123def456abcd'

describe('GithubProvenanceBadge', () => {
  it('renders the badge with org/repo text and a github.com commit link', () => {
    const meta: TriggerMetaDto = {
      branch: 'main',
      commitSha: SHA,
      actor: 'octocat',
      provenance: 'github-app',
      repoFullName: 'hadamrd/titan',
      commitUrl: `https://github.com/hadamrd/titan/commit/${SHA}`,
    }
    render(<GithubProvenanceBadge meta={meta} />)

    const badge = screen.getByTestId('github-provenance-badge')
    expect(badge.textContent).toContain('via GitHub App')
    expect(screen.getByTestId('github-provenance-repo').textContent).toBe(
      'hadamrd/titan',
    )

    const link = screen.getByTestId('github-provenance-link') as HTMLAnchorElement
    expect(link.tagName).toBe('A')
    expect(link.textContent).toBe('view on github.com')
    expect(link.getAttribute('href')).toBe(
      `https://github.com/hadamrd/titan/commit/${SHA}`,
    )
    expect(link.getAttribute('target')).toBe('_blank')
    expect(link.getAttribute('rel')).toBe('noopener noreferrer')
  })

  it('renders NO badge for a manually-triggered build (negative case)', () => {
    // A manual build: webhook meta may still be present, but provenance is absent.
    const meta: TriggerMetaDto = {
      branch: 'main',
      commitSha: SHA,
      actor: 'alice',
    }
    const { container } = render(<GithubProvenanceBadge meta={meta} />)

    expect(
      container.querySelector('[data-testid="github-provenance-badge"]'),
    ).toBeNull()
    expect(container.textContent ?? '').not.toContain('via GitHub App')
  })

  it('renders NO badge when triggerMeta is null/undefined', () => {
    const { container: a } = render(<GithubProvenanceBadge meta={null} />)
    const { container: b } = render(<GithubProvenanceBadge meta={undefined} />)
    expect(
      a.querySelector('[data-testid="github-provenance-badge"]'),
    ).toBeNull()
    expect(
      b.querySelector('[data-testid="github-provenance-badge"]'),
    ).toBeNull()
  })

  it('adversarial: provenance set but repoFullName missing → suppressed, no undefined leak', () => {
    const meta: TriggerMetaDto = {
      provenance: 'github-app',
      repoFullName: null,
      commitUrl: `https://github.com/x/y/commit/${SHA}`,
    }
    const { container } = render(<GithubProvenanceBadge meta={meta} />)

    expect(
      container.querySelector('[data-testid="github-provenance-badge"]'),
    ).toBeNull()
    expect(container.textContent ?? '').not.toMatch(/undefined|null/i)
    expect(container.textContent ?? '').not.toContain('via GitHub App')
  })

  it('adversarial: provenance set but commitUrl empty → suppressed (no broken link)', () => {
    const meta: TriggerMetaDto = {
      provenance: 'github-app',
      repoFullName: 'hadamrd/titan',
      commitUrl: '   ',
    }
    const { container } = render(<GithubProvenanceBadge meta={meta} />)

    expect(
      container.querySelector('[data-testid="github-provenance-link"]'),
    ).toBeNull()
    expect(
      container.querySelector('[data-testid="github-provenance-badge"]'),
    ).toBeNull()
  })
})
