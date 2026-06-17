/**
 * Unit tests for the OIDC auth wiring:
 *
 *  1. useAuth() resolves to an authenticated user when UserManager.getUser()
 *     returns a non-expired User, and mirrors the access token into tokenStore
 *     so the API client can pick it up.
 *  2. The API client attaches Authorization: Bearer <token> on every request
 *     once a token is in the store.
 *  3. The TanStack-Router route guard redirects unauthenticated users to
 *     /login when they try to reach a protected path.
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { renderHook, render, screen, waitFor, act } from '@testing-library/react'
import type { ReactNode } from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { RouterProvider, createRouter, createMemoryHistory } from '@tanstack/react-router'
import type { User, UserManager } from 'oidc-client-ts'

import { AuthProvider, useAuth } from '../auth/AuthProvider'
import { getAccessToken, setAccessToken } from '../auth/tokenStore'
import { routeTree } from '../routeTree.gen'
import { fetchJobs } from '../api/client'
import { setupFetchMock, resetFetchMock } from './msw-handlers'

// ── EventSource stub (jsdom has none, $buildId routes need it) ──────────────
class EventSourceStub {
  static readonly CONNECTING = 0
  static readonly OPEN = 1
  static readonly CLOSED = 2
  readyState = EventSourceStub.CONNECTING
  onmessage: ((e: MessageEvent) => void) | null = null
  onerror: ((e: Event) => void) | null = null
  addEventListener() { /* no-op */ }
  removeEventListener() { /* no-op */ }
  close() { this.readyState = EventSourceStub.CLOSED }
}
vi.stubGlobal('EventSource', EventSourceStub)

// ── Fake UserManager ────────────────────────────────────────────────────────
function makeFakeUser(token = 'test-access-token'): User {
  return {
    access_token: token,
    expired: false,
    profile: { sub: 'u1', preferred_username: 'alice' },
  } as unknown as User
}

function makeFakeUserManager(initialUser: User | null): UserManager {
  const listeners = {
    loaded: [] as Array<(u: User) => void>,
    unloaded: [] as Array<() => void>,
    expired: [] as Array<() => void>,
  }
  return {
    getUser: vi.fn(async () => initialUser),
    signinRedirect: vi.fn(async () => undefined),
    signinRedirectCallback: vi.fn(async () => initialUser ?? makeFakeUser()),
    signoutRedirect: vi.fn(async () => undefined),
    removeUser: vi.fn(async () => undefined),
    events: {
      addUserLoaded: (cb: (u: User) => void) => listeners.loaded.push(cb),
      removeUserLoaded: (cb: (u: User) => void) => {
        const i = listeners.loaded.indexOf(cb)
        if (i >= 0) listeners.loaded.splice(i, 1)
      },
      addUserUnloaded: (cb: () => void) => listeners.unloaded.push(cb),
      removeUserUnloaded: (cb: () => void) => {
        const i = listeners.unloaded.indexOf(cb)
        if (i >= 0) listeners.unloaded.splice(i, 1)
      },
      addAccessTokenExpired: (cb: () => void) => listeners.expired.push(cb),
      removeAccessTokenExpired: (cb: () => void) => {
        const i = listeners.expired.indexOf(cb)
        if (i >= 0) listeners.expired.splice(i, 1)
      },
    },
  } as unknown as UserManager
}

// ── Reset module-level token slot between tests ─────────────────────────────
beforeEach(() => setAccessToken(null))
afterEach(() => setAccessToken(null))

// ── 1. useAuth ──────────────────────────────────────────────────────────────

describe('useAuth()', () => {
  it('resolves to authenticated when UserManager has a non-expired user', async () => {
    const user = makeFakeUser('token-abc')
    const manager = makeFakeUserManager(user)
    const wrapper = ({ children }: { children: ReactNode }) => (
      <AuthProvider userManager={manager}>{children}</AuthProvider>
    )

    const { result } = renderHook(() => useAuth(), { wrapper })

    await waitFor(() => expect(result.current.isLoading).toBe(false))
    expect(result.current.isAuthenticated).toBe(true)
    expect(result.current.user?.access_token).toBe('token-abc')
    // Mirrored into the in-memory slot for the fetch wrapper
    expect(getAccessToken()).toBe('token-abc')
  })

  it('signinRedirect delegates to UserManager.signinRedirect', async () => {
    const manager = makeFakeUserManager(null)
    const wrapper = ({ children }: { children: ReactNode }) => (
      <AuthProvider userManager={manager}>{children}</AuthProvider>
    )
    const { result } = renderHook(() => useAuth(), { wrapper })
    await waitFor(() => expect(result.current.isLoading).toBe(false))

    await act(async () => {
      await result.current.signinRedirect()
    })
    expect(manager.signinRedirect).toHaveBeenCalledOnce()
  })

  it('signoutRedirect clears the in-memory token and calls UserManager', async () => {
    const manager = makeFakeUserManager(makeFakeUser('to-be-cleared'))
    const wrapper = ({ children }: { children: ReactNode }) => (
      <AuthProvider userManager={manager}>{children}</AuthProvider>
    )
    const { result } = renderHook(() => useAuth(), { wrapper })
    await waitFor(() => expect(getAccessToken()).toBe('to-be-cleared'))

    await act(async () => {
      await result.current.signoutRedirect()
    })
    expect(getAccessToken()).toBeNull()
    expect(manager.removeUser).toHaveBeenCalledOnce()
    expect(manager.signoutRedirect).toHaveBeenCalledOnce()
  })
})

// ── 2. API client attaches the bearer header ────────────────────────────────

describe('API client', () => {
  beforeEach(() => setupFetchMock())
  afterEach(() => resetFetchMock())

  it('does NOT send Authorization when no token is set', async () => {
    setAccessToken(null)
    await fetchJobs()
    const call = (globalThis.fetch as unknown as ReturnType<typeof vi.fn>).mock.calls[0]
    const init = call[1] as RequestInit
    const headers = init.headers as Record<string, string>
    expect(headers.Authorization).toBeUndefined()
  })

  it('attaches Authorization: Bearer <token> when one is set', async () => {
    setAccessToken('bearer-xyz')
    await fetchJobs()
    const call = (globalThis.fetch as unknown as ReturnType<typeof vi.fn>).mock.calls[0]
    const init = call[1] as RequestInit
    const headers = init.headers as Record<string, string>
    expect(headers.Authorization).toBe('Bearer bearer-xyz')
  })
})

// ── 3. Route guard ──────────────────────────────────────────────────────────

describe('Route guard', () => {
  beforeEach(() => setupFetchMock())
  afterEach(() => resetFetchMock())

  function renderAt(path: string) {
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const router = createRouter({
      routeTree,
      history: createMemoryHistory({ initialEntries: [path] }),
    })
    return render(
      <AuthProvider userManager={makeFakeUserManager(null)}>
        <QueryClientProvider client={qc}>
          <RouterProvider router={router} />
        </QueryClientProvider>
      </AuthProvider>,
    )
  }

  it('redirects unauthenticated users from /jobs to /login', async () => {
    setAccessToken(null)
    renderAt('/jobs')
    await waitFor(() =>
      expect(screen.getByRole('heading', { name: /sign in to titan/i })).toBeInTheDocument(),
    )
  })

  it('lets authenticated users through to /jobs', async () => {
    setAccessToken('bearer-abc')
    renderAt('/jobs')
    // Job list seed renders the displayName eventually
    await waitFor(
      () => expect(screen.getAllByText('Main Pipeline').length).toBeGreaterThan(0),
      { timeout: 5000 },
    )
  })
})
