/**
 * UI-flow spec — the JUnit Test Result page on a TitanRun (design/32 §12).
 *
 * The generic scenario runner asserts the build's overall result and log; it
 * does NOT reach the dedicated Test Result page. This spec covers the gap: it
 * submits a pipeline whose `junit` step archives a mixed surefire report, then
 * asserts the build's `testReport/api/json` reports the right pass/fail/skip
 * counts — proving the controller-side TitanJUnitTestResultFactory parsed the
 * archived reports and attached a working TitanTestResultAction.
 *
 * Adding this to the YAML scenario runner would need non-trivial harness work
 * (a new generic `testReport` assertion shape); a hand-written spec that hits
 * the build's own `testReport/api/json` endpoint is the cheaper, cleaner route.
 */
import { test, expect } from '../fixtures';
import { RIG_URL } from '../fixtures/rig';

// The junit plugin's TestResult api/json exports passCount/failCount/skipCount;
// it does NOT export a totalCount field — the total is their sum.
interface JUnitTestResult {
  passCount: number;
  failCount: number;
  skipCount: number;
}

test.describe('UI — JUnit Test Result page', () => {
  test('a junit build exposes testReport/api/json with the right counts', async ({
    request,
    titanApi,
  }) => {
    const build = await titanApi.submitPipeline(
      'e2e-ui-junit-report',
      [
        'agent: titan-worker-1',
        'stages:',
        '  - stage: report',
        '    steps:',
        '      - sh: |',
        '          mkdir -p target/surefire-reports',
        "          cat > target/surefire-reports/TEST-app.MixedTest.xml <<'XML'",
        '          <?xml version="1.0" encoding="UTF-8"?>',
        '          <testsuite name="app.MixedTest" tests="4">',
        '          <testcase name="passes" classname="app.MixedTest"/>',
        '          <testcase name="alsoPasses" classname="app.MixedTest"/>',
        '          <testcase name="breaks" classname="app.MixedTest"><failure>boom</failure></testcase>',
        '          <testcase name="wip" classname="app.MixedTest"><skipped/></testcase>',
        '          </testsuite>',
        '          XML',
        '      - junit: "**/surefire-reports/TEST-*.xml"',
      ].join('\n'),
    );

    // Drive the build to completion so every report is archived and parsed.
    const graph = await titanApi.pollToCompletion(build);
    expect(graph.complete).toBe(true);

    // The Test Result page is the controller's TitanTestResultAction; its
    // api/json is the junit plugin's own TestResultAction export.
    const res = await request.get(`${RIG_URL}${build.runPath}testReport/api/json`);
    expect(res.ok(), `testReport/api/json must exist (HTTP ${res.status()})`).toBe(true);

    const result = (await res.json()) as JUnitTestResult;
    expect(result.passCount).toBe(2);
    expect(result.failCount).toBe(1);
    expect(result.skipCount).toBe(1);
    expect(
      result.passCount + result.failCount + result.skipCount,
      'mixed report: 4 testcases total',
    ).toBe(4);
  });
});
