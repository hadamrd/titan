/**
 * Unit tests for the runtime-config fetcher + the OIDC-settings adapter that
 * sits on top of it (closes #898). The contract is the wire shape titan-server
 * agreed to:
 *
 *   GET /api/v1/system/ui-config  →
 *   { oidc: { authority, clientId, redirectUri, postLogoutRedirectUri },
 *     publicUrl }
 *
 * loadOidcSettings() must map the snake-case-free JSON into the OIDC lib's
 * snake_case UserManagerSettings shape.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  loadRuntimeConfig,
  __resetRuntimeConfigForTests,
} from '../runtimeConfig'
import { loadOidcSettings } from '../auth/oidcConfig'

const SAMPLE = {
  oidc: {
    authority: 'https://titan.test.example.com/realms/titan-dev',
    clientId: 'titan-ui',
    redirectUri: 'https://titan.test.example.com/login/callback',
    postLogoutRedirectUri: 'https://titan.test.example.com/login',
  },
  publicUrl: 'https://titan.test.example.com',
}

beforeEach(() => {
  __resetRuntimeConfigForTests()
})

afterEach(() => {
  // `vi.restoreAllMocks()` does NOT undo `vi.stubGlobal()` — it leaks the
  // stubbed `fetch` to later test files (e.g. routes.test.tsx queue rendering
  // calls real-ish fetch via msw-handlers). `unstubAllGlobals` is the canonical
  // pair for stubGlobal.
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
  __resetRuntimeConfigForTests()
})

describe('loadRuntimeConfig()', () => {
  it('GETs /api/v1/system/ui-config and returns the parsed body', async () => {
    const fetchMock = vi.fn(async () =>
      new Response(JSON.stringify(SAMPLE), {
        status: 200,
        headers: { 'content-type': 'application/json' },
      }),
    )
    vi.stubGlobal('fetch', fetchMock)

    const cfg = await loadRuntimeConfig()
    expect(cfg).toEqual(SAMPLE)
    expect(fetchMock).toHaveBeenCalledOnce()
    expect(fetchMock).toHaveBeenCalledWith('/api/v1/system/ui-config', expect.anything())
  })

  it('memoises across calls — only one network round-trip', async () => {
    const fetchMock = vi.fn(async () =>
      new Response(JSON.stringify(SAMPLE), { status: 200 }),
    )
    vi.stubGlobal('fetch', fetchMock)

    await loadRuntimeConfig()
    await loadRuntimeConfig()
    await loadRuntimeConfig()

    expect(fetchMock).toHaveBeenCalledOnce()
  })

  it('rejects on non-2xx so the boot screen can show a retry', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => new Response('boom', { status: 503 })),
    )
    await expect(loadRuntimeConfig()).rejects.toThrow(/HTTP 503/)
  })

  it('rejects when oidc.* fields are missing — defensive validation', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(
        async () =>
          new Response(JSON.stringify({ publicUrl: 'x' }), { status: 200 }),
      ),
    )
    await expect(loadRuntimeConfig()).rejects.toThrow(/oidc/i)
  })

  it('clears the in-flight cache on failure so retry re-fetches', async () => {
    let n = 0
    const fetchMock = vi.fn(async () => {
      n += 1
      if (n === 1) return new Response('nope', { status: 500 })
      return new Response(JSON.stringify(SAMPLE), { status: 200 })
    })
    vi.stubGlobal('fetch', fetchMock)

    await expect(loadRuntimeConfig()).rejects.toThrow()
    const ok = await loadRuntimeConfig()
    expect(ok).toEqual(SAMPLE)
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })
})

describe('loadOidcSettings()', () => {
  it('maps the wire shape to oidc-client-ts UserManagerSettings', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => new Response(JSON.stringify(SAMPLE), { status: 200 })),
    )

    const settings = await loadOidcSettings()
    expect(settings.authority).toBe(SAMPLE.oidc.authority)
    expect(settings.client_id).toBe(SAMPLE.oidc.clientId)
    expect(settings.redirect_uri).toBe(SAMPLE.oidc.redirectUri)
    expect(settings.post_logout_redirect_uri).toBe(
      SAMPLE.oidc.postLogoutRedirectUri,
    )
    expect(settings.response_type).toBe('code')
    expect(settings.scope).toBe('openid profile email')
    // sessionStorage user-store wired (jsdom provides window.sessionStorage).
    expect(settings.userStore).toBeDefined()
  })
})
