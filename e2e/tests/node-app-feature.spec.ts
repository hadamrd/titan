/**
 * node-app fixture — skip path on a feature branch (issue #1119).
 *
 * Same fixture, triggered against `feature/x`. Asserts:
 *   - install / lint / unit-test / build => SUCCESS
 *   - deploy-staging / smoke-test       => SKIPPED (not failed, not omitted)
 *
 * `when: branch == 'main'` on deploy-staging must short-circuit; smoke-test
 * gates on deploy-staging.result == 'success', so it ALSO skips (cascade).
 *
 * Skipped until #1093 (when: evaluation) lands.
 */
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { test, expect, TitanApi, slug } from '../fixtures';

const FIXTURE = readFileSync(
  join(__dirname, '..', 'pipelines', 'node-app', 'titan-pipeline.yml'),
  'utf8',
);

test.describe('node-app fixture — feature branch', () => {
  test.skip(true, 'fixture-only delivery — unskip when #1093 (when:) lands');

  test('deploy + smoke are SKIPPED on a non-main branch', async ({
    titanApi,
  }: {
    titanApi: TitanApi;
  }) => {
    const build = await titanApi.submitPipeline('e2e-node-app-feature', FIXTURE, {
      branch: 'feature/x',
    });
    await titanApi.pollToCompletion(build);

    const nodes = await titanApi.flowNodes(build);
    // flowNodes() keys each NodeView by its Titan node id (== slug(stageName)).
    const status = (name: string) => nodes.get(slug(name))?.status;

    expect(status('install')).toBe('SUCCESS');
    expect(status('lint')).toBe('SUCCESS');
    expect(status('unit-test')).toBe('SUCCESS');
    expect(status('build')).toBe('SUCCESS');
    // Adversarial assertions — explicit SKIPPED, not failed, not absent.
    expect(status('deploy-staging')).toBe('SKIPPED');
    expect(status('smoke-test')).toBe('SKIPPED');
  });
});
