import { useEffect, useRef } from 'react'
import { Button } from '@/components/ui/Button'

interface TriggerPreviewModalProps {
  jobFullName: string
  pipelineScript: string | null | undefined
  isPending: boolean
  onCancel: () => void
  onConfirm: () => void
}

/**
 * Confirmation modal shown before firing a build from the job detail page
 * (issue #641). Renders the job's pipelineScript verbatim in a mono block so
 * the user sees exactly what is about to run.
 *
 * <p>Deliberately read-only — parameter editing and diff-against-last-build
 * are explicit follow-ups (file under the same issue label).
 */
export function TriggerPreviewModal({
  jobFullName,
  pipelineScript,
  isPending,
  onCancel,
  onConfirm,
}: TriggerPreviewModalProps) {
  const dialogRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && !isPending) onCancel()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onCancel, isPending])

  const hasScript = typeof pipelineScript === 'string' && pipelineScript.length > 0

  return (
    <div
      className="gate-modal-backdrop"
      role="presentation"
      onClick={() => {
        if (!isPending) onCancel()
      }}
      data-testid="trigger-preview-backdrop"
    >
      <div
        ref={dialogRef}
        className="gate-modal"
        role="dialog"
        aria-modal="true"
        aria-labelledby="trigger-preview-title"
        onClick={(e) => e.stopPropagation()}
        style={{ maxWidth: 720, width: '100%' }}
      >
        <div className="gate-modal-head">
          <div id="trigger-preview-title" style={{ fontSize: 14, fontWeight: 600 }}>
            About to run pipeline for{' '}
            <span style={{ fontFamily: 'var(--font-mono)' }}>{jobFullName}</span>
          </div>
          <div style={{ fontSize: 12, marginTop: 2, color: 'var(--fg-dim)' }}>
            Review the pipeline YAML below, then confirm to queue a build.
          </div>
        </div>

        <div style={{ padding: '14px 18px' }}>
          {hasScript ? (
            <pre
              data-testid="trigger-preview-body"
              style={{
                margin: 0,
                padding: 12,
                fontFamily: 'var(--font-mono)',
                fontSize: 12,
                lineHeight: 1.55,
                overflow: 'auto',
                maxHeight: 'min(50vh, 420px)',
                whiteSpace: 'pre',
                color: 'var(--fg)',
                background: 'var(--bg-elevated, transparent)',
                border: '1px solid var(--border)',
                borderRadius: 'var(--r-md)',
              }}
            >
              <code>{pipelineScript}</code>
            </pre>
          ) : (
            <div
              data-testid="trigger-preview-empty"
              style={{
                fontSize: 12,
                color: 'var(--fg-dim)',
                fontStyle: 'italic',
                padding: 12,
                border: '1px solid var(--border)',
                borderRadius: 'var(--r-md)',
              }}
            >
              Pipeline not loaded — the build will run with whatever the server
              has on file for this job.
            </div>
          )}
        </div>

        <div className="gate-modal-foot">
          <button
            type="button"
            className="btn"
            onClick={onCancel}
            disabled={isPending}
            data-testid="trigger-preview-cancel"
          >
            Cancel
          </button>
          <Button
            size="sm"
            onClick={onConfirm}
            disabled={isPending}
            data-testid="trigger-preview-confirm"
          >
            {isPending ? 'Running…' : 'Confirm'}
          </Button>
        </div>
      </div>
    </div>
  )
}
