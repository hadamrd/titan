/**
 * The generic scenario runner — design/43 §4 + build step 43-S.
 *
 * Discovers every `scenarios/*.e2e.yaml`; for each, submits the pipeline to
 * the rig, polls the TitanGraphApiAction DAG to completion, and asserts the
 * scenario's `expect` block — overall build result, per-node `flow_nodes`
 * statuses, `ranInParallel` ordering, and console-log inclusion/exclusion.
 *
 * Adding coverage is dropping a YAML file — no test code (design/43 §4).
 * A scenario carrying `pending:` is registered as a skipped test with the
 * rig-fixture it needs spelled out, so the suite stays green and discoverable.
 */
import { test, expect, TitanApi } from './fixtures';
import {
  loadScenarios,
  assertNodes,
  assertRanInParallel,
  assertLog,
} from './fixtures/scenario';
import { RIG_URL } from './fixtures/rig';
import { sweepOrphanedGateBuilds } from './fixtures/sweep';

const scenarios = loadScenarios();

test.describe('Titan scenarios', () => {
  // Startup sweep — a gate scenario pauses a build mid-run; a prior crashed
  // run can leave one stuck "Awaiting approval". Cancel every orphaned gate
  // build before the suite starts so it runs against a known-clean rig.
  test.beforeAll(async ({ request }) => {
    await sweepOrphanedGateBuilds(new TitanApi(request, RIG_URL));
  });

  if (scenarios.length === 0) {
    test('no scenarios discovered', () => {
      throw new Error('scenarios/ contains no *.e2e.yaml files');
    });
  }

  for (const scenario of scenarios) {
    const title = `${scenario.file} — ${scenario.name}`;

    if (scenario.pending) {
      // Registered as a skipped test; the rig-fixture gap is in the title so
      // it is visible in `--list` and the HTML report without running.
      test.skip(`${title} [PENDING: ${scenario.pending.trim()}]`, () => {});
      continue;
    }

    test(title, async ({ titanApi }) => {
      // Job name derived from the file — stable + idempotent across runs.
      const jobName = `e2e-${scenario.file.replace(/\.e2e\.yaml$/, '')}`;

      // Optional self-provisioning preamble (e.g. seeding a credential).
      if (scenario.setup) {
        await titanApi.runGroovy(scenario.setup);
      }

      const build = await titanApi.submitPipeline(jobName, scenario.pipeline);

      // A gate scenario must be unblocked mid-run, then polled to completion.
      // approveGate/rejectGate themselves wait (bounded) for the gate to be
      // paused, read its real node id from the API, POST the decision, and
      // assert the response — so a stuck gate fails fast, never hangs.
      if (scenario.gate) {
        if (scenario.gate.reject) {
          await titanApi.rejectGate(build, scenario.gate.reject);
        } else {
          // Default + explicit `approve:` both approve; the scenario's id (if
          // any) is asserted against the API-read id inside approveGate.
          await titanApi.approveGate(build, scenario.gate.approve);
        }
      }

      await titanApi.pollToCompletion(build);

      const result = await titanApi.buildResult(build);
      expect(result, `${scenario.file}: overall build result`).toBe(scenario.expect.build);

      const nodes = await titanApi.flowNodes(build);
      assertNodes(scenario, nodes);
      assertRanInParallel(scenario, nodes);

      if (scenario.expect.logContains || scenario.expect.logExcludes) {
        const log = await titanApi.consoleLog(build);
        assertLog(scenario, log);
      }
    });
  }
});
