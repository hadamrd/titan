import { useState } from 'react'
import { Button } from '@/components/ui/Button'
import { TriggerEditor } from '@/components/TriggerEditor'
import { useJobTriggers } from '@/api/hooks'
import { formatDate } from '@/lib/format'
import { parseTriggers, type ParsedTrigger } from '@/lib/triggers'
import type { JobTriggerDto } from '@/api/types'

interface CronTriggersPanelProps {
  jobId: number
  pipelineScript: string | null | undefined
  jobEnabled: boolean
}

type CronEntry = Extract<ParsedTrigger, { kind: 'cron' }>

/**
 * Read + edit panel for the job's cron triggers — closes #722, runtime state #725.
 *
 * <p>Two data sources joined client-side:
 *
 * <ul>
 *   <li>{@code pipelineScript} → the YAML-parsed cron entries (the source of
 *       truth for "what is configured?", including humanized text);
 *   <li>{@code GET /api/v1/jobs/{id}/triggers} → per-trigger runtime state
 *       (the source of truth for "when did it last fire?" / lastError).
 * </ul>
 *
 * <p>We position rows by index: the order of cron entries in the YAML matches
 * the order the engine ships back. Runtime state is optional — if the API call
 * is in flight or fails the panel still renders, just without the extra
 * columns (the configured view is the load-bearing one).
 */
export function CronTriggersPanel({
  jobId,
  pipelineScript,
  jobEnabled,
}: CronTriggersPanelProps) {
  const [editing, setEditing] = useState(false)
  const parsed = parseTriggers(pipelineScript)
  const triggersQuery = useJobTriggers(jobId)

  const cronTriggers: CronEntry[] =
    parsed.kind === 'ok'
      ? parsed.triggers.filter((t): t is CronEntry => t.kind === 'cron')
      : []

  const parseFailed = parsed.kind === 'error'

  // Pick the runtime rows whose type is 'cron' — the API filters by closed
  // discriminator so we never have to sniff anything else.
  const runtimeCron: JobTriggerDto[] = (triggersQuery.data ?? []).filter(
    (t) => t.type === 'cron',
  )

  // Collapse the panel entirely when the job has no cron schedules and no
  // parse error. Users can add cron via the page-header "Edit triggers"
  // button — no need for an empty card eating viewport.
  if (!parseFailed && cronTriggers.length === 0) {
    return null
  }

  return (
    <div className="card" data-testid="cron-triggers-panel">
      <div className="card-header">
        <h3 className="card-title">Cron triggers</h3>
        <span className="card-sub">
          {`${cronTriggers.length} schedule${cronTriggers.length === 1 ? '' : 's'}`}
        </span>
      </div>

      {parseFailed && (
        <div
          className="empty"
          data-testid="cron-triggers-parse-error"
          style={{ color: 'var(--warn)' }}
        >
          Pipeline triggers block is unparseable — open Edit triggers to repair.
        </div>
      )}

      {!parseFailed && cronTriggers.length === 0 && (
        <div className="empty" data-testid="cron-triggers-empty">
          No triggers configured
        </div>
      )}

      {!parseFailed && cronTriggers.length > 0 && (
        <div className="row-list" data-testid="cron-triggers-list">
          {cronTriggers.map((t, i) => {
            // Index-join: the YAML order matches the engine's array order.
            // If the runtime list is shorter (server still parsing a newly
            // PATCHed script) we just render the configured row.
            const runtime = runtimeCron[i]
            const lastFiredAt = runtime?.lastFiredAt ?? null
            const lastError = runtime?.lastError ?? null
            return (
              <div
                key={i}
                className="row"
                style={{
                  gridTemplateColumns: '1fr 1fr auto auto',
                  alignItems: 'center',
                  gap: 8,
                }}
                data-testid="cron-triggers-row"
              >
                <span
                  className="mono"
                  style={{ fontSize: 12, color: 'var(--fg)' }}
                  data-testid="cron-triggers-expr"
                >
                  {t.expr}
                </span>
                <span
                  style={{
                    fontSize: 12,
                    color: 'var(--fg-muted)',
                  }}
                  data-testid="cron-triggers-humanized"
                >
                  {t.humanized}
                </span>
                {lastFiredAt && (
                  <span
                    style={{ fontSize: 12, color: 'var(--fg-muted)' }}
                    data-testid="cron-triggers-last-fired"
                    title={`Last fired at ${lastFiredAt}`}
                  >
                    {formatDate(lastFiredAt, 'relative')}
                  </span>
                )}
                {lastError && (
                  <span
                    className="chip chip-error"
                    style={{
                      fontSize: 11,
                      color: 'var(--err)',
                      border: '1px solid var(--err)',
                      borderRadius: 4,
                      padding: '1px 6px',
                    }}
                    data-testid="cron-triggers-last-error"
                    title={lastError}
                  >
                    error
                  </span>
                )}
              </div>
            )
          })}
        </div>
      )}

      <div
        style={{
          marginTop: 12,
          display: 'flex',
          gap: 8,
          alignItems: 'center',
        }}
      >
        <Button
          size="sm"
          variant="ghost"
          onClick={() => setEditing(true)}
          disabled={!jobEnabled}
          data-testid="cron-triggers-edit-btn"
          title={
            jobEnabled
              ? 'Edit cron schedules in the full trigger editor'
              : 'Enable the job to edit triggers'
          }
        >
          {cronTriggers.length === 0 ? 'Add cron trigger' : 'Edit cron triggers'}
        </Button>
      </div>

      {editing && (
        <TriggerEditor
          jobId={jobId}
          pipelineScript={pipelineScript ?? ''}
          onClose={() => setEditing(false)}
        />
      )}
    </div>
  )
}
