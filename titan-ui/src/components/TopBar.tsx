/**
 * Top bar — v3.
 *
 * Layout: breadcrumbs · live chip (variants per v3: idle / normal / saturated)
 * · search · run · notifications · avatar.
 *
 * Live chip behaviour (v3 §5.2):
 *   - Idle (running=0 AND queued=0):   single ok dot, "Idle", no pulse / bar.
 *   - Saturated (queued > running×3):  warn tint + warn-coloured longest.
 *   - Normal:                          pulse + scrolling bar.
 *
 * Counts default to 0/0 until a real /api/v1/queue endpoint ships (tracked
 * follow-up). The variant CSS is in components.css.
 */
import { Link, useMatches, useNavigate } from '@tanstack/react-router'
import { CheckSquare, LogOut, Search } from 'lucide-react'
import { useApprovals } from '@/api/hooks'
import { useAuth } from '@/auth/AuthProvider'
import { hasRole, useAuthRoles } from '@/lib/auth'
import { NotificationsBell } from '@/components/NotificationsBell'
import { ThemeToggle } from '@/components/ThemeToggle'
import { TweaksPanel } from '@/components/TweaksPanel'

interface Crumb {
  label: string
  to?: string
}

const ROUTE_LABELS: Record<string, string> = {
  '/': 'Overview',
  '/builds': 'Builds',
  '/builds/$buildId': 'Build',
  '/queue': 'Queue',
  '/workers': 'Workers',
  '/jobs': 'Pipelines',
  '/jobs/$jobId': 'Pipeline',
  '/pipelines': 'Pipelines',
  '/pipelines/$pipelineId': 'Pipeline',
  '/pipelines/validate': 'Validate',
  '/profile': 'Profile',
  '/settings': 'Settings',
  '/system': 'System',
  '/system/': 'System',
}

function deriveCrumbs(routeIds: string[], params: Record<string, string>): Crumb[] {
  const out: Crumb[] = []
  for (const id of routeIds) {
    if (id === '__root__' || id === '/') continue
    const label = ROUTE_LABELS[id]
    if (!label) continue
    let href = id
    let unresolved = false
    href = href.replace(/\$(\w+)/g, (_, k) => {
      const v = params[k]
      if (v === undefined) {
        unresolved = true
        return ''
      }
      return v
    })
    const isLeaf = id === routeIds[routeIds.length - 1]
    let displayLabel = label
    if (isLeaf) {
      const lastParamKey = Object.keys(params).pop()
      if (lastParamKey) displayLabel = `${label} ${params[lastParamKey]}`
    }
    out.push({ label: displayLabel, to: unresolved ? undefined : href })
  }
  return out
}

interface LiveChipProps {
  running: number
  queued: number
  longest: string | null
}

function LiveChip({ running, queued, longest }: LiveChipProps) {
  const idle = running === 0 && queued === 0
  // v3 saturation heuristic — documented inline. queued > running × 3, OR
  // queued > 12 outright. Either flips the chip into warn state.
  const saturated = !idle && (queued > Math.max(1, running) * 3 || queued > 12)
  const cls = `live-chip${idle ? ' idle' : ''}${saturated ? ' saturated' : ''}`
  return (
    <div className={cls} title="Live activity">
      <span className="live-dot" aria-hidden />
      {idle ? (
        <span style={{ color: 'var(--fg-muted)' }}>Idle</span>
      ) : (
        <>
          <span>
            <strong>{running}</strong> running
          </span>
          <span style={{ color: 'var(--fg-faint)' }}>·</span>
          <span>
            <strong>{queued}</strong> queued
          </span>
          {longest && (
            <>
              <span style={{ color: 'var(--fg-faint)' }}>·</span>
              <span className="live-longest">
                longest <strong>{longest}</strong>
              </span>
            </>
          )}
          <span className="live-chip-bar" aria-hidden />
        </>
      )}
    </div>
  )
}

export function TopBar() {
  const matches = useMatches()
  const last = matches[matches.length - 1]
  const params = (last?.params ?? {}) as Record<string, string>
  const routeIds = matches.map((m) => m.routeId)
  const crumbs = deriveCrumbs(routeIds, params)
  const lastIdx = crumbs.length - 1

  return (
    <div className="topbar">
      <div className="crumbs">
        <span
          style={{
            color: 'var(--fg-faint)',
            fontFamily: 'var(--font-mono)',
            fontSize: 11,
            letterSpacing: '0.06em',
          }}
        >
          TITAN
        </span>
        <span className="crumb-sep">/</span>
        {crumbs.length === 0 ? (
          <span className="crumb-current">Overview</span>
        ) : (
          crumbs.map((c, i) => {
            const isLast = i === lastIdx
            const node =
              isLast || !c.to ? (
                <span key={i} className={isLast ? 'crumb-current' : ''}>
                  {c.label}
                </span>
              ) : (
                <Link key={i} to={c.to} style={{ color: 'inherit' }}>
                  {c.label}
                </Link>
              )
            return (
              <span
                key={`g${i}`}
                style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}
              >
                {node}
                {!isLast && <span className="crumb-sep">/</span>}
              </span>
            )
          })
        )}
      </div>

      <LiveChip running={0} queued={0} longest={null} />

      <button
        type="button"
        className="search"
        role="search"
        aria-label="Open command palette"
        onClick={() => {
          // Dispatching the same hotkey the global listener handles keeps the
          // palette as the single source of open/close state — no prop drilling.
          window.dispatchEvent(
            new KeyboardEvent('keydown', { key: 'k', metaKey: true }),
          )
        }}
        style={{ cursor: 'pointer', background: 'transparent', border: 0 }}
      >
        <Search size={14} style={{ color: 'var(--fg-faint)' }} />
        <span>Search…</span>
        <kbd>⌘K</kbd>
      </button>

      <ApprovalsBadge />
      <NotificationsBell />

      <ThemeToggle />

      <TweaksPanel />

      <UserAvatar />
    </div>
  )
}

/**
 * Topbar pending-approvals chip (#721). Visible only to users with the
 * APPROVE_BUILD or ADMIN role; renders nothing when the count is 0 so the
 * topbar isn't visually noisy when there's no work to do.
 */
function ApprovalsBadge() {
  const roles = useAuthRoles()
  const navigate = useNavigate()
  const mayApprove = hasRole(roles, 'APPROVE_BUILD') || hasRole(roles, 'ADMIN')
  // Hooks must run unconditionally (CONSTITUTION §6 — no hooks after return).
  // We pass `enabled: mayApprove` implicitly via the early-return below; the
  // query still mounts but never throws because the server returns 403 only
  // on decide, not on list (READ_JOB suffices). We just hide the chip.
  const { data } = useApprovals({ status: 'PENDING', limit: 1 })
  if (!mayApprove) return null
  const count = data?.total ?? 0
  if (count <= 0) return null
  return (
    <button
      type="button"
      className="icon-btn"
      data-testid="approvals-badge"
      data-count={count}
      title={`${count} pending approval${count === 1 ? '' : 's'}`}
      aria-label={`${count} pending approvals — open inbox`}
      onClick={() => {
        void navigate({ to: '/approvals' })
      }}
      style={{ position: 'relative' }}
    >
      <CheckSquare size={16} />
      <span
        aria-hidden
        style={{
          position: 'absolute',
          top: 2,
          right: 2,
          minWidth: 14,
          height: 14,
          padding: '0 4px',
          borderRadius: 999,
          background: 'oklch(0.65 0.18 50)',
          color: 'white',
          fontSize: 9,
          fontWeight: 600,
          display: 'inline-flex',
          alignItems: 'center',
          justifyContent: 'center',
          fontFamily: 'var(--font-mono)',
          lineHeight: 1,
        }}
      >
        {count > 99 ? '99+' : count}
      </span>
    </button>
  )
}

function UserAvatar() {
  const { user, signoutRedirect, isAuthenticated } = useAuth()
  if (!isAuthenticated) return null
  const name =
    (user?.profile?.preferred_username as string | undefined) ??
    (user?.profile?.name as string | undefined) ??
    user?.profile?.email ??
    'user'
  const initials = name
    .split(/[.\s_-]+/)
    .slice(0, 2)
    .map((s) => s.charAt(0).toUpperCase())
    .join('')

  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
      <Link to="/profile" className="avatar" title={`${name} — open profile`} aria-label="Open profile">
        {initials || 'U'}
      </Link>
      <button
        type="button"
        className="icon-btn"
        title="Sign out"
        aria-label="Sign out"
        onClick={() => {
          void signoutRedirect()
        }}
      >
        <LogOut size={14} />
      </button>
    </div>
  )
}
