import { useState } from 'react'
import { Badge } from '@/components/ui/Badge'
import { parseTriggers, type ParsedTrigger } from '@/lib/triggers'

interface TriggerChipsProps {
  pipelineScript: string | null | undefined
}

/**
 * Read-only chips summarising the pipeline's triggers (cron / github /
 * on-demand). Click a chip → inline YAML snippet popover.
 *
 * v0.1.0 — display-only; edit UI lives behind ticket #439.
 */
export function TriggerChips({ pipelineScript }: TriggerChipsProps) {
  const [openIdx, setOpenIdx] = useState<number | null>(null)
  const parsed = parseTriggers(pipelineScript)

  if (parsed.kind === 'error') {
    // Defensive: log + degrade gracefully. Never let a malformed YAML break
    // the whole page.
    // eslint-disable-next-line no-console
    console.warn('[TriggerChips] unparseable pipeline triggers:', parsed.reason)
    return (
      <Badge variant="warn" title={parsed.reason} data-testid="trigger-chip-unparseable">
        triggers unparseable
      </Badge>
    )
  }

  const triggers = parsed.triggers
  if (triggers.length === 0) {
    return (
      <Badge data-testid="trigger-chip-ondemand" title="No triggers configured">
        on demand
      </Badge>
    )
  }

  return (
    <div style={{ display: 'inline-flex', gap: 6, flexWrap: 'wrap', position: 'relative' }}>
      {triggers.map((t, i) => (
        <TriggerChip
          key={i}
          trigger={t}
          isOpen={openIdx === i}
          onToggle={() => setOpenIdx((cur) => (cur === i ? null : i))}
        />
      ))}
    </div>
  )
}

interface TriggerChipProps {
  trigger: ParsedTrigger
  isOpen: boolean
  onToggle: () => void
}

function TriggerChip({ trigger, isOpen, onToggle }: TriggerChipProps) {
  const { label, snippet, variant, testid } = describe(trigger)
  return (
    <span style={{ position: 'relative', display: 'inline-block' }}>
      <Badge
        variant={variant}
        role="button"
        tabIndex={0}
        data-testid={testid}
        onClick={onToggle}
        onKeyDown={(e) => {
          if (e.key === 'Enter' || e.key === ' ') {
            e.preventDefault()
            onToggle()
          }
        }}
        style={{ cursor: 'pointer' }}
        title={snippet}
      >
        {label}
      </Badge>
      {isOpen && (
        <div
          role="tooltip"
          className="card"
          style={{
            position: 'absolute',
            top: 'calc(100% + 4px)',
            left: 0,
            zIndex: 20,
            padding: 8,
            minWidth: 200,
            fontFamily: 'var(--font-mono)',
            fontSize: 11,
            whiteSpace: 'pre',
            color: 'var(--fg-muted)',
          }}
        >
          {snippet}
        </div>
      )}
    </span>
  )
}

interface ChipDescriptor {
  label: string
  snippet: string
  variant: 'default' | 'success' | 'fail' | 'warn' | 'info' | 'accent'
  testid: string
}

/**
 * Discriminated-union switch — no string-sniffing. Each kind maps to a
 * fixed label/variant/testid pair.
 */
function describe(t: ParsedTrigger): ChipDescriptor {
  switch (t.kind) {
    case 'cron':
      return {
        label: `cron · ${t.humanized}`,
        snippet: `- cron: "${t.expr}"`,
        variant: 'info',
        testid: 'trigger-chip-cron',
      }
    case 'github':
      return {
        label: `github · ${t.branches.length === 0 ? 'any branch' : t.branches.join('|')}`,
        snippet:
          t.branches.length === 0
            ? '- github: {}'
            : `- github:\n    branches: [${t.branches.map((b) => `"${b}"`).join(', ')}]`,
        variant: 'accent',
        testid: 'trigger-chip-github',
      }
    case 'unknown':
      return {
        label: `trigger · ${t.raw.slice(0, 24)}`,
        snippet: `- ${t.raw}`,
        variant: 'warn',
        testid: 'trigger-chip-unknown',
      }
  }
}
