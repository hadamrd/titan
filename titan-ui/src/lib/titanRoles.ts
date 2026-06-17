/**
 * The Titan flat-RBAC role ladder (#1114 item 6).
 *
 * <p>Mirrored from the server enum {@code io.adaptiq.titan.auth.Authz.TitanRole}
 * and ordered most→least privileged (ADMIN > MAINTAINER > DEVELOPER > VIEWER).
 * The discriminator is a CLOSED union, not a free string — adding a server-side
 * role MUST extend this list or the type-check fails on the next consumer
 * (manifesto: no stringly-typed cross-module discriminators).
 *
 * <p>The ladder names double as Keycloak realm-role names, so a user's
 * "effective Titan role" is derivable from the flat {@code realmRoles} the
 * extended {@code GET /api/v1/admin/users} returns — no extra wire field needed.
 */
export const TITAN_ROLE_LADDER = [
  'ADMIN',
  'MAINTAINER',
  'DEVELOPER',
  'VIEWER',
] as const

export type TitanRole = (typeof TITAN_ROLE_LADDER)[number]

const LADDER_SET: ReadonlySet<string> = new Set(TITAN_ROLE_LADDER)

export function isTitanRole(value: string): value is TitanRole {
  return LADDER_SET.has(value)
}

/**
 * The user's effective Titan role = the highest-privileged ladder role present
 * in their realmRoles, or {@code null} if they hold none (rendered as "none";
 * the server treats absence as the VIEWER floor).
 */
export function effectiveTitanRole(
  realmRoles: readonly string[],
): TitanRole | null {
  for (const role of TITAN_ROLE_LADDER) {
    if (realmRoles.includes(role)) return role
  }
  return null
}
