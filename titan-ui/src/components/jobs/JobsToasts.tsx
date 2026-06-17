/**
 * Bottom-right transient toasts for the pipelines list (extracted from
 * `routes/pipelines/index.tsx` for issue #1070).
 *
 * Two independent banners that the route mounts conditionally:
 *   - {@link StarErrorToast}   — favorite (star) mutation failed, e.g. the
 *     server-side 10-star cap returned 409.
 *   - {@link NewJobErrorToast} — the "New job" dialog's create call failed at
 *     the network layer; offers a Retry that re-opens the dialog.
 *
 * We deliberately avoid a global toast primitive — no such component exists in
 * the bundle today and adding one for two call sites would inflate wire weight.
 * Pure presentation: state + dismissal handlers are owned by the route.
 */

const toastShellStyle = {
  position: 'fixed',
  right: 20,
  bottom: 20,
  padding: '10px 14px',
  display: 'flex',
  alignItems: 'center',
  gap: 12,
  zIndex: 60,
  borderColor: 'var(--fail)',
} as const

export function StarErrorToast({
  message,
  onDismiss,
}: {
  message: string
  onDismiss: () => void
}) {
  return (
    <div role="status" data-testid="star-job-toast" className="card" style={toastShellStyle}>
      <span style={{ fontSize: 12, color: 'var(--fail)' }}>{message}</span>
      <button type="button" className="btn btn-sm" onClick={onDismiss}>
        Dismiss
      </button>
    </div>
  )
}

export function NewJobErrorToast({
  message,
  onRetry,
}: {
  message: string
  onRetry: () => void
}) {
  return (
    <div role="status" data-testid="new-job-toast" className="card" style={toastShellStyle}>
      <span style={{ fontSize: 12, color: 'var(--fail)' }}>
        Could not create job: {message}
      </span>
      <button type="button" className="btn btn-sm" onClick={onRetry}>
        Retry
      </button>
    </div>
  )
}
