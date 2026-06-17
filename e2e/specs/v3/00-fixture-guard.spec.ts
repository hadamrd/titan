/**
 * 00-fixture-guard — silent-skip prevention for @golden fixture specs.
 *
 * Closes #955: specs 42/43/44 (and any future spec) reference a YAML in
 * `hadamrd/titan-e2e-fixture` via raw.githubusercontent.com. When the fixture
 * doesn't yet exist on the target branch the in-spec
 * `test.skip(meta.status() === 404, ...)` guard SILENTLY skips the test, so
 * the spec ships zero coverage and the rig stays green by accident.
 *
 * This guard scans every `e2e/specs/v3/*.spec.ts`, extracts each
 * `const FIXTURE_PATH = '...'` literal, HEAD-checks the corresponding
 * raw.githubusercontent.com URL on `TITAN_FIXTURE_BRANCH` (default `main`),
 * and HARD-FAILS the run if any returns 404. Dormancy now surfaces as a
 * red guard test, not as silently-passing dormant specs.
 *
 * Scoped intentionally to the fixture repo we own (`hadamrd/titan-e2e-fixture`);
 * specs that reference a different `FIXTURE_REPO` (none today) are out of scope.
 */
import * as fs from 'node:fs'
import * as path from 'node:path'
import { fileURLToPath } from 'node:url'
import { test, expect, type APIRequestContext } from '@playwright/test'

const FIXTURE_REPO = 'hadamrd/titan-e2e-fixture'
const FIXTURE_BRANCH = process.env.TITAN_FIXTURE_BRANCH ?? 'main'

// ESM scope (package.json "type": "module") — bare `__dirname` is undefined under the pinned
// Playwright 1.60 loader, which silently turned this guard RED. Derive it from import.meta.url
// like the other v3 specs (see 20-real-pipeline-end-to-end.spec.ts).
const THIS_DIR = path.dirname(fileURLToPath(import.meta.url))

interface SpecFixtureRef {
  specFile: string
  fixturePath: string
  rawUrl: string
}

function collectFixtureRefs(): SpecFixtureRef[] {
  const specsDir = path.resolve(THIS_DIR)
  const refs: SpecFixtureRef[] = []
  const seen = new Set<string>()
  for (const name of fs.readdirSync(specsDir)) {
    if (!name.endsWith('.spec.ts')) continue
    if (name.startsWith('00-fixture-guard')) continue
    const full = path.join(specsDir, name)
    const src = fs.readFileSync(full, 'utf8')
    if (!src.includes(FIXTURE_REPO)) continue
    // Require both markers so we only pick up specs that fetch FROM the fixture
    // repo (not specs that merely mention the repo name in a comment).
    if (!/raw\.githubusercontent\.com/.test(src)) continue
    const m = /^const FIXTURE_PATH\s*=\s*'([^']+)'/m.exec(src)
    const fixturePath = m?.[1]
    if (fixturePath === undefined) continue
    const rawUrl = `https://raw.githubusercontent.com/${FIXTURE_REPO}/${FIXTURE_BRANCH}/${fixturePath}`
    const key = `${name}::${fixturePath}`
    if (seen.has(key)) continue
    seen.add(key)
    refs.push({ specFile: name, fixturePath, rawUrl })
  }
  return refs.sort((a, b) => a.specFile.localeCompare(b.specFile))
}

async function headCheck(request: APIRequestContext, url: string): Promise<number> {
  // Use GET (HEAD on raw.githubusercontent.com sometimes returns 405); we
  // discard the body. 200 = present, 404 = dormant.
  const r = await request.get(url, { headers: { 'Cache-Control': 'no-cache' } })
  return r.status()
}

test.describe('fixture-repo guard @golden', () => {
  test('every @golden spec fixture URL must resolve on FIXTURE_BRANCH (no silent SKIP)', async ({ request }) => {
    test.setTimeout(60_000)
    const refs = collectFixtureRefs()
    expect(refs.length, 'no fixture refs detected — collector regex drift').toBeGreaterThan(0)

    const results = await Promise.all(
      refs.map(async (r) => ({ ...r, status: await headCheck(request, r.rawUrl) })),
    )

    const dormant = results.filter((r) => r.status === 404)
    const errors = results.filter((r) => r.status !== 200 && r.status !== 404)

    const fmt = (rs: typeof results) =>
      rs.map((r) => `  - ${r.specFile} -> ${r.fixturePath} (HTTP ${r.status}) ${r.rawUrl}`).join('\n')

    expect(
      dormant,
      `\nDORMANT FIXTURES — these specs will silently SKIP on the live rig:\n${fmt(dormant)}\n\n` +
        `Either merge the upstream fixture PR or remove the spec. Silent SKIP is a regression — see #955.\n`,
    ).toEqual([])

    expect(
      errors,
      `\nFIXTURE PROBE FAILED with non-200/404 status — likely network/GitHub throttling:\n${fmt(errors)}\n`,
    ).toEqual([])
  })
})
