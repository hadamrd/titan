/**
 * Module-level slot for the current access token, populated by AuthProvider.
 *
 * The fetch wrapper in {@link ../api/client.ts} reads this synchronously so
 * every request can attach `Authorization: Bearer <token>` without a hook
 * indirection. Memory-only — never persisted by this module directly.
 * oidc-client-ts is the source of truth in sessionStorage.
 */

let _accessToken: string | null = null

export function setAccessToken(token: string | null): void {
  _accessToken = token
}

export function getAccessToken(): string | null {
  return _accessToken
}
