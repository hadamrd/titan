/**
 * UI-flow spec — the manual-judgement gate approval click-through
 * (design/43 §2 axis A, §4).
 *
 * Submits a pipeline with a gate, waits for the gate to PAUSE awaiting a
 * decision, then drives the approval through the real `input` endpoint the
 * console frontend POSTs to — `<runUrl>input/<gateNodeId>/proceedEmpty`. After
 * approval the downstream stage must run and the build go green.
 *
 * This asserts the gate lifecycle a human experiences: the run holds, a
 * decision is made, the DAG proceeds.
 */
import { test, expect, TitanApi } from '../fixtures';
import { flattenStages } from '../fixtures/titan-api';
import { RIG_URL } from '../fixtures/rig';
import { sweepOrphanedGateBuilds } from '../fixtures/sweep';

test.describe('UI — gate approval', () => {
  // Startup sweep — clear any pre-existing orphaned paused gate builds (e.g.
  // a prior crashed run) so this suite starts against a known-clean rig.
  test.beforeAll(async ({ request }) => {
    await sweepOrphanedGateBuilds(new TitanApi(request, RIG_URL));
  });

  test('gate holds the DAG, then approval lets it proceed', async ({ page, titanApi }) => {
    const build = await titanApi.submitPipeline(
      'e2e-ui-gate',
      [
        'agent: titan-worker-1',
        'stages:',
        '  - stage: build',
        '    steps:',
        '      - sh: echo built',
        '  - gate: Release',
        '    requiresApproval: true',
        '    dependsOn: [build]',
        '  - stage: deploy',
        '    dependsOn: [Release]',
        '    steps:',
        '      - sh: echo deployed',
      ].join('\n'),
    );

    // Open the build page so a trace captures the gate-pending UI state.
    await page.goto(`${build.runPath}`);

    // The gate must reach a PAUSED state — the run holds, not completes.
    // The gate's real Titan node id is read from the API, never guessed.
    const { stage: gate, gateNodeId } = await titanApi.waitForPausedGate(build);
    expect(gate.state).toBe('paused');
    expect(gateNodeId, 'gate node id resolved from the API').toBeTruthy();

    // Before approval the run is NOT complete and `deploy` has not run.
    const before = await titanApi.graph(build);
    expect(before.complete).toBe(false);
    const deployBefore = flattenStages(before.stages).find(
      (s) => s.name.toLowerCase() === 'deploy',
    );
    expect(deployBefore?.state).not.toBe('success');

    // Approve the gate — approveGate re-reads the real node id from the API,
    // POSTs the decision, and asserts the engine's response (fails fast on a
    // non-success status). The expected id `release` is cross-checked.
    await titanApi.approveGate(build, 'release');

    // The DAG proceeds: deploy runs and the build goes green.
    const after = await titanApi.pollToCompletion(build);
    expect(after.complete).toBe(true);
    const deployAfter = flattenStages(after.stages).find(
      (s) => s.name.toLowerCase() === 'deploy',
    );
    expect(deployAfter?.state).toBe('success');

    const result = await titanApi.buildResult(build);
    expect(result).toBe('SUCCESS');
  });
});
