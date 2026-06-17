/**
 * Route-level frame tests for the #1188 H1 sweep.
 *
 * `page-frame.test.tsx` covers <PageContainer>/<PageHeader> in ISOLATION. That
 * proves the primitives work but NOT that the converted routes actually adopt
 * them — exactly the gap a critic flagged (a route could add `PageContainer`
 * and forget `PageHeader`, as `integrations.github.index` originally did).
 *
 * These tests mount REAL converted route components under a synthetic router
 * and assert the structural invariant the sweep promises: every page renders
 * inside a max-width PageContainer AND surfaces a single dominant <h1> from the
 * shared PageHeader — at the width the route declared.
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

vi.mock('@/api/githubApp', async () => {
  const actual = await vi.importActual<typeof import('@/api/githubApp')>('@/api/githubApp')
  return {
    ...actual,
    useGithubApp: vi.fn(),
    useGithubInstallations: vi.fn(),
    useSyncInstallation: vi.fn(),
  }
})

import {
  useGithubApp,
  useGithubInstallations,
  useSyncInstallation,
} from '@/api/githubApp'
import { Route as IntegrationsGithubIndexRoute } from '@/routes/integrations.github.index'
import { Route as BuildCompareRoute } from '@/routes/builds/compare'
import { Route as PipelineValidateRoute } from '@/routes/pipelines/validate'

const mockedUseGithubApp = vi.mocked(useGithubApp)
const mockedUseInstalls = vi.mocked(useGithubInstallations)
const mockedUseSync = vi.mocked(useSyncInstallation)

// eslint-disable-next-line @typescript-eslint/no-explicit-any
function makeQuery(overrides: Record<string, unknown> = {}): any {
  return { data: undefined, isLoading: false, isError: false, error: null, isFetching: false, ...overrides }
}
// eslint-disable-next-line @typescript-eslint/no-explicit-any
function makeMutation(): any {
  return { mutate: vi.fn(), isPending: false, isError: false, error: null }
}

/**
 * Mount a route component under a memory router that registers every link
 * target the page can navigate to. `validateSearch` is optional so routes that
 * read search params (builds/compare) parse the URL correctly.
 */
function renderRoute(
  Component: () => React.JSX.Element,
  opts: {
    initialPath: string
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    validateSearch?: (raw: Record<string, unknown>) => any
  },
) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const rootRoute = createRootRoute({ component: () => <Outlet /> })
  const targetPath = opts.initialPath.split('?')[0]
  const targetRoute = createRoute({
    getParentRoute: () => rootRoute,
    path: targetPath,
    component: Component,
    validateSearch: opts.validateSearch,
  })
  // Register the link targets the converted pages reference (minus the target
  // route's own path, which would collide as a duplicate route id).
  const linkTargets = ['/integrations', '/integrations/github', '/onboarding']
    .filter((path) => path !== targetPath)
    .map((path) =>
      createRoute({ getParentRoute: () => rootRoute, path, component: () => <div /> }),
    )
  const router = createRouter({
    routeTree: rootRoute.addChildren([targetRoute, ...linkTargets]),
    history: createMemoryHistory({ initialEntries: [opts.initialPath] }),
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
})
afterEach(() => cleanup())

describe('converted routes render inside the shared frame (#1188 H1)', () => {
  it('integrations.github.index: PageContainer (default width) + single PageHeader <h1>', async () => {
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
    mockedUseInstalls.mockReturnValue(makeQuery({ data: [] }))
    const Component = IntegrationsGithubIndexRoute.options.component as () => React.JSX.Element
    const { container } = renderRoute(Component, { initialPath: '/integrations/github' })

    // 1. The content is framed in a default-width PageContainer (H1) — not
    //    floating in the top-left of the canvas.
    await screen.findByTestId('github-breadcrumb')
    expect(container.querySelector('.max-w-screen-xl')).not.toBeNull()
    expect(container.querySelector('.mx-auto')).not.toBeNull()

    // 2. The shared PageHeader emits exactly ONE dominant <h1> reading "GitHub"
    //    (the regression: this route previously kept a bespoke <h1> OUTSIDE the
    //    shared frame, or — half-migrated — had PageContainer but no PageHeader).
    const headings = screen.getAllByRole('heading', { level: 1 })
    expect(headings).toHaveLength(1)
    expect(headings[0].textContent).toContain('GitHub')

    // 3. The header's <h1> lives inside a <header> element — proving it is the
    //    shared <PageHeader>, not hand-rolled heading markup.
    expect(headings[0].closest('header')).not.toBeNull()

    // 4. The page's actions (Docs + the primary Add-integration CTA) render
    //    inside that same header (H5: actions belong to the PageHeader).
    const addBtn = screen.getByTestId('github-add-integration-btn')
    expect(addBtn.closest('header')).not.toBeNull()

    // 5. The breadcrumb survives the migration, above the header.
    expect(screen.getByTestId('github-breadcrumb')).toBeInTheDocument()

    // 6. Hero weight (review thread #1192 — "gateway visually demoted"): this is
    //    the provider GATEWAY, so its brand tile carries the documented 56×56
    //    hero size (vs the 32×32 tile on the per-install detail sibling). We do
    //    NOT revive a bespoke 38px <h1> — H8 keeps the shared header — but the
    //    larger tile restores the parent>child hierarchy. Lock the size so a
    //    future refactor can't silently shrink the gateway back to a flat tile.
    const heroTile = headings[0].querySelector('span[aria-hidden]') as HTMLElement | null
    expect(heroTile).not.toBeNull()
    expect(heroTile?.style.width).toBe('56px')
    expect(heroTile?.style.height).toBe('56px')
  })

  it('pipelines/validate: YAML editor route uses DEFAULT width, not narrow', async () => {
    // Review thread #1192 [sev3/style]: the validate page hosts a full-height
    // YAML textarea; width="narrow" (max-w-3xl ≈ 768px) cramped long pipeline
    // definitions. It must use width="default" (max-w-screen-xl) so the editor
    // has room to breathe — matching the previous max-w-5xl intent.
    const Component = PipelineValidateRoute.options.component as () => React.JSX.Element
    const { container } = renderRoute(Component, { initialPath: '/pipelines/validate' })

    await screen.findByTestId('pipeline-validate-page')
    // Default, NOT narrow — the regression the critic caught.
    expect(container.querySelector('.max-w-screen-xl')).not.toBeNull()
    expect(container.querySelector('.max-w-3xl')).toBeNull()

    // Still framed with a single dominant <h1> from the shared PageHeader.
    const headings = screen.getAllByRole('heading', { level: 1 })
    expect(headings).toHaveLength(1)
    expect(headings[0].textContent).toContain('Validate pipeline')
    expect(headings[0].closest('header')).not.toBeNull()
  })

  it('builds/compare: dense diff route uses the WIDE PageContainer, not narrow', async () => {
    // Mount with NO search params → the missing-params branch renders. We only
    // need the frame's width here; the wide class must be present on EVERY
    // state (it is a dense multi-column diff table, matching the sibling
    // builds/$buildId.compare.$other route).
    const Component = BuildCompareRoute.options.component as () => React.JSX.Element
    const { container } = renderRoute(Component, {
      initialPath: '/builds/compare',
      validateSearch: BuildCompareRoute.options.validateSearch as (
        raw: Record<string, unknown>,
      ) => unknown,
    })

    await screen.findByTestId('compare-missing-params')
    // Wide, NOT narrow — the regression the critic caught was max-w-3xl on a
    // dense diff table.
    expect(container.querySelector('.max-w-screen-2xl')).not.toBeNull()
    expect(container.querySelector('.max-w-3xl')).toBeNull()

    // Still framed with a single dominant <h1> from the shared PageHeader.
    const headings = screen.getAllByRole('heading', { level: 1 })
    expect(headings).toHaveLength(1)
    expect(headings[0].closest('header')).not.toBeNull()
  })
})
