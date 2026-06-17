#!/usr/bin/env node
/*
 * build-fixtures.mjs — build the Tier-2 SPI-step fixture jar and place it where
 * the rig worker discovers it (design/42 §4.3).
 *
 * The fixture jar (titan-e2e-spi-step.jar) is a build artifact: it is git-ignored
 * and must NOT be committed. This script makes its production reproducible with
 * one command — `npm run build:fixtures` from e2e/ — so the spi-step
 * scenario stays committed-reproducible without a manual copy step.
 *
 * What it does:
 *   1. Ensure titan-step-api is installed in the local Maven repo (the fixture
 *      compiles against it, `provided` scope).
 *   2. `mvn -q -o package` the fixture module -> target/titan-e2e-spi-step.jar.
 *   3. Copy that jar into rig/local/worker-steps/ (the worker's bind-mounted
 *      TITAN_STEPS_DIR). After this, `pwsh rig/local/dev.ps1 reload` (or a
 *      rig bring-up) exposes the e2eSpiGreeting step.
 *
 * Run after any change to e2e/fixtures/spi-step/ or titan-step-api.
 */
import { execFileSync } from 'node:child_process';
import { copyFileSync, existsSync, mkdirSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(here, '..', '..');
const fixtureDir = join(here, 'spi-step');
const stepApiDir = join(repoRoot, 'titan-step-api');
const jarName = 'titan-e2e-spi-step.jar';
const builtJar = join(fixtureDir, 'target', jarName);
const workerSteps = join(repoRoot, 'rig', 'local', 'worker-steps');

const mvn = process.platform === 'win32' ? 'mvn.cmd' : 'mvn';

function run(label, cwd, args) {
  process.stdout.write(`  ${label} ...\n`);
  execFileSync(mvn, args, { cwd, stdio: 'inherit', shell: process.platform === 'win32' });
}

// 1. titan-step-api into the local repo (the fixture's provided-scope dependency).
run('installing titan-step-api', stepApiDir, ['-q', '-o', 'install', '-DskipTests']);

// 2. build the fixture jar.
run('packaging spi-step fixture', fixtureDir, ['-q', '-o', 'package']);
if (!existsSync(builtJar)) {
  throw new Error(`fixture build produced no jar at ${builtJar}`);
}

// 3. drop it into the worker's TITAN_STEPS_DIR.
mkdirSync(workerSteps, { recursive: true });
copyFileSync(builtJar, join(workerSteps, jarName));
process.stdout.write(`  ${jarName} -> ${workerSteps}\n`);
process.stdout.write('  fixtures built. Run `pwsh rig/local/dev.ps1 reload` to load them.\n');
