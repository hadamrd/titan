import { useEffect, useRef } from 'react'
import { createPortal } from 'react-dom'
import { Button } from '@/components/ui/Button'

export interface ConfirmDialogProps {
  open: boolean
  title: string
  message: string
  confirmLabel?: string
  cancelLabel?: string
  destructive?: boolean
  busy?: boolean
  onConfirm: () => void
  onCancel: () => void
  testId?: string
}

export function ConfirmDialog({
  open,
  title,
  message,
  confirmLabel = 'Confirm',
  cancelLabel = 'Cancel',
  destructive = false,
  busy = false,
  onConfirm,
  onCancel,
  testId,
}: ConfirmDialogProps) {
  const confirmRef = useRef<HTMLButtonElement>(null)

  useEffect(() => {
    if (!open) return
    const t = window.setTimeout(() => confirmRef.current?.focus(), 0)
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && !busy) onCancel()
    }
    window.addEventListener('keydown', onKey)
    return () => {
      window.clearTimeout(t)
      window.removeEventListener('keydown', onKey)
    }
  }, [open, busy, onCancel])

  if (!open) return null

  // Portal to <body> (#113): rendered inline, the position:fixed backdrop is
  // captured by any transformed/animated ancestor (e.g. `.tab-pane`'s fade-in
  // keyframe), which offsets it from the viewport and makes gesture-time hit
  // testing land on the backdrop instead of the buttons. Same house pattern
  // as TriggerParamsModal.
  return createPortal(
    <div
      className="gate-modal-backdrop"
      role="presentation"
      onClick={() => !busy && onCancel()}
      data-testid={testId ? `${testId}-backdrop` : 'confirm-backdrop'}
    >
      <div
        className="gate-modal"
        role="alertdialog"
        aria-modal="true"
        aria-labelledby={testId ? `${testId}-title` : 'confirm-title'}
        aria-describedby={testId ? `${testId}-message` : 'confirm-message'}
        onClick={(e) => e.stopPropagation()}
        style={{ maxWidth: 440, width: '100%' }}
        data-testid={testId}
      >
        <div className="gate-modal-head">
          <div
            id={testId ? `${testId}-title` : 'confirm-title'}
            style={{ fontSize: 14, fontWeight: 600 }}
          >
            {title}
          </div>
          <div
            id={testId ? `${testId}-message` : 'confirm-message'}
            style={{ fontSize: 12, marginTop: 6, color: 'var(--fg-dim)', lineHeight: 1.5 }}
          >
            {message}
          </div>
        </div>
        <div
          style={{
            display: 'flex',
            justifyContent: 'flex-end',
            gap: 8,
            padding: '12px 18px 16px',
          }}
        >
          <Button
            type="button"
            variant="ghost"
            onClick={onCancel}
            disabled={busy}
            data-testid={testId ? `${testId}-cancel` : 'confirm-cancel'}
          >
            {cancelLabel}
          </Button>
          <Button
            ref={confirmRef}
            type="button"
            variant={destructive ? 'destructive' : 'default'}
            onClick={onConfirm}
            disabled={busy}
            data-testid={testId ? `${testId}-confirm` : 'confirm-ok'}
          >
            {busy ? 'Working…' : confirmLabel}
          </Button>
        </div>
      </div>
    </div>,
    document.body,
  )
}
