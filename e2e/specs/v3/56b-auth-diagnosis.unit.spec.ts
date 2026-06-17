/**
 * 56b-auth-diagnosis.unit — UNIT coverage for the realm-missing diagnosis logic
 * that powers the auth golden path (#1200).
 *
 * Why this is a separate, RIG-FREE spec:
 *   The advertised value of spec 56 is that a real outage yields the RIGHT
 *   diagnosis (`realm <r> missing or Keycloak unreachable`, mirroring
 *   smoke-auth.sh). But spec 56's happy path only exercises that branch when the
 *   rig is actually broken, and its bad-realm negative deliberately bypasses
 *   `loginWithDiagnosis()` (it drives Keycloak's authorize endpoint directly).
 *   So `keycloakRealmReachable()` and the `!realmHealthy` re-throw branch were
 *   DEAD CODE from a coverage standpoint: if `keycloakRealmReachable()` silently
 *   always returned true (a swallowed fetch error), the WRONG diagnosis would
 *   fire on every real outage — the exact failure mode this PR exists to prevent
 *   — and no test would notice.
 *
 *   These tests close that gap. They use INJECTED fakes (a fake fetch, a stubbed
 *   login driver, a stubbed realm probe) so every branch runs deterministically
 *   with NO rig and NO browser. There is no `page`/`request` fixture requested,
 *   so Playwright never launches Chromium — this is a pure-logic spec that runs
 *   on every agent, rigless or not (hence: NO `TITAN_RIG_URL` skip guard). It is
 *   the only place the diagnosis branches are genuinely asserted.
 *
 *   This is NOT the "mocked-OIDC unit test" the ticket forbids: it does not
 *   re-implement or re-assert the OIDC login flow (spec 56 owns that against a
 *   real rig). It asserts only the DIAGNOSIS wrapper — which is application logic
 *   in its own right and must be correct for the canary to be trustworthy.
 */
import { test, expect, type Page } from '@playwright/test'
import { keycloakRealmReachable, loginWithDiagnosis } from '../../fixtures/auth-diagnosis'
import { type AuthEnv } from '../../fixtures/auth-v3'

const ENV: AuthEnv = {
  uiBaseUrl: 'http://ui.test',
  keycloakUrl: 'http://kc.test',
  realm: 'titan-dev',
  clientId: 'titan-ui',
  directGrantClientId: 'titan-e2e',
  username: 'dev',
  password: 'dev',
}

// A throwaway stand-in for the Playwright Page — loginWithDiagnosis only passes
// it through to the (stubbed) login driver, so it is never dereferenced here.
const FAKE_PAGE = {} as Page

test.describe('auth-diagnosis (unit, rig-free) @auth', () => {
  test.describe('keycloakRealmReachable', () => {
    test('realm discovery doc answers 200 → reachable (true)', async () => {
      const calls: string[] = []
      const ok = await keycloakRealmReachable(ENV, async (url) => {
        calls.push(url)
        return { ok: true }
      })
      expect(ok, 'a 200 on the realm discovery doc must read as reachable').toBe(true)
      // Probes the RIGHT url — the realm's OIDC discovery doc — so a future
      // refactor that points it elsewhere fails here, not silently in prod.
      expect(calls).toEqual([
        'http://kc.test/realms/titan-dev/.well-known/openid-configuration',
      ])
    })

    test('realm discovery doc 404s → NOT reachable (false) — the realm-missing signal', async () => {
      const ok = await keycloakRealmReachable(ENV, async () => ({ ok: false }))
      // This is the crux: a 404 (realm missing) MUST map to false, otherwise the
      // !realmHealthy branch never fires and the realm-missing diagnosis dies.
      expect(ok, 'a non-2xx (404 realm-missing) must read as NOT reachable').toBe(false)
    })

    test('fetch throws (TCP/DNS down) → NOT reachable (false), not a thrown error', async () => {
      const ok = await keycloakRealmReachable(ENV, async () => {
        throw new Error('ECONNREFUSED')
      })
      // A connection-level failure is "Keycloak unreachable" → false. It must be
      // SWALLOWED into a boolean, never propagated (else the diagnosis wrapper's
      // probe itself throws and the SRE gets a raw stack instead of a diagnosis).
      expect(ok, 'a TCP/DNS-level fetch failure must read as NOT reachable').toBe(false)
    })
  })

  test.describe('loginWithDiagnosis', () => {
    test('login succeeds → resolves, no diagnosis thrown', async () => {
      let loginCalls = 0
      let probeCalls = 0
      await loginWithDiagnosis(FAKE_PAGE, ENV, {
        login: async () => {
          loginCalls += 1
        },
        realmReachable: async () => {
          probeCalls += 1
          return true
        },
      })
      expect(loginCalls, 'the login driver must be invoked once').toBe(1)
      // On success we must NOT probe — the probe is a failure-path diagnostic only.
      expect(probeCalls, 'no realm probe on a successful login').toBe(0)
    })

    test('login fails AND realm unreachable → re-throws the realm-missing diagnosis', async () => {
      let caught: unknown
      try {
        await loginWithDiagnosis(FAKE_PAGE, ENV, {
          login: async () => {
            throw new Error('Timeout 15000ms exceeded waiting for #kc-form-login')
          },
          realmReachable: async () => false,
        })
      } catch (e) {
        caught = e
      }
      expect(caught, 'a failed login with an unreachable realm MUST throw').toBeDefined()
      const msg = String(caught)
      // The smoke-auth.sh-aligned diagnosis: names the realm-missing/unreachable
      // cause so the SRE sees the SAME message at PR time and deploy time.
      expect(msg, `diagnosis must name the realm-missing cause; got: ${msg}`).toMatch(
        /realm titan-dev missing or Keycloak unreachable/i,
      )
      // And it must carry the underlying (authoritative) error for triage.
      expect(msg, 'diagnosis must preserve the underlying error').toContain('kc-form-login')
    })

    test('login fails BUT realm reachable → re-throws the NOT-a-missing-realm message (no over-blame)', async () => {
      let caught: unknown
      try {
        await loginWithDiagnosis(FAKE_PAGE, ENV, {
          login: async () => {
            throw new Error('Timeout 20000ms exceeded waiting for navigation (PKCE callback)')
          },
          realmReachable: async () => true,
        })
      } catch (e) {
        caught = e
      }
      expect(caught, 'a failed login MUST throw even when the realm is reachable').toBeDefined()
      const msg = String(caught)
      // A reachable realm must NOT be blamed as missing — that would misdirect
      // the SRE. The message explicitly says this is NOT a missing realm.
      expect(msg, `must NOT blame the realm when reachable; got: ${msg}`).toMatch(
        /NOT a missing\s+realm/i,
      )
      expect(msg, 'must not emit the realm-missing diagnosis when the realm is reachable').not.toMatch(
        /realm titan-dev missing or Keycloak unreachable/i,
      )
      // Still preserves the underlying cause for downstream triage.
      expect(msg, 'must preserve the underlying error').toContain('PKCE callback')
    })

    test('production default wiring: omitting deps does not throw at call-assembly time', async () => {
      // Guards the default-deps wiring (login ?? loginViaKeycloak,
      // realmReachable ?? keycloakRealmReachable). We still inject a failing
      // login so we never touch a real browser/rig, but we DON'T pass
      // realmReachable — proving the real keycloakRealmReachable default is wired
      // and that a fetch failure there is swallowed into the realm-missing branch
      // (kc.test is unresolvable, so the real probe returns false).
      let caught: unknown
      try {
        await loginWithDiagnosis(FAKE_PAGE, ENV, {
          login: async () => {
            throw new Error('boom')
          },
        })
      } catch (e) {
        caught = e
      }
      expect(caught, 'must throw a diagnosis even with the default probe wired').toBeDefined()
      // kc.test does not resolve → the default keycloakRealmReachable returns
      // false → realm-missing branch. This exercises the REAL default probe end
      // to end (its catch arm) without a rig.
      expect(String(caught)).toMatch(/realm titan-dev missing or Keycloak unreachable/i)
    })
  })
})
