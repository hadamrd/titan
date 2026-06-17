/**
 * BuildParametersSection — lists the parameters the build actually ran with
 * (issue #1266). Typed projection of `titan.builds.parameters_json`, surfaced
 * on the build-detail DTO as `parametersUsed` (detail endpoint only).
 *
 * Renders ONE `key = value` row per param. Hidden ENTIRELY when no params are
 * present (null / undefined / empty object) — no empty card, no "—" placeholder
 * — so a params-less build's detail page is byte-unchanged from before. Follows
 * the `<details>`-strip pattern of StageTimingSection; default-open because the
 * params are the at-a-glance "what did this run with" context an operator opens
 * the page for.
 */
import type { BuildDto } from '@/api/types'

export function BuildParametersSection({
  params,
}: {
  params: BuildDto['parametersUsed']
}) {
  // Null / undefined / empty → render nothing (no empty card).
  if (params == null) return null
  const entries = Object.entries(params)
  if (entries.length === 0) return null

  return (
    <details
      data-testid="build-parameters-section"
      open
      style={{
        borderBottom: '1px solid var(--border)',
        background: 'var(--bg-1)',
      }}
    >
      <summary
        style={{
          cursor: 'pointer',
          padding: '8px 14px',
          fontSize: 12,
          color: 'var(--fg-dim)',
          userSelect: 'none',
          listStyle: 'revert',
        }}
      >
        Parameters
      </summary>
      <dl
        style={{
          margin: 0,
          padding: '4px 14px 12px',
          display: 'grid',
          gridTemplateColumns: 'max-content 1fr',
          gap: '4px 12px',
          fontSize: 12,
        }}
      >
        {entries.map(([key, value]) => (
          <div
            key={key}
            data-testid={`build-param-${key}`}
            style={{ display: 'contents' }}
          >
            <dt
              className="mono"
              style={{
                fontFamily: 'var(--font-mono)',
                color: 'var(--fg-dim)',
                whiteSpace: 'nowrap',
              }}
            >
              {key}
            </dt>
            <dd
              className="mono"
              style={{
                fontFamily: 'var(--font-mono)',
                color: 'var(--fg)',
                margin: 0,
                wordBreak: 'break-word',
              }}
            >
              {value}
            </dd>
          </div>
        ))}
      </dl>
    </details>
  )
}
