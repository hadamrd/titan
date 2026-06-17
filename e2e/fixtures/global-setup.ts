/**
 * Playwright global setup — design/43 §3. Brings the ephemeral rig up and
 * waits for readiness before any test runs. Only wired when
 * TITAN_RIG_EPHEMERAL=1 (see playwright.config.ts).
 */
import { dockerAvailable, rigUp } from './rig';

export default async function globalSetup(): Promise<void> {
  if (!dockerAvailable()) {
    throw new Error(
      'TITAN_RIG_EPHEMERAL=1 but `docker` is not on PATH. Either install Docker ' +
        'or point the harness at a running rig with TITAN_RIG_URL.',
    );
  }
  await rigUp();
}
