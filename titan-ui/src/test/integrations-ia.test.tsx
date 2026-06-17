/**
 * Adversarial tests for the /integrations IA split (closes #878).
 *
 * Covers:
 *   1. Provider index — "Not connected" state when no GitHub App is registered.
 *   2. Provider index — "Connected · N installs" when App is registered and ≥1 install exists.
 *   3. Install detail — graceful "not found" state for a stale URL.
 *   4. Install detail — renders the install body when the id matches.
 *
 * Implementation note: each route's page component is closed over by the
 * `Route` export. We pull the component through `Route.options.component`
 * and render it under a synthetic memory router that registers ALL the
 * routes the page might link to (the type-checked `<Link to=…>` calls fail
 * at runtime if their targets are not in the tree).
 */
import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest'
import { render, screen, cleanup } from '@testing-library/react'
import {
  RouterProvider,
  createRouter,
  createMemoryHistory,
  createRootRoute,
  createRoute,
  Outlet,
} from '@tanstack/react-router'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'

// We must mock BEFORE importing the route modules. The hooks live in
// '@/api/githubApp' and '@/api/hooks' — both are referenced by the
// install-detail page.
vi.mock('@/api/githubApp', async () => {
  const actual = await vi.importActual<typeof import('@/api/githubApp')>('@/api/githubApp')
  return {
    ...actual,
    useGithubApp: vi.fn(),
    useGithubInstallations: vi.fn(),
    useSyncInstallation: vi.fn(),
  }
})
vi.mock('@/api/hooks', async () => {
  const actual = await vi.importActual<typeof import('@/api/hooks')>('@/api/hooks')
  return {
    ...actual,
    useCreateJob: vi.fn(),
  }
})

import {
  useGithubApp,
  useGithubInstallations,
  useSyncInstallation,
  type GithubInstallationDto,
} from '@/api/githubApp'
import { useCreateJob } from '@/api/hooks'
import { Route as IntegrationsIndexRoute } from '@/routes/integrations.index'
import { Route as IntegrationsGithubIndexRoute } from '@/routes/integrations.github.index'
import { Route as IntegrationsGithubInstallRoute } from '@/routes/integrations.github.$installId'

const mockedUseGithubApp = vi.mocked(useGithubApp)
const mockedUseInstalls = vi.mocked(useGithubInstallations)
const mockedUseSync = vi.mocked(useSyncInstallation)
const mockedUseCreateJob = vi.mocked(useCreateJob)

// eslint-disable-next-line @typescript-eslint/no-explicit-any
function makeQuery(overrides: Record<string, unknown> = {}): any {
  return {
    data: undefined,
    isLoading: false,
    isError: false,
    error: null,
    isFetching: false,
    ...overrides,
  }
}

// eslint-disable-next-line @typescript-eslint/no-explicit-any
function makeMutation(): any {
  return {
    mutate: vi.fn(),
    isPending: false,
    isError: false,
    error: null,
  }
}

function makeInstall(over: Partial<GithubInstallationDto> = {}): GithubInstallationDto {
  return {
    id: 42,
    githubInstallationId: 9001,
    accountLogin: 'acme-co',
    accountType: 'Organization',
    suspended: false,
    createdAt: '2026-01-15T00:00:00Z',
    repos: [],
    ...over,
  }
}

function renderPage(
  Component: () => React.JSX.Element,
  opts: { initialPath?: string; params?: Record<string, string> } = {},
) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const rootRoute = createRootRoute({ component: () => <Outlet /> })
  // Register every link target the pages can navigate to, so TanStack
  // doesn't blow up looking them up.
  const integrationsIndexRoute = createRoute({
    getParentRoute: () => rootRoute,
    path: '/integrations',
    component: Component,
  })
  const githubIndexRoute = createRoute({
    getParentRoute: () => rootRoute,
    path: '/integrations/github',
    component: Component,
  })
  const installRoute = createRoute({
    getParentRoute: () => rootRoute,
    path: '/integrations/github/$installId',
    component: Component,
  })
  const onboardingRoute = createRoute({
    getParentRoute: () => rootRoute,
    path: '/onboarding',
    component: () => <div>onboarding</div>,
  })
  const router = createRouter({
    routeTree: rootRoute.addChildren([
      integrationsIndexRoute,
      githubIndexRoute,
      installRoute,
      onboardingRoute,
    ]),
    history: createMemoryHistory({ initialEntries: [opts.initialPath ?? '/integrations'] }),
  })
  return render(
    <QueryClientProvider client={qc}>
      {/* eslint-disable-next-line @typescript-eslint/no-explicit-any */}
      <RouterProvider router={router as any} />
    </QueryClientProvider>,
  )
}

beforeEach(() => {
  vi.clearAllMocks()
  mockedUseSync.mockReturnValue(makeMutation())
  mockedUseCreateJob.mockReturnValue(makeMutation())
})
afterEach(() => cleanup())

describe('/integrations — provider index', () => {
  it('renders "Not connected" when GitHub App is missing', async () => {
    mockedUseGithubApp.mockReturnValue(makeQuery({ data: null }))
    mockedUseInstalls.mockReturnValue(makeQuery({ data: [] }))
    const Component = IntegrationsIndexRoute.options.component as () => React.JSX.Element
    renderPage(Component, { initialPath: '/integrations' })
    const card = await screen.findByTestId('provider-card-github')
    expect(card).toBeInTheDocument()
    // Idle state on the new list IA renders a "+ Connect" pill.
    expect(screen.getByTestId('provider-card-github-status').textContent).toMatch(/Connect/)
    // The card itself is the navigation affordance.
    expect(card.tagName.toLowerCase()).toBe('a')
    expect(card.getAttribute('href')).toBe('/integrations/github')
  })

  it('renders "Connected · 1 install" when App + one installation exist', async () => {
    mockedUseGithubApp.mockReturnValue(
      makeQuery({
        data: {
          id: 1,
          appId: 12345,
          slug: 'titan-acme',
          name: 'Titan @ acme',
          htmlUrl: 'https://github.com/apps/titan-acme',
          createdAt: '2026-01-01T00:00:00Z',
        },
      }),
    )
    mockedUseInstalls.mockReturnValue(makeQuery({ data: [makeInstall()] }))
    const Component = IntegrationsIndexRoute.options.component as () => React.JSX.Element
    renderPage(Component, { initialPath: '/integrations' })
    // Connected state shows "{count} integration{s}" inline in the row.
    expect(
      (await screen.findByTestId('provider-card-github-status')).textContent,
    ).toMatch(/1\s*integration/)
  })

  it('renders all four provider cards, only GitHub is clickable', async () => {
    mockedUseGithubApp.mockReturnValue(makeQuery({ data: null }))
    mockedUseInstalls.mockReturnValue(makeQuery({ data: [] }))
    const Component = IntegrationsIndexRoute.options.component as () => React.JSX.Element
    renderPage(Component, { initialPath: '/integrations' })

    const gh = await screen.findByTestId('provider-card-github')
    const gl = screen.getByTestId('provider-card-gitlab')
    const bb = screen.getByTestId('provider-card-bitbucket')
    const ge = screen.getByTestId('provider-card-gerrit')

    // GitHub: live anchor, no aria-disabled.
    expect(gh.tagName.toLowerCase()).toBe('a')
    expect(gh.getAttribute('aria-disabled')).toBeNull()

    // Inactive providers: NOT anchors, marked aria-disabled, "Coming soon" pill.
    for (const card of [gl, bb, ge]) {
      expect(card.tagName.toLowerCase()).not.toBe('a')
      expect(card.getAttribute('aria-disabled')).toBe('true')
    }
    expect(screen.getByTestId('provider-card-gitlab-status').textContent).toBe(
      'Coming soon',
    )
    expect(screen.getByTestId('provider-card-bitbucket-status').textContent).toBe(
      'Coming soon',
    )
    expect(screen.getByTestId('provider-card-gerrit-status').textContent).toBe(
      'Coming soon',
    )
  })
})

describe('/integrations/github — provider detail', () => {
  it('renders "Add another GitHub integration" with the App htmlUrl + target=_blank', async () => {
    mockedUseGithubApp.mockReturnValue(
      makeQuery({
        data: {
          id: 1,
          appId: 12345,
          slug: 'titan-acme',
          name: 'Titan @ acme',
          htmlUrl: 'https://github.com/apps/titan-acme',
          createdAt: '2026-01-01T00:00:00Z',
        },
      }),
    )
    mockedUseInstalls.mockReturnValue(makeQuery({ data: [makeInstall()] }))
    const Component = IntegrationsGithubIndexRoute.options
      .component as () => React.JSX.Element
    renderPage(Component, { initialPath: '/integrations/github' })

    const btn = await screen.findByTestId('github-add-another-btn')
    expect(btn.getAttribute('href')).toBe(
      'https://github.com/apps/titan-acme/installations/new',
    )
    expect(btn.getAttribute('target')).toBe('_blank')
    // rel must include "noopener" — opens an external surface.
    expect(btn.getAttribute('rel') ?? '').toContain('noopener')
  })

  it('omits the "Add another" CTA when no App is registered yet', async () => {
    mockedUseGithubApp.mockReturnValue(makeQuery({ data: null }))
    mockedUseInstalls.mockReturnValue(makeQuery({ data: [] }))
    const Component = IntegrationsGithubIndexRoute.options
      .component as () => React.JSX.Element
    renderPage(Component, { initialPath: '/integrations/github' })

    // Empty state renders the /onboarding fallback, NOT the Add-another button.
    expect(await screen.findByTestId('integrations-empty')).toBeInTheDocument()
    expect(screen.queryByTestId('github-add-another-btn')).toBeNull()
    // The empty state's "Set up Titan GitHub App" link (the only CTA when no
    // App row exists yet).
    expect(screen.getByTestId('github-empty-onboarding')).toBeInTheDocument()
  })

  it('install row renders dense meta + event chips + settings external link', async () => {
    mockedUseGithubApp.mockReturnValue(
      makeQuery({
        data: {
          id: 1,
          appId: 12345,
          slug: 'titan-acme',
          name: 'Titan @ acme',
          htmlUrl: 'https://github.com/apps/titan-acme',
          createdAt: '2026-01-01T00:00:00Z',
        },
      }),
    )
    mockedUseInstalls.mockReturnValue(
      makeQuery({
        data: [
          makeInstall({
            id: 42,
            githubInstallationId: 9001,
            accountLogin: 'acme-co',
            createdAt: '2026-01-15T00:00:00Z',
            repos: [
              // Two synthetic repo entries so the count line reads "2 repos".
              // The structure mirrors GithubRepoDto; cast through unknown
              // because the test doesn't exercise repo fields directly.
              { fullName: 'acme-co/a', defaultBranch: 'main', htmlUrl: '', pipelines: [] },
              { fullName: 'acme-co/b', defaultBranch: 'main', htmlUrl: '', pipelines: [] },
            ] as GithubInstallationDto['repos'],
          }),
        ],
      }),
    )
    const Component = IntegrationsGithubIndexRoute.options
      .component as () => React.JSX.Element
    renderPage(Component, { initialPath: '/integrations/github' })

    // Dense meta line — URL, method, repo count, added date are all rendered.
    expect((await screen.findByTestId('install-url-42')).textContent).toBe(
      'github.com/acme-co',
    )
    expect(screen.getByTestId('install-repo-count-42').textContent).toMatch(/2 repos/)
    // YYYY-MM-DD format (mock format from mock spec).
    expect(screen.getByTestId('install-added-42').textContent).toBe('added 2026-01-15')

    // Event chips from providers.ts — push, pull_request, repository.
    expect(screen.getByTestId('install-event-42-push')).toBeInTheDocument()
    expect(screen.getByTestId('install-event-42-pull_request')).toBeInTheDocument()
    expect(screen.getByTestId('install-event-42-repository')).toBeInTheDocument()

    // Settings link goes to the user's GitHub install-management page using
    // the GitHub-side numeric id (9001), not our row id (42).
    const settings = screen.getByTestId('install-settings-link-42')
    expect(settings.getAttribute('href')).toBe(
      'https://github.com/settings/installations/9001',
    )
    expect(settings.getAttribute('target')).toBe('_blank')
    expect(settings.getAttribute('rel') ?? '').toContain('noopener')

    // Re-sync icon button exists and is enabled when not pending.
    const sync = screen.getByTestId('install-sync-icon-42')
    expect(sync.tagName.toLowerCase()).toBe('button')
    expect(sync.hasAttribute('disabled')).toBe(false)
  })
})

describe('/integrations — roadmap footer', () => {
  it('renders the muted "Custom OIDC, Azure DevOps, …" roadmap note', async () => {
    mockedUseGithubApp.mockReturnValue(makeQuery({ data: null }))
    mockedUseInstalls.mockReturnValue(makeQuery({ data: [] }))
    const Component = IntegrationsIndexRoute.options.component as () => React.JSX.Element
    renderPage(Component, { initialPath: '/integrations' })
    const note = await screen.findByTestId('integrations-roadmap-note')
    expect(note.textContent ?? '').toMatch(/Custom OIDC/)
    expect(note.textContent ?? '').toMatch(/Azure DevOps/)
    expect(note.textContent ?? '').toMatch(/roadmap/)
  })
})

describe('/integrations/github/$installId — install detail', () => {
  it('renders "not found" gracefully when no install matches the id', async () => {
    mockedUseInstalls.mockReturnValue(makeQuery({ data: [makeInstall({ id: 1 })] }))
    const Component = IntegrationsGithubInstallRoute.options
      .component as () => React.JSX.Element
    renderPage(Component, { initialPath: '/integrations/github/9999' })
    const notFound = await screen.findByTestId('install-not-found')
    expect(notFound).toBeInTheDocument()
    expect(notFound.textContent).toMatch(/#9999/)
    // The back link is a Link, not a hard <a href>, and renders into the
    // anchor tag.
    const back = screen.getByTestId('install-not-found-back')
    expect(back.getAttribute('href')).toBe('/integrations/github')
  })

  it('renders the install body when the id matches', async () => {
    const install = makeInstall({ id: 42, accountLogin: 'acme-co' })
    mockedUseInstalls.mockReturnValue(makeQuery({ data: [install] }))
    const Component = IntegrationsGithubInstallRoute.options
      .component as () => React.JSX.Element
    renderPage(Component, { initialPath: '/integrations/github/42' })
    expect(await screen.findByTestId('install-card-42')).toBeInTheDocument()
    expect(screen.queryByTestId('install-not-found')).toBeNull()
  })

  it('breadcrumb shows Integrations / GitHub / {accountLogin}', async () => {
    const install = makeInstall({ id: 42, accountLogin: 'acme-co' })
    mockedUseInstalls.mockReturnValue(makeQuery({ data: [install] }))
    const Component = IntegrationsGithubInstallRoute.options
      .component as () => React.JSX.Element
    renderPage(Component, { initialPath: '/integrations/github/42' })

    const crumb = await screen.findByTestId('install-breadcrumb')
    // Text-content collapses whitespace; assert order + presence of all three.
    const text = crumb.textContent ?? ''
    expect(text).toMatch(/Integrations/)
    expect(text).toMatch(/GitHub/)
    expect(text).toMatch(/acme-co/)
    // "Integrations" precedes "GitHub" precedes "acme-co".
    expect(text.indexOf('Integrations')).toBeLessThan(text.indexOf('GitHub'))
    expect(text.indexOf('GitHub')).toBeLessThan(text.indexOf('acme-co'))
  })
})
