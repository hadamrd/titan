/**
 * auth-diagnosis — the realm-missing DIAGNOSIS logic for the auth golden path
 * (#1200), extracted from `specs/v3/56-golden-path-auth.spec.ts` so it can be
 * unit-tested in isolation.
 *
 * Why this is its own module:
 *   The whole VALUE of the auth canary is that a real outage produces the RIGHT
 *   diagnosis — `realm <r> missing or Keycloak unreachable`, mirroring
 *   `rig/k3s/smoke-auth.sh`. If `keycloakRealmReachable()` silently always
 *   returned true (e.g. a swallowed fetch error), the WRONG diagnosis would fire
 *   on every real outage — exactly the failure mode this PR exists to prevent.
 *   Living inside a Playwright spec, that logic could only ever run against a
 *   live rig, so its branches were untested. Pulled out here, both the probe and
 *   the re-throw branches are covered by `auth-diagnosis.unit.spec.ts` with
 *   injected fakes — no rig, no browser, fully deterministic.
 *
 * Dependencies are injected (not imported-and-monkeypatched) so the tests stub
 * them cleanly:
 *   - `keycloakRealmReachable(env, fetchImpl)` takes the fetch implementation.
 *   - `loginWithDiagnosis(page, env, deps)` takes the login driver + the realm
 *     probe.
 * Production callers use the defaults, which wire the real `fetch` and the real
 * `loginViaKeycloak` from `auth-v3.ts`.
 */
import type { Page } from '@playwright/test'
import { loginViaKeycloak, type AuthEnv } from './auth-v3'

/** Minimal shape of the global `fetch` we depend on — only `ok` is read. */
export type FetchLike = (url: string) => Promise<{ ok: boolean }>

/** The browser login driver (defaults to the real `loginViaKeycloak`). */
export type LoginFn = (page: Page, env: AuthEnv) => Promise<void>

export interface LoginDiagnosisDeps {
  /** Drives the real browser OIDC flow. Defaults to `loginViaKeycloak`. */
  login?: LoginFn
  /** Probes whether the realm's discovery doc answers. Defaults to the real probe. */
  realmReachable?: (env: AuthEnv) => Promise<boolean>
}

/**
 * Probe whether the Keycloak realm is actually serving — the AUTHORITATIVE
 * signal for "realm missing or Keycloak unreachable". Hits the realm's OIDC
 * discovery doc directly:
 *   - 200            → realm exists AND Keycloak is up  → NOT a realm/reachability fault
 *   - 404 (or other  → realm missing (Keycloak up but no such realm)
 *     non-2xx)
 *   - fetch throws   → Keycloak unreachable at the TCP/DNS level
 * Returns true only for the 200 case. This is what lets loginWithDiagnosis stop
 * guessing from Playwright's internal error text (where a PRE-form navigation
 * timeout and a POST-form PKCE-exchange timeout BOTH read "waitForURL …").
 *
 * `fetchImpl` is injected so the unit test can feed a 404 / a 200 / a throwing
 * fetch and assert each maps to the right boolean — without a live Keycloak.
 */
export async function keycloakRealmReachable(
  env: AuthEnv,
  fetchImpl: FetchLike = fetch,
): Promise<boolean> {
  const url = `${env.keycloakUrl}/realms/${env.realm}/.well-known/openid-configuration`
  try {
    const res = await fetchImpl(url)
    return res.ok
  } catch {
    // TCP/DNS-level failure — Keycloak is unreachable.
    return false
  }
}

/**
 * Run the REAL browser OIDC login, annotating failures with the
 * smoke-auth.sh-aligned diagnosis. The diagnosis must NOT be inferred from
 * Playwright's internal error string: loginViaKeycloak() has TWO `waitForURL`
 * waiters (one PRE-form, redirecting to Keycloak; one POST-form, redirecting
 * back to the SPA after the PKCE exchange) plus a `#kc-form-login`
 * `waitForSelector`. A missing realm / TCP-unreachable Keycloak can blow up at
 * EITHER the pre-form `waitForURL` (the SPA never reaches a live Keycloak) OR
 * the `#kc-form-login` wait — and a string match on `kc-form-login` misses the
 * former (its message says "waitForURL", not "kc-form-login"), so the old code
 * would mis-blame it as a post-form failure and send the SRE down the wrong
 * path. Conversely, a genuine post-form fault (bounced credential, PKCE
 * token-exchange error, SPA crash) ALSO surfaces as a `waitForURL` timeout — so
 * naively matching `waitForURL` would over-blame the realm.
 *
 * We therefore PROBE Keycloak directly on failure: if the realm's discovery doc
 * isn't reachable, it IS a realm-missing/unreachable outage (mirroring
 * smoke-auth.sh); otherwise the realm is healthy and the failure is genuinely
 * downstream, so we say so and let the underlying (authoritative) cause stand.
 */
export async function loginWithDiagnosis(
  page: Page,
  env: AuthEnv,
  deps: LoginDiagnosisDeps = {},
): Promise<void> {
  const login = deps.login ?? loginViaKeycloak
  const realmReachable = deps.realmReachable ?? keycloakRealmReachable
  try {
    await login(page, env)
  } catch (e) {
    const underlying = String(e)
    const realmHealthy = await realmReachable(env)
    if (!realmHealthy) {
      throw new Error(
        `login failed for ${env.username}@${env.realm}: realm ${env.realm} missing or ` +
          `Keycloak unreachable (the realm's OIDC discovery doc at ` +
          `${env.keycloakUrl}/realms/${env.realm}/.well-known/openid-configuration did not ` +
          `answer 200 — same diagnosis smoke-auth.sh emits at deploy time). ` +
          `Underlying: ${underlying}`,
      )
    }
    throw new Error(
      `login failed for ${env.username}@${env.realm} but realm ${env.realm} IS reachable ` +
        `NOW (its OIDC discovery doc answered 200 at probe time) — so this is NOT a missing ` +
        `realm. Most likely a PKCE token-exchange error, a bounced credential, or an SPA ` +
        `crash on the OIDC callback. CAVEAT: the probe runs AFTER login timed out, so if ` +
        `Keycloak was transiently slow/down DURING login and recovered by now, the real ` +
        `cause may still be Keycloak — check the underlying timeout + Keycloak latency ` +
        `before ruling it out. Underlying (authoritative): ${underlying}`,
    )
  }
}
