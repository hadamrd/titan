/**
 * New-job dialog — POST /api/v1/jobs from the /jobs page (closes #512).
 *
 * <p>Design discipline:
 * <ul>
 *   <li>Required fields validated client-side BEFORE the round-trip — submit
 *       button stays disabled until {@code fullName} is non-blank, matches
 *       {@code ^[a-zA-Z0-9._/-]+$}, and {@code pipelineScript} is non-blank.
 *   <li>400 → render parser error inline BELOW the textarea (the SRE is
 *       editing YAML right there; a toast would force a context jump).
 *   <li>409 → render "Job name already taken" inline below {@code fullName}.
 *   <li>Network failure → toast (handled by parent) — the dialog only surfaces
 *       network errors via the {@code onNetworkError} callback so a retry is
 *       reachable without losing the form state.
 * </ul>
 *
 * <p>No string-sniffing: the discriminator is the HTTP status code on the
 * {@link ApiError} (the only place the wire carries semantic intent today).
 */
import { useEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { useNavigate } from '@tanstack/react-router'
import { Button } from '@/components/ui/Button'
import { Input } from '@/components/ui/Input'
import { useCreateJob } from '@/api/hooks'
import { ApiError } from '@/api/types'

const FULLNAME_PATTERN = /^[a-zA-Z0-9._/-]+$/

export const DEFAULT_PIPELINE_SCRIPT = `stages:
  - stage: build
    steps:
      - sh: echo hi`

export interface NewJobDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  /** Invoked when the failure is a transport-level error (not 400/409). */
  onNetworkError?: (message: string) => void
}

interface FormState {
  fullName: string
  displayName: string
  folderPath: string
  pipelineScript: string
  enabled: boolean
}

const INITIAL_STATE: FormState = {
  fullName: '',
  displayName: '',
  folderPath: '',
  pipelineScript: DEFAULT_PIPELINE_SCRIPT,
  enabled: true,
}

export function NewJobDialog({ open, onOpenChange, onNetworkError }: NewJobDialogProps) {
  const navigate = useNavigate()
  const mutation = useCreateJob()
  const [form, setForm] = useState<FormState>(INITIAL_STATE)
  const [nameError, setNameError] = useState<string | null>(null)
  const [scriptError, setScriptError] = useState<string | null>(null)
  const firstFieldRef = useRef<HTMLInputElement>(null)

  // Reset state every time the dialog opens so a previous error/value never
  // leaks into a fresh create attempt.
  useEffect(() => {
    if (!open) return
    setForm(INITIAL_STATE)
    setNameError(null)
    setScriptError(null)
    mutation.reset()
    // Focus first field shortly after the dialog mounts.
    const id = window.setTimeout(() => firstFieldRef.current?.focus(), 0)
    return () => window.clearTimeout(id)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open])

  useEffect(() => {
    if (!open) return
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onOpenChange(false)
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [open, onOpenChange])

  if (!open) return null

  const trimmedName = form.fullName.trim()
  const nameValid = trimmedName.length > 0 && FULLNAME_PATTERN.test(trimmedName)
  const scriptValid = form.pipelineScript.trim().length > 0
  const canSubmit = nameValid && scriptValid && !mutation.isPending

  const clientNameError =
    trimmedName.length === 0
      ? null
      : nameValid
        ? null
        : 'Use letters, digits, `.`, `_`, `/`, or `-` only.'

  const onSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    setNameError(null)
    setScriptError(null)

    if (!nameValid) {
      setNameError(clientNameError ?? 'Job name is required')
      return
    }
    if (!scriptValid) {
      setScriptError('Pipeline script is required')
      return
    }

    mutation.mutate(
      {
        fullName: trimmedName,
        displayName: form.displayName.trim() || null,
        folderPath: form.folderPath.trim() || null,
        pipelineScript: form.pipelineScript,
        enabled: form.enabled,
      },
      {
        onSuccess: (created) => {
          onOpenChange(false)
          void navigate({
            to: '/pipelines/$pipelineId',
            params: { pipelineId: String(created.id) },
          })
        },
        onError: (err) => {
          if (err instanceof ApiError) {
            if (err.status === 409) {
              setNameError('Job name already taken')
              return
            }
            if (err.status === 400) {
              setScriptError(err.problem.detail ?? err.problem.title ?? 'Invalid pipeline')
              return
            }
          }
          // Anything else (5xx, network, CORS) — bubble to the parent as a
          // toast so the user gets a retry affordance without losing form state.
          const msg = err instanceof Error ? err.message : 'Network error'
          onNetworkError?.(msg)
        },
      },
    )
  }

  // Portal to <body> (#113): keeps the fixed backdrop viewport-relative even
  // when the modal is mounted under a transformed/animated ancestor.
  return createPortal(
    <div
      className="gate-modal-backdrop"
      role="presentation"
      onClick={() => onOpenChange(false)}
      data-testid="new-job-backdrop"
    >
      <div
        className="gate-modal"
        role="dialog"
        aria-modal="true"
        aria-labelledby="new-job-title"
        onClick={(e) => e.stopPropagation()}
        style={{ maxWidth: 620, width: '100%' }}
      >
        <form onSubmit={onSubmit}>
          <div className="gate-modal-head">
            <div id="new-job-title" style={{ fontSize: 14, fontWeight: 600 }}>
              New job
            </div>
            <div style={{ fontSize: 12, marginTop: 2, color: 'var(--fg-dim)' }}>
              Create a Titan pipeline job. The YAML is validated server-side before save.
            </div>
          </div>

          <div style={{ padding: '14px 18px', display: 'flex', flexDirection: 'column', gap: 12 }}>
            <Field
              label="Full name"
              required
              hint="Letters, digits, dot, underscore, slash, or hyphen — e.g. `org/my-pipeline`."
              error={nameError ?? clientNameError ?? undefined}
              errorTestId="new-job-name-error"
            >
              <Input
                ref={firstFieldRef}
                type="text"
                value={form.fullName}
                placeholder="org/my-pipeline"
                onChange={(e) => {
                  setForm((s) => ({ ...s, fullName: e.target.value }))
                  if (nameError) setNameError(null)
                }}
                data-testid="new-job-fullname"
                aria-label="Full name"
                aria-invalid={nameError != null || clientNameError != null}
                required
              />
            </Field>

            <Field label="Display name" hint="Optional — defaults to the full name.">
              <Input
                type="text"
                value={form.displayName}
                placeholder="My Pipeline"
                onChange={(e) => setForm((s) => ({ ...s, displayName: e.target.value }))}
                data-testid="new-job-displayname"
                aria-label="Display name"
              />
            </Field>

            <Field label="Folder path" hint="Optional — group jobs in a folder.">
              <Input
                type="text"
                value={form.folderPath}
                placeholder="team-a/services"
                onChange={(e) => setForm((s) => ({ ...s, folderPath: e.target.value }))}
                data-testid="new-job-folder"
                aria-label="Folder path"
              />
            </Field>

            <Field
              label="Pipeline script"
              required
              hint="Titan YAML. Validated server-side via TitanYamlParser."
              error={scriptError ?? undefined}
              errorTestId="new-job-script-error"
              headerExtra={
                <button
                  type="button"
                  className="link"
                  onClick={() => {
                    void navigate({ to: '/pipelines/validate' })
                  }}
                  data-testid="new-job-validate-yaml"
                  style={{
                    fontSize: 11,
                    color: 'var(--accent, var(--fg-dim))',
                    background: 'none',
                    border: 'none',
                    padding: 0,
                    cursor: 'pointer',
                    textDecoration: 'underline',
                  }}
                  title="Open the standalone YAML validator"
                >
                  Validate YAML →
                </button>
              }
            >
              <textarea
                className="field"
                style={{
                  minHeight: 180,
                  fontFamily: 'var(--font-mono, "Geist Mono", ui-monospace, monospace)',
                  fontSize: 12,
                  tabSize: 2,
                }}
                value={form.pipelineScript}
                onChange={(e) => {
                  setForm((s) => ({ ...s, pipelineScript: e.target.value }))
                  if (scriptError) setScriptError(null)
                }}
                spellCheck={false}
                data-testid="new-job-script"
                aria-label="Pipeline script"
                aria-invalid={scriptError != null}
                required
              />
            </Field>

            <label
              style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 12 }}
              data-testid="new-job-enabled-label"
            >
              <input
                type="checkbox"
                checked={form.enabled}
                onChange={(e) => setForm((s) => ({ ...s, enabled: e.target.checked }))}
                data-testid="new-job-enabled"
                aria-label="Enabled"
              />
              Enabled — trigger this job from triggers/API immediately.
            </label>
          </div>

          <div className="gate-modal-foot">
            <button
              type="button"
              className="btn"
              onClick={() => onOpenChange(false)}
              disabled={mutation.isPending}
            >
              Cancel
            </button>
            <Button
              type="submit"
              variant="default"
              size="sm"
              disabled={!canSubmit}
              data-testid="new-job-submit"
            >
              {mutation.isPending ? 'Creating…' : 'Create job'}
            </Button>
          </div>
        </form>
      </div>
    </div>,
    document.body,
  )
}

// ── Internal: labelled field with consistent error rhythm ───────────────────

interface FieldProps {
  label: string
  required?: boolean
  hint?: string
  error?: string
  errorTestId?: string
  /** Right-aligned content inside the label row (e.g. a side-action link). */
  headerExtra?: React.ReactNode
  children: React.ReactNode
}

function Field({
  label,
  required,
  hint,
  error,
  errorTestId,
  headerExtra,
  children,
}: FieldProps) {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
      <div
        style={{
          fontSize: 12,
          color: 'var(--fg-dim)',
          fontWeight: 500,
          display: 'flex',
          alignItems: 'baseline',
          justifyContent: 'space-between',
          gap: 8,
        }}
      >
        <span>
          {label}
          {required ? <span style={{ color: 'var(--fail)', marginLeft: 2 }}>*</span> : null}
        </span>
        {headerExtra ?? null}
      </div>
      {children}
      {error ? (
        <div
          role="alert"
          data-testid={errorTestId}
          style={{ fontSize: 12, color: 'var(--fail)' }}
        >
          {error}
        </div>
      ) : hint ? (
        <div style={{ fontSize: 11, color: 'var(--fg-faint)' }}>{hint}</div>
      ) : null}
    </div>
  )
}
