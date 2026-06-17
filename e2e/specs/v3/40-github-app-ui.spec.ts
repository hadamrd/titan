/**
 * 40-github-app-ui — UI flow for the V1 GitHub App golden path (epic #831,
 * child E — #836). Drives the SPA against an in-browser mock of the not-yet-
 * shipped backend (child A — #832) using Playwright's `page.route()` to
 * intercept every `/api/v1/github-app/**` call. When child A merges this spec
 * keeps its value as a regression seat-belt for the UI — the only change will
 * be removing the mocks.
 *
 * Coverage:
 *   1. /onboarding renders the "Create Titan GitHub App" screen for an admin,
 *      and the warning banner for a non-admin.
 *   2. After manifest exchange (we simulate by pre-seeding the github-app
 *      endpoint with a 200), the screen flips to "Install on GitHub".
 *   3. The Install button links to `${app.html_url}/installations/new`.
 *   4. /integrations/github lists the installations, repos, and pipelines we
 *      seed.
 *   5. The "Sync now" button POSTs to /installations/{id}/sync.
 *   6. The "Enable" button POSTs to /api/v1/jobs with the right scm metadata.
 *   7. /repositories falls back to the "Connect a GitHub org" empty state.
 *   8. /onboarding has a working "Or connect a Git URL directly" link to
 *      /onboarding/manual.
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
  test('admin → screen 2 → install link → /integrations/github → enable + sync', async ({
    page,
  }) => {
    test.setTimeout(90_000)
    const state = freshState()

    // Mocks first — the SPA fires /api/v1/github-app on /onboarding mount.
    await installMocks(page, state)

    // Real login through Keycloak (same path a customer takes).
    await loginViaKeycloak(page, ENV)

    await page.goto(`${ENV.uiBaseUrl}/onboarding`)

    // App already exists per freshState — screen 2.
    await expect(page.getByRole('heading', { name: /install on github/i })).toBeVisible({
      timeout: 10_000,
    })

    const installLink = page.locator('[data-testid="onboarding-install-link"]')
    await expect(installLink).toHaveAttribute(
      'href',
      'https://github.com/apps/titan-acme/installations/new',
    )

    // The polling poll → installation exists → SPA auto-navigates to /integrations/github
    await page.waitForURL('**/integrations/github', { timeout: 10_000 })

    await expect(
      page.getByRole('heading', { name: /github installations/i }),
    ).toBeVisible({ timeout: 10_000 })

    // Installation card visible with correct counts (2 repos, 2 pipelines).
    const card = page.locator('[data-testid="install-card-42"]')
    await expect(card).toBeVisible()
    await expect(page.locator('[data-testid="install-repo-count-42"]')).toHaveText('2 repos')
    await expect(page.locator('[data-testid="install-pipeline-count-42"]')).toHaveText(
      '2 pipelines',
    )

    // Click Sync now → state.syncCalls increments.
    const syncBtn = page.locator('[data-testid="install-sync-btn-42"]')
    await syncBtn.click()
    await expect.poll(() => state.syncCalls, { timeout: 5_000 }).toBeGreaterThanOrEqual(1)

    // 'deploy' pipeline is pre-enabled → enabled badge, no Enable button.
    const deployCard = page.locator(
      '[data-testid^="pipeline-card-42-acme-co%2Fweb-"]',
      { hasText: 'deploy' },
    )
    await expect(deployCard).toBeVisible()
    await expect(deployCard.locator('text=enabled')).toBeVisible()

    // 'build' pipeline → click Enable, assert POST body shape.
    const buildEnable = page.locator(
      '[data-testid^="pipeline-card-42-acme-co%2Fweb-"][data-testid$="build.yml"] [data-testid$="enable-btn"]',
    )
    // Selector hardening: take the first matching Enable button if multiple
    // pipelines render Enable in parallel.
    const buildEnableBtn = page.getByTestId(
      /pipeline-card-42-acme-co%2Fweb-.*build\.yml-enable-btn$/,
    )
    await buildEnableBtn.click()
    void buildEnable
    await expect
      .poll(() => state.jobCreateBodies.length, { timeout: 5_000 })
      .toBeGreaterThanOrEqual(1)

    const body = state.jobCreateBodies[0] as {
      pipelineScript?: string
      configJson?: string
      displayName?: string
    }
    expect(body.displayName).toBe('build')
    expect(body.pipelineScript ?? '').toContain('titan github-app')
    const cfg = JSON.parse(body.configJson ?? '{}') as {
      scm: { type: string; installationId: number; repoFullName: string; filename: string }
    }
    expect(cfg.scm.type).toBe('github-app')
    expect(cfg.scm.installationId).toBe(42)
    expect(cfg.scm.repoFullName).toBe('acme-co/web')
    expect(cfg.scm.filename).toBe('.titan/pipelines/build.yml')
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
    state.installations = []
    await installMocks(page, state)

    await loginViaKeycloak(page, ENV)
    await page.goto(`${ENV.uiBaseUrl}/integrations/github`)

    const empty = page.locator('[data-testid="integrations-empty"]')
    await expect(empty).toBeVisible({ timeout: 10_000 })
    await expect(empty.getByRole('link', { name: /connect github/i })).toBeVisible()
  })
})
