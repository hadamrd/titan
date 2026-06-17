/**
 * Startup orphan sweep — design/43 §0 (a suite must run against a known-clean
 * Titan).
 *
 * Gate E2E tests pause a build mid-run. A prior crashed run — or a run from
 * before the teardown safety net existed — can leave a build stuck "Awaiting
 * approval" indefinitely; every gate run then adds another. This sweep runs at
 * gate-spec startup (`beforeAll`) and cancels every currently non-terminal
 * build on any gate job, so a poisoned rig is cleaned before the suite starts.
 *
 * It is deliberately broad: it sweeps the well-known gate jobs AND any job
 * whose name matches `e2e-*gate*`, so a newly-added gate scenario is covered
 * without editing this list.
 */
import { TitanApi } from './titan-api';
import { RIG_URL } from './rig';

/** Gate jobs known by name — the scenario runner + the UI gate spec. */
const KNOWN_GATE_JOBS = ['e2e-gate-approve', 'e2e-gate-reject', 'e2e-ui-gate'];

/** Any job whose name looks like a gate E2E job. */
const GATE_JOB_PATTERN = /^e2e-.*gate.*$/i;

/**
 * Find every non-terminal build on every gate job and cancel it. Bounded by
 * the shared poll cap inside `cancelBuild`. Safe to call repeatedly — a build
 * already terminal is a no-op.
 *
 * @param api a TitanApi client (any test's, or a throwaway one in a beforeAll)
 */
export async function sweepOrphanedGateBuilds(api: TitanApi): Promise<void> {
  const discovered = await api.jobNamesMatching(GATE_JOB_PATTERN);
  const jobs = Array.from(new Set([...KNOWN_GATE_JOBS, ...discovered]));

  let cancelled = 0;
  for (const job of jobs) {
    const orphans = await api.nonTerminalBuilds(job);
    for (const build of orphans) {
      // eslint-disable-next-line no-console
      console.log(`[sweep] cancelling orphaned build ${build.runPath}`);
      try {
        await api.cancelBuild(build);
        cancelled++;
      } catch (err) {
        // eslint-disable-next-line no-console
        console.warn(`[sweep] failed to cancel ${build.runPath}: ${(err as Error).message}`);
      }
    }
  }
  // eslint-disable-next-line no-console
  console.log(
    `[sweep] complete — ${cancelled} orphaned gate build(s) cancelled at ${RIG_URL}`,
  );
}
