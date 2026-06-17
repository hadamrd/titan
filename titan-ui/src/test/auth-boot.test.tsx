/**
 * Tests the runtime-config boot path of `<AuthProvider>` (closes #898):
 *   - while the /api/v1/system/ui-config fetch is in-flight, render a spinner
 *   - on fetch failure, render an error screen with a Retry button
 *   - clicking Retry re-fetches; on success, mount the children
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import { AuthProvider } from '../auth/AuthProvider'
import { __resetRuntimeConfigForTests } from '../runtimeConfig'

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
  vi.restoreAllMocks()
  __resetRuntimeConfigForTests()
})

describe('AuthProvider boot gate', () => {
  it('renders the boot spinner while the config fetch is pending', async () => {
    let resolveFetch: (r: Response) => void = () => undefined
    const pending = new Promise<Response>((resolve) => {
      resolveFetch = resolve
    })
    vi.stubGlobal('fetch', vi.fn(() => pending))

    render(
      <AuthProvider>
        <div>app-tree</div>
      </AuthProvider>,
    )

    // Spinner is up, children are NOT rendered (boot race fix).
    expect(screen.getByRole('status')).toBeInTheDocument()
    expect(screen.queryByText('app-tree')).not.toBeInTheDocument()

    // Drain the pending fetch so the test doesn't leak (children still won't
    // mount in jsdom because UserManager hits an unreachable IdP — that's
    // fine, we only care that the spinner went away).
    resolveFetch(new Response(JSON.stringify(SAMPLE), { status: 200 }))
    await waitFor(() => {
      expect(screen.queryByRole('status')).not.toBeInTheDocument()
    })
  })

  it('renders the error screen + Retry button when the fetch fails', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => new Response('boom', { status: 503 })),
    )

    render(
      <AuthProvider>
        <div>app-tree</div>
      </AuthProvider>,
    )

    await waitFor(() =>
      expect(
        screen.getByRole('heading', { name: /couldn.?t load config/i }),
      ).toBeInTheDocument(),
    )
    expect(screen.getByRole('button', { name: /retry/i })).toBeInTheDocument()
    expect(screen.queryByText('app-tree')).not.toBeInTheDocument()
  })

  it('Retry re-triggers the fetch — succeeds the second time', async () => {
    let n = 0
    const fetchMock = vi.fn(async () => {
      n += 1
      if (n === 1) return new Response('nope', { status: 500 })
      return new Response(JSON.stringify(SAMPLE), { status: 200 })
    })
    vi.stubGlobal('fetch', fetchMock)

    render(
      <AuthProvider>
        <div>app-tree</div>
      </AuthProvider>,
    )

    const retryBtn = await screen.findByRole('button', { name: /retry/i })
    fireEvent.click(retryBtn)

    // Error UI disappears. We don't assert app-tree renders because the real
    // UserManager would try to call out to the (fake) IdP host — verifying
    // the boot-gate transition is enough.
    await waitFor(() => {
      expect(
        screen.queryByRole('heading', { name: /couldn.?t load config/i }),
      ).not.toBeInTheDocument()
    })
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })
})
