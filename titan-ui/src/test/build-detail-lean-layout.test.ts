/**
 * Unit tests for the lean build-detail layout (#830).
 *
 * Targets the pure helpers that drive the visible-DOM invariants:
 *   - `dagCanvasBodyHeight` decides the DAG canvas size (AC4).
 *   - `descriptorOf`-equivalent guard: we re-run the same expectations
 *     through `buildLayout` so a 1-node STAGE no longer leaks
 *     `stage:<nodeId>` into the card data (AC2).
 *
 * Why pure helpers + buildLayout rather than a full render: the page
 * under test mounts xyflow + TanStack Query + the router; the size + id
 * leak invariants live in pure data, so this keeps the assertion narrow
 * and fast and HARD-FAILS on regression.
 */
import { describe, it, expect } from 'vitest'
import {
  buildLayout,
  dagCanvasBodyHeight,
  DAG_HEIGHT_OVERRIDE_KEY,
} from '../components/BuildDetail/DagView'
import type { FlowNodeDto } from '../api/types'

function node(
  nodeId: string,
  displayName: string,
  nodeType: string,
  parentIds: string | null = null,
  stepDescriptor: string | null = null,
): FlowNodeDto {
  return {
    buildId: 1,
    nodeId,
    parentIds,
    nodeType,
    displayName,
    stepDescriptor,
    status: 'SUCCESS',
    agentLabel: null,
    startedAt: null,
    completedAt: null,
    durationMs: 1000,
    attempt: 1,
    maxAttempts: 1,
    failureCategory: null,
    failureReason: null,
    logTaskId: null,
  }
}

describe('dagCanvasBodyHeight (#830 AC4)', () => {
  it('content-sizes for a tiny graph (no fixed 480 / 32vh floor)', () => {
    // 3 single-row columns → tallest column = 80px (one CARD_H), body
    // ≈ 80 + 24 padding = 104 → clamped up to the 96 floor it already
    // exceeds. Cap on a 1000px viewport is 40*1000 - 36 toolbar = 364.
    expect(dagCanvasBodyHeight(80, 1000)).toBe(104)
  })

  it('honors the floor for an empty layout', () => {
    expect(dagCanvasBodyHeight(0, 1000)).toBe(96)
  })

  it('caps at 40vh - toolbar when content is taller than the viewport allows', () => {
    // 30 nodes stacked in one column: 30 * 80 + 29 * 30 = 3270 → way past
    // 40% of a 720px viewport (288 - 36 toolbar = 252).
    expect(dagCanvasBodyHeight(3270, 720)).toBe(252)
  })

  it('clamps the floor even when 40vh cap would be smaller (very short viewport)', () => {
    // 40% of 200px = 80 - 36 toolbar = 44 → must not drop below the 96 floor.
    expect(dagCanvasBodyHeight(3270, 200)).toBe(96)
  })

  it('returns raw content height when viewport is unknown (SSR / jsdom)', () => {
    expect(dagCanvasBodyHeight(800, 0)).toBe(824)
  })
})

describe('buildLayout — no raw id leaks (#830 AC2)', () => {
  it('STAGE nodes do not emit `stage:<nodeId>` as the card descriptor', () => {
    const stage = node('build-stage-uuid', 'Build', 'STAGE')
    const { rfNodes } = buildLayout([stage], null, () => {})
    expect(rfNodes).toHaveLength(1)
    const data = rfNodes[0]!.data as Record<string, unknown>
    expect(data.descriptor).toBeNull()
  })

  it('STEP nodes suppress descriptors that smuggle `stage:` or `node:` prefixes', () => {
    const stepA = node('a', 'compile', 'STEP', null, 'stage:build')
    const stepB = node('b', 'unit', 'STEP', null, 'node:build/unit')
    const { rfNodes } = buildLayout([stepA, stepB], null, () => {})
    const dA = rfNodes[0]!.data as Record<string, unknown>
    const dB = rfNodes[1]!.data as Record<string, unknown>
    expect(dA.descriptor).toBeNull()
    expect(dB.descriptor).toBeNull()
  })

  it('STEP nodes keep legitimate descriptors (the happy path)', () => {
    const step = node('a', 'compile', 'STEP', null, 'sh "pnpm build"')
    const { rfNodes } = buildLayout([step], null, () => {})
    const data = rfNodes[0]!.data as Record<string, unknown>
    expect(data.descriptor).toBe('sh "pnpm build"')
  })

  it('reports contentHeight = tallest column for content-sized canvas', () => {
    // Three single-row stages (Build / Test / Deploy) → maxColHeight = 80.
    const ns = [
      node('a', 'Build: x', 'STEP'),
      node('b', 'Test: y', 'STEP'),
      node('c', 'Deploy: z', 'STEP'),
    ]
    const { contentHeight } = buildLayout(ns, null, () => {})
    expect(contentHeight).toBe(80)
  })

  it('localStorage key for DAG drag override is stable (#830 AC5)', () => {
    // The key string is part of the persistence contract — renaming it
    // silently invalidates every user's saved handle position. Pin it.
    expect(DAG_HEIGHT_OVERRIDE_KEY).toBe('titan.buildDetail.dagHeight.v1')
  })
})
