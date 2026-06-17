/**
 * cache-headers — verifies the SPA cache contract enforced by the titan-ui
 * nginx config (rig/local/nginx.conf, issue #1207).
 *
 * What #1207 actually fixed: nginx blanketed EVERY response with
 * `Cache-Control: no-store`, including the content-hashed `/assets/*` files —
 * forcing a full JS/CSS re-download on every navigation. The fix caches the
 * hashed assets `immutable` for a year. The HTML document stays `no-store`
 * (already correct: a real user's full reload always re-fetches the current
 * `<script src>`). The originally-reported "stale bundle" was a Playwright
 * browser-context artifact, not a user-facing bug — see #1207's correction
 * comment and docs/ops/runbooks/ui-cache-contract.md.
 *
 *   - index.html (+ SPA deep-link fallback) → `no-store`.
 *   - /assets/<hash>.{js,css} → `public, max-age=31536000, immutable`
 *     (content-hashed, safe to cache for a year) — but NOT on a 404 (a
 *     stale-hash miss must not be cached immutable).
 *
 * Lightweight by design (Playwright-lightweight-checks practice): we read
 * response headers via `page.on('response')` + raw `request` calls — no visual
 * snapshot, no auth (cache headers are emitted by nginx regardless of login).
 *
 * All paths are RELATIVE so the spec resolves against the Playwright-configured
 * `baseURL` (`TITAN_RIG_URL`, see playwright.config.ts) — exactly like every
 * other spec in the suite. No private base-URL env var.
 */
import { test, expect, type Response } from '@playwright/test'

const ASSET_RE = /\/assets\/[^?]*\.(js|css)(\?|$)/i

function cacheControlOf(headers: Record<string, string>): string {
  // header names are lower-cased by Playwright.
  return headers['cache-control'] ?? ''
}

test.describe('cache-headers (#1207)', () => {
  // ── index.html document → no-store (the already-correct policy) ──────────
  // Read via a raw request (like the other header tests) so the assertion is
  // deterministic and not coupled to asset capture: a redirect or asset-load
  // failure cannot mask this check.
  test('the index document is served no-store', async ({ request }) => {
    const resp = await request.get('/')
    expect(resp.status(), 'index document is 200').toBe(200)
    const cc = cacheControlOf(resp.headers())
    expect(cc, `index document Cache-Control was "${cc}"`).toContain('no-store')
  })

  // ── a hashed asset → immutable, year-long max-age ───────────────────────
  // index.html always references at least one hashed JS bundle, which the
  // browser fetches before any client-side auth redirect, so it is captured.
  // Auth on this rig is client-side (TanStack Router + Keycloak JS adapter) —
  // nginx serves index.html directly with NO server-side auth guard / 302, so
  // the assets load before any redirect. If that ever changes (nginx/Javalin
  // returns a 302 → Keycloak before serving the document), the asset capture
  // would silently time out; the `request.get('/')` 200 pre-assertion below
  // turns that into a legible 302 failure instead of an opaque 10s poll timeout.
  test('a hashed /assets/* response is immutable', async ({ page, request }) => {
    expect(
      (await request.get('/')).status(),
      'index document must be served directly (no server-side auth 302)',
    ).toBe(200)

    const responses: Response[] = []
    page.on('response', (r) => responses.push(r))

    await page.goto('/', { waitUntil: 'load' })

    await expect
      .poll(() => responses.some((r) => ASSET_RE.test(r.url())), { timeout: 10_000 })
      .toBe(true)
    const asset = responses.find((r) => ASSET_RE.test(r.url()))
    // Guard the lookup explicitly: if ASSET_RE ever fails to match a future
    // Vite asset URL shape, fail with a legible Playwright assertion instead of
    // a raw TypeError from dereferencing `undefined`.
    expect(asset, 'should have captured an /assets/*.{js,css} response').toBeDefined()
    const assetCache = cacheControlOf(await asset!.allHeaders())
    expect(assetCache, `asset ${asset!.url()} Cache-Control was "${assetCache}"`).toContain(
      'immutable',
    )
    expect(assetCache, 'asset must be cached long-term').toMatch(/max-age=31536000/)
  })

  test('GET /index.html carries no-store AND keeps the CSP + security headers', async ({
    request,
  }) => {
    // Adversarial guard for the nginx `add_header` non-inheritance gotcha:
    // adding `location /assets/` must not strip the security headers from the
    // HTML document. A raw request is the deterministic way to read exactly
    // what the origin emits.
    const resp = await request.get('/index.html')
    expect(resp.status()).toBe(200)
    const h = resp.headers()
    expect(cacheControlOf(h)).toContain('no-store')
    expect(h['content-security-policy'], 'CSP header must survive the asset-split').toBeTruthy()
    expect(h['content-security-policy']).toContain("default-src 'self'")
    expect(h['x-content-type-options']).toBe('nosniff')
    expect(h['referrer-policy']).toBe('no-referrer')
  })

  test('an /assets/* response carries nosniff + referrer-policy but NOT a CSP (no-op on non-HTML)', async ({
    page,
    request,
  }) => {
    // CSP is intentionally omitted from /assets/ — it only governs HTML
    // document contexts and is a no-op on application/javascript|text/css.
    // The other security headers (nosniff, referrer-policy) ARE re-declared
    // there (the `add_header` non-inheritance split, see nginx.conf) and the
    // runbook documents them as required survivors — assert both.
    // Same server-side-redirect guard as the immutable test above: a 200 on
    // `/` proves nginx serves the document directly (no auth 302), so the
    // page-driven asset capture below cannot time out opaquely.
    expect(
      (await request.get('/')).status(),
      'index document must be served directly (no server-side auth 302)',
    ).toBe(200)

    const responses: Response[] = []
    page.on('response', (r) => responses.push(r))
    await page.goto('/', { waitUntil: 'load' })
    await expect
      .poll(() => responses.some((r) => ASSET_RE.test(r.url())), { timeout: 10_000 })
      .toBe(true)
    const asset = responses.find((r) => ASSET_RE.test(r.url()))
    expect(asset, 'should have captured an /assets/*.{js,css} response').toBeDefined()
    const h = await asset!.allHeaders()
    expect(h['x-content-type-options'], 'asset keeps nosniff').toBe('nosniff')
    expect(h['referrer-policy'], 'asset keeps referrer-policy (re-declared in /assets/)').toBe(
      'no-referrer',
    )
    expect(h['content-security-policy'], 'CSP is a no-op on assets — must be absent').toBeFalsy()
  })

  test('a stale-hash asset 404 is NOT marked immutable (the `always` trap)', async ({ request }) => {
    // The `always` flag on a Cache-Control "immutable" would apply it to the
    // 4xx/5xx response too, so a transient stale-hash 404 (the window between
    // a deploy and a user's next full reload) would be cached immutable for a
    // year by browsers / any CDN. We dropped `always`, so the 404 must NOT
    // carry the immutable Cache-Control.
    const resp = await request.get('/assets/this-hash-never-existed-deadbeef.js')
    expect(resp.status(), 'a missing hashed asset 404s (does not fall back to HTML)').toBe(404)
    const cc = cacheControlOf(resp.headers())
    expect(cc, `404 Cache-Control was "${cc}" — must not be immutable`).not.toContain('immutable')
  })

  test('SPA deep-link fallback serves index.html (200) with no-store + CSP', async ({ request }) => {
    // An unknown client route must fall back to index.html, and that fallback
    // response must carry the same no-store + security headers as a direct
    // index.html load.
    const resp = await request.get('/pipelines/this-route-only-exists-client-side', {
      headers: { Accept: 'text/html' },
    })
    expect(resp.status(), 'deep link falls back to index.html (200)').toBe(200)
    const body = await resp.text()
    expect(body, 'fallback body is the SPA shell').toMatch(/<div id="root">|<script/i)
    const h = resp.headers()
    expect(cacheControlOf(h), 'fallback document is no-store').toContain('no-store')
    expect(h['content-security-policy'], 'fallback must keep CSP').toBeTruthy()
    expect(h['x-content-type-options']).toBe('nosniff')
  })
})
