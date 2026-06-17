/**
 * EmptyState — the v3 calm placeholder primitive (closes #764).
 *
 * <p>Replaces the scattered "No X" one-liners that left first-run users
 * staring at a sad app. Every empty surface explains WHY the list is empty
 * AND offers the most likely next action (Create a job / Trigger a build /
 * etc). The component is intentionally austere — icon + 2 lines of copy +
 * at most one CTA — so it never crosses the line into marketing fluff.
 *
 * <p>Action discrimination: callers pass either {@code action.to} for an
 * internal TanStack Router link, or {@code action.onClick} for an in-page
 * handler. The compiler enforces exhaustivity at the call site.
 *
 * <p>A11y: the root carries {@code role="status"} so screen readers announce
 * the change as a non-urgent state update, and the title is exposed as an
 * accessible name via {@code aria-label} (the icon stays {@code aria-hidden}).
 */
import { Link } from '@tanstack/react-router'
import type { ReactNode } from 'react'

type Action =
  | { label: string; to: string }
  | { label: string; onClick: () => void }

export interface EmptyStateProps {
  /** Optional decorative lucide icon. Always rendered aria-hidden. */
  icon?: ReactNode
  /** Short headline — e.g. "No jobs yet". */
  title: string
  /** One-line explanation of why the list is empty and what fills it. */
  message: string
  /** Optional single CTA. Internal route via {@code to}, or in-page {@code onClick}. */
  action?: Action
  'data-testid'?: string
}

function isLinkAction(a: Action): a is { label: string; to: string } {
  return 'to' in a
}

export function EmptyState({
  icon,
  title,
  message,
  action,
  'data-testid': testId,
}: EmptyStateProps) {
  return (
    <div
      role="status"
      aria-label={title}
      data-testid={testId}
      className="card empty"
      style={{
        padding: 32,
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        justifyContent: 'center',
        textAlign: 'center',
        gap: 8,
      }}
    >
      {icon ? (
        <span
          aria-hidden
          style={{
            display: 'inline-flex',
            alignItems: 'center',
            justifyContent: 'center',
            color: 'var(--fg-dim)',
            marginBottom: 4,
          }}
        >
          {icon}
        </span>
      ) : null}
      <p style={{ fontSize: 14, fontWeight: 500, color: 'var(--fg)', margin: 0 }}>
        {title}
      </p>
      <p style={{ fontSize: 12, color: 'var(--fg-dim)', margin: 0, maxWidth: 420 }}>
        {message}
      </p>
      {action ? (
        <div style={{ marginTop: 8 }}>
          {isLinkAction(action) ? (
            <Link
              to={action.to}
              className="btn btn-sm btn-primary"
              data-testid={testId ? `${testId}-action` : undefined}
            >
              {action.label}
            </Link>
          ) : (
            <button
              type="button"
              className="btn btn-sm btn-primary"
              onClick={action.onClick}
              data-testid={testId ? `${testId}-action` : undefined}
            >
              {action.label}
            </button>
          )}
        </div>
      ) : null}
    </div>
  )
}
