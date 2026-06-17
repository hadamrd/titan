/**
 * Sidebar — v2 design system port.
 *
 * Visual order: brand glyph (titan ci), org switcher, two nav sections
 * (Workspace / Account). The "Build minutes" usage footer was removed
 * for 0.1.0 (issue #448) — billing/quota is not yet a Titan feature so
 * an em-dash placeholder was misleading. Re-add once the metric exists.
 *
 * Active state: surface-2 tint + accent-coloured icon. The v2 critique
 * explicitly dropped the 2px left-edge accent bar (fix #4).
 *
 * Rail mode: triggered by `<html data-sidebar="rail">` set by TweaksPanel.
 * tokens.css collapses everything to icon width and hides labels/badges/kbds.
 *
 * Kbd hints: revealed on `:hover .nav-item` per tokens.css (fix #11).
 */
import { Link, useRouterState } from '@tanstack/react-router'
import {
  Activity,
  Boxes,
  CheckSquare,
  Layers,
  ListChecks,
  Server,
  Settings as SettingsIcon,
  ShieldAlert,
  ShieldCheck,
  Star,
  Users,
  Briefcase,
  LayoutDashboard,
  UserCircle,
} from 'lucide-react'
// Design 66: the /repositories surface was folded into the pipeline detail
// page; its sidebar entry (GitBranch icon) and the standalone "Jobs" entry
// collapsed into a single "Pipelines" entry below.
import type { ComponentType } from 'react'
import { KbdHint } from '@/components/ui/KbdHint'
import { TitanGlyph } from '@/components/TitanGlyph'
import { useStarredJobs } from '@/api/hooks'
import { hasRole, useAuthRoles, type Role } from '@/lib/auth'
import { cn } from '@/lib/utils'

interface NavItem {
  to: string
  label: string
  Icon: ComponentType<{ size?: number; className?: string }>
  kbd?: string
  badge?: string
  /** Extra path prefixes that should mark this item active (e.g. /builds/$id). */
  prefixes?: string[]
}

function buildSections(roles: Role[]): { group: string; items: NavItem[] }[] {
  const workspace: NavItem[] = [
    { to: '/', label: 'Overview', Icon: LayoutDashboard, kbd: 'g o' },
    { to: '/builds', label: 'Builds', Icon: Layers, kbd: 'g b', prefixes: ['/builds'] },
    { to: '/queue', label: 'Queue', Icon: ListChecks, kbd: 'g q' },
    { to: '/workers', label: 'Workers', Icon: Server, kbd: 'g w' },
    {
      to: '/pipelines',
      label: 'Pipelines',
      Icon: Briefcase,
      kbd: 'g p',
      prefixes: ['/pipelines', '/jobs'],
    },
    ...((hasRole(roles, 'APPROVE_BUILD') || hasRole(roles, 'ADMIN'))
      ? [
          {
            to: '/approvals',
            label: 'Approvals',
            Icon: CheckSquare,
            kbd: 'g a',
            prefixes: ['/approvals'],
          } as NavItem,
        ]
      : []),
    {
      to: '/integrations',
      label: 'Integrations',
      Icon: Boxes,
      prefixes: ['/integrations'],
    },
    { to: '/system', label: 'System', Icon: Activity, kbd: 'g s' },
  ]
  const account: NavItem[] = [
    { to: '/profile', label: 'Profile', Icon: UserCircle },
    { to: '/settings', label: 'Settings', Icon: SettingsIcon, kbd: ',' },
  ]
  // Audit + Admin Users are ADMIN-only; the server enforces 403 too, this is a
  // UX early-hide so non-admins don't see entries they can't open.
  if (hasRole(roles, 'ADMIN')) {
    account.unshift({ to: '/users', label: 'Admin · Users', Icon: Users })
    account.unshift({ to: '/audit', label: 'Audit', Icon: ShieldCheck })
  }
  // RBAC audit (#1167) is visible to READ_AUDIT as well as ADMIN — the endpoint
  // is gated @RolesAllowed({READ_AUDIT, ADMIN}), so the early-hide must match.
  if (hasRole(roles, 'ADMIN') || hasRole(roles, 'READ_AUDIT')) {
    account.unshift({ to: '/rbac-audit', label: 'RBAC Audit', Icon: ShieldAlert })
  }
  return [
    { group: 'Workspace', items: workspace },
    { group: 'Account', items: account },
  ]
}

function isActive(pathname: string, item: NavItem): boolean {
  if (pathname === item.to) return true
  if (item.prefixes) {
    return item.prefixes.some((p) => pathname === p || pathname.startsWith(p + '/'))
  }
  return false
}

export function Sidebar() {
  const pathname = useRouterState({ select: (s) => s.location.pathname })
  const roles = useAuthRoles()
  const sections = buildSections(roles)
  // Per-user starred jobs (#703). The section renders ONLY when the user has at
  // least one star — an empty 'Starred' header is noise on a fresh install.
  // The query is throttled by useStarredJobs's staleTime, so the sidebar does
  // not spam the API on route changes.
  const { data: starredJobs } = useStarredJobs()
  const hasStars = !!starredJobs && starredJobs.length > 0

  return (
    <aside className="sidebar" data-testid="sidebar">
      <div className="brand">
        <TitanGlyph />
        <span
          className="brand-name"
          style={{
            fontFamily: 'var(--font-mono)',
            fontSize: 14,
            fontWeight: 600,
            letterSpacing: '-0.01em',
          }}
        >
          Titan
        </span>
      </div>

      <div className="org-switch" title="Switch organization">
        <div className="org-avatar">
          <Boxes size={12} />
        </div>
        <div className="org-name">
          Titan
          <small>Workspace</small>
        </div>
      </div>

      {hasStars ? (
        <div className="nav-section" data-testid="sidebar-starred-section">
          <div className="nav-label">Starred</div>
          {starredJobs!.map((job) => {
            const href = `/pipelines/${job.id}`
            const active = pathname === href
            return (
              <Link
                key={job.id}
                to="/pipelines/$pipelineId"
                params={{ pipelineId: String(job.id) }}
                className={cn('nav-item', active && 'active')}
                aria-current={active ? 'page' : undefined}
                title={job.fullName}
                data-testid={`sidebar-starred-${job.id}`}
              >
                <Star size={16} className="nav-icon" aria-hidden />
                <span
                  style={{
                    overflow: 'hidden',
                    textOverflow: 'ellipsis',
                    whiteSpace: 'nowrap',
                  }}
                >
                  {job.displayName}
                </span>
              </Link>
            )
          })}
        </div>
      ) : null}

      {sections.map((sec) => (
        <div className="nav-section" key={sec.group}>
          <div className="nav-label">{sec.group}</div>
          {sec.items.map((it) => {
            const active = isActive(pathname, it)
            return (
              <Link
                key={it.to}
                to={it.to}
                className={cn('nav-item', active && 'active')}
                aria-current={active ? 'page' : undefined}
              >
                <it.Icon size={16} className="nav-icon" />
                <span>{it.label}</span>
                {it.badge && <span className="nav-badge">{it.badge}</span>}
                {it.kbd && !it.badge && <KbdHint keys={it.kbd} />}
              </Link>
            )
          })}
        </div>
      ))}

    </aside>
  )
}
