/**
 * 48-multi-repo-checkout — prove a single build spanning TWO source repos on the rig
 * (closes #1228, test matrix #3).
 *
 * Every other fixture clones exactly one repo. This one drives
 * `hadamrd/titan-e2e-fixture/.titan/pipelines/multi-repo-checkout.yml`, which:
 *
 *   - checks out the app repo (this fixture) via the `checkout:` step, then
 *   - clones a SECOND repo (hadamrd/titan-ci-templates) into a sub-dir, then
 *   - a build step reads a file from EACH workspace and writes
 *     `MULTIREPO_OK_<version>` to multi-out.txt — where <version> comes from the
 *     SECOND repo's VERSION file, so the marker can only be produced by reading both.
 *
 * Asserting `MULTIREPO_OK_v1` in the archived artifact proves both repos were checked out and
 * read in one build. (The `checkout:` step clones into the workspace root today, so the second
 * repo arrives via `git clone` into a sub-dir; a first-class `checkout: { dir }` is a #1228
 * follow-up.)
 */
import { test, expect, type Page } from '@playwright/test'
import { authEnv, loginViaKeycloak } from '../../fixtures/auth-v3'
import { extractAccessToken, runFixtureBuildAndFetchArtifact } from '../../fixtures/golden-build'
import { readFixtureYaml } from '../../fixtures/fixture-files'

const ENV = authEnv()
const FIXTURE_REPO = 'hadamrd/titan-e2e-fixture'
const FIXTURE_BRANCH = process.env.TITAN_FIXTURE_BRANCH ?? 'main'
// Read from the vendored mirror (#48) — hermetic; the build itself clones the LIVE
// second repo (titan-ci-templates), which is this spec's declared network edge.
const FIXTURE_PATH = '.titan/pipelines/multi-repo-checkout.yml'

// Composed from BOTH repos: the literal prefix + the SECOND repo's VERSION file content.
const MULTIREPO_MARKER = 'MULTIREPO_OK_v1'

const RUN_TAG = `multi-repo-${Date.now()}-${Math.floor(Math.random() * 1_000_000)}`

test.describe('v3 multi-repo-checkout @golden', () => {
  test('a build checks out app repo + a second repo and reads from both', async ({
    page,
    request,
  }: {
    page: Page
    request: import('@playwright/test').APIRequestContext
  }) => {
    test.setTimeout(200_000)

    const fixtureYaml = readFixtureYaml(FIXTURE_PATH)
    // Sanity: the fixture really references two distinct repos.
    expect(fixtureYaml, 'fixture must check out the app repo').toMatch(/titan-e2e-fixture/)
    expect(fixtureYaml, 'fixture must reference the second repo').toMatch(/titan-ci-templates/)
    expect(fixtureYaml).toMatch(/checkout:/)

    await loginViaKeycloak(page, ENV)
    const bearer = await extractAccessToken(page)

    const { text, buildId } = await runFixtureBuildAndFetchArtifact({
      request,
      page,
      bearer,
      fixtureRepo: FIXTURE_REPO,
      fixtureBranch: FIXTURE_BRANCH,
      fixtureYaml,
      runTag: RUN_TAG,
      artifactNameRe: /multi-out\.txt/,
    })

    expect(
      text,
      `multi-out.txt on build ${buildId} did not contain "${MULTIREPO_MARKER}" ` +
        `(got: ${JSON.stringify(text.slice(0, 200))}). The build did not successfully read from ` +
        `both the app checkout and the second repo's workspace.`,
    ).toContain(MULTIREPO_MARKER)
  })
})
