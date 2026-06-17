/**
 * Per-row trigger-source glyph for the /builds list (closes #600).
 *
 * The source is derived from BuildDto.triggerType + triggerMeta via a
 * discriminated-union resolver (CONSTITUTION: typed design, no string sniffing
 * downstream). The resolver is exported so tests pin the mapping directly
 * without DOM scraping.
 *
 *   GITHUB  → GitFork  (webhook OR any build carrying commitSha)
 *   CRON    → Clock    (schedule trigger)
 *   MANUAL  → Play     (UI / API user-initiated)
 *   DOGFOOD → Zap      (titan self-pipeline / null fallback)
 */
import { GitFork, Clock, Play, Zap } from 'lucide-react'
import type { TriggerMetaDto } from '@/api/types'

export type TriggerSource = 'GITHUB' | 'CRON' | 'MANUAL' | 'DOGFOOD'

export function resolveTriggerSource(
  triggerType: string | null | undefined,
  triggerMeta: TriggerMetaDto | null | undefined,
): TriggerSource {
  // Webhook payloads with a commitSha are unambiguously GitHub-born even if
  // the type column lags (legacy rows before #589 may not carry the type).
  if (triggerType === 'GITHUB_WEBHOOK') return 'GITHUB'
  if (triggerMeta && triggerMeta.commitSha) return 'GITHUB'
  if (triggerType === 'CRON') return 'CRON'
  if (triggerType === 'MANUAL') return 'MANUAL'
  // DOGFOOD or null → dogfood fallback.
  return 'DOGFOOD'
}

interface IconSpec {
  Icon: typeof GitFork
  tooltip: string
  testId: string
}

const SPEC: Record<TriggerSource, IconSpec> = {
  GITHUB: {
    Icon: GitFork,
    tooltip: 'Triggered via GitHub webhook',
    testId: 'trigger-source-github',
  },
  CRON: {
    Icon: Clock,
    tooltip: 'Triggered via cron schedule',
    testId: 'trigger-source-cron',
  },
  MANUAL: {
    Icon: Play,
    tooltip: 'Triggered manually',
    testId: 'trigger-source-manual',
  },
  DOGFOOD: {
    Icon: Zap,
    tooltip: 'Triggered via dogfood',
    testId: 'trigger-source-dogfood',
  },
}

export interface TriggerSourceIconProps {
  triggerType: string | null | undefined
  triggerMeta?: TriggerMetaDto | null
  size?: number
}

export function TriggerSourceIcon({
  triggerType,
  triggerMeta,
  size = 14,
}: TriggerSourceIconProps) {
  const source = resolveTriggerSource(triggerType, triggerMeta)
  const { Icon, tooltip, testId } = SPEC[source]
  return (
    <span
      title={tooltip}
      aria-label={tooltip}
      data-testid={testId}
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        justifyContent: 'center',
        width: 24,
        color: 'var(--fg-muted)',
      }}
    >
      <Icon size={size} aria-hidden="true" />
    </span>
  )
}
