/**
 * NotificationsBell — top-bar bell that drops down the last 10 audit events.
 *
 * <p>Closes #573. The bell was previously inert; it now fetches
 * {@code GET /api/v1/audit?limit=10} via {@link useAuditEvents} and renders a
 * hand-rolled popover (same idiom as {@link TweaksPanel} — no radix dep yet).
 *
 * <p>Behaviour:
 * <ul>
 *   <li>Click → popover opens; opening marks {@code lastSeenId} = newest id in
 *       localStorage so the unread dot clears.</li>
 *   <li>Polling: refetch every 30s. If {@code latestEventId > lastSeenId} the
 *       small accent dot shows on the bell.</li>
 *   <li>403 (caller lacks READ_AUDIT / ADMIN): the bell stays inert — no dot,
 *       no popover. We don't want to surface "you're not allowed" inside the
 *       chrome of every page; the /audit route already handles that.</li>
 *   <li>Each row dispatches its icon by switching on the discriminated-union
 *       {@code AuditAction} — never on the URL or detailsJson keys.</li>
 *   <li>"View all" link → {@code /audit}.</li>
 * </ul>
 *
 * <p>No new top-level deps. The popover uses oklch tokens (matches the v3
 * design floor and the TweaksPanel surface). Reduce-motion friendly: the only
 * animation is the unread dot's accent pulse — purely a static dot if the
 * caller has {@code prefers-reduced-motion: reduce}.
 */
import { Link } from '@tanstack/react-router'
import {
  Activity,
  Ban,
  Bell,
  KeyRound,
  Pencil,
  PlayCircle,
  Plus,
  Trash2,
} from 'lucide-react'
import type { LucideIcon } from 'lucide-react'
import { useEffect, useMemo, useState } from 'react'
import { useAuditEvents } from '@/api/hooks'
import { ApiError, type AuditAction, type AuditEventDto } from '@/api/types'
import { formatDate } from '@/lib/format'

const STORAGE_KEY = 'titan.notifications.lastSeenId.v1'
const POLL_INTERVAL_MS = 30_000

function readLastSeenId(): number {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (raw === null) return 0
    const n = Number.parseInt(raw, 10)
    return Number.isFinite(n) ? n : 0
  } catch {
    return 0
  }
}

function writeLastSeenId(id: number) {
  try {
    localStorage.setItem(STORAGE_KEY, String(id))
  } catch {
    /* private-mode / quota — silently no-op */
  }
}

/**
 * Per-action lucide icon. Discriminated-union dispatch: exhaustiveness check
 * in the {@code default} branch forces a tsc failure when a new AuditAction
 * lands so the bell does not silently render a stale fallback.
 */
function iconForAction(action: AuditAction): LucideIcon {
  switch (action) {
    case 'JOB_CREATE':
      return Plus
    case 'JOB_UPDATE':
      return Pencil
    case 'BUILD_TRIGGER':
      return PlayCircle
    case 'BUILD_ABORT':
      return Ban
    case 'PAT_CREATE':
      return KeyRound
    case 'PAT_REVOKE':
      return Trash2
    case 'PAT_SCOPE_DENIED':
      return Ban
    case 'TRANSITION_CAP_WARN':
      return Activity
    case 'TRANSITION_CAP_HALT':
      return Ban
    default: {
      const _exhaustive: never = action
      void _exhaustive
      return Activity
    }
  }
}

function summarise(event: AuditEventDto): string {
  switch (event.action) {
    case 'JOB_CREATE':
      return 'created job'
    case 'JOB_UPDATE':
      return 'updated job'
    case 'BUILD_TRIGGER':
      return 'triggered build'
    case 'BUILD_ABORT':
      return 'aborted build'
    case 'PAT_CREATE':
      return 'minted token'
    case 'PAT_REVOKE':
      return 'revoked token'
    case 'PAT_SCOPE_DENIED':
      return 'denied token scope'
    case 'TRANSITION_CAP_WARN':
      return 'transition soft-cap warning'
    case 'TRANSITION_CAP_HALT':
      return 'halted by spam guard'
    default: {
      const _exhaustive: never = event.action
      return String(_exhaustive)
    }
  }
}

export function NotificationsBell() {
  const [open, setOpen] = useState(false)
  const [lastSeenId, setLastSeenId] = useState<number>(() => readLastSeenId())

  const { data, error } = useAuditEvents(
    { limit: 10 },
    { refetchInterval: POLL_INTERVAL_MS },
  )

  const forbidden = error instanceof ApiError && error.problem.status === 403
  const items = data?.items ?? []
  const latestId = items.length > 0 ? items[0].id : 0
  const hasUnread = !forbidden && latestId > lastSeenId

  // Cross-tab sync: another tab opening the bell should clear this tab's dot
  // too. Storage events fire on every tab EXCEPT the writer, so the local
  // setLastSeenId in handleOpen covers the writer side.
  useEffect(() => {
    const onStorage = (e: StorageEvent) => {
      if (e.key !== STORAGE_KEY) return
      setLastSeenId(readLastSeenId())
    }
    window.addEventListener('storage', onStorage)
    return () => window.removeEventListener('storage', onStorage)
  }, [])

  // ESC closes the popover.
  useEffect(() => {
    if (!open) return
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false)
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [open])

  const handleOpen = () => {
    if (forbidden) return
    const next = !open
    setOpen(next)
    if (next && latestId > 0) {
      writeLastSeenId(latestId)
      setLastSeenId(latestId)
    }
  }

  const rowsOrEmpty = useMemo(() => items, [items])

  return (
    <>
      <button
        type="button"
        className={`icon-btn${hasUnread ? ' has-dot' : ''}`}
        title="Notifications"
        aria-label="Notifications"
        aria-expanded={open}
        aria-haspopup="dialog"
        aria-disabled={forbidden}
        onClick={handleOpen}
        data-testid="notifications-bell"
        data-unread={hasUnread ? 'true' : 'false'}
        data-forbidden={forbidden ? 'true' : 'false'}
      >
        <Bell size={16} />
      </button>
      {open && !forbidden && (
        <>
          <div
            role="presentation"
            onClick={() => setOpen(false)}
            style={{
              position: 'fixed',
              inset: 0,
              zIndex: 49,
            }}
          />
          <div
            role="dialog"
            aria-label="Recent activity"
            data-testid="notifications-dropdown"
            style={{
              position: 'fixed',
              top: 'calc(var(--topbar-h) + 8px)',
              right: 16,
              width: 360,
              background: 'var(--surface)',
              border: '1px solid var(--border)',
              borderRadius: 'var(--r-lg)',
              boxShadow: 'var(--shadow-pop)',
              zIndex: 50,
              display: 'flex',
              flexDirection: 'column',
              maxHeight: 'min(520px, 70vh)',
              overflow: 'hidden',
            }}
          >
            <div
              style={{
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'space-between',
                padding: '10px 14px',
                borderBottom: '1px solid var(--border)',
              }}
            >
              <strong style={{ fontSize: 12.5, letterSpacing: '-0.01em' }}>
                Recent activity
              </strong>
              <span
                style={{
                  fontSize: 11,
                  color: 'var(--fg-faint)',
                  fontFamily: 'var(--font-mono)',
                }}
              >
                last {rowsOrEmpty.length}
              </span>
            </div>
            <div
              style={{
                overflowY: 'auto',
                display: 'flex',
                flexDirection: 'column',
              }}
              data-testid="notifications-rows"
            >
              {rowsOrEmpty.length === 0 ? (
                <div
                  style={{ padding: 24, textAlign: 'center', color: 'var(--fg-dim)', fontSize: 12.5 }}
                  data-testid="notifications-empty"
                >
                  No recent activity.
                </div>
              ) : (
                rowsOrEmpty.map((evt) => (
                  <NotificationRow key={evt.id} event={evt} />
                ))
              )}
            </div>
            <div
              style={{
                padding: '8px 14px',
                borderTop: '1px solid var(--border)',
                textAlign: 'right',
              }}
            >
              <Link
                to="/audit"
                search={{ action: [], actor: '', resource: '', since: '7d' }}
                onClick={() => setOpen(false)}
                data-testid="notifications-view-all"
                style={{
                  fontSize: 12,
                  color: 'var(--accent)',
                  textDecoration: 'none',
                }}
              >
                View all →
              </Link>
            </div>
          </div>
        </>
      )}
    </>
  )
}

function NotificationRow({ event }: { event: AuditEventDto }) {
  const Icon = iconForAction(event.action)
  const target =
    event.targetId !== null ? `${event.targetType}/${event.targetId}` : event.targetType
  return (
    <div
      data-testid={`notification-row-${event.id}`}
      style={{
        display: 'grid',
        gridTemplateColumns: 'auto 1fr auto',
        gap: 10,
        padding: '10px 14px',
        borderBottom: '1px solid var(--border)',
        alignItems: 'center',
      }}
    >
      <span
        aria-hidden
        data-testid={`notification-icon-${event.action}-${event.id}`}
        style={{
          width: 26,
          height: 26,
          borderRadius: 999,
          background: 'var(--surface-2)',
          display: 'inline-flex',
          alignItems: 'center',
          justifyContent: 'center',
          color: 'var(--accent)',
        }}
      >
        <Icon size={13} />
      </span>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 2, minWidth: 0 }}>
        <div
          style={{
            fontSize: 12.5,
            color: 'var(--fg)',
            whiteSpace: 'nowrap',
            overflow: 'hidden',
            textOverflow: 'ellipsis',
          }}
        >
          <strong>{event.actor}</strong>{' '}
          <span style={{ color: 'var(--fg-muted)' }}>{summarise(event)}</span>
        </div>
        <div
          style={{
            fontSize: 11,
            color: 'var(--fg-faint)',
            fontFamily: 'var(--font-mono)',
          }}
        >
          {target}
        </div>
      </div>
      <span
        className="tabnum"
        style={{
          fontSize: 11,
          color: 'var(--fg-faint)',
          fontFamily: 'var(--font-mono)',
          whiteSpace: 'nowrap',
        }}
      >
        {formatDate(event.occurredAt, 'relative')}
      </span>
    </div>
  )
}
