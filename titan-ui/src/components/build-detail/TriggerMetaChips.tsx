/**
 * Inline trigger-meta chips (closes #596). Renders ONLY the fields present
 * on the TriggerMetaDto — null/undefined fields are silently dropped so no
 * literal "undefined" can leak into the DOM. Mockup reference:
 * docs/design/build-detail-v3-mockups/project/build-detail/v3-stack.html
 * lines 244–268.
 *
 * Separator policy: a dim "·" appears BEFORE every chip after the first
 * present one (never trailing, never leading). The duration / time chips
 * that follow in the parent .bd-meta block render their own leading "·"
 * unconditionally, so we omit a trailing separator here.
 */
import React from 'react'
import type { TriggerMetaDto } from '@/api/types'

export function TriggerMetaChips({ meta }: { meta: TriggerMetaDto }) {
  const chips: React.ReactNode[] = []
  if (meta.branch) {
    chips.push(
      <span
        key="branch"
        data-testid="trigger-meta-branch"
        style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}
      >
        <svg
          width="12"
          height="12"
          viewBox="0 0 16 16"
          fill="none"
          stroke="currentColor"
          strokeWidth="1.5"
          aria-hidden="true"
          style={{ verticalAlign: '-2px' }}
        >
          <circle cx="4" cy="3" r="1.4" />
          <circle cx="4" cy="13" r="1.4" />
          <circle cx="12" cy="8" r="1.4" />
          <path d="M4 4.4v7.2M5.4 11.2C8 11 10.6 9.5 10.6 8M5 4.4c3 0 5.6 1.5 5.6 3.6" />
        </svg>
        {meta.branch}
      </span>,
    )
  }
  if (meta.commitSha) {
    chips.push(
      <span
        key="sha"
        data-testid="trigger-meta-sha"
        className="mono"
        style={{ fontFamily: 'var(--font-mono)' }}
      >
        {meta.commitSha.slice(0, 7)}
      </span>,
    )
  }
  if (meta.actor) {
    chips.push(
      <span key="actor" data-testid="trigger-meta-actor">
        {meta.actor}
      </span>,
    )
  }

  if (chips.length === 0) return null

  return (
    <>
      {chips.map((chip, i) => (
        <React.Fragment key={i}>
          {i > 0 && <span className="sep">·</span>}
          {chip}
        </React.Fragment>
      ))}
      <span className="sep">·</span>
    </>
  )
}
