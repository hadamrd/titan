/**
 * React context exposing the Quarkus-OIDC-authenticated user.
 *
 * Wraps {@link UserManager} from oidc-client-ts and surfaces a minimal API:
 *
 *   const { user, isAuthenticated, isLoading, signinRedirect, signoutRedirect } = useAuth()
 *
 * The access token is mirrored into a module-level slot so the fetch wrapper
 * in {@link ../api/client.ts} can read it without going through React.
 *
 * Boot sequence (post-#898):
 *   1. Mount → await {@link loadOidcSettings} (fetches /api/v1/system/ui-config)
 *   2. While pending: render a one-shot boot spinner (NOT the routed tree —
 *      the OIDC callback race needs the UserManager wired before any route
 *      renders).
 *   3. On fetch failure: render a "Couldn't load config from server" screen
 *      with a Retry button.
 *   4. On success: instantiate UserManager and render children.
 */
import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react'
import { User, UserManager, type UserManagerSettings } from 'oidc-client-ts'
import { loadOidcSettings } from './oidcConfig'
import { setAccessToken } from './tokenStore'

export interface AuthState {
  /** The OIDC user, or null when unauthenticated. */
  user: User | null
  /** True once the initial user-load has resolved (authenticated or not). */
  isLoading: boolean
  isAuthenticated: boolean
  /** Bounce to the IdP login page. */
  signinRedirect: () => Promise<void>
  /** Complete the OIDC redirect-callback (code → token exchange). */
  signinRedirectCallback: () => Promise<User>
  /** Bounce to the IdP end-session endpoint. */
  signoutRedirect: () => Promise<void>
}

// Exported so tests can wrap renders with a fixed AuthState without spinning up
// a real UserManager. Production code MUST go through <AuthProvider>; only the
// test harness reaches for AuthContext directly.
export const AuthContext = createContext<AuthState | null>(null)

export interface AuthProviderProps {
  children: ReactNode
  /** Override the default settings (used by tests — skips the runtime-config fetch). */
  settings?: UserManagerSettings
  /** Inject a pre-built UserManager (used by tests — skips the runtime-config fetch). */
  userManager?: UserManager
}

/**
 * Outer boot wrapper. When tests pre-supply `userManager` or `settings`, we
 * skip the runtime-config fetch entirely (those code-paths are exercised in
 * runtime-config.test.ts). When production wires the provider with no
 * overrides, we await the runtime config before mounting `<AuthProviderInner>`.
 */
export function AuthProvider({ children, settings, userManager }: AuthProviderProps) {
  // Test fast-path: caller already has a manager/settings.
  if (userManager !== undefined || settings !== undefined) {
    return (
      <AuthProviderInner settings={settings} userManager={userManager}>
        {children}
      </AuthProviderInner>
    )
  }

  return <AuthBootGate>{children}</AuthBootGate>
}

type BootState =
  | { phase: 'loading' }
  | { phase: 'error'; message: string }
  | { phase: 'ready'; settings: UserManagerSettings }

function AuthBootGate({ children }: { children: ReactNode }) {
  const [state, setState] = useState<BootState>({ phase: 'loading' })
  const [retryNonce, setRetryNonce] = useState(0)

  useEffect(() => {
    let cancelled = false
    setState({ phase: 'loading' })
    loadOidcSettings()
      .then((settings) => {
        if (!cancelled) setState({ phase: 'ready', settings })
      })
      .catch((err: unknown) => {
        if (cancelled) return
        const message = err instanceof Error ? err.message : String(err)
        setState({ phase: 'error', message })
      })
    return () => {
      cancelled = true
    }
  }, [retryNonce])

  if (state.phase === 'loading') {
    return <BootSpinner />
  }
  if (state.phase === 'error') {
    return (
      <BootError
        message={state.message}
        onRetry={() => setRetryNonce((n) => n + 1)}
      />
    )
  }
  return (
    <AuthProviderInner settings={state.settings}>{children}</AuthProviderInner>
  )
}

/**
 * The actual UserManager-wired provider. Split out so we can statically
 * guarantee `settings` (or `userManager`) is present — the runtime-config
 * fetch happens in {@link AuthBootGate}.
 */
function AuthProviderInner({
  children,
  settings,
  userManager,
}: {
  children: ReactNode
  settings?: UserManagerSettings
  userManager?: UserManager
}) {
  // Build a single UserManager instance per provider lifetime.
  const managerRef = useRef<UserManager | null>(null)
  if (managerRef.current === null) {
    if (userManager !== undefined) {
      managerRef.current = userManager
    } else if (settings !== undefined) {
      managerRef.current = new UserManager(settings)
    } else {
      // Should be unreachable — AuthProvider guarantees one of these is set.
      throw new Error(
        'AuthProviderInner mounted without settings or userManager — this is a bug.',
      )
    }
  }
  const manager = managerRef.current

  const [user, setUser] = useState<User | null>(null)
  const [isLoading, setIsLoading] = useState(true)

  // Pull any persisted user out of sessionStorage on mount.
  useEffect(() => {
    let cancelled = false
    manager
      .getUser()
      .then((u) => {
        if (cancelled) return
        if (u && !u.expired) {
          setUser(u)
          setAccessToken(u.access_token)
        } else {
          setUser(null)
          setAccessToken(null)
        }
      })
      .catch(() => {
        if (!cancelled) {
          setUser(null)
          setAccessToken(null)
        }
      })
      .finally(() => {
        if (!cancelled) setIsLoading(false)
      })

    const onUserLoaded = (u: User) => {
      setUser(u)
      setAccessToken(u.access_token)
    }
    const onUserUnloaded = () => {
      setUser(null)
      setAccessToken(null)
    }
    manager.events.addUserLoaded(onUserLoaded)
    manager.events.addUserUnloaded(onUserUnloaded)
    manager.events.addAccessTokenExpired(onUserUnloaded)

    return () => {
      cancelled = true
      manager.events.removeUserLoaded(onUserLoaded)
      manager.events.removeUserUnloaded(onUserUnloaded)
      manager.events.removeAccessTokenExpired(onUserUnloaded)
    }
  }, [manager])

  const signinRedirect = useCallback(() => manager.signinRedirect(), [manager])
  const signinRedirectCallback = useCallback(
    () => manager.signinRedirectCallback() as Promise<User>,
    [manager],
  )
  const signoutRedirect = useCallback(async () => {
    setAccessToken(null)
    await manager.removeUser()
    await manager.signoutRedirect()
  }, [manager])

  const value = useMemo<AuthState>(
    () => ({
      user,
      isLoading,
      isAuthenticated: user !== null && !user.expired,
      signinRedirect,
      signinRedirectCallback,
      signoutRedirect,
    }),
    [user, isLoading, signinRedirect, signinRedirectCallback, signoutRedirect],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

// ── Boot UI (intentionally minimal — no dep on the design system since the
// stylesheet bundle hasn't necessarily applied yet by first paint) ───────────

function BootSpinner() {
  return (
    <div
      role="status"
      aria-live="polite"
      aria-label="Loading Titan"
      style={{
        position: 'fixed',
        inset: 0,
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        fontFamily:
          'Geist, system-ui, -apple-system, "Segoe UI", Roboto, sans-serif',
        fontSize: 13,
        color: '#888',
        background: 'var(--bg, #0b0b0c)',
      }}
    >
      <span>Loading Titan…</span>
    </div>
  )
}

function BootError({
  message,
  onRetry,
}: {
  message: string
  onRetry: () => void
}) {
  return (
    <div
      role="alert"
      style={{
        position: 'fixed',
        inset: 0,
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        justifyContent: 'center',
        gap: 16,
        padding: 24,
        fontFamily:
          'Geist, system-ui, -apple-system, "Segoe UI", Roboto, sans-serif',
        background: 'var(--bg, #0b0b0c)',
        color: 'var(--fg, #e7e7e7)',
        textAlign: 'center',
      }}
    >
      <h1 style={{ fontSize: 18, fontWeight: 600, margin: 0 }}>
        Couldn&apos;t load config from server
      </h1>
      <p style={{ fontSize: 13, color: '#888', margin: 0, maxWidth: 480 }}>
        Titan failed to fetch its runtime configuration from{' '}
        <code style={{ fontFamily: '"Geist Mono", monospace' }}>
          /api/v1/system/ui-config
        </code>
        . Check that titan-server is reachable and try again.
      </p>
      <p
        style={{
          fontSize: 12,
          color: '#666',
          margin: 0,
          fontFamily: '"Geist Mono", ui-monospace, monospace',
          maxWidth: 560,
          wordBreak: 'break-word',
        }}
      >
        {message}
      </p>
      <button
        type="button"
        onClick={onRetry}
        style={{
          padding: '8px 16px',
          fontSize: 13,
          fontWeight: 500,
          fontFamily: 'inherit',
          color: '#fff',
          background: '#3b82f6',
          border: 'none',
          borderRadius: 6,
          cursor: 'pointer',
        }}
      >
        Retry
      </button>
    </div>
  )
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext)
  if (ctx === null) {
    throw new Error('useAuth() must be used inside <AuthProvider>')
  }
  return ctx
}
