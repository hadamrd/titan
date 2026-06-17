/**
 * 00-library-drift-guard — locks the shared-library contract that specs 47 + 48 depend on
 * (closes #1243, acceptance #3).
 *
 * Specs 47 (shared-library) and 48 (multi-repo) assert two HARD-CODED markers in archived
 * build artifacts:
 *
 *   - 47 asserts `TITAN_LIB_MARKER_buildAndTest_v1` lands in lib-out.txt. That marker lives
 *     ONLY in `hadamrd/titan-ci-templates@v1` → `vars/ci.groovy::buildAndTest`.
 *   - 48 asserts `MULTIREPO_OK_v1` lands in multi-out.txt. The `v1` suffix is the literal
 *     content of `hadamrd/titan-ci-templates@v1` → `VERSION` (the build does
 *     `echo MULTIREPO_OK_$(cat VERSION)`).
 *
 * Both markers are a CONTRACT split across two repos: the e2e spec (here) and the library repo
 * (on GitHub). If either side drifts — someone bumps `VERSION` to `v2`, renames the groovy var,
 * or edits the spec constant — the live 47/48 builds would FAIL with an opaque "marker not in
 * artifact" 200s after a webhook, deep in a rig run. This guard surfaces that drift in <5s as a
 * red unit-shaped check BEFORE the expensive rig path, and pins BOTH sides so neither can move
 * silently.
 *
 * Mechanism: extract each spec's marker constant FROM ITS SOURCE (not a copy — so the spec is
 * the single source of truth), fetch the live library repo files, and assert they reconcile.
 * Tag @golden so it runs in the same e2e profile as 47/48.
 */
import * as fs from 'node:fs'
import * as path from 'node:path'
import { fileURLToPath } from 'node:url'
import { test, expect, type APIRequestContext } from '@playwright/test'

// ESM scope (package.json "type": "module") — `__dirname` is undefined under the pinned
// Playwright 1.60 loader; derive it from import.meta.url like the other v3 specs.
const THIS_DIR = path.dirname(fileURLToPath(import.meta.url))

// The library repo + tag the consumer pipelines pin via `...titan-ci-templates.git@v1`.
const LIB_REPO = 'hadamrd/titan-ci-templates'
const LIB_REF = 'v1'
const rawUrl = (file: string) =>
  `https://raw.githubusercontent.com/${LIB_REPO}/${LIB_REF}/${file}`

/** Read a sibling spec's source so we assert against the SPEC's own constant, never a copy. */
function readSpec(name: string): string {
  return fs.readFileSync(path.join(THIS_DIR, name), 'utf8')
}

/** Extract `const <NAME> = '<value>'` from spec source. Throws a precise error if it drifted. */
function specConst(src: string, name: string, specFile: string): string {
  const m = new RegExp(`const\\s+${name}\\s*=\\s*'([^']+)'`).exec(src)
  const value = m?.[1]
  if (value === undefined) {
    throw new Error(
      `drift-guard: could not find \`const ${name} = '...'\` in ${specFile}. ` +
        `The spec was refactored — update 00-library-drift-guard to match, or the guard is blind.`,
    )
  }
  return value
}

// ── Pure reconciliation helpers (testable without the network) ──────────────────────────────
// The whole guard reduces to these two predicates; the live tests below feed them real GitHub
// bytes, the self-check tests below feed them drift scenarios. Keeping them pure means "does
// drift fail the guard?" is provable deterministically, not only against the live repo.

/** spec-47 contract: the library-only marker must appear verbatim in the library's groovy. */
export function libraryMarkerPresent(groovySource: string, marker: string): boolean {
  return groovySource.includes(marker)
}

/** spec-48 contract: the marker the build emits is `MULTIREPO_OK_<trimmed VERSION>`. */
export function expectedMultiRepoMarker(versionFileContent: string): string {
  return `MULTIREPO_OK_${versionFileContent.trim()}`
}

async function fetchText(request: APIRequestContext, url: string): Promise<string> {
  const r = await request.get(url, { headers: { 'Cache-Control': 'no-cache' } })
  // 404 = library file moved/renamed; any non-200 (403 throttle, 5xx) = probe failure. Both are
  // surfaced as a precise failure, never a silent pass.
  expect(
    r.status(),
    `drift-guard: GET ${url} returned HTTP ${r.status()} (expected 200). ` +
      `Either the library repo file moved, the @${LIB_REF} tag was deleted, or GitHub throttled us.`,
  ).toBe(200)
  return r.text()
}

test.describe('shared-library drift-guard @golden', () => {
  // 47: the library-only marker the spec asserts MUST exist verbatim in the library's groovy.
  test('spec-47 library marker still lives in titan-ci-templates vars/ci.groovy', async ({
    request,
  }) => {
    test.setTimeout(30_000)
    const marker = specConst(
      readSpec('47-shared-library-pipeline.spec.ts'),
      'LIBRARY_ONLY_MARKER',
      '47-shared-library-pipeline.spec.ts',
    )
    const groovy = await fetchText(request, rawUrl('vars/ci.groovy'))
    expect(
      libraryMarkerPresent(groovy, marker),
      `drift-guard: spec-47 asserts marker "${marker}" but it is NOT in ` +
        `${LIB_REPO}@${LIB_REF}/vars/ci.groovy. The library was edited or the spec constant drifted; ` +
        `the live 47 build would FAIL with "marker not in lib-out.txt". Reconcile both sides.`,
    ).toBe(true)
  })

  // 48: the multi-repo marker is `MULTIREPO_OK_<VERSION>`; VERSION is the literal in the lib repo.
  test('spec-48 multi-repo marker version still matches titan-ci-templates VERSION', async ({
    request,
  }) => {
    test.setTimeout(30_000)
    const marker = specConst(
      readSpec('48-multi-repo-checkout.spec.ts'),
      'MULTIREPO_MARKER',
      '48-multi-repo-checkout.spec.ts',
    )
    const versionRaw = await fetchText(request, rawUrl('VERSION'))
    expect(versionRaw.trim().length, `drift-guard: ${LIB_REPO}@${LIB_REF}/VERSION is empty`).toBeGreaterThan(
      0,
    )

    const expectedMarker = expectedMultiRepoMarker(versionRaw)
    expect(
      marker,
      `drift-guard: spec-48 asserts "${marker}" but the live build produces ` +
        `"MULTIREPO_OK_$(cat VERSION)" = "${expectedMarker}" (VERSION=${JSON.stringify(versionRaw.trim())}). ` +
        `The library VERSION moved or the spec constant drifted; the live 48 build would FAIL. ` +
        `Reconcile both sides.`,
    ).toBe(expectedMarker)
  })
})

// ── Adversarial self-check: prove DRIFT actually fails the guard (no network) ────────────────
// Testing-manifesto: a guard is only worth shipping if its sad path could fail. These deterministic
// cases prove the reconciliation predicates flip to "fail" the moment EITHER side drifts — the exact
// regressions (#1243 test-matrix "mutating the lib marker/VERSION or the spec constant fails").
test.describe('drift-guard self-check @golden', () => {
  test('libraryMarkerPresent: passes on match, FAILS when the groovy marker is renamed', () => {
    const groovy = 'def buildAndTest(Map a){ sh "echo TITAN_LIB_MARKER_buildAndTest_v1 > lib-out.txt" }'
    // happy: spec constant present verbatim
    expect(libraryMarkerPresent(groovy, 'TITAN_LIB_MARKER_buildAndTest_v1')).toBe(true)
    // drift: library renamed the marker (v1 -> v2) — guard MUST reject
    expect(libraryMarkerPresent(groovy, 'TITAN_LIB_MARKER_buildAndTest_v2')).toBe(false)
    // drift: spec constant typo'd — guard MUST reject
    expect(libraryMarkerPresent(groovy, 'TITAN_LIB_MARKER_buildandtest_v1')).toBe(false)
  })

  test('expectedMultiRepoMarker: derives marker from VERSION, so a VERSION bump breaks the match', () => {
    // happy: VERSION=v1 (trailing newline as raw.githubusercontent serves it) -> spec's MULTIREPO_OK_v1
    expect(expectedMultiRepoMarker('v1\n')).toBe('MULTIREPO_OK_v1')
    expect(expectedMultiRepoMarker('  v1  ')).toBe('MULTIREPO_OK_v1') // tolerant of stray whitespace
    // drift: library bumped VERSION to v2 -> derived marker no longer equals the spec's MULTIREPO_OK_v1
    expect(expectedMultiRepoMarker('v2\n')).not.toBe('MULTIREPO_OK_v1')
    expect(expectedMultiRepoMarker('v2\n')).toBe('MULTIREPO_OK_v2')
  })

  test('specConst: extracts the spec constant, THROWS a precise error when it cannot be found', () => {
    const src = "const LIBRARY_ONLY_MARKER = 'TITAN_LIB_MARKER_buildAndTest_v1'\n"
    expect(specConst(src, 'LIBRARY_ONLY_MARKER', 'x.spec.ts')).toBe('TITAN_LIB_MARKER_buildAndTest_v1')
    // adversarial: the spec was refactored away from the expected `const NAME = '...'` shape —
    // the guard must blow up loudly (blind-guard prevention), never silently pass.
    expect(() => specConst('export const X = `tpl`', 'LIBRARY_ONLY_MARKER', 'x.spec.ts')).toThrow(
      /could not find/,
    )
  })
})
