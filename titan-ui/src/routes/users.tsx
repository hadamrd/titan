/**
 * /users — admin-only Keycloak user-list (closes #609; UI half of #603).
 *
 * <p>Reads {@code GET /api/v1/admin/users} via {@link useAdminUsers}. Backed by
 * a STUB list (alice + bob) until #608 wires the Keycloak service-account; this
 * page works against both because the wire shape is invariant.
 *
 * <p>Visual: page header + dense table (username mono / email / role chips /
 * Open-in-Keycloak). Role chips are colour-coded by {@link roleCategory} —
 * admin / titan / system / other. Categories are derived from the role-name
 * string because Keycloak emits a flat {@code realmRoles: string[]} without
 * metadata; partitioning at render time means a new role added in KC surfaces
 * with a sensible default colour rather than disappearing.
 *
 * <p>States:
 * <ul>
 *   <li>Loading — skeleton rows.</li>
 *   <li>403 — "Admin role required to view this page" + a link to the Keycloak
 *       user admin (so a non-admin operator can still get there if they have
 *       Keycloak credentials).</li>
 *   <li>Other error — generic banner with the problem detail.</li>
 *   <li>Empty list — "No users in this realm yet." (rare — KC always has at
 *       least the bootstrap admin, but the stub could theoretically be wiped).</li>
 * </ul>
 *
 * <p>The "Open in Keycloak" action targets the user list (not a specific user),
 * because our {@code UserDto} does not carry the KC user UUID — only the
 * {@code username}. Deep-linking to {@code /users/<id>} would need that id.
 */
import { createFileRoute } from '@tanstack/react-router'
import { ExternalLink } from 'lucide-react'
import { useAdminUsers } from '@/api/hooks'
import { ApiError, type UserDto } from '@/api/types'
import { effectiveTitanRole } from '@/lib/titanRoles'
import { PageContainer, PageHeader } from '@/components/ui/Page'
import { Skeleton } from '@/components/ui/Skeleton'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/Table'
import { useDocumentTitle } from '@/lib/useDocumentTitle'
import { getRuntimeConfig } from '@/runtimeConfig'

export const Route = createFileRoute('/users')({
  component: AdminUsersPage,
})

/**
 * Deep-link into Keycloak's user admin UI for the configured realm. Derived
 * from the runtime config's `oidc.authority` (shape: `${kcBase}/realms/${realm}`)
 * so one image works against any rig. Closes #898 (was VITE_KC_BASE / VITE_KC_REALM).
 */
function kcUsersUrl(): string {
  try {
    const { authority } = getRuntimeConfig().oidc
    // authority looks like https://host/realms/<realm> — parse the trailing
    // /realms/<name> off the URL and treat the rest as the Keycloak base.
    const m = authority.match(/^(.*)\/realms\/([^/?#]+)/)
    if (m) {
      const [, base, realm] = m
      return `${base}/admin/master/console/#/${realm}/users`
    }
  } catch {
    /* fall through to the local-dev default */
  }
  return 'http://localhost:8081/admin/master/console/#/titan-dev/users'
}

type RoleCategory = 'admin' | 'titan' | 'system' | 'other'

/**
 * Bucket a Keycloak role name into a colour category. The mapping is
 * defensive — anything we don't recognise falls into "other" so a new role
 * still renders a chip (just in the neutral colour).
 */
function roleCategory(role: string): RoleCategory {
  const r = role.toLowerCase()
  if (r === 'admin' || r.includes('admin')) return 'admin'
  if (r === 'user' || r === 'titan' || r.startsWith('titan')) return 'titan'
  if (
    r.startsWith('default-') ||
    r === 'offline_access' ||
    r === 'uma_authorization'
  ) {
    return 'system'
  }
  return 'other'
}

function categoryStyle(cat: RoleCategory): { bg: string; fg: string } {
  switch (cat) {
    case 'admin':
      return { bg: 'var(--fail-bg, rgba(220, 38, 38, 0.12))', fg: 'var(--fail, oklch(0.62 0.21 25))' }
    case 'titan':
      return { bg: 'var(--accent-bg, rgba(99, 102, 241, 0.12))', fg: 'var(--accent, oklch(0.65 0.18 265))' }
    case 'system':
      return { bg: 'var(--surface-2)', fg: 'var(--fg-dim)' }
    case 'other':
      return { bg: 'var(--surface-2)', fg: 'var(--fg-muted)' }
  }
}

function AdminUsersPage() {
  useDocumentTitle('Admin · Users')
  const { data, isLoading, error } = useAdminUsers({ offset: 0, limit: 100 })

  return (
    <PageContainer width="wide">
      <PageHeader
        title="Admin · Users"
        description="Realm users surfaced from Keycloak. ADMIN-only."
        actions={
          <a
            href={kcUsersUrl()}
            target="_blank"
            rel="noreferrer noopener"
            className="btn btn-sm"
            data-testid="admin-users-kc-link"
            style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}
          >
            <ExternalLink size={12} />
            Open in Keycloak
          </a>
        }
      />

      <div className="card">
        {error ? (
          <AdminUsersError error={error} />
        ) : isLoading ? (
          <AdminUsersSkeleton />
        ) : !data || data.length === 0 ? (
          <div className="empty" style={{ padding: 30 }} data-testid="admin-users-empty">
            <p style={{ fontSize: 13 }}>No users in this realm yet.</p>
            <p style={{ fontSize: 12, color: 'var(--fg-dim)', marginTop: 4 }}>
              New sign-ins (or admin-created users) will appear here.
            </p>
          </div>
        ) : (
          <AdminUsersTable users={data} />
        )}
      </div>
    </PageContainer>
  )
}

function AdminUsersError({ error }: { error: unknown }) {
  const status = error instanceof ApiError ? error.problem.status : 0
  if (status === 403) {
    return (
      <div className="empty" style={{ padding: 30 }} data-testid="admin-users-error-403">
        <p style={{ fontSize: 13, color: 'var(--fail)' }}>
          Admin role required to view this page.
        </p>
        <p style={{ fontSize: 12, color: 'var(--fg-dim)', marginTop: 4 }}>
          Ask a workspace administrator to grant the ADMIN role, then sign in
          again. You can also manage users directly in{' '}
          <a
            href={kcUsersUrl()}
            target="_blank"
            rel="noreferrer noopener"
            data-testid="admin-users-error-kc-link"
            style={{ color: 'var(--accent)', textDecoration: 'underline' }}
          >
            Keycloak
          </a>
          .
        </p>
      </div>
    )
  }
  return (
    <div className="empty" style={{ padding: 30 }} data-testid="admin-users-error-generic">
      <p style={{ fontSize: 13, color: 'var(--fail)' }}>Failed to load users</p>
      <p style={{ fontSize: 12, color: 'var(--fg-dim)', marginTop: 4 }}>
        {error instanceof ApiError
          ? error.problem.detail ?? error.problem.title
          : error instanceof Error
            ? error.message
            : String(error)}
      </p>
    </div>
  )
}

function AdminUsersSkeleton() {
  return (
    <div style={{ padding: 12 }}>
      {Array.from({ length: 4 }).map((_, i) => (
        <div
          key={i}
          style={{ display: 'flex', gap: 12, alignItems: 'center', padding: '10px 4px' }}
        >
          <Skeleton style={{ height: 12, width: 120 }} />
          <Skeleton style={{ height: 12, width: 180 }} />
          <Skeleton style={{ height: 12, flex: 1 }} />
          <Skeleton style={{ height: 12, width: 100 }} />
        </div>
      ))}
    </div>
  )
}

function AdminUsersTable({ users }: { users: UserDto[] }) {
  return (
    <Table>
      <TableHeader>
        <TableRow>
          <TableHead style={{ width: 180 }}>Username</TableHead>
          <TableHead style={{ width: 220 }}>Email</TableHead>
          <TableHead style={{ width: 200 }}>Titan role</TableHead>
          <TableHead>Roles</TableHead>
          <TableHead style={{ width: 160 }}>Actions</TableHead>
        </TableRow>
      </TableHeader>
      <TableBody data-testid="admin-users-table-body">
        {users.map((u) => (
          <UserRow key={u.username} user={u} />
        ))}
      </TableBody>
    </Table>
  )
}

/**
 * The user's effective Titan role rendered as a read-only badge — the highest
 * ladder role present in their {@code realmRoles}, or "none". The inline
 * grant/revoke control (AC #2) is deferred to a follow-up: it needs the
 * role-grant write API ({@code PUT /api/v1/admin/users/{userId}/roles}) plus a
 * {@code userId} on the GET projection, neither of which exists yet.
 */
function TitanRoleCell({ user }: { user: UserDto }) {
  const effective = effectiveTitanRole(user.realmRoles)

  return (
    <span
      data-testid={`admin-users-titanrole-${user.username}`}
      data-titan-role={effective ?? 'none'}
      style={{
        alignSelf: 'flex-start',
        fontFamily: 'var(--font-mono)',
        fontSize: 11,
        padding: '2px 6px',
        borderRadius: 4,
        background: effective
          ? 'var(--accent-bg, rgba(99, 102, 241, 0.12))'
          : 'var(--surface-2)',
        color: effective ? 'var(--accent, oklch(0.65 0.18 265))' : 'var(--fg-dim)',
        lineHeight: 1.4,
      }}
    >
      {effective ?? 'none'}
    </span>
  )
}

function UserRow({ user }: { user: UserDto }) {
  return (
    <TableRow data-testid={`admin-users-row-${user.username}`}>
      <TableCell
        className="tabnum"
        style={{ fontFamily: 'var(--font-mono)', fontSize: 12.5 }}
      >
        {user.username}
      </TableCell>
      <TableCell style={{ fontSize: 12.5, color: 'var(--fg-muted)' }}>
        {user.email ?? '—'}
      </TableCell>
      <TableCell>
        <TitanRoleCell user={user} />
      </TableCell>
      <TableCell>
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 4 }}>
          {user.realmRoles.length === 0 ? (
            <span style={{ fontSize: 12, color: 'var(--fg-dim)' }}>—</span>
          ) : (
            user.realmRoles.map((role) => {
              const cat = roleCategory(role)
              const { bg, fg } = categoryStyle(cat)
              return (
                <span
                  key={role}
                  data-testid={`admin-users-role-${user.username}-${role}`}
                  data-role-category={cat}
                  title={`${role} · ${cat}`}
                  style={{
                    fontFamily: 'var(--font-mono)',
                    fontSize: 11,
                    padding: '2px 6px',
                    borderRadius: 4,
                    background: bg,
                    color: fg,
                    lineHeight: 1.4,
                  }}
                >
                  {role}
                </span>
              )
            })
          )}
        </div>
      </TableCell>
      <TableCell>
        <a
          href={kcUsersUrl()}
          target="_blank"
          rel="noreferrer noopener"
          className="btn btn-sm btn-ghost"
          data-testid={`admin-users-open-kc-${user.username}`}
          style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}
        >
          <ExternalLink size={12} />
          Open in Keycloak
        </a>
      </TableCell>
    </TableRow>
  )
}
