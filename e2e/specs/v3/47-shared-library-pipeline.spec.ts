/**
 * 47-shared-library-pipeline — prove a `libraries:` block fetches + runs real remote library
 * code on the rig (closes #1228, test matrix #2).
 *
 * The consumer pipeline `hadamrd/titan-e2e-fixture/.titan/pipelines/shared-library-build.yml`
 * declares:
 *
 *   libraries:
 *     ci: "https://github.com/hadamrd/titan-ci-templates.git@v1"
 *   stages:
 *     - stage: Build
 *       steps:
 *         - ci.buildAndTest: { profile: fast }   # -> vars/ci.groovy::buildAndTest(Map)
 *         - archiveArtifacts: { artifacts: "lib-out.txt" }
 *
 * `ci.buildAndTest` rewrites into a libraryCall; the worker's LibraryFetcher resolves + caches
 * `titan-ci-templates@v1` and runs `vars/ci.groovy::buildAndTest`, which writes the marker
 * `TITAN_LIB_MARKER_buildAndTest_v1` to `lib-out.txt`. That marker lives ONLY in the library
 * repo — it appears nowhere in the consumer pipeline. So asserting it in the archived artifact
 * proves the library was actually fetched + executed (not inlined): the exact gap #1228 calls out.
 *
 * Webhook → dispatched → build → SUCCESS → artifact bytes is the shared golden-build flow.
 */
import { test, expect, type Page } from '@playwright/test'
import { authEnv, loginViaKeycloak } from '../../fixtures/auth-v3'
import {
  extractAccessToken,
  fetchFixtureYaml,
  runFixtureBuildAndFetchArtifact,
} from '../../fixtures/golden-build'

const ENV = authEnv()
const FIXTURE_REPO = 'hadamrd/titan-e2e-fixture'
const FIXTURE_BRANCH = process.env.TITAN_FIXTURE_BRANCH ?? 'main'
const FIXTURE_PATH = '.titan/pipelines/shared-library-build.yml'
const FIXTURE_RAW_URL = `https://raw.githubusercontent.com/${FIXTURE_REPO}/${FIXTURE_BRANCH}/${FIXTURE_PATH}`

// This marker is defined ONLY in hadamrd/titan-ci-templates vars/ci.groovy — never in the
// consumer pipeline. Observing it in the build artifact is the library-was-fetched proof.
const LIBRARY_ONLY_MARKER = 'TITAN_LIB_MARKER_buildAndTest_v1'

const RUN_TAG = `shared-lib-${Date.now()}-${Math.floor(Math.random() * 1_000_000)}`

test.describe('v3 shared-library-pipeline @golden', () => {
  test('libraries: fetches + runs titan-ci-templates; library-only marker lands in the artifact', async ({
    page,
    request,
  }: {
    page: Page
    request: import('@playwright/test').APIRequestContext
  }) => {
    test.setTimeout(200_000)

    const fixtureYaml = await fetchFixtureYaml(request, FIXTURE_RAW_URL)
    // Sanity: the fixture really does declare a libraries: block pointing at titan-ci-templates,
    // and crucially does NOT inline the marker — otherwise the proof below would be vacuous.
    expect(fixtureYaml, 'fixture must declare a libraries: block').toMatch(/libraries:/)
    expect(fixtureYaml).toMatch(/titan-ci-templates/)
    expect(
      fixtureYaml.includes(LIBRARY_ONLY_MARKER),
      'consumer pipeline must NOT inline the library-only marker — the marker is the library proof',
    ).toBe(false)

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
      artifactNameRe: /lib-out\.txt/,
    })

    expect(
      text,
      `lib-out.txt on build ${buildId} did not contain the library-only marker ` +
        `"${LIBRARY_ONLY_MARKER}" (got: ${JSON.stringify(text.slice(0, 200))}). ` +
        `Either the libraries: fetch did not run vars/ci.groovy::buildAndTest, or the marker drifted.`,
    ).toContain(LIBRARY_ONLY_MARKER)
  })
})
