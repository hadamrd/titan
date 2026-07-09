/**
 * 40-github-app-ui — UI flow for the V1 GitHub App golden path (epic #831,
 * child E — #836). Drives the SPA against an in-browser mock of the not-yet-
 * shipped backend (child A — #832) using Playwright's `page.route()` to
 * intercept every `/api/v1/github-app/**` call. When child A merges this spec
 * keeps its value as a regression seat-belt for the UI — the only change will
 * be removing the mocks.
 *
 * Coverage (rewritten for Design 66 — Refs #52):
 *   1. /onboarding renders the "Create Titan GitHub App" screen (screen 1)
 *      when no App exists, with the manual fallback link reachable.
 *   2. With an App + an installation present, /onboarding auto-navigates to
 *      /integrations/github — screen 2 never lingers (Design 66), so its
 *      heading is deliberately NOT asserted.
 *   3. /integrations/github lists installations as hairline rows
 *      (`install-row-*`) with repo counts; the row meta link drills into the
 *      per-install detail page /integrations/github/:installId.
 *   4. The detail page shows repos + discovered pipelines. Every discovered
 *      pipeline is auto-enabled by the scanner: cards render an informational
 *      "active" pill and NO per-pipeline Enable button exists (Design 66
 *      removed the enable flow). Repos without pipelines hide behind the
 *      "Show all" toggle.
 *   5. The "Sync now" button (`install-sync-btn-*`) POSTs to
 *      /installations/{id}/sync.
 *   6. Regression guard: the integrations surface never POSTs /api/v1/jobs
 *      (job rows are created server-side by the scanner, not by the UI).
 *   7. /integrations/github falls back to the "Set up Titan GitHub App" empty
 *      state when no App and no installs exist.
 *   8. /onboarding has a working "Or connect a Git URL directly" link to
 *      /onboarding/manual.
 *
 * Required env (#50): NONE beyond the local rig (`task dev:titan`). Every
 * `/api/v1/github-app/**` call is mocked via `page.route()` — this spec needs
 * NO GitHub App registration, installation, or webhook tunnel. Only the
 * standard rig env applies (TITAN_UI_URL, TITAN_KEYCLOAK_URL, TITAN_DEV_USER /
 * TITAN_DEV_PASSWORD — see fixtures/auth-v3.ts defaults).
 *
 * @tag @golden
 */
import { test, expect, type Page, type Route } from '@playwright/test'
import { authEnv, loginViaKeycloak } from '../../fixtures/auth-v3'

const ENV = authEnv()

interface MockState {
  app: { id: number; appId: number; slug: string; name: string; htmlUrl: string; createdAt: string } | null
  installations: Array<{
    id: number
    githubInstallationId: number
    accountLogin: string
    accountType: 'Organization' | 'User'
    suspended: boolean
    createdAt: string
    repos: Array<{
      fullName: string
      defaultBranch: string
      htmlUrl: string
      pipelines: Array<{
        filename: string
        name: string
        stagesCount: number
        triggers: string[]
        paramsCount: number
        enabled: boolean
        jobId?: number | null
      }>
    }>
  }>
  syncCalls: number
  jobCreateBodies: unknown[]
}

function freshState(): MockState {
  return {
    app: {
      id: 1,
      appId: 999001,
      slug: 'titan-acme',
      name: 'Titan @ acme',
      htmlUrl: 'https://github.com/apps/titan-acme',
      createdAt: '2026-05-24T10:00:00Z',
    },
    installations: [
      {
        id: 42,
        githubInstallationId: 7777,
        accountLogin: 'acme-co',
        accountType: 'Organization',
        suspended: false,
        createdAt: '2026-05-24T10:05:00Z',
        repos: [
          {
            fullName: 'acme-co/web',
            defaultBranch: 'main',
            htmlUrl: 'https://github.com/acme-co/web',
            pipelines: [
              {
                filename: '.titan/pipelines/build.yml',
                name: 'build',
                stagesCount: 3,
                triggers: ['push', 'pull_request'],
                paramsCount: 0,
                enabled: false,
              },
              {
                filename: '.titan/pipelines/deploy.yml',
                name: 'deploy',
                stagesCount: 5,
                triggers: ['push'],
                paramsCount: 2,
                enabled: true,
                jobId: 101,
              },
            ],
          },
          {
            fullName: 'acme-co/cli',
            defaultBranch: 'main',
            htmlUrl: 'https://github.com/acme-co/cli',
            pipelines: [],
          },
        ],
      },
    ],
    syncCalls: 0,
    jobCreateBodies: [],
  }
}

/**
 * Install route handlers BEFORE navigating. Order matters — more-specific
 * matchers must register first so Playwright dispatches them.
 */
async function installMocks(page: Page, state: MockState) {
  await page.route('**/api/v1/github-app', (route: Route) => {
    if (state.app === null) {
      return route.fulfill({
        status: 404,
        contentType: 'application/json',
        body: JSON.stringify({ title: 'Not Found', status: 404 }),
      })
    }
    return route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(state.app),
    })
  })

  await page.route('**/api/v1/github-app/manifest-callback*', (route: Route) => {
    state.app = state.app ?? {
      id: 1,
      appId: 999001,
      slug: 'titan-acme',
      name: 'Titan @ acme',
      htmlUrl: 'https://github.com/apps/titan-acme',
      createdAt: new Date().toISOString(),
    }
    return route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(state.app),
    })
  })

  await page.route('**/api/v1/github-app/installations/*/sync', (route: Route) => {
    state.syncCalls += 1
    const inst = state.installations[0]
    return route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(inst),
    })
  })

  await page.route('**/api/v1/github-app/installations', (route: Route) => {
    return route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(state.installations),
    })
  })

  // Intercept /api/v1/jobs POST only — let GETs fall through to the real rig
  // so the rest of the app keeps working (jobs list, sidebar starred query…).
  await page.route('**/api/v1/jobs', (route: Route) => {
    if (route.request().method() === 'POST') {
      let body: unknown = null
      try {
        body = JSON.parse(route.request().postData() ?? '{}')
      } catch {
        body = null
      }
      state.jobCreateBodies.push(body)
      return route.fulfill({
        status: 201,
        contentType: 'application/json',
        body: JSON.stringify({
          id: 12345,
          fullName: 'acme-co-web-build',
          displayName: 'build',
          folderPath: null,
          enabled: true,
          createdAt: new Date().toISOString(),
          updatedAt: new Date().toISOString(),
        }),
      })
    }
    return route.fallback()
  })
}

test.describe('@golden v3 github-app-ui', () => {
  test('admin → onboarding auto-navigates → install detail: active pipelines + sync', async ({
    page,
  }) => {
    test.setTimeout(90_000)
    const state = freshState()

    // Mocks first — the SPA fires /api/v1/github-app on /onboarding mount.
    await installMocks(page, state)

    // Real login through Keycloak (same path a customer takes).
    await loginViaKeycloak(page, ENV)

    await page.goto(`${ENV.uiBaseUrl}/onboarding`)

    // App + installation both exist per freshState → the installation poll
    // resolves immediately and /onboarding auto-navigates to
    // /integrations/github (Design 66). Screen 2 never lingers, so we follow
    // the redirect instead of asserting its heading.
    await page.waitForURL('**/integrations/github', { timeout: 15_000 })

    // Index page (Design 66 IA): installations render as hairline rows.
    await expect(page.locator('[data-testid="install-row-42"]')).toBeVisible({
      timeout: 10_000,
    })
    await expect(page.locator('[data-testid="install-repo-count-42"]')).toHaveText('2 repos')

    // Drill into the per-install detail page — pipeline management (repo
    // expansion, pipeline cards, "Sync now") moved here in Design 66.
    await page.locator('[data-testid="install-row-meta-42"]').click()
    await page.waitForURL('**/integrations/github/42', { timeout: 10_000 })

    const card = page.locator('[data-testid="install-card-42"]')
    await expect(card).toBeVisible({ timeout: 10_000 })
    await expect(page.locator('[data-testid="install-repo-count-42"]')).toHaveText('2 repos')
    await expect(page.locator('[data-testid="install-pipeline-count-42"]')).toHaveText(
      '2 pipelines',
    )

    // acme-co/web carries pipelines → shown (and expanded) by default;
    // acme-co/cli has none → hidden until the "Show all repos" toggle.
    const webRepo = encodeURIComponent('acme-co/web')
    const cliRepo = encodeURIComponent('acme-co/cli')
    await expect(page.locator(`[data-testid="repo-42-${webRepo}"]`)).toBeVisible()
    await expect(page.locator(`[data-testid="repo-42-${cliRepo}"]`)).toHaveCount(0)
    await page.locator('[data-testid="install-toggle-all-42"]').click()
    await expect(page.locator(`[data-testid="repo-42-${cliRepo}"]`)).toBeVisible()

    // Design 66: every discovered pipeline is auto-enabled by the scanner.
    // Both cards render the informational "active" pill — and NO per-pipeline
    // Enable button exists anywhere on the page (removed product behavior).
    const buildId = `pipeline-card-42-${webRepo}-${encodeURIComponent('.titan/pipelines/build.yml')}`
    const deployId = `pipeline-card-42-${webRepo}-${encodeURIComponent('.titan/pipelines/deploy.yml')}`
    await expect(page.locator(`[data-testid="${buildId}"]`)).toBeVisible()
    await expect(page.locator(`[data-testid="${buildId}-active"]`)).toHaveText(/active/)
    await expect(page.locator(`[data-testid="${deployId}"]`)).toBeVisible()
    await expect(page.locator(`[data-testid="${deployId}-active"]`)).toHaveText(/active/)
    await expect(page.locator('[data-testid$="-enable-btn"]')).toHaveCount(0)

    // Click Sync now → POST /api/v1/github-app/installations/42/sync.
    await page.locator('[data-testid="install-sync-btn-42"]').click()
    await expect.poll(() => state.syncCalls, { timeout: 5_000 }).toBeGreaterThanOrEqual(1)

    // Regression guard for the removed enable flow: the integrations surface
    // must never POST /api/v1/jobs — job rows are created server-side by the
    // scanner, not by the UI.
    expect(state.jobCreateBodies).toHaveLength(0)
  })

  test('no app yet → screen 1 + manual fallback link visible', async ({ page }) => {
    test.setTimeout(60_000)
    const state = freshState()
    state.app = null
    state.installations = []
    await installMocks(page, state)

    await loginViaKeycloak(page, ENV)
    await page.goto(`${ENV.uiBaseUrl}/onboarding`)

    await expect(
      page.getByRole('heading', { name: /create the titan github app/i }),
    ).toBeVisible({ timeout: 10_000 })

    // The "Create" button is present (admin status not asserted — depends on
    // the dev user's Keycloak groups). We only assert the screen rendered + the
    // manual-fallback link is reachable.
    await expect(page.locator('[data-testid="onboarding-card"]')).toBeVisible()

    await page.locator('[data-testid="onboarding-manual-toggle"]').click()
    const manualLink = page.locator('[data-testid="onboarding-manual-link"]')
    await expect(manualLink).toBeVisible()
    await expect(manualLink).toHaveAttribute('href', '/onboarding/manual')
  })

  test('no installs → /integrations/github empty state links to /onboarding', async ({
    page,
  }) => {
    test.setTimeout(60_000)
    const state = freshState()
    // No app AND no installs → EmptyInstalls renders the "Set up Titan GitHub
    // App" CTA (github-empty-onboarding) linking to /onboarding. (With an app
    // present it renders the external installations/new link instead — see
    // integrations.github.index.tsx EmptyInstalls.)
    state.app = null
    state.installations = []
    await installMocks(page, state)

    await loginViaKeycloak(page, ENV)
    await page.goto(`${ENV.uiBaseUrl}/integrations/github`)

    const empty = page.locator('[data-testid="integrations-empty"]')
    await expect(empty).toBeVisible({ timeout: 10_000 })
    const onboardingLink = empty.locator('[data-testid="github-empty-onboarding"]')
    await expect(onboardingLink).toBeVisible()
    await expect(onboardingLink).toHaveAttribute('href', '/onboarding')
  })
})
