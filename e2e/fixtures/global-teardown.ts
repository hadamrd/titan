/**
 * Playwright global teardown — design/43 §3. Tears the ephemeral rig down.
 * Only wired when TITAN_RIG_EPHEMERAL=1 (see playwright.config.ts).
 */
import { rigDown } from './rig';

export default async function globalTeardown(): Promise<void> {
  await rigDown();
}
