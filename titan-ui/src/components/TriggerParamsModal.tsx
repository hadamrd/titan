import { useEffect, useMemo, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { Button } from '@/components/ui/Button'
import type { PipelineParameterDto } from '@/api/types'

interface TriggerParamsModalProps {
  jobFullName: string
  parameters: PipelineParameterDto[]
  isPending: boolean
  /** RFC 7807 detail surfaced from the trigger mutation when the server 400s. */
  errorMessage?: string | null
  onCancel: () => void
  /**
   * Fires when the user confirms. {@code overrides} carries ONLY the keys whose
   * current value differs from the declared default — un-overridden params are
   * omitted so the server applies its own default rather than wire-trailing
   * every key as a no-op override.
   */
  onConfirm: (overrides: Record<string, string>) => void
}

/**
 * Trigger-with-params modal (closes #779 — UI half of #774).
 *
 * <p>Type-aware inputs per declared parameter:
 * <ul>
 *   <li>{@code string} → text input</li>
 *   <li>{@code number} → type="number" input</li>
 *   <li>{@code boolean} → checkbox</li>
 *   <li>{@code choice} → select of {@code choices}</li>
 * </ul>
 *
 * <p>The mounted-only-when-needed contract is owned by the caller: the modal
 * itself doesn't decide whether to render — the route opens it iff
 * {@code parameters.length ≥ 1}.
 *
 * <p>Only diff-from-default keys are submitted (the server applies defaults
 * for missing keys), keeping the wire shape minimal and back-compat — a build
 * triggered with all defaults travels the same path as a no-params build.
 */
export function TriggerParamsModal({
  jobFullName,
  parameters,
  isPending,
  errorMessage,
  onCancel,
  onConfirm,
}: TriggerParamsModalProps) {
  const dialogRef = useRef<HTMLDivElement>(null)

  // Normalise every default to its display-string form. Booleans render as
  // 'true'/'false'; numbers as their stringified value; null/undefined as ''.
  const defaultsByName = useMemo(() => {
    const out: Record<string, string> = {}
    for (const p of parameters) {
      out[p.name] = defaultToString(p)
    }
    return out
  }, [parameters])

  const [values, setValues] = useState<Record<string, string>>(defaultsByName)

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      // Escape ALWAYS dismisses — even mid-trigger. A pending request must never
      // trap the user in the modal (the trigger may have hung); onCancel aborts
      // and resets the mutation. Only Confirm stays guarded against double-submit.
      if (e.key === 'Escape') onCancel()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onCancel, isPending])

  const handleSubmit = () => {
    const overrides: Record<string, string> = {}
    for (const p of parameters) {
      const current = values[p.name] ?? ''
      const dflt = defaultsByName[p.name] ?? ''
      if (current !== dflt) {
        overrides[p.name] = current
      }
    }
    onConfirm(overrides)
  }

  // Portal to <body> so the fixed-position backdrop escapes the table row's
  // stacking/transform context. Rendered inline, the backdrop didn't cover the
  // viewport, so "click outside to dismiss" landed back on the row's trigger and
  // RE-OPENED the modal. A portal makes the backdrop cover the screen → outside
  // clicks hit it → onCancel closes cleanly.
  return createPortal(
    <div
      className="gate-modal-backdrop"
      role="presentation"
      onClick={() => {
        // Backdrop click ALWAYS dismisses (even mid-trigger) — never trap the user.
        onCancel()
      }}
      data-testid="trigger-params-backdrop"
    >
      <div
        ref={dialogRef}
        className="gate-modal"
        role="dialog"
        aria-modal="true"
        aria-labelledby="trigger-params-title"
        onClick={(e) => e.stopPropagation()}
        style={{ maxWidth: 560, width: '100%' }}
      >
        <div className="gate-modal-head">
          <div id="trigger-params-title" style={{ fontSize: 14, fontWeight: 600 }}>
            Run pipeline for{' '}
            <span style={{ fontFamily: 'var(--font-mono)' }}>{jobFullName}</span>
          </div>
          <div style={{ fontSize: 12, marginTop: 2, color: 'var(--fg-dim)' }}>
            Review and override declared parameters, then confirm to queue a build.
          </div>
        </div>

        <form
          onSubmit={(e) => {
            e.preventDefault()
            if (!isPending) handleSubmit()
          }}
        >
          <div
            style={{
              padding: '14px 18px',
              display: 'flex',
              flexDirection: 'column',
              gap: 14,
              maxHeight: 'min(60vh, 480px)',
              overflowY: 'auto',
            }}
          >
            {parameters.map((p) => (
              <ParamField
                key={p.name}
                param={p}
                value={values[p.name] ?? ''}
                disabled={isPending}
                onChange={(v) => setValues((prev) => ({ ...prev, [p.name]: v }))}
              />
            ))}
          </div>

          {errorMessage && (
            <div
              data-testid="trigger-params-error"
              style={{
                margin: '0 18px 12px',
                padding: '8px 10px',
                fontSize: 12,
                color: 'var(--fail)',
                border: '1px solid var(--fail)',
                borderRadius: 'var(--r-md)',
                background: 'var(--bg-elevated, transparent)',
              }}
            >
              {errorMessage}
            </div>
          )}

          <div className="gate-modal-foot">
            <button
              type="button"
              className="btn"
              onClick={onCancel}
              data-testid="trigger-params-cancel"
            >
              Cancel
            </button>
            <Button
              size="sm"
              type="submit"
              disabled={isPending}
              data-testid="trigger-params-confirm"
            >
              {isPending ? 'Running…' : 'Trigger'}
            </Button>
          </div>
        </form>
      </div>
    </div>,
    document.body,
  )
}

// ── Field renderers ─────────────────────────────────────────────────────────

interface ParamFieldProps {
  param: PipelineParameterDto
  value: string
  disabled: boolean
  onChange: (v: string) => void
}

function ParamField({ param, value, disabled, onChange }: ParamFieldProps) {
  const inputId = `trigger-param-${param.name}`
  const descId = param.description ? `${inputId}-hint` : undefined
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
      <label
        htmlFor={inputId}
        style={{
          fontSize: 12,
          fontWeight: 600,
          color: 'var(--fg)',
          display: 'inline-flex',
          alignItems: 'center',
          gap: 6,
        }}
      >
        <span style={{ fontFamily: 'var(--font-mono)' }}>{param.name}</span>
        <span style={{ color: 'var(--fg-faint)', fontWeight: 400 }}>
          ({param.type})
        </span>
        {param.required && (
          <span style={{ color: 'var(--fail)', fontWeight: 400 }} aria-label="required">
            *
          </span>
        )}
      </label>
      {renderInput(param, inputId, value, disabled, onChange, descId)}
      {param.description && (
        <div
          id={descId}
          style={{ fontSize: 11, color: 'var(--fg-dim)', lineHeight: 1.4 }}
        >
          {param.description}
        </div>
      )}
    </div>
  )
}

function renderInput(
  param: PipelineParameterDto,
  id: string,
  value: string,
  disabled: boolean,
  onChange: (v: string) => void,
  descId: string | undefined,
): React.ReactNode {
  const baseStyle: React.CSSProperties = {
    fontSize: 13,
    padding: '6px 8px',
    border: '1px solid var(--border)',
    borderRadius: 'var(--r-md)',
    background: 'var(--bg-elevated, transparent)',
    color: 'var(--fg)',
    fontFamily: 'var(--font-mono)',
  }
  switch (param.type) {
    case 'boolean':
      return (
        <input
          id={id}
          type="checkbox"
          checked={value === 'true'}
          disabled={disabled}
          aria-describedby={descId}
          data-testid={`trigger-param-${param.name}-input`}
          onChange={(e) => onChange(e.target.checked ? 'true' : 'false')}
          style={{ width: 16, height: 16, accentColor: 'var(--accent)' }}
        />
      )
    case 'number':
      return (
        <input
          id={id}
          type="number"
          value={value}
          disabled={disabled}
          aria-describedby={descId}
          data-testid={`trigger-param-${param.name}-input`}
          onChange={(e) => onChange(e.target.value)}
          style={baseStyle}
        />
      )
    case 'choice':
      return (
        <select
          id={id}
          value={value}
          disabled={disabled}
          aria-describedby={descId}
          data-testid={`trigger-param-${param.name}-input`}
          onChange={(e) => onChange(e.target.value)}
          style={baseStyle}
        >
          {param.choices.map((c) => (
            <option key={c} value={c}>
              {c}
            </option>
          ))}
        </select>
      )
    case 'string':
    default:
      return (
        <input
          id={id}
          type="text"
          value={value}
          disabled={disabled}
          aria-describedby={descId}
          data-testid={`trigger-param-${param.name}-input`}
          onChange={(e) => onChange(e.target.value)}
          style={baseStyle}
        />
      )
  }
}

function defaultToString(p: PipelineParameterDto): string {
  const d = p.defaultValue
  if (d === null || d === undefined) {
    return p.type === 'boolean' ? 'false' : ''
  }
  if (typeof d === 'boolean') return d ? 'true' : 'false'
  if (typeof d === 'number') return String(d)
  if (typeof d === 'string') return d
  return String(d)
}
