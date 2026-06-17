/**
 * The Titan E2E test fixtures — design/43 §5.
 *
 * Extends Playwright's base `test` with:
 *   - titanApi   — the TitanApi client (DAG API, submit, poll, console, gate).
 *   - rigUrl     — the resolved rig base URL.
 *
 * Specs import `test` / `expect` from here, not from `@playwright/test`.
 *
 * It also installs an `afterEach` SAFETY NET: any build a test submitted that
 * is still non-terminal when the test ends — most importantly a build left
 * "Awaiting approval" because a gate test FAILED before deciding its gate —
 * is cancelled. A failing test must still leave the rig clean (design/43 §0:
 * a suite that rots into a flaky liability is the failure mode this prevents).
 */
import { test as base, expect } from '@playwright/test';
import { TitanApi } from './titan-api';
import { RIG_URL } from './rig';

interface TitanFixtures {
  /** A Titan API client bound to this test's traced `request` context. */
  titanApi: TitanApi;
  /** The rig base URL (TITAN_RIG_URL or the local dev default). */
  rigUrl: string;
}

export const test = base.extend<TitanFixtures>({
  rigUrl: async ({}, use) => {
    await use(RIG_URL);
  },
  titanApi: async ({ request }, use) => {
    const api = new TitanApi(request, RIG_URL);
    await use(api);

    // ── Teardown safety net ─────────────────────────────────────────────
    // After the test body (whether it passed, failed, or threw), cancel any
    // build it created that is still non-terminal. A passing gate test will
    // already have driven its build terminal, so this is usually a no-op;
    // the case it exists for is a gate test that throws BEFORE approving —
    // without this the build sits paused forever and every run adds another.
    for (const build of api.createdBuilds) {
      try {
        await api.cancelBuild(build);
      } catch (err) {
        // Teardown must never mask the test's own failure — log and move on.
        // eslint-disable-next-line no-console
        console.warn(
          `[teardown] could not cancel ${build.runPath}: ${(err as Error).message}`,
        );
      }
    }
  },
});

export { expect };
export { TitanApi, slug } from './titan-api';
export type {
  TitanBuild,
  PipelineGraph,
  GraphStage,
  GraphStep,
  NodeView,
  BuildResult,
} from './titan-api';
