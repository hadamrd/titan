/**
 * Adversarial tests for the /profile v3 redesign (closes #574).
 *
 * Covered:
 *  - 7-section left nav renders with the v3 labels
 *  - Clicking each nav item swaps the content panel (data-testid="section-X")
 *  - Sections without a backend show an explicit "Coming soon" placeholder
 *    (notifications, sessions) and tagged badges for security 2FA + github
 *  - Access tokens section preserves PAT issuance: submit form → POST fires →
 *    reveal dialog appears with the plaintext → list refreshes
 *  - Not-signed-in path renders the sentinel, NOT a fake profile
 */
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import {
  RouterProvider,
  createMemoryHistory,
  createRootRoute,
  createRoute,
  createRouter,
  Outlet,
} from '@tanstack/react-router'
import type { User, UserManager } from 'oidc-client-ts'

import { AuthProvider } from '../auth/AuthProvider'
import { setAccessToken } from '../auth/tokenStore'
import { Route as ProfileRoute } from '../routes/profile'
import type {
  PersonalAccessTokenCreatedDto,
  PersonalAccessTokenDto,
} from '../api/types'
import {
  defaultHandlers,
  setupFetchMock,
  resetFetchMock,
} from './msw-handlers'

// ── Fake OIDC user manager ──────────────────────────────────────────────────

function makeFakeUser(opts: { signed: boolean }): User | null {
  if (!opts.signed) return null
  return {
    access_token: 'test-token',
    expired: false,
    profile: {
      sub: 'u1',
      preferred_username: 'kira.rai',
      name: 'Kira Rai',
      email: 'kira@titan-labs.com',
      iss: 'https://kc.test/realms/titan',
      iat: 1_700_000_000,
    },
  } as unknown as User
}

function makeFakeUserManager(user: User | null): UserManager {
  return {
    getUser: async () => user,
    signinRedirect: async () => undefined,
    signinRedirectCallback: async () => user ?? ({} as User),
    signoutRedirect: async () => undefined,
    removeUser: async () => undefined,
    events: {
      addUserLoaded: () => {},
      removeUserLoaded: () => {},
      addUserUnloaded: () => {},
      removeUserUnloaded: () => {},
      addAccessTokenExpired: () => {},
      removeAccessTokenExpired: () => {},
    },
  } as unknown as UserManager
}

// ── Token handler helpers ───────────────────────────────────────────────────

interface MatchResult {
  status: number
  body: unknown
}
type Handler = (url: URL, method: string) => MatchResult | null

function tokensListHandler(state: { tokens: PersonalAccessTokenDto[] }): Handler {
  return (url, method) => {
    if (method !== 'GET') return null
    if (url.pathname !== '/api/v1/me/tokens') return null
    return { status: 200, body: state.tokens }
  }
}

function tokensCreateHandler(state: {
  tokens: PersonalAccessTokenDto[]
  created: PersonalAccessTokenCreatedDto | null
}): Handler {
  return (url, method) => {
    if (method !== 'POST') return null
    if (url.pathname !== '/api/v1/me/tokens') return null
    const newRow: PersonalAccessTokenDto = {
      id: 1,
      name: 'ci-bot',
      prefix: 'tt_live_AbCd',
      scopes: null,
      jobPattern: null,
      createdAt: '2026-05-24T10:00:00Z',
      lastUsedAt: null,
      revokedAt: null,
    }
    const created: PersonalAccessTokenCreatedDto = {
      id: 1,
      name: 'ci-bot',
      prefix: 'tt_live_AbCd',
      scopes: null,
      jobPattern: null,
      token: 'tt_live_AbCdEf1234567890SECRETvalue',
      createdAt: '2026-05-24T10:00:00Z',
    }
    state.tokens = [newRow]
    state.created = created
    return { status: 201, body: created }
  }
}

// ── Harness ─────────────────────────────────────────────────────────────────

function mountProfile(opts: { signed?: boolean; extraHandlers?: Handler[] } = {}) {
  const signed = opts.signed !== false
  const handlers: Handler[] = [...(opts.extraHandlers ?? []), ...defaultHandlers()]
  setupFetchMock(handlers)
  if (signed) setAccessToken('test-token')

  const rootRoute = createRootRoute({ component: () => <Outlet /> })
  const profileRoute = createRoute({
    getParentRoute: () => rootRoute,
    path: '/profile',
    component: ProfileRoute.options.component,
  })
  const router = createRouter({
    routeTree: rootRoute.addChildren([profileRoute]),
    history: createMemoryHistory({ initialEntries: ['/profile'] }),
  })

  const qc = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })

  return render(
    <AuthProvider userManager={makeFakeUserManager(makeFakeUser({ signed }))}>
      <QueryClientProvider client={qc}>
        <RouterProvider router={router} />
      </QueryClientProvider>
    </AuthProvider>,
  )
}

// ── Tests ───────────────────────────────────────────────────────────────────

describe('Profile v3 — settings-grid + 7-section nav', () => {
  beforeEach(() => {
    // The route seeds its initial section from `window.location.hash` and
    // writes back via history.replaceState on every tab click. Tests share one
    // global location, so a prior test that clicked into #tokens would leak that
    // hash into the next test's initial section. Reset to a clean /profile.
    window.history.replaceState(null, '', '/profile')
  })
  afterEach(() => {
    resetFetchMock()
  })

  it('renders inside the shared PageContainer/PageHeader frame (H1)', async () => {
    const { container } = mountProfile()
    await screen.findByTestId('settings-nav')

    // H1: the route must be framed by the shared <PageContainer> (centred,
    // max-width column) — not hugging the top-left of a dead canvas. The
    // container is the mx-auto max-w-* wrapper; the header carries the title.
    const frame = container.querySelector('.mx-auto.max-w-5xl')
    expect(frame).toBeTruthy()
    expect(screen.getByRole('heading', { name: 'Profile & settings' })).toBeTruthy()
  })

  it('wraps every section panel in the shared SectionCard shell (H8)', async () => {
    mountProfile()
    await screen.findByTestId('settings-nav')

    // Profile (default) panel is a Card with a title + description header.
    const card = screen.getByTestId('section-card')
    expect(card.classList.contains('card')).toBe(true)
    expect(within(card).getByText('Profile')).toBeTruthy()

    // Switching tabs keeps the SAME shell (one card, new title) — H8.
    fireEvent.click(screen.getByTestId('nav-tokens'))
    const tokenCard = screen.getByTestId('section-card')
    expect(tokenCard.classList.contains('card')).toBe(true)
    expect(within(tokenCard).getByText('Personal access tokens')).toBeTruthy()
  })

  it('renders all 7 section nav items in v3 order', async () => {
    mountProfile()
    await screen.findByTestId('settings-nav')

    const labels = [
      'Profile',
      'Notifications',
      'Security & 2FA',
      'Sessions',
      'Access tokens',
      'CLI & integrations',
      'Appearance',
    ]
    for (const label of labels) {
      expect(screen.getByRole('button', { name: label })).toBeTruthy()
    }
  })

  it('starts on Profile and swaps content when nav items are clicked', async () => {
    mountProfile()
    await screen.findByTestId('section-profile')

    fireEvent.click(screen.getByTestId('nav-notifications'))
    expect(screen.getByTestId('section-notifications')).toBeTruthy()

    fireEvent.click(screen.getByTestId('nav-security'))
    expect(screen.getByTestId('section-security')).toBeTruthy()

    fireEvent.click(screen.getByTestId('nav-sessions'))
    expect(screen.getByTestId('section-sessions')).toBeTruthy()

    fireEvent.click(screen.getByTestId('nav-cli'))
    expect(screen.getByTestId('section-cli')).toBeTruthy()

    fireEvent.click(screen.getByTestId('nav-appearance'))
    expect(screen.getByTestId('section-appearance')).toBeTruthy()
  })

  it('sections without backend (notifications, sessions) show "Coming soon"', async () => {
    mountProfile()
    await screen.findByTestId('settings-nav')

    fireEvent.click(screen.getByTestId('nav-notifications'))
    const notifPanel = screen.getByTestId('coming-soon')
    expect(notifPanel).toBeTruthy()
    expect(within(notifPanel).getByText('Notifications')).toBeTruthy()
    expect(within(notifPanel).getByText(/coming soon/i)).toBeTruthy()

    fireEvent.click(screen.getByTestId('nav-sessions'))
    const sessionsPanel = screen.getByTestId('coming-soon')
    expect(sessionsPanel).toBeTruthy()
    expect(within(sessionsPanel).getByText(/Active sessions/i)).toBeTruthy()
  })

  it('security section shows 2FA Coming soon badge + Account Console link', async () => {
    mountProfile()
    await screen.findByTestId('settings-nav')
    fireEvent.click(screen.getByTestId('nav-security'))

    expect(screen.getByTestId('2fa-coming-soon')).toBeTruthy()
    const link = screen.getByTestId('kc-account-link') as HTMLAnchorElement
    expect(link.href).toContain('/realms/titan/account')
  })

  it('cli section shows install snippet + GitHub Coming soon', async () => {
    mountProfile()
    await screen.findByTestId('settings-nav')
    fireEvent.click(screen.getByTestId('nav-cli'))

    expect(screen.getByTestId('cli-install-copy')).toBeTruthy()
    expect(screen.getByTestId('github-coming-soon')).toBeTruthy()
  })

  it('access-tokens section preserves PAT issuance flow', async () => {
    const state: {
      tokens: PersonalAccessTokenDto[]
      created: PersonalAccessTokenCreatedDto | null
    } = { tokens: [], created: null }

    mountProfile({
      extraHandlers: [tokensListHandler(state), tokensCreateHandler(state)],
    })

    await screen.findByTestId('settings-nav')
    fireEvent.click(screen.getByTestId('nav-tokens'))

    // initially the DataTable empty state is rendered (no rows yet)
    await screen.findByTestId('pat-list-empty')

    // fill name + submit
    const nameInput = screen.getByTestId('pat-name-input') as HTMLInputElement
    fireEvent.change(nameInput, { target: { value: 'ci-bot' } })

    const btn = screen.getByTestId('pat-generate-btn')
    await act(async () => {
      fireEvent.click(btn)
    })

    // reveal panel shows the plaintext
    const reveal = await screen.findByTestId('token-reveal')
    expect(reveal).toBeTruthy()
    expect(screen.getByTestId('token-secret').textContent).toBe(
      'tt_live_AbCdEf1234567890SECRETvalue',
    )

    // list refreshes to show the new row
    await waitFor(() => {
      expect(screen.getByTestId('pat-list')).toBeTruthy()
    })
    const row = screen.getByTestId('token-row')
    expect(row.getAttribute('data-token-name')).toBe('ci-bot')
    expect(row.getAttribute('data-token-status')).toBe('active')

    // #1201 + #1186: the tokens content reads as a deliberate framed card, not
    // a bare grid floating left-of-centre (Balance / H1). In the #1186 redesign
    // the `.card` frame is the shared SectionCard shell that wraps every tab
    // (H8); `pat-card` is its body, nested inside that frame.
    const sectionCard = screen.getByTestId('section-card')
    expect(sectionCard.classList.contains('card')).toBe(true)
    const card = screen.getByTestId('pat-card')
    expect(sectionCard.contains(card)).toBe(true)

    // #1201: the always-empty "Last used" column is gone (design chart H4).
    expect(
      screen.queryByRole('columnheader', { name: /last used/i }),
    ).toBeNull()
  })

  it('renders honest not-signed-in sentinel when no OIDC user', async () => {
    mountProfile({ signed: false })
    await screen.findByTestId('profile-not-signed-in')
    // The settings grid MUST NOT render — we won't fake a profile.
    expect(screen.queryByTestId('settings-nav')).toBeNull()
  })
})
