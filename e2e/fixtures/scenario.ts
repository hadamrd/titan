/**
 * Scenario loading + assertion — design/43 §4.
 *
 * A scenario is declarative data: a `*.e2e.yaml` file declaring a pipeline and
 * its expected outcome. This module defines the file shape, discovers every
 * scenario, and asserts an `expect` block against a completed build's DAG.
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import yaml from 'js-yaml';
import { expect } from '@playwright/test';
import type { NodeView } from './titan-api';

const HERE = path.dirname(fileURLToPath(import.meta.url));
/** e2e/scenarios/ — where every *.e2e.yaml lives. */
export const SCENARIOS_DIR = path.resolve(HERE, '..', 'scenarios');

/** The expected status of one DAG node. */
export interface NodeExpectation {
  status: string;
  /**
   * The expected per-step retry attempt the node reached (design/44 §5). When
   * set, asserted against the graph API's `attempt` field — only a step
   * carrying a `retry:` policy surfaces one.
   */
  attempt?: number;
}

/** The `expect` block of a scenario. */
export interface ScenarioExpect {
  /** Overall build result — SUCCESS / FAILURE / ABORTED / UNSTABLE. */
  build: string;
  /** Per-node expected statuses, keyed by Titan node id (`a`, `a-s0`, ...). */
  nodes?: Record<string, NodeExpectation>;
  /**
   * Node ids that must have run concurrently. The graph API reports a reliable
   * per-node `startTimeMillis` but not a per-step duration, so concurrency is
   * proven by start-time clustering: all listed nodes must have STARTED within
   * `parallelWindowMs` of each other. Make each stage's work longer than that
   * window (e.g. `sleep 3` with the default 2.5s window) so a serial run —
   * where starts are spaced by a stage's full duration — fails the assertion.
   */
  ranInParallel?: string[];
  /** Start-time clustering window for `ranInParallel`, in ms (default 2500). */
  parallelWindowMs?: number;
  /** Substrings that MUST appear in the build console log. */
  logContains?: string[];
  /** Substrings that must NOT appear in the console log (e.g. a raw secret). */
  logExcludes?: string[];
}

/** A parsed scenario file. */
export interface Scenario {
  /** Source file basename, e.g. `linear.e2e.yaml`. */
  file: string;
  /** Human-readable scenario name. */
  name: string;
  /** The Titan pipeline definition (YAML or Groovy synthesis program). */
  pipeline: string;
  /**
   * An optional Groovy preamble run on the rig's script console before the
   * pipeline is submitted — for self-provisioning rig state the harness CAN
   * create (e.g. seeding a credential-store credential). Rig fixtures the harness
   * CANNOT provision (an SSH key on the worker, a jar in TITAN_STEPS_DIR) must
   * use `pending:` instead.
   */
  setup?: string;
  /** When set, the scenario is skipped with this reason (rig-fixture gap). */
  pending?: string;
  /** When true the build is expected to need a gate decision mid-run. */
  gate?: { approve?: string; reject?: string };
  expect: ScenarioExpect;
}

/** Discover and parse every scenarios/*.e2e.yaml. */
export function loadScenarios(): Scenario[] {
  if (!fs.existsSync(SCENARIOS_DIR)) return [];
  return fs
    .readdirSync(SCENARIOS_DIR)
    .filter((f) => f.endsWith('.e2e.yaml'))
    .sort()
    .map((file) => {
      const raw = fs.readFileSync(path.join(SCENARIOS_DIR, file), 'utf-8');
      const doc = yaml.load(raw) as Partial<Scenario>;
      if (!doc || typeof doc !== 'object') {
        throw new Error(`scenario ${file}: not a YAML mapping`);
      }
      if (!doc.name || !doc.pipeline || !doc.expect) {
        throw new Error(`scenario ${file}: missing required key (name/pipeline/expect)`);
      }
      return { ...(doc as Scenario), file };
    });
}

/**
 * Assert a scenario's `expect` block against a completed build's node map.
 * `nodes` is the flat Titan-id->NodeView map from `titanApi.flowNodes()`.
 */
export function assertNodes(scenario: Scenario, nodes: Map<string, NodeView>): void {
  for (const [nodeId, exp] of Object.entries(scenario.expect.nodes ?? {})) {
    const actual = nodes.get(nodeId);
    expect(actual, `${scenario.file}: node '${nodeId}' present in DAG`).toBeTruthy();
    expect(actual!.status, `${scenario.file}: node '${nodeId}' status`).toBe(exp.status);
    if (exp.attempt !== undefined) {
      expect(actual!.attempt, `${scenario.file}: node '${nodeId}' retry attempt`).toBe(
        exp.attempt,
      );
    }
  }
}

/**
 * Assert the `ranInParallel` claim: every listed node STARTED within
 * `parallelWindowMs` of every other. A serial run spaces starts by a full
 * stage's duration, so a window shorter than the per-stage work fails it.
 */
export function assertRanInParallel(scenario: Scenario, nodes: Map<string, NodeView>): void {
  const ids = scenario.expect.ranInParallel ?? [];
  if (ids.length < 2) return;
  const windowMs = scenario.expect.parallelWindowMs ?? 2500;
  const starts = ids.map((id) => {
    const n = nodes.get(id);
    expect(n, `${scenario.file}: ranInParallel node '${id}' present`).toBeTruthy();
    return { id, start: n!.startTimeMillis };
  });
  const min = Math.min(...starts.map((s) => s.start));
  const max = Math.max(...starts.map((s) => s.start));
  expect(
    max - min,
    `${scenario.file}: ranInParallel [${ids.join(', ')}] start spread ${max - min}ms ` +
      `must be < ${windowMs}ms (a serial run would space starts by a full stage)`,
  ).toBeLessThan(windowMs);
}

/** Assert console-log inclusion / exclusion expectations. */
export function assertLog(scenario: Scenario, log: string): void {
  for (const needle of scenario.expect.logContains ?? []) {
    expect(log, `${scenario.file}: log contains '${needle}'`).toContain(needle);
  }
  for (const needle of scenario.expect.logExcludes ?? []) {
    expect(log, `${scenario.file}: log must NOT contain '${needle}'`).not.toContain(needle);
  }
}
