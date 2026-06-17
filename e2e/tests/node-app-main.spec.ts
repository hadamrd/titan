/**
 * node-app fixture — happy path on branch `main` (issue #1119).
 *
 * Drives e2e/pipelines/node-app/titan-pipeline.yml against the local rig on
 * branch `main` and asserts that all 6 stages (install, lint, unit-test,
 * build, deploy-staging, smoke-test) terminate SUCCESS.
 *
 * Skipped until #1094 (top-level env: + secret refs) and #1093 (when:
 * evaluation) land. The fixture YAML itself is complete and ready — the
 * spec unblocks the moment those tickets ship.
 */
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { test, expect, TitanApi, slug } from '../fixtures';

const FIXTURE = readFileSync(
  join(__dirname, '..', 'pipelines', 'node-app', 'titan-pipeline.yml'),
  'utf8',
);

const EXPECTED_STAGES = [
  'install',
  'lint',
  'unit-test',
  'build',
  'deploy-staging',
  'smoke-test',
];

test.describe('node-app fixture — branch main', () => {
  test.skip(
    true,
    'fixture-only delivery — unskip when #1094 (env+secrets) and #1093 (when) land',
  );

  test('all 6 stages succeed on main', async ({ titanApi }: { titanApi: TitanApi }) => {
    const build = await titanApi.submitPipeline('e2e-node-app-main', FIXTURE);
    await titanApi.pollToCompletion(build);

    const result = await titanApi.buildResult(build);
    expect(result).toBe('SUCCESS');

    const nodes = await titanApi.flowNodes(build);
    for (const stage of EXPECTED_STAGES) {
      // flowNodes() keys each NodeView by its Titan node id (== slug(stageName)).
      const node = nodes.get(slug(stage));
      expect(node, `stage ${stage} should be in the DAG`).toBeDefined();
      expect(node?.status, `stage ${stage} should be SUCCESS`).toBe('SUCCESS');
    }
  });
});
