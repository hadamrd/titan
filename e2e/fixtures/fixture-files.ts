/**
 * fixture-files — disk-backed fixture YAML for Layer-1 (@golden) specs (closes #48).
 *
 * The @golden specs drive the local rig with a synthesized, HMAC-signed webhook and an
 * inline `pipelineScript` — nothing in that flow needs GitHub. Fetching the fixture YAML
 * from raw.githubusercontent.com at runtime made every smoke run network-dependent, and
 * GitHub 429-throttled the unauthenticated fetches under the loop's repeated-run pattern.
 *
 * `e2e/fixtures/titan-e2e-fixture/` is the in-repo, byte-identical mirror of
 * `hadamrd/titan-e2e-fixture` (see its README.md for the pinned source commit + re-sync
 * commands). Layer-1 specs read the YAML from that mirror via this module; only Layer-2
 * (@real-commit) specs still talk to the live repo — that layer declares the dependency.
 *
 * `e2e/fixtures/titan-ci-templates/` is the same for the shared-library contract files
 * (`vars/ci.groovy`, `VERSION` at tag v1) that 00-library-drift-guard reconciles against.
 */
import * as fs from 'node:fs'
import * as path from 'node:path'
import { fileURLToPath } from 'node:url'

const THIS_DIR = path.dirname(fileURLToPath(import.meta.url))

/** In-repo mirror of `hadamrd/titan-e2e-fixture` (fixture pipelines). */
export const FIXTURE_MIRROR_DIR = path.join(THIS_DIR, 'titan-e2e-fixture')

/** In-repo mirror of `hadamrd/titan-ci-templates@v1` (shared-library contract files). */
export const LIBRARY_MIRROR_DIR = path.join(THIS_DIR, 'titan-ci-templates')

/**
 * Read a fixture pipeline's YAML from the vendored mirror. `fixturePath` is the
 * path INSIDE the fixture repo (e.g. `.titan/pipelines/simple-build.yml`) — the same
 * literal the spec previously interpolated into a raw.githubusercontent.com URL.
 *
 * Throws (hard-fails the spec) when the file is missing or trivially empty: a missing
 * vendored fixture is a repo defect, never a reason to silently skip — the
 * 00-fixture-guard spec enforces presence for every referenced path.
 */
export function readFixtureYaml(fixturePath: string): string {
  const full = path.join(FIXTURE_MIRROR_DIR, fixturePath)
  if (!fs.existsSync(full)) {
    throw new Error(
      `vendored fixture missing: ${full} — the spec references '${fixturePath}' but ` +
        `e2e/fixtures/titan-e2e-fixture/ does not carry it. Re-sync the mirror from ` +
        `hadamrd/titan-e2e-fixture (see its README.md) and commit the file.`,
    )
  }
  const yaml = fs.readFileSync(full, 'utf8')
  if (yaml.trim().length < 30) {
    throw new Error(
      `vendored fixture ${full} is empty/truncated (${yaml.length} bytes) — re-sync the mirror.`,
    )
  }
  return yaml
}

/** Read a shared-library contract file (e.g. `vars/ci.groovy`, `VERSION`) from the vendored mirror. */
export function readLibraryFile(filePath: string): string {
  const full = path.join(LIBRARY_MIRROR_DIR, filePath)
  if (!fs.existsSync(full)) {
    throw new Error(
      `vendored library file missing: ${full} — re-sync from hadamrd/titan-ci-templates@v1 ` +
        `(see e2e/fixtures/titan-ci-templates/README.md) and commit the file.`,
    )
  }
  return fs.readFileSync(full, 'utf8')
}
