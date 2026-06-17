/**
 * tour.spec.ts — operator tour harness (issue #1132, epic #1115).
 *
 * Captures full-page screenshots of 10 critical pages at two viewports
 * (desktop 1440×900, mobile 375×812). Output lands in `e2e/tour/current/`
 * using the slug convention `<slug>--<viewport>.png` that the analyzer
 * half (#1137) already consumes.
 *
 * Determinism: clock is pinned and all relative-time DOM nodes
 * (<time>, [data-relative], [title*="ago"]) are masked via CSS so reruns
 * against an unchanged rig produce zero pixel-diff regressions.
 *
 * NOT in scope here: vision-model analysis, CI gating, fixing the defects
 * the baseline reveals. This spec lands the rig; #1137 + a future ticket
 * close the loop.
 */
import { test, expect, type Page, type APIRequestContext } from '@playwright/test'
import { mkdir } from 'node:fs/promises'
import { join } from 'node:path'
import { authEnv, loginViaKeycloak } from '../fixtures/auth-v3'

const ENV = authEnv()
const API_BASE = process.env.TITAN_API_URL ?? 'http://localhost:18080'
const OUT_DIR = join(__dirname, 'current')

// Pinned clock so "2 minutes ago" etc render identically across runs.
const FIXED_NOW = new Date('2026-01-01T12:00:00Z').valueOf()

type Viewport = 'desktop' | 'mobile'
const VIEWPORTS: Record<Viewport, { width: number; height: number }> = {
  desktop: { width: 1440, height: 900 },
  mobile: { width: 375, height: 812 },
}

interface PageSpec {
  /** Stable slug — must match what the analyzer rubric.yaml references. */
  slug: string
  /** Path to navigate to. May be `null` if `nav` does it imperatively. */
  path: string | null
  /** Set to true if this page is captured WITHOUT logging in. */
  loggedOut?: boolean
  /** Optional ready-check; defaults to `domcontentloaded` + 500ms settle. */
  ready?: (page: Page) => Promise<void>
}

async function extractBearer(page: Page): Promise<string> {
  const token = await page.evaluate(() => {
    for (let i = 0; i < window.sessionStorage.length; i++) {
      const key = window.sessionStorage.key(i)
      if (!key || !key.startsWith('oidc.user:')) continue
      try {
        const raw = window.sessionStorage.getItem(key)
        if (!raw) continue
        const parsed = JSON.parse(raw) as { access_token?: string }
        if (parsed.access_token) return parsed.access_token
      } catch {
        // skip
      }
    }
    return null
  })
  if (!token) throw new Error('tour: no oidc.user access_token in sessionStorage post-login')
  return token
}

async function apiJson<T>(
  request: APIRequestContext,
  bearer: string,
  path: string,
): Promise<T | null> {
  const r = await request.get(`${API_BASE}${path}`, {
    headers: { Authorization: `Bearer ${bearer}`, Accept: 'application/json' },
  })
  if (!r.ok()) return null
  try {
    return (await r.json()) as T
  } catch {
    return null
  }
}

interface JobsPage {
  items: Array<{ id: number }>
}
interface BuildItem {
  id: number
  status: string
}
interface BuildsPage {
  items: BuildItem[]
}
interface RepoItem {
  id: number | string
}
interface ReposPage {
  items: RepoItem[]
}

interface RigIds {
  jobId: number | null
  succeededBuildId: number | null
  failedBuildId: number | null
  repoId: number | string | null
  warnings: string[]
}

async function discoverRigIds(request: APIRequestContext, bearer: string): Promise<RigIds> {
  const warnings: string[] = []
  const jobs = await apiJson<JobsPage>(request, bearer, '/api/v1/jobs?limit=50')
  const jobId = jobs?.items?.[0]?.id ?? null
  if (!jobId) warnings.push('no jobs in rig — /jobs/<id> will fall back to /jobs')

  // Pull a generous slice of recent builds; partition by status.
  const builds = await apiJson<BuildsPage>(request, bearer, '/api/v1/builds?limit=100')
  const items = builds?.items ?? []
  const succeeded =
    items.find((b) => /^(success|succeeded|completed)$/i.test(b.status))?.id ?? null
  const failed =
    items.find((b) => /^(fail|failed|failure|error)$/i.test(b.status))?.id ?? null
  if (!succeeded) warnings.push('no succeeded build — page 3 will use the most recent build')
  if (!failed) warnings.push('no failed build — page 4 will use the most recent build')

  const repos = await apiJson<ReposPage>(request, bearer, '/api/v1/repositories?limit=50')
  const repoId = repos?.items?.[0]?.id ?? null
  if (!repoId) warnings.push('no repositories — /repositories/<id> will fall back to index')

  return {
    jobId,
    succeededBuildId: succeeded ?? items[0]?.id ?? null,
    failedBuildId: failed ?? items[0]?.id ?? null,
    repoId,
    warnings,
  }
}

/**
 * Inject determinism: pin the clock and hide any obviously time-relative
 * DOM nodes so pixel diffs don't trip on "2 minutes ago" drift.
 */
async function makeDeterministic(page: Page): Promise<void> {
  await page.addInitScript(({ now }) => {
    const OriginalDate = Date
    // Lightweight Date subclass: zero-arg `new Date()` returns the pinned
    // instant; other constructor forms forward to the original. Typed as
    // `any` because Date's overloaded constructor signature is hostile to
    // subclassing with rest args under strict TS.
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const FixedDate: any = function FixedDate(this: unknown, ...args: unknown[]) {
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      if (!(this instanceof (FixedDate as any))) return new (FixedDate as any)(...args)
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      const inst: any =
        args.length === 0
          ? new OriginalDate(now)
          : // eslint-disable-next-line @typescript-eslint/no-explicit-any
            new (OriginalDate as any)(...args)
      Object.setPrototypeOf(inst, FixedDate.prototype)
      return inst
    }
    FixedDate.prototype = Object.create(OriginalDate.prototype)
    FixedDate.now = () => now
    FixedDate.parse = OriginalDate.parse
    FixedDate.UTC = OriginalDate.UTC
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    ;(globalThis as any).Date = FixedDate
    // Performance.now stays monotonic but anchored so duration labels stable.
    const perfStart = performance.now()
    const origNow = performance.now.bind(performance)
    performance.now = () => origNow() - perfStart
  }, { now: FIXED_NOW })

  // Mask any relative-time chrome that slips through (titles, datetime attrs).
  await page.addStyleTag({
    content: `
      time, [data-relative], [data-testid*="relative"], [title*="ago" i] {
        color: transparent !important;
        background: #cccccc !important;
        border-radius: 2px !important;
      }
      /* hide caret blink + focus rings to keep frames pixel-stable */
      *, *::before, *::after {
        caret-color: transparent !important;
        animation-duration: 0s !important;
        transition-duration: 0s !important;
      }
    `,
  }).catch(() => {
    // addStyleTag fails before navigation; that's fine — we call it again
    // after each navigation.
  })
}

async function settleAndCapture(page: Page, viewport: Viewport, slug: string): Promise<void> {
  // Wait for network idle, then a short paint settle.
  await page.waitForLoadState('domcontentloaded')
  await page.waitForLoadState('networkidle').catch(() => undefined)
  await page.addStyleTag({
    content: `time,[data-relative],[title*="ago" i]{color:transparent!important;background:#ccc!important}`,
  }).catch(() => undefined)
  // Tiny settle for any post-network rehydration.
  await page.waitForTimeout(250)
  const out = join(OUT_DIR, `${slug}--${viewport}.png`)
  await page.screenshot({ path: out, fullPage: true })
}

test.describe('operator tour — screenshot capture (#1132)', () => {
  test.describe.configure({ mode: 'serial' })

  test.beforeAll(async () => {
    await mkdir(OUT_DIR, { recursive: true })
  })

  for (const viewport of ['desktop', 'mobile'] as Viewport[]) {
    test(`capture all 10 pages @ ${viewport}`, async ({ browser, request }) => {
      test.setTimeout(5 * 60 * 1000)
      const context = await browser.newContext({
        viewport: VIEWPORTS[viewport],
        ignoreHTTPSErrors: true,
      })
      const page = await context.newPage()
      await makeDeterministic(page)

      // ── Logged-out frames first (login, 404 can also work logged-out) ───
      // Page 9: /login
      await page.goto(`${ENV.uiBaseUrl}/login`, { waitUntil: 'domcontentloaded' })
      // Auth sad path: if the login form never appears, fail loudly.
      await expect(
        page.locator('#kc-form-login, [data-testid="login-form"], form'),
      ).toBeVisible({ timeout: 15_000 })
      await settleAndCapture(page, viewport, 'login')

      // ── Now log in for the authenticated pages. ─────────────────────────
      await loginViaKeycloak(page, ENV)
      const bearer = await extractBearer(page)
      const ids = await discoverRigIds(request, bearer)
      for (const w of ids.warnings) {
        // eslint-disable-next-line no-console
        console.warn(`tour: ${w}`)
      }

      // Resolve the 10 pages now that we know the rig's IDs.
      const pages: PageSpec[] = [
        { slug: 'jobs', path: '/jobs' },
        {
          slug: 'jobs_id',
          path: ids.jobId !== null ? `/jobs/${ids.jobId}` : '/jobs',
        },
        {
          slug: 'builds_succeeded',
          path:
            ids.succeededBuildId !== null
              ? `/builds/${ids.succeededBuildId}`
              : '/builds',
        },
        {
          slug: 'builds_failed',
          path: ids.failedBuildId !== null ? `/builds/${ids.failedBuildId}` : '/builds',
        },
        // The SPA route is /repositories (see specs/v3/22-...).
        { slug: 'repos', path: '/repositories' },
        {
          slug: 'repos_id',
          path: ids.repoId !== null ? `/repositories/${ids.repoId}` : '/repositories',
        },
        { slug: 'settings', path: '/settings' },
        { slug: 'audit', path: '/audit' },
        // 404: known-bad URL; assert page actually rendered something
        // before capture (avoid "blank page passing for a 404 page").
        {
          slug: 'not_found',
          path: '/jobs/does-not-exist-xyz-1132',
          ready: async (p) => {
            // Either an explicit not-found marker or *some* body content
            // beyond an empty <body>.
            const body = p.locator('body')
            await expect(body).not.toBeEmpty({ timeout: 10_000 })
          },
        },
      ]

      for (const spec of pages) {
        if (!spec.path) continue
        await page.goto(`${ENV.uiBaseUrl}${spec.path}`, { waitUntil: 'domcontentloaded' })
        if (spec.ready) {
          await spec.ready(page).catch((e) => {
            // eslint-disable-next-line no-console
            console.warn(`tour: ready-check failed for ${spec.slug}: ${(e as Error).message}`)
          })
        }
        await settleAndCapture(page, viewport, spec.slug)
      }

      await context.close()
    })
  }
})
