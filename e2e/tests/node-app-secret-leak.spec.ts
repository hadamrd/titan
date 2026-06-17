/**
 * node-app fixture — adversarial secret-leak guard (issue #1119).
 *
 * Injects a known sentinel value as the NPM_TOKEN secret, runs the full
 * pipeline, captures every streamed log line from every step, and asserts
 * the sentinel STRING NEVER APPEARS in any captured line — even on failure
 * paths.
 *
 * This is the test that would catch a redaction regression (a worker
 * echoing its env at startup, a step printing `env | grep TOKEN`, a stack
 * trace leaking a credential through a typed-error message). The sentinel
 * is structured (sentinel-deadbeef-9f2a) so a partial substring leak still
 * matches.
 *
 * Skipped until #1094 (secret refs) lands.
 */
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { test, expect, TitanApi } from '../fixtures';

const FIXTURE = readFileSync(
  join(__dirname, '..', 'pipelines', 'node-app', 'titan-pipeline.yml'),
  'utf8',
);

const SENTINEL = 'sentinel-deadbeef-9f2a';

test.describe('node-app fixture — secret leak guard', () => {
  test.skip(true, 'fixture-only delivery — unskip when #1094 (secrets) lands');

  test('NPM_TOKEN never appears in step logs', async ({
    titanApi,
  }: {
    titanApi: TitanApi;
  }) => {
    await titanApi.seedSecret('NPM_TOKEN', SENTINEL);

    const build = await titanApi.submitPipeline('e2e-node-app-leak', FIXTURE);
    await titanApi.pollToCompletion(build);

    const log = await titanApi.consoleLog(build);
    const offending = log
      .split('\n')
      .filter((line: string) => line.includes(SENTINEL));

    // ZERO matches — even on a failed build. A single match is a sev1 leak.
    expect(
      offending,
      `NPM_TOKEN sentinel leaked into ${offending.length} log line(s)`,
    ).toEqual([]);
  });
});
