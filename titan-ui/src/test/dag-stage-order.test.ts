/**
 * Adversarial tests for #922 — DAG stages must render in pipeline-declared
 * (topological) order.
 *
 * Repro: the server's `GET /api/v1/builds/{id}/nodes` returns rows sorted
 * by `ORDER BY node_id` (lexical by synthetic stage id). A pipeline that
 * declares `Prepare -> Approval -> Deploy` came back from the API in the
 * lexical order `Approval, Deploy, Prepare` and was rendered left-to-right
 * in that wrong order. The fix is a UI-side topological sort by parentIds.
 *
 * These tests pin the topoOrder helper's contract:
 *   1. Roots-first, children only after all declared parents.
 *   2. Lexically-shuffled input (the bug condition) is sorted back into
 *      declared order.
 *   3. Same-depth siblings preserve input order (stable).
 *   4. Orphan / unknown parents are not silently dropped.
 *   5. Cycle fallback emits every input node exactly once.
 */
import { describe, it, expect } from 'vitest'
import type { FlowNodeDto } from '../api/types'
import { topoOrder, buildLayout } from '../components/BuildDetail/DagView'

function node(nodeId: string, displayName: string, parentIds: string | null = null): FlowNodeDto {
  return {
    buildId: 1,
    nodeId,
    parentIds,
    nodeType: 'STAGE',
    displayName,
    stepDescriptor: null,
    status: 'SUCCESS',
    agentLabel: null,
    startedAt: null,
    completedAt: null,
    durationMs: null,
    attempt: 1,
    maxAttempts: 1,
    failureCategory: null,
    failureReason: null,
    logTaskId: null,
  }
}

describe('topoOrder (#922)', () => {
  it('puts root nodes before their children', () => {
    const a = node('a', 'Prepare')
    const b = node('b', 'Approval', 'a')
    const c = node('c', 'Deploy', 'b')
    const out = topoOrder([c, b, a]) // reverse-of-declared input
    expect(out.map((n) => n.displayName)).toEqual(['Prepare', 'Approval', 'Deploy'])
  })

  it('reproduces the build-32 bug — lexical input becomes declared order', () => {
    // Pipeline declares: Prepare -> Approval -> Deploy
    // Server's ORDER BY node_id returns: Approval, Deploy, Prepare (lexical)
    const prepare = node('stage.prepare', 'Prepare')
    const approval = node('stage.approval', 'Approval', 'stage.prepare')
    const deploy = node('stage.deploy', 'Deploy', 'stage.approval')
    // Mirror the server's broken ordering exactly.
    const out = topoOrder([approval, deploy, prepare])
    expect(out.map((n) => n.displayName)).toEqual(['Prepare', 'Approval', 'Deploy'])
  })

  it('preserves input order among same-depth siblings (stable)', () => {
    const root = node('r', 'Root')
    const left = node('l', 'Left', 'r')
    const right = node('rr', 'Right', 'r')
    const out = topoOrder([root, left, right])
    expect(out.map((n) => n.nodeId)).toEqual(['r', 'l', 'rr'])
    // Flip the siblings' input order — output should reflect that.
    const out2 = topoOrder([root, right, left])
    expect(out2.map((n) => n.nodeId)).toEqual(['r', 'rr', 'l'])
  })

  it('does not drop nodes with parents missing from the input (treats as roots)', () => {
    const orphan = node('o', 'Orphan', 'ghost-parent-id')
    const a = node('a', 'A')
    const out = topoOrder([orphan, a])
    expect(out).toHaveLength(2)
    expect(out.map((n) => n.nodeId).sort()).toEqual(['a', 'o'])
  })

  it('returns every input node exactly once even with a cycle', () => {
    // a -> b -> a (pathological — server validates DAG, but we must not loop).
    const a = node('a', 'A', 'b')
    const b = node('b', 'B', 'a')
    const out = topoOrder([a, b])
    expect(out).toHaveLength(2)
    const ids = out.map((n) => n.nodeId).sort()
    expect(ids).toEqual(['a', 'b'])
  })

  it('handles empty input', () => {
    expect(topoOrder([])).toEqual([])
  })

  it('handles comma-separated parentIds with multiple parents', () => {
    const a = node('a', 'A')
    const b = node('b', 'B')
    const c = node('c', 'C', 'a,b')
    const out = topoOrder([c, b, a])
    // c must come last; a and b are roots in input order.
    expect(out[out.length - 1]!.nodeId).toBe('c')
  })
})

describe('buildLayout column ordering (#922)', () => {
  it('assigns columns by topological order, not lexical node_id', () => {
    // Same bug scenario — three stages with distinct displayNames.
    // The bucket key is the first token of displayName, so each stage
    // owns its own column.
    const prepare = node('stage.prepare', 'Prepare')
    const approval = node('stage.approval', 'Approval', 'stage.prepare')
    const deploy = node('stage.deploy', 'Deploy', 'stage.approval')

    // Lexical-input order as the server returns it.
    const { rfNodes } = buildLayout([approval, deploy, prepare], null, () => {})

    const byId = new Map(rfNodes.map((n) => [n.id, n.position.x]))
    const xPrepare = byId.get('stage.prepare')!
    const xApproval = byId.get('stage.approval')!
    const xDeploy = byId.get('stage.deploy')!

    // Column x-coords must be Prepare < Approval < Deploy.
    expect(xPrepare).toBeLessThan(xApproval)
    expect(xApproval).toBeLessThan(xDeploy)
  })
})
