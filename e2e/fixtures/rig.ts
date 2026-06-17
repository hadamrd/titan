/**
 * Rig lifecycle — design/43 §3.
 *
 * The harness owns an ephemeral docker-compose stack based on the existing
 * `rig/local/` compose (the Titan controller + Postgres + a Titan
 * worker). `rigUp()` brings it up and waits for readiness; `rigDown()` tears
 * it down. When TITAN_RIG_URL points at an already-running rig the lifecycle
 * is a no-op — the rig is not ours to manage.
 */
import { execFileSync, spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const HERE = path.dirname(fileURLToPath(import.meta.url));
/** rig/local/ — the compose stack this harness reuses. */
export const RIG_DIR = path.resolve(HERE, '..', '..', 'rig', 'local');

export const RIG_URL = process.env.TITAN_RIG_URL ?? 'http://localhost:18080';
/** Whether the harness owns the rig (brings it up/down itself). */
export const RIG_EPHEMERAL = process.env.TITAN_RIG_EPHEMERAL === '1';

/** Readiness deadline for a cold rig boot — JVM + Flyway + worker registration. */
const READY_TIMEOUT_MS = 6 * 60 * 1000;
const POLL_MS = 5_000;

function compose(args: string[], opts: { ignoreError?: boolean } = {}): void {
  const r = spawnSync('docker', ['compose', ...args], {
    cwd: RIG_DIR,
    stdio: 'inherit',
    env: { ...process.env },
  });
  if (r.status !== 0 && !opts.ignoreError) {
    throw new Error(`docker compose ${args.join(' ')} failed (exit ${r.status})`);
  }
}

async function sleep(ms: number): Promise<void> {
  return new Promise((res) => setTimeout(res, ms));
}

/** True once the controller answers a request — "fully up" per design/43 §3. */
async function controllerReady(): Promise<boolean> {
  try {
    const res = await fetch(`${RIG_URL}/login`, { redirect: 'manual' });
    return res.status > 0 && res.status < 500;
  } catch {
    return false;
  }
}

/**
 * True once the Titan worker has self-registered. The worker appears in
 * the controller's `/computer/` index once it has reported into `titan.agents`
 * (design/28) — that is the channel-less rig's "worker registered" signal.
 */
async function workerRegistered(): Promise<boolean> {
  try {
    const res = await fetch(`${RIG_URL}/computer/api/json?tree=computer[displayName,offline]`);
    if (!res.ok) return false;
    const json = (await res.json()) as { computer?: Array<{ offline?: boolean }> };
    const nodes = json.computer ?? [];
    // built-in node is always present; need at least one extra, online node.
    return nodes.some((c) => c.offline === false) && nodes.length > 1;
  } catch {
    return false;
  }
}

/** Wait for the rig to be fully ready, or throw on timeout. */
export async function waitForRig(): Promise<void> {
  const deadline = Date.now() + READY_TIMEOUT_MS;
  let controller = false;
  let worker = false;
  while (Date.now() < deadline) {
    if (!controller) controller = await controllerReady();
    if (controller && !worker) worker = await workerRegistered();
    if (controller && worker) {
      // eslint-disable-next-line no-console
      console.log(`[rig] ready — controller up + worker registered at ${RIG_URL}`);
      return;
    }
    await sleep(POLL_MS);
  }
  throw new Error(
    `[rig] not ready after ${READY_TIMEOUT_MS / 1000}s ` +
      `(controller=${controller}, worker=${worker}, url=${RIG_URL})`,
  );
}

/**
 * Bring the ephemeral rig up. Requires that Maven has already built
 * `target/release-flow.hpi` and `titan-worker/target/titan-worker.jar` — the
 * compose stack stages those (the CI job's build stage produces them; locally
 * `pwsh dev.ps1 up` does). No-op when pointed at an existing rig.
 */
export async function rigUp(): Promise<void> {
  if (!RIG_EPHEMERAL) {
    // eslint-disable-next-line no-console
    console.log(`[rig] using existing rig at ${RIG_URL} (set TITAN_RIG_EPHEMERAL=1 to own it)`);
    await waitForRig();
    return;
  }
  // eslint-disable-next-line no-console
  console.log(`[rig] bringing ephemeral stack up from ${RIG_DIR}`);
  // -v on a prior down already wiped volumes; start clean.
  compose(['up', '-d', '--build']);
  await waitForRig();
}

/** Tear the ephemeral rig down, wiping volumes so the next run starts clean. */
export async function rigDown(): Promise<void> {
  if (!RIG_EPHEMERAL) return;
  // eslint-disable-next-line no-console
  console.log('[rig] tearing ephemeral stack down (-v wipes volumes)');
  compose(['down', '-v'], { ignoreError: true });
}

/** Whether `docker` is on PATH — a friendly preflight for the ephemeral path. */
export function dockerAvailable(): boolean {
  try {
    execFileSync('docker', ['--version'], { stdio: 'ignore' });
    return true;
  } catch {
    return false;
  }
}
