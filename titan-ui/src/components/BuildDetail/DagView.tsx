/**
 * DagView — v3-stack pipeline graph rendered with @xyflow/react.
 *
 * - Layered left-to-right layout (parents first by topological order).
 * - Stage column heuristic: bucket nodes by `displayName.split(/[:/]/)[0]`,
 *   the same naive bucket the legacy pipeline tab used. Columns map to
 *   x-coordinates so siblings within a stage stack vertically.
 * - Edges colored by downstream variant; skip-edges dashed.
 * - Custom node component = StackCardNode (chunky 180x80 card).
 *
 * The visual spec lives at docs/design/build-detail-v3-mockups/project/
 * build-detail/v3-stack.html lines 290–490.
 *
 * #552 / #553 — toolbar gained a Collapse button (chevron-up) that the
 * parent route uses to fold the DAG down to a 32px summary bar. We also
 * tie a ResizeObserver to the canvas wrapper so the graph re-fits when
 * the surrounding split resizes (a window resize / dragging the rail
 * splitter no longer leaves stale viewport math).
 */
import { useEffect, useMemo, useRef, useState, type PointerEvent as ReactPointerEvent } from 'react'
import {
  Background,
  Controls,
  MarkerType,
  ReactFlow,
  ReactFlowProvider,
  useReactFlow,
  type Edge,
  type Node,
} from '@xyflow/react'
import '@xyflow/react/dist/style.css'
import type { FlowNodeDto } from '@/api/types'
import { StackCardNode, type CardVariant, type StackCardData } from './StackCardNode'

export type { CardVariant } from './StackCardNode'

const NODE_TYPES = { stackCard: StackCardNode }

const CARD_W = 180
const CARD_H = 80
const COL_GAP = 60 // horizontal gap between stage columns
const ROW_GAP = 30 // vertical gap between sibling cards inside a column

export function variantOf(status: string): CardVariant {
  switch (status) {
    case 'SUCCESS':
      return 'ok'
    case 'FAILED':
    case 'FAILURE':
      return 'fail'
    case 'RUNNING':
      return 'run'
    case 'QUEUED':
    case 'NOT_BUILT':
      return 'queued'
    case 'ABORTED':
    case 'CANCELLED':
    case 'SKIPPED':
      // A `when:`-guarded step the orchestrator skipped (GH #1093) — render it as a
      // skipped node (dimmed, "SKIPPED" label), never as QUEUED via the default fall-through.
      return 'skip'
    default:
      return 'queued'
  }
}

function stageOf(node: FlowNodeDto): string {
  const name = node.displayName ?? node.nodeType
  const first = name.split(/[:/]/)[0]?.trim()
  return first && first.length > 0 ? first : 'Steps'
}

/**
 * Card descriptor line. For stage nodes we used to render `stage:<nodeId>`
 * (the raw synthetic id), and steps fell back to `stepDescriptor`. Both
 * leaked implementation labels into the UI (issue #830 AC2). We now:
 *   - return null for stage nodes (the stage name already shows above),
 *   - suppress any descriptor that starts with `stage:` or `node:` so
 *     malformed step descriptors don't smuggle ids back in.
 */
function descriptorOf(node: FlowNodeDto): string | null {
  if (isStageNode(node)) return null
  const d = node.stepDescriptor
  if (!d) return null
  if (/^(stage|node):/i.test(d)) return null
  return d
}

/**
 * STAGE → `<N> step(s)` if we can count children; STEP → retry counter
 * `<attempt>/<max>` when a retry happened, otherwise blank (keeps the
 * footer line honest — we don't invent data).
 */
function footerOf(node: FlowNodeDto, childCount: number): string | null {
  if (isStageNode(node)) {
    if (childCount <= 0) return ''
    return `${childCount} step${childCount === 1 ? '' : 's'}`
  }
  if (node.attempt > 1) {
    return `${node.attempt}/${node.maxAttempts}`
  }
  return ''
}

/**
 * Topological sort of flow nodes by `parentIds` (Kahn's algorithm). A node
 * appears only after all of its declared parents. Nodes sharing a depth
 * (roots, siblings, etc.) preserve their input order — Kahn's queue is FIFO
 * and we seed it with input-order roots. Nodes whose parents are missing
 * from the input (defensive — should not happen for a valid build) are
 * treated as roots so we never drop them silently.
 *
 * Closes #922: the server's `ORDER BY node_id` lexical ordering put
 * `Approval` before `Prepare` despite Prepare being the declared first
 * stage. Topological order pins the columns to declared dependency order.
 */
export function topoOrder(nodes: readonly FlowNodeDto[]): FlowNodeDto[] {
  const byId = new Map<string, FlowNodeDto>()
  for (const n of nodes) byId.set(n.nodeId, n)

  const remainingParents = new Map<string, number>()
  const children = new Map<string, string[]>()
  for (const n of nodes) {
    const parents = (n.parentIds ?? '')
      .split(',')
      .map((p) => p.trim())
      .filter((p) => p.length > 0 && byId.has(p))
    remainingParents.set(n.nodeId, parents.length)
    for (const p of parents) {
      if (!children.has(p)) children.set(p, [])
      children.get(p)!.push(n.nodeId)
    }
  }

  // Seed roots in input order so siblings stay stable.
  const queue: string[] = []
  for (const n of nodes) {
    if ((remainingParents.get(n.nodeId) ?? 0) === 0) queue.push(n.nodeId)
  }

  const out: FlowNodeDto[] = []
  while (queue.length > 0) {
    const id = queue.shift()!
    const node = byId.get(id)
    if (!node) continue
    out.push(node)
    for (const childId of children.get(id) ?? []) {
      const rem = (remainingParents.get(childId) ?? 0) - 1
      remainingParents.set(childId, rem)
      if (rem === 0) queue.push(childId)
    }
  }

  // Cycle fallback: if anything is left (shouldn't happen — server validates
  // DAG), append in input order so we never drop a node.
  if (out.length < nodes.length) {
    const emitted = new Set(out.map((n) => n.nodeId))
    for (const n of nodes) {
      if (!emitted.has(n.nodeId)) out.push(n)
    }
  }
  return out
}

function isStageNode(node: FlowNodeDto): boolean {
  // STAGE / SECTION / GROUP synthetic node-types. The engine sets one of
  // these for grouping nodes; everything else (sh, archiveArtifacts,
  // withCredentials, …) is treated as a STEP.
  const t = node.nodeType?.toUpperCase() ?? ''
  return t === 'STAGE' || t === 'SECTION' || t === 'GROUP'
}

interface Props {
  nodes: FlowNodeDto[]
  selectedNodeId: string | null
  onSelect: (nodeId: string) => void
  /** Optional collapse trigger — when present we render a chevron toolbar button. */
  onCollapse?: () => void
  /** Current collapsed state — flips the chevron orientation. */
  collapsed?: boolean
}

interface LayoutResult {
  rfNodes: Node[]
  rfEdges: Edge[]
  stageCount: number
  /** Tallest column in px (card geometry + gaps), used by the page to
      size the DAG canvas to its content (#830 AC4). */
  contentHeight: number
}

const DAG_TOOLBAR_H = 36
const DAG_CANVAS_PAD = 24
/** Smallest content height we let the canvas render at — keeps a 1-node
 *  graph from collapsing to nothing while still being well under the 40vh
 *  cap (#830 adversarial: 1-node ≤ 120px chrome+canvas). */
const DAG_CANVAS_MIN_BODY = 96
/** localStorage key for the user's dragged DAG canvas height (#830 AC5). */
export const DAG_HEIGHT_OVERRIDE_KEY = 'titan.buildDetail.dagHeight.v1'

/**
 * Compute the canvas body height for a graph layout. Pure helper so tests
 * can pin the floor / cap behaviour without a renderer.
 *
 *   - `contentHeight` is the tallest column from `buildLayout()`.
 *   - `viewportH` is `window.innerHeight` (caller passes 0 in SSR; we
 *     return the raw content height in that case so unit tests are stable).
 *
 * Returns the body (canvas) height — toolbar height is added by the caller.
 */
export function dagCanvasBodyHeight(contentHeight: number, viewportH: number): number {
  const body = Math.max(DAG_CANVAS_MIN_BODY, contentHeight + DAG_CANVAS_PAD)
  if (viewportH <= 0) return body
  const cap = Math.floor(viewportH * 0.4) - DAG_TOOLBAR_H
  return Math.min(body, Math.max(DAG_CANVAS_MIN_BODY, cap))
}

export function buildLayout(
  flowNodes: FlowNodeDto[],
  selectedNodeId: string | null,
  onSelect: (nodeId: string) => void,
): LayoutResult {
  if (flowNodes.length === 0) {
    return { rfNodes: [], rfEdges: [], stageCount: 0, contentHeight: 0 }
  }

  // Count children per node so STAGE footers can report "N steps".
  const childCount = new Map<string, number>()
  for (const n of flowNodes) {
    if (!n.parentIds) continue
    for (const p of n.parentIds.split(',').map((x) => x.trim()).filter(Boolean)) {
      childCount.set(p, (childCount.get(p) ?? 0) + 1)
    }
  }

  // Fix #922 — the server returns nodes in `ORDER BY node_id` (lexical by
  // synthetic stage id), which has no relation to the pipeline-declared
  // order. A pipeline that declares Prepare → Approval → Deploy was rendering
  // as Approval → Deploy → Prepare because the bucketing below uses
  // first-occurrence ordering. Sort topologically by parentIds so a node
  // never precedes any of its declared parents. Same-depth siblings keep
  // their server-returned order (stable).
  const ordered = topoOrder(flowNodes)

  // Group into stages by display-name first token (matches the legacy
  // groupIntoStages heuristic in $buildId.tsx — keeps the column ordering
  // intuitive until a real stage field lands on FlowNodeDto).
  const stageOrder: string[] = []
  const byStage = new Map<string, FlowNodeDto[]>()
  for (const n of ordered) {
    const s = stageOf(n)
    if (!byStage.has(s)) {
      stageOrder.push(s)
      byStage.set(s, [])
    }
    byStage.get(s)!.push(n)
  }

  const positions = new Map<string, { x: number; y: number }>()
  let maxColHeight = 0
  stageOrder.forEach((stage, col) => {
    const group = byStage.get(stage)!
    const colHeight = group.length * CARD_H + (group.length - 1) * ROW_GAP
    if (colHeight > maxColHeight) maxColHeight = colHeight
    const yOffset = -colHeight / 2
    group.forEach((node, row) => {
      positions.set(node.nodeId, {
        x: col * (CARD_W + COL_GAP),
        y: yOffset + row * (CARD_H + ROW_GAP),
      })
    })
  })

  const rfNodes: Node[] = flowNodes.map((n) => {
    const pos = positions.get(n.nodeId) ?? { x: 0, y: 0 }
    const variant = variantOf(n.status)
    const data: StackCardData = {
      nodeId: n.nodeId,
      name: n.displayName ?? n.nodeType,
      descriptor: descriptorOf(n),
      footer: footerOf(n, childCount.get(n.nodeId) ?? 0),
      durationMs: n.durationMs,
      status: n.status,
      variant,
      onSelect,
      selected: selectedNodeId === n.nodeId,
    }
    return {
      id: n.nodeId,
      type: 'stackCard',
      position: pos,
      data: data as unknown as Record<string, unknown>,
      selected: selectedNodeId === n.nodeId,
      draggable: false,
      connectable: false,
      // xyflow nodes need explicit width/height when using HTML nodes
      // for accurate edge endpoint calculation.
      width: CARD_W,
      height: CARD_H,
    }
  })

  // Edges from parentIds — the FlowNodeDto.parentIds is a comma-separated
  // string of parent node IDs. Edge color picks up the DOWNSTREAM (child)
  // variant so a fail downstream node colors the edge red.
  const rfEdges: Edge[] = []
  for (const n of flowNodes) {
    if (!n.parentIds) continue
    const parents = n.parentIds.split(',').map((p) => p.trim()).filter(Boolean)
    const childVariant = variantOf(n.status)
    for (const p of parents) {
      const edgeColor = edgeStrokeFor(childVariant)
      const dashed = childVariant === 'skip' || childVariant === 'queued'
      rfEdges.push({
        id: `${p}->${n.nodeId}`,
        source: p,
        target: n.nodeId,
        type: 'smoothstep',
        animated: childVariant === 'run',
        style: {
          stroke: edgeColor,
          strokeWidth: childVariant === 'fail' ? 1.5 : 1.25,
          strokeDasharray: dashed ? '5 5' : undefined,
        },
        markerEnd: {
          type: MarkerType.ArrowClosed,
          color: edgeColor,
          width: 14,
          height: 14,
        },
      })
    }
  }

  return {
    rfNodes,
    rfEdges,
    stageCount: stageOrder.length,
    contentHeight: maxColHeight,
  }
}

function edgeStrokeFor(v: CardVariant): string {
  switch (v) {
    case 'ok':
      return 'oklch(0.55 0.10 160 / 0.7)'
    case 'fail':
      return 'oklch(0.68 0.20 25)'
    case 'run':
      return 'oklch(0.70 0.13 240)'
    case 'queued':
      return 'oklch(0.40 0.012 240)'
    default:
      return 'oklch(0.34 0 0)'
  }
}

/**
 * Inner ReactFlow body — sits inside the ReactFlowProvider so it can use
 * `useReactFlow()` to call `fitView()` from a ResizeObserver on the
 * wrapper. Without this the graph stayed at its first-render zoom when
 * the surrounding split panel resized.
 */
function DagBody({
  rfNodes,
  rfEdges,
  onSelect,
}: {
  rfNodes: Node[]
  rfEdges: Edge[]
  onSelect: (nodeId: string) => void
}) {
  const rf = useReactFlow()
  const wrapRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    const el = wrapRef.current
    if (!el || typeof ResizeObserver === 'undefined') return
    const ro = new ResizeObserver(() => {
      // Defer to the next frame — xyflow's internal node-size cache
      // updates on the same tick as the resize observer fires.
      requestAnimationFrame(() => {
        try {
          rf.fitView({ padding: 0.1, maxZoom: 1.0, minZoom: 0.5, duration: 0 })
        } catch {
          // fitView throws if the flow isn't fully mounted yet — safe to swallow.
        }
      })
    })
    ro.observe(el)
    return () => ro.disconnect()
  }, [rf])

  return (
    <div ref={wrapRef} style={{ width: '100%', height: '100%' }}>
      <ReactFlow
        nodes={rfNodes}
        edges={rfEdges}
        nodeTypes={NODE_TYPES}
        fitView
        fitViewOptions={{ padding: 0.1, maxZoom: 1.0, minZoom: 0.5 }}
        minZoom={0.4}
        maxZoom={1.4}
        proOptions={{ hideAttribution: true }}
        nodesDraggable={false}
        nodesConnectable={false}
        elementsSelectable
        panOnDrag
        zoomOnScroll={false}
        zoomOnPinch
        onNodeClick={(_, n) => onSelect(n.id)}
      >
        <Background gap={20} size={1} color="oklch(0.25 0.01 240)" />
        <Controls showInteractive={false} position="bottom-right" />
      </ReactFlow>
    </div>
  )
}

export function DagView({ nodes, selectedNodeId, onSelect, onCollapse, collapsed }: Props) {
  const { rfNodes, rfEdges, stageCount, contentHeight } = useMemo(
    () => buildLayout(nodes, selectedNodeId, onSelect),
    [nodes, selectedNodeId, onSelect],
  )

  // Track viewport height for the 40vh cap (#830 AC4). useSyncExternalStore-
  // style listener keeps the canvas responsive to resize without dragging
  // the whole page into a re-render.
  const [viewportH, setViewportH] = useState<number>(() =>
    typeof window === 'undefined' ? 0 : window.innerHeight,
  )
  useEffect(() => {
    if (typeof window === 'undefined') return
    const onResize = () => setViewportH(window.innerHeight)
    window.addEventListener('resize', onResize)
    return () => window.removeEventListener('resize', onResize)
  }, [])
  // Optional user override (drag handle), persisted across reloads (#830 AC5).
  const [overrideH, setOverrideH] = useState<number | null>(() => {
    if (typeof window === 'undefined') return null
    try {
      const raw = window.localStorage.getItem(DAG_HEIGHT_OVERRIDE_KEY)
      if (!raw) return null
      const n = Number(raw)
      return Number.isFinite(n) && n > 40 ? n : null
    } catch {
      return null
    }
  })
  // Clamp the override against the current viewport — a user who saved a
  // tall DAG on a big monitor shouldn't get an overflow on a 720px laptop.
  const clampedOverride =
    overrideH !== null && viewportH > 0
      ? Math.min(overrideH, Math.floor(viewportH * 0.7) - DAG_TOOLBAR_H)
      : overrideH
  const canvasH = clampedOverride ?? dagCanvasBodyHeight(contentHeight, viewportH)

  const [dragging, setDragging] = useState(false)
  const wrapRef = useRef<HTMLElement>(null)
  const onResizerPointerDown = (e: ReactPointerEvent<HTMLDivElement>) => {
    if (typeof window === 'undefined') return
    e.preventDefault()
    setDragging(true)
    const startY = e.clientY
    const startH = canvasH
    const onMove = (ev: PointerEvent) => {
      const next = Math.max(DAG_CANVAS_MIN_BODY, startH + (ev.clientY - startY))
      setOverrideH(next)
    }
    const onUp = (ev: PointerEvent) => {
      window.removeEventListener('pointermove', onMove)
      window.removeEventListener('pointerup', onUp)
      setDragging(false)
      try {
        const finalH = Math.max(DAG_CANVAS_MIN_BODY, startH + (ev.clientY - startY))
        window.localStorage.setItem(DAG_HEIGHT_OVERRIDE_KEY, String(finalH))
      } catch {
        // localStorage may throw (quota / private mode) — drop silently.
      }
    }
    window.addEventListener('pointermove', onMove)
    window.addEventListener('pointerup', onUp)
  }

  const counts = useMemo(() => {
    let ok = 0, fail = 0, run = 0, skip = 0
    for (const n of nodes) {
      const v = variantOf(n.status)
      if (v === 'ok') ok++
      else if (v === 'fail') fail++
      else if (v === 'run') run++
      else skip++
    }
    return { ok, fail, run, skip }
  }, [nodes])

  return (
    <section
      ref={wrapRef}
      className="bd-dag-wrap"
      aria-label="Pipeline graph"
      data-testid="dag-wrap"
      style={{ height: canvasH + DAG_TOOLBAR_H }}
    >
      <div className="bd-dag-toolbar">
        <span>
          Pipeline · {nodes.length} node{nodes.length !== 1 ? 's' : ''} · {stageCount} stage{stageCount !== 1 ? 's' : ''}
        </span>
        {/* The "selected: …" pill duplicated the right-pane node header
            and the topbar build label (#830 AC1 — redundancy collapse).
            Selection is communicated by the highlighted card + tree row. */}
        <span className="legend" style={{ marginLeft: 'auto' }}>
          <span className="status-dot success" style={{ width: 6, height: 6 }} /> {counts.ok} ok
          <span className="status-dot fail" style={{ width: 6, height: 6 }} /> {counts.fail} fail
          {counts.run > 0 && (
            <>
              <span className="status-dot running" style={{ width: 6, height: 6 }} /> {counts.run} run
            </>
          )}
          <span className="status-dot cancelled" style={{ width: 6, height: 6 }} /> {counts.skip} skip
        </span>
        {onCollapse && (
          <button
            type="button"
            className="btn btn-sm btn-ghost"
            data-testid="dag-collapse-btn"
            aria-label={collapsed ? 'Expand pipeline graph' : 'Collapse pipeline graph'}
            aria-expanded={!collapsed}
            onClick={onCollapse}
            style={{ marginLeft: 8 }}
            title={collapsed ? 'Expand graph' : 'Collapse graph'}
          >
            <svg
              width="14"
              height="14"
              viewBox="0 0 16 16"
              fill="none"
              stroke="currentColor"
              strokeWidth="1.5"
              aria-hidden="true"
            >
              {collapsed ? (
                <path d="M4 6l4 4 4-4" />
              ) : (
                <path d="M4 10l4-4 4 4" />
              )}
            </svg>
          </button>
        )}
      </div>
      <div className="bd-dag-canvas" data-testid="dag-canvas" style={{ height: canvasH }}>
        <ReactFlowProvider>
          <DagBody rfNodes={rfNodes} rfEdges={rfEdges} onSelect={onSelect} />
        </ReactFlowProvider>
      </div>
      <div
        className={`bd-dag-resizer${dragging ? ' dragging' : ''}`}
        data-testid="dag-resizer"
        role="separator"
        aria-orientation="horizontal"
        aria-label="Resize pipeline graph"
        onPointerDown={onResizerPointerDown}
      />
    </section>
  )
}
