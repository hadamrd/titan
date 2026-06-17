/**
 * Role helpers over the OIDC `groups` claim.
 *
 * <p>Quarkus + Keycloak emit role membership as the {@code groups} claim on the
 * access token. The UI reads it through {@link useAuthRoles} so a sidebar entry
 * or page can be admin-gated without going through the network — the bearer
 * already carries the answer. A 403 from the server is still the authoritative
 * gate; this helper is only a UX-level early-hide.
 *
 * <p>Closed enum locked to the server-side {@code io.adaptiq.titan.auth.Roles}
 * constants (USER, ADMIN). Adding a new role on the server MUST extend this
 * union or the type-check fails on the next consumer.
 */
import { useMemo } from 'react'
import { useAuth } from '@/auth/AuthProvider'

export type Role =
  | 'USER'
  | 'ADMIN'
  | 'READ_JOB'
  | 'TRIGGER_BUILD'
  | 'EDIT_PIPELINE'
  | 'APPROVE_GATE'
  | 'APPROVE_BUILD'
  | 'READ_AUDIT'
  | 'ABORT_BUILD'
  | 'OPERATE_WORKER'
  | 'REPLAY_BUILD'
  | 'MANAGE_CREDENTIALS'

const KNOWN_ROLES: ReadonlySet<Role> = new Set<Role>([
  'USER',
  'ADMIN',
  'READ_JOB',
  'TRIGGER_BUILD',
  'EDIT_PIPELINE',
  'APPROVE_GATE',
  'APPROVE_BUILD',
  'READ_AUDIT',
  'ABORT_BUILD',
  'OPERATE_WORKER',
  'REPLAY_BUILD',
  'MANAGE_CREDENTIALS',
])

function isRole(value: unknown): value is Role {
  return typeof value === 'string' && KNOWN_ROLES.has(value as Role)
}

/**
 * Extract the {@code groups} claim off the OIDC user profile. The claim is
 * documented by Keycloak as {@code string[]}; we still defensively coerce
 * single-string and unknown shapes to an empty list so a malformed token
 * does NOT crash the UI shell.
 */
export function rolesFromProfile(profile: unknown): Role[] {
  if (profile === null || typeof profile !== 'object') return []
  const groups = (profile as Record<string, unknown>).groups
  if (Array.isArray(groups)) return groups.filter(isRole)
  if (isRole(groups)) return [groups]
  return []
}

/**
 * Returns the current bearer's role set. Empty array when unauthenticated or
 * when the token carries no {@code groups} claim. Memoised on the user object
 * so consumers can use it as a dependency safely.
 */
export function useAuthRoles(): Role[] {
  const { user } = useAuth()
  return useMemo(() => rolesFromProfile(user?.profile), [user])
}

export function hasRole(roles: Role[], role: Role): boolean {
  return roles.includes(role)
}
