import { useEffect, useRef, useState } from 'react'
import { Button } from '@/components/ui/Button'
import { Input } from '@/components/ui/Input'
import { useGithubWebhookCredentials, useUpdateJobScript } from '@/api/hooks'
import { ApiError } from '@/api/types'
import {
  blankTrigger,
  humanizeCron,
  parseTriggers,
  replaceTriggersBlock,
  type ParsedTrigger,
} from '@/lib/triggers'

interface TriggerEditorProps {
  jobId: number
  pipelineScript: string
  onClose: () => void
  onSaved?: () => void
}

/**
 * Modal editor for the `triggers:` block of a job's pipeline script.
 *
 * <p>Renders one row per trigger; each row switches on {@code trigger.kind} so
 * fields are typed (no string-sniffing). Save serialises the local form state
 * back to YAML, replaces the {@code triggers:} block in the existing script,
 * and PATCHes the job. Server-side {@code TitanYamlParser} is the source of
 * truth — a 400 surfaces as an inline error here.
 */
export function TriggerEditor({ jobId, pipelineScript, onClose, onSaved }: TriggerEditorProps) {
  const initial = parseTriggers(pipelineScript)
  const [triggers, setTriggers] = useState<ParsedTrigger[]>(
    initial.kind === 'ok' ? initial.triggers : [],
  )
  const [serverError, setServerError] = useState<string | null>(null)
  const mutation = useUpdateJobScript()
  const creds = useGithubWebhookCredentials()
  const dialogRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  const addRow = (kind: ParsedTrigger['kind']) =>
    setTriggers((prev) => [...prev, blankTrigger(kind)])
  const removeRow = (idx: number) =>
    setTriggers((prev) => prev.filter((_, i) => i !== idx))
  const updateRow = (idx: number, next: ParsedTrigger) =>
    setTriggers((prev) => prev.map((t, i) => (i === idx ? next : t)))

  const onSave = () => {
    setServerError(null)
    // Light client-side guard: github triggers without a credentialsId will be
    // rejected by the server (HMAC secret required, PR #403). Surface that
    // immediately instead of waiting for a round-trip.
    const missingCreds = triggers.some(
      (t) => t.kind === 'github' && (!t.credentialsId || t.credentialsId.trim() === ''),
    )
    if (missingCreds) {
      setServerError('github triggers require a credentialsId')
      return
    }
    const newScript = replaceTriggersBlock(pipelineScript, triggers)
    mutation.mutate(
      { jobId, pipelineScript: newScript },
      {
        onSuccess: () => {
          onSaved?.()
          onClose()
        },
        onError: (err) => {
          const msg =
            err instanceof ApiError
              ? (err.problem.detail ?? err.problem.title ?? 'Save failed')
              : 'Save failed'
          setServerError(msg)
        },
      },
    )
  }

  return (
    <div
      className="gate-modal-backdrop"
      role="presentation"
      onClick={onClose}
      data-testid="trigger-editor-backdrop"
    >
      <div
        ref={dialogRef}
        className="gate-modal"
        role="dialog"
        aria-modal="true"
        aria-labelledby="trigger-editor-title"
        onClick={(e) => e.stopPropagation()}
        style={{ maxWidth: 560, width: '100%' }}
      >
        <div className="gate-modal-head">
          <div id="trigger-editor-title" style={{ fontSize: 14, fontWeight: 600 }}>
            Edit triggers
          </div>
          <div style={{ fontSize: 12, marginTop: 2, color: 'var(--fg-dim)' }}>
            Configure how this job is triggered — cron schedule, github push, or on-demand.
          </div>
        </div>

        <div style={{ padding: '14px 18px', display: 'flex', flexDirection: 'column', gap: 10 }}>
          {triggers.length === 0 && (
            <div
              style={{ fontSize: 12, color: 'var(--fg-dim)', fontStyle: 'italic' }}
              data-testid="trigger-editor-empty"
            >
              No triggers — this job runs on demand only.
            </div>
          )}
          {triggers.map((t, i) => (
            <TriggerRow
              key={i}
              trigger={t}
              credentials={creds.data?.items ?? []}
              onChange={(next) => updateRow(i, next)}
              onRemove={() => removeRow(i)}
            />
          ))}

          <div style={{ display: 'flex', gap: 8, marginTop: 4 }}>
            <Button
              type="button"
              size="sm"
              variant="ghost"
              onClick={() => addRow('cron')}
              data-testid="trigger-editor-add-cron"
            >
              + Cron
            </Button>
            <Button
              type="button"
              size="sm"
              variant="ghost"
              onClick={() => addRow('github')}
              data-testid="trigger-editor-add-github"
            >
              + GitHub
            </Button>
          </div>

          {serverError && (
            <div
              role="alert"
              style={{ fontSize: 12, color: 'var(--fail)', marginTop: 4 }}
              data-testid="trigger-editor-error"
            >
              {serverError}
            </div>
          )}
        </div>

        <div className="gate-modal-foot">
          <button
            type="button"
            className="btn"
            onClick={onClose}
            disabled={mutation.isPending}
          >
            Cancel
          </button>
          <button
            type="button"
            className="btn btn-primary"
            onClick={onSave}
            disabled={mutation.isPending}
            data-testid="trigger-editor-save"
          >
            {mutation.isPending ? 'Saving…' : 'Save'}
          </button>
        </div>
      </div>
    </div>
  )
}

interface TriggerRowProps {
  trigger: ParsedTrigger
  credentials: { id: number; key: string }[]
  onChange: (next: ParsedTrigger) => void
  onRemove: () => void
}

function TriggerRow({ trigger, credentials, onChange, onRemove }: TriggerRowProps) {
  // Type changer — swap the row to a blank of the requested kind. Switching
  // kind discards the old row's fields on purpose (avoids carrying stale data
  // across discriminated-union variants).
  const onKindChange = (kind: ParsedTrigger['kind']) => {
    if (kind === trigger.kind) return
    onChange(blankTrigger(kind))
  }

  return (
    <div
      style={{
        display: 'flex',
        flexDirection: 'column',
        gap: 6,
        padding: 10,
        border: '1px solid var(--border)',
        borderRadius: 'var(--r-md)',
        background: 'var(--surface-2, var(--surface))',
      }}
      data-testid={`trigger-row-${trigger.kind}`}
    >
      <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
        <select
          className="field"
          value={trigger.kind === 'unknown' ? 'cron' : trigger.kind}
          onChange={(e) => onKindChange(e.target.value as ParsedTrigger['kind'])}
          style={{ width: 120 }}
          aria-label="Trigger type"
        >
          <option value="cron">cron</option>
          <option value="github">github</option>
        </select>
        <div style={{ flex: 1 }} />
        <Button
          type="button"
          size="sm"
          variant="ghost"
          onClick={onRemove}
          aria-label="Remove trigger"
        >
          Remove
        </Button>
      </div>

      {trigger.kind === 'cron' && (
        <CronFields trigger={trigger} onChange={onChange} />
      )}
      {trigger.kind === 'github' && (
        <GithubFields trigger={trigger} credentials={credentials} onChange={onChange} />
      )}
    </div>
  )
}

function CronFields({
  trigger,
  onChange,
}: {
  trigger: Extract<ParsedTrigger, { kind: 'cron' }>
  onChange: (next: ParsedTrigger) => void
}) {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
      <Input
        type="text"
        value={trigger.expr}
        placeholder="0 */6 * * *"
        onChange={(e) =>
          onChange({
            kind: 'cron',
            expr: e.target.value,
            humanized: humanizeCron(e.target.value),
          })
        }
        data-testid="trigger-cron-expr"
        aria-label="Cron expression"
      />
      <div style={{ fontSize: 11, color: 'var(--fg-dim)' }}>
        {trigger.humanized === trigger.expr ? 'expression' : trigger.humanized}
      </div>
    </div>
  )
}

function GithubFields({
  trigger,
  credentials,
  onChange,
}: {
  trigger: Extract<ParsedTrigger, { kind: 'github' }>
  credentials: { id: number; key: string }[]
  onChange: (next: ParsedTrigger) => void
}) {
  const branchesText = trigger.branches.join(', ')
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
      <label style={{ fontSize: 11, color: 'var(--fg-faint)' }}>Branches (comma-separated)</label>
      <Input
        type="text"
        value={branchesText}
        placeholder="trunk, main"
        onChange={(e) => {
          const branches = e.target.value
            .split(',')
            .map((b) => b.trim())
            .filter((b) => b.length > 0)
          onChange({ ...trigger, branches })
        }}
        data-testid="trigger-github-branches"
        aria-label="Branches"
      />
      <label style={{ fontSize: 11, color: 'var(--fg-faint)', marginTop: 4 }}>
        HMAC credential id
      </label>
      {credentials.length > 0 ? (
        <select
          className="field"
          value={trigger.credentialsId ?? ''}
          onChange={(e) =>
            onChange({ ...trigger, credentialsId: e.target.value || null })
          }
          data-testid="trigger-github-credentials"
          aria-label="HMAC credential"
        >
          <option value="">— pick one —</option>
          {credentials.map((c) => (
            <option key={c.id} value={c.key}>
              {c.key}
            </option>
          ))}
        </select>
      ) : (
        <Input
          type="text"
          value={trigger.credentialsId ?? ''}
          placeholder="github-webhook-secret"
          onChange={(e) =>
            onChange({ ...trigger, credentialsId: e.target.value || null })
          }
          data-testid="trigger-github-credentials"
          aria-label="HMAC credential id"
        />
      )}
    </div>
  )
}
