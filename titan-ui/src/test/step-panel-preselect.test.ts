/**
 * Closes #450 — /builds/$id right-rail "Step" panel must pre-select the
 * most-relevant node so it isn't an empty stub on page load.
 *
 * Rules:
 *   - build FAILED  → first FAILED node in DAG order (multiple FAILED →
 *     first one wins)
 *   - build RUNNING → first RUNNING node in DAG order
 *   - build SUCCESS → last node in DAG order
 *   - empty nodes / other build statuses → null (don't auto-select)
 *
 * The picker switches on the typed FlowNodeStatus enum, not on raw string
 * matching, per CONSTITUTION discriminated-union typed-design.
 */
import { describe, expect, it } from 'vitest'
import { pickAutoSelectNodeId } from '@/routes/builds/$buildId'
import type { FlowNodeDto } from '@/api/types'

function node(nodeId: string, status: string): FlowNodeDto {
  return {
    buildId: 1,
    nodeId,
    parentIds: null,
    nodeType: 'STEP',
    displayName: nodeId,
    stepDescriptor: null,
    status,
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

describe('pickAutoSelectNodeId', () => {
  it('returns null when nodes array is empty', () => {
    expect(pickAutoSelectNodeId([], 'FAILED')).toBeNull()
    expect(pickAutoSelectNodeId([], 'RUNNING')).toBeNull()
    expect(pickAutoSelectNodeId([], 'SUCCESS')).toBeNull()
  })

  it('FAILED build → picks the first FAILED node in DAG order', () => {
    const nodes = [
      node('checkout', 'SUCCESS'),
      node('build', 'SUCCESS'),
      node('test', 'FAILED'),
      node('deploy', 'NOT_BUILT'),
    ]
    expect(pickAutoSelectNodeId(nodes, 'FAILED')).toBe('test')
  })

  it('FAILED build with multiple FAILED nodes → picks the first one', () => {
    const nodes = [
      node('checkout', 'SUCCESS'),
      node('lint', 'FAILED'),
      node('test', 'FAILED'),
      node('deploy', 'NOT_BUILT'),
    ]
    expect(pickAutoSelectNodeId(nodes, 'FAILED')).toBe('lint')
  })

  it('FAILED build with legacy FAILURE wire value is treated as FAILED', () => {
    const nodes = [node('checkout', 'SUCCESS'), node('test', 'FAILURE')]
    expect(pickAutoSelectNodeId(nodes, 'FAILED')).toBe('test')
  })

  it('FAILED build but no node is FAILED → returns null (defensive)', () => {
    const nodes = [node('checkout', 'SUCCESS'), node('build', 'SUCCESS')]
    expect(pickAutoSelectNodeId(nodes, 'FAILED')).toBeNull()
  })

  it('RUNNING build → picks the first RUNNING node in DAG order', () => {
    const nodes = [
      node('checkout', 'SUCCESS'),
      node('build', 'SUCCESS'),
      node('test', 'RUNNING'),
      node('deploy', 'QUEUED'),
    ]
    expect(pickAutoSelectNodeId(nodes, 'RUNNING')).toBe('test')
  })

  it('RUNNING build with multiple RUNNING nodes → picks the first one', () => {
    const nodes = [
      node('checkout', 'SUCCESS'),
      node('test-a', 'RUNNING'),
      node('test-b', 'RUNNING'),
    ]
    expect(pickAutoSelectNodeId(nodes, 'RUNNING')).toBe('test-a')
  })

  it('SUCCESS build → picks the last node in DAG order', () => {
    const nodes = [
      node('checkout', 'SUCCESS'),
      node('build', 'SUCCESS'),
      node('test', 'SUCCESS'),
      node('deploy', 'SUCCESS'),
    ]
    expect(pickAutoSelectNodeId(nodes, 'SUCCESS')).toBe('deploy')
  })

  it('QUEUED build → returns null (nothing meaningful to show)', () => {
    const nodes = [node('checkout', 'QUEUED')]
    expect(pickAutoSelectNodeId(nodes, 'QUEUED')).toBeNull()
  })

  it('ABORTED build → returns null (no auto-select rule)', () => {
    const nodes = [node('checkout', 'SUCCESS'), node('build', 'ABORTED')]
    expect(pickAutoSelectNodeId(nodes, 'ABORTED')).toBeNull()
  })

  it('unknown build status → returns null', () => {
    const nodes = [node('checkout', 'SUCCESS')]
    expect(pickAutoSelectNodeId(nodes, 'WHATEVER')).toBeNull()
  })
})
