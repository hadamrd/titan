/**
 * 00-fixture-guard — silent-skip / missing-fixture prevention for @golden fixture specs.
 *
 * History: #955 — specs referencing a YAML in `hadamrd/titan-e2e-fixture` used to fetch it
 * from raw.githubusercontent.com at runtime and `test.skip` on 404, so a deleted upstream
 * file silently zeroed the spec's coverage. This guard then HEAD-checked every referenced
 * URL against live GitHub.
 *
 * #48 — the runtime fetches themselves became a structural flake source: GitHub 429-throttled
 * the unauthenticated raw fetches under the loop's repeated smoke runs. All Layer-1 (@golden)
 * specs now read their fixture YAML from the vendored mirror `e2e/fixtures/titan-e2e-fixture/`
 * (see fixture-files.ts), so this guard is hermetic too: it scans every
 * `e2e/specs/v3/*.spec.ts`, extracts each `const FIXTURE_PATH = '...'` literal, and
 * HARD-FAILS if the vendored file is missing or trivially empty. A dangling reference
 * surfaces as a red guard test in <1s, never as a silently-failing spec deep in a rig run.
 *
 * Scoped intentionally to the fixture repo we mirror (`hadamrd/titan-e2e-fixture`); specs
 * that reference a different `FIXTURE_REPO` (none today) are out of scope. Layer-2
 * (@real-commit) specs drive the LIVE repo and declare no `FIXTURE_PATH` const — they are
 * invisible to this collector by design.
 */
import * as fs from 'node:fs'
import * as path from 'node:path'
import { fileURLToPath } from 'node:url'
import { test, expect } from '@playwright/test'
import { FIXTURE_MIRROR_DIR } from '../../fixtures/fixture-files'

const FIXTURE_REPO = 'hadamrd/titan-e2e-fixture'

// ESM scope (package.json "type": "module") — bare `__dirname` is undefined under the pinned
// Playwright 1.60 loader, which silently turned this guard RED. Derive it from import.meta.url
// like the other v3 specs (see 20-real-pipeline-end-to-end.spec.ts).
const THIS_DIR = path.dirname(fileURLToPath(import.meta.url))

interface SpecFixtureRef {
  specFile: string
  fixturePath: string
  vendoredFile: string
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
    // Only specs that consume the mirrored fixture repo declare a FIXTURE_PATH const;
    // require the repo marker so a stray FIXTURE_PATH in an unrelated spec can't bind
    // this guard to the wrong mirror.
    if (!src.includes(FIXTURE_REPO)) continue
    const m = /^const FIXTURE_PATH\s*=\s*'([^']+)'/m.exec(src)
    const fixturePath = m?.[1]
    if (fixturePath === undefined) continue
    const key = `${name}::${fixturePath}`
    if (seen.has(key)) continue
    seen.add(key)
    refs.push({
      specFile: name,
      fixturePath,
      vendoredFile: path.join(FIXTURE_MIRROR_DIR, fixturePath),
    })
  }
  return refs.sort((a, b) => a.specFile.localeCompare(b.specFile))
}

test.describe('fixture-repo guard @golden', () => {
  test('every @golden spec FIXTURE_PATH must exist in the vendored mirror (no dangling refs)', () => {
    const refs = collectFixtureRefs()
    expect(refs.length, 'no fixture refs detected — collector regex drift').toBeGreaterThan(0)

    const results = refs.map((r) => {
      const exists = fs.existsSync(r.vendoredFile)
      const bytes = exists ? fs.readFileSync(r.vendoredFile, 'utf8').trim().length : 0
      return { ...r, exists, bytes }
    })

    const missing = results.filter((r) => !r.exists)
    const empty = results.filter((r) => r.exists && r.bytes < 30)

    const fmt = (rs: typeof results) =>
      rs.map((r) => `  - ${r.specFile} -> ${r.fixturePath} (${r.vendoredFile})`).join('\n')

    expect(
      missing,
      `\nMISSING VENDORED FIXTURES — these specs will hard-fail at readFixtureYaml():\n${fmt(missing)}\n\n` +
        `Re-sync e2e/fixtures/titan-e2e-fixture/ from ${FIXTURE_REPO} (see its README.md) ` +
        `and commit the file(s). A dangling reference is a repo defect — see #955/#48.\n`,
    ).toEqual([])

    expect(
      empty,
      `\nEMPTY/TRUNCATED VENDORED FIXTURES:\n${fmt(empty)}\n`,
    ).toEqual([])
  })
})
