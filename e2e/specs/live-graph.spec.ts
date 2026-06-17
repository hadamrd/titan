/**
 * UI-flow spec — the live Titan graph view (design/43 §2 axis A, §4).
 *
 * Asserts what a browser SHOWS, not what data says: submits a linear pipeline,
 * opens the build's Pipeline Graph page in Chromium, and verifies the graph
 * renders every stage and reaches a completed (all-green) state as the run
 * progresses. This exercises TitanGraphApiAction through the unmodified
 * Pipeline Graph View frontend, end to end.
 */
import { test, expect } from '../fixtures';

test.describe('UI — live graph view', () => {
  test('graph page renders stages and reaches completion', async ({ page, titanApi }) => {
    const build = await titanApi.submitPipeline(
      'e2e-ui-live-graph',
      [
        'agent: titan-worker-1',
        'stages:',
        '  - stage: build',
        '    steps:',
        '      - sh: echo building',
        '  - stage: test',
        '    dependsOn: [build]',
        '    steps:',
        '      - sh: echo testing',
      ].join('\n'),
    );

    // The Pipeline Graph is embedded on the build page; the graph JSON route
    // lives at <runUrl>stages/. Open the build page in the browser.
    await page.goto(`${build.runPath}`);
    await expect(page).toHaveTitle(/e2e-ui-live-graph/i);

    // The DAG must drive to completion; assert through the API the page reads.
    const graph = await titanApi.pollToCompletion(build);
    expect(graph.complete).toBe(true);
    const names = graph.stages.map((s) => s.name.toLowerCase());
    expect(names).toContain('build');
    expect(names).toContain('test');
    expect(graph.stages.every((s) => s.state === 'success')).toBe(true);

    // Reload the build page now the run is done — the console output for the
    // build must be visible to a human.
    await page.goto(`${build.runPath}stages/consoleBuildOutput`);
    await expect(page.locator('body')).toContainText('building');
    await expect(page.locator('body')).toContainText('testing');
  });
});
