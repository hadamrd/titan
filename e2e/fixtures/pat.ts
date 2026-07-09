/**
 * pat — helper for seeding personal access tokens through the real
 * /api/v1/me/tokens endpoint, used by profile-tokens-*.spec.ts (#1039).
 *
 * Uses a Keycloak bearer (Direct-Grants on the titan-e2e client) so the
 * setup phase doesn't pay the cost of a browser flow just to plant rows.
 */
import { fetchBearerToken, authEnv, type AuthEnv } from './auth-v3'

export interface SeededToken {
  id: string
  name: string
  prefix: string
  scopes: string[] | null
}

export interface CreateResponse {
  id: string
  name: string
  prefix: string
  token: string
  scopes?: string[] | null
  jobPattern?: string | null
}

export interface ListEntry {
  id: string
  name: string
  prefix: string
  scopes?: string[] | null
  jobPattern?: string | null
  revokedAt?: string | null
}

function baseUrl(env: AuthEnv = authEnv()): string {
  return env.uiBaseUrl.replace(/\/$/, '')
}

export async function createToken(
  name: string,
  scopes: string[] | undefined,
  bearer: string,
  env: AuthEnv = authEnv(),
  extra?: { jobPattern?: string },
): Promise<CreateResponse> {
  const body: Record<string, unknown> = { name }
  if (scopes !== undefined) body.scopes = scopes
  if (extra?.jobPattern !== undefined) body.jobPattern = extra.jobPattern
  const res = await fetch(`${baseUrl(env)}/api/v1/me/tokens`, {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${bearer}`,
      'Content-Type': 'application/json',
      Accept: 'application/json',
    },
    body: JSON.stringify(body),
  })
  if (!res.ok) {
    const text = await res.text()
    throw new Error(`createToken ${name} failed: ${res.status} ${text}`)
  }
  return (await res.json()) as CreateResponse
}

export async function listTokens(
  bearer: string,
  env: AuthEnv = authEnv(),
): Promise<ListEntry[]> {
  const res = await fetch(`${baseUrl(env)}/api/v1/me/tokens`, {
    headers: {
      Authorization: `Bearer ${bearer}`,
      Accept: 'application/json',
    },
  })
  if (!res.ok) throw new Error(`listTokens failed: ${res.status}`)
  return (await res.json()) as ListEntry[]
}

export async function deleteToken(
  id: string,
  bearer: string,
  env: AuthEnv = authEnv(),
): Promise<void> {
  const res = await fetch(`${baseUrl(env)}/api/v1/me/tokens/${encodeURIComponent(id)}`, {
    method: 'DELETE',
    headers: { Authorization: `Bearer ${bearer}` },
  })
  // 204 expected; 404 acceptable (already gone after a prior teardown)
  if (!res.ok && res.status !== 404) {
    throw new Error(`deleteToken ${id} failed: ${res.status}`)
  }
}

/**
 * Seed two tokens covering the two render-time scope cases #1036 hardened:
 *   - one created without scopes (server omits the field → undefined in DTO)
 *   - one with explicit scopes
 *
 * Returns both seeded rows plus a teardown function — call it in afterAll
 * to soft-revoke + hard-clean.
 */
export async function seedMixedScopeTokens(opts?: { prefixLabel?: string }): Promise<{
  bearer: string
  noScopes: SeededToken
  withScopes: SeededToken
  cleanup: () => Promise<void>
}> {
  const env = authEnv()
  const bearer = await fetchBearerToken(env)
  const label = opts?.prefixLabel ?? 'e2e-1039'
  const stamp = Date.now()

  const noScopesResp = await createToken(`${label}-noscopes-${stamp}`, undefined, bearer, env)
  const withScopesResp = await createToken(
    `${label}-scoped-${stamp}`,
    ['builds:read', 'pipelines:read'],
    bearer,
    env,
  )

  const noScopes: SeededToken = {
    id: noScopesResp.id,
    name: noScopesResp.name,
    prefix: noScopesResp.prefix,
    scopes: noScopesResp.scopes ?? null,
  }
  const withScopes: SeededToken = {
    id: withScopesResp.id,
    name: withScopesResp.name,
    prefix: withScopesResp.prefix,
    scopes: withScopesResp.scopes ?? null,
  }

  async function cleanup(): Promise<void> {
    // Best-effort teardown — don't fail the spec on a 404 from a row a
    // mid-test revoke already removed.
    await Promise.allSettled([
      deleteToken(noScopes.id, bearer, env),
      deleteToken(withScopes.id, bearer, env),
    ])
  }

  return { bearer, noScopes, withScopes, cleanup }
}
