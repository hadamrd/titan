/**
 * StackCardNode — the chunky 180x80 v3-stack card rendered as an inline SVG.
 *
 * Visual spec: `docs/design/build-detail-v3-mockups/project/build-detail/
 * v3-stack.html` lines 359–487 (single "checkout" / "test-it" / "package"
 * cards). The card is rendered as an inline <svg> so the exact mockup
 * geometry — 180x80 frame, 4×80 status rail, 22-tall header strip,
 * tabular-num duration — survives without the browser's flex math.
 *
 * The SVG sits inside an HTML wrapper that owns:
 *   - the `.bd-stack-card.<variant>` CSS hook (drives skip opacity,
 *     run dashed border, fail tint, queued dashed border),
 *   - the selection caret SVG that points into the card from the left
 *     rail (design line 447 — shipped in PR #554),
 *   - the brief outline-pulse on selection (respects
 *     `prefers-reduced-motion`),
 *   - the click / Enter / Space activation that drives the parent route's
 *     per-step log filter.
 *
 * Why HTML wrapper + inner SVG instead of either pure HTML or pure SVG:
 *   - Pure HTML drifts from the mockup pixel-grid (the original 180x80
 *     spec uses absolute SVG positions for status text + duration).
 *   - Pure SVG can't host an xyflow `<Handle>` (Handle is an HTML node),
 *     and the existing routes-test suite asserts the `.bd-stack-card`
 *     class hook + pulse animation.
 *   - HTML+inner-SVG keeps both invariants happy and is the lightest
 *     diff.
 */
import { useEffect, useRef, useState } from 'react'
import { Handle, Position, type NodeProps } from '@xyflow/react'
import { formatDuration } from '@/lib/format'

export type CardVariant = 'ok' | 'fail' | 'run' | 'skip' | 'queued'

export interface StackCardData {
  name: string
  descriptor: string | null
  footer: string | null
  durationMs: number | null
  status: string
  variant: CardVariant
  selected?: boolean
  // We pipe the click handler through node data because xyflow's
  // built-in selection event runs through ReactFlow.onNodeClick — we
  // want our explicit handler so we can also wire log-filter side-effects.
  onSelect?: (nodeId: string) => void
  nodeId: string
}

const VARIANT_LABEL: Record<CardVariant, string> = {
  ok: 'SUCCESS',
  fail: 'FAILED',
  run: 'RUNNING',
  skip: 'SKIPPED',
  queued: 'QUEUED',
}

/**
 * Per-variant token names. The CSS variables live in tokens.css and are
 * the same names the mockup SVG uses verbatim — see lines 363, 367, etc.
 */
interface VariantStyle {
  statusVar: string // CSS var name for the rail + dot + status text
  durColor: string // CSS var for the duration text
  nameColor: string // CSS var for the displayName
  nameWeight: number
  stripBg: string // CSS color for the 22h header strip
  stripBorderBottom: string // CSS color for the 1px divider under the strip
  descriptorColor: string
  footerColor: string
  statusWeight: number
}

function variantStyle(v: CardVariant): VariantStyle {
  switch (v) {
    case 'fail':
      return {
        statusVar: 'var(--fail)',
        durColor: 'var(--fail)',
        nameColor: 'var(--fg)',
        nameWeight: 700,
        stripBg: 'color-mix(in oklab, var(--fail) 14%, oklch(0.18 0 0))',
        stripBorderBottom: 'color-mix(in oklab, var(--fail) 25%, var(--border))',
        descriptorColor: 'var(--fail)',
        footerColor: 'var(--fg-faint)',
        statusWeight: 600,
      }
    case 'run':
      return {
        statusVar: 'var(--info, oklch(0.70 0.13 240))',
        durColor: 'var(--fg-dim)',
        nameColor: 'var(--fg)',
        nameWeight: 600,
        stripBg: 'oklch(0.22 0 0)',
        stripBorderBottom: 'var(--border)',
        descriptorColor: 'var(--fg-dim)',
        footerColor: 'var(--fg-faint)',
        statusWeight: 500,
      }
    case 'skip':
      return {
        statusVar: 'var(--fg-faint)',
        durColor: 'var(--fg-faint)',
        nameColor: 'var(--fg-dim)',
        nameWeight: 500,
        stripBg: 'oklch(0.21 0 0)',
        stripBorderBottom: 'var(--border)',
        descriptorColor: 'var(--fg-faint)',
        footerColor: 'var(--fg-faint)',
        statusWeight: 500,
      }
    case 'queued':
      return {
        statusVar: 'var(--pending, var(--fg-faint))',
        durColor: 'var(--fg-faint)',
        nameColor: 'var(--fg-dim)',
        nameWeight: 500,
        stripBg: 'oklch(0.21 0 0)',
        stripBorderBottom: 'var(--border)',
        descriptorColor: 'var(--fg-dim)',
        footerColor: 'var(--fg-faint)',
        statusWeight: 500,
      }
    case 'ok':
    default:
      return {
        statusVar: 'var(--ok)',
        durColor: 'var(--fg-dim)',
        nameColor: 'var(--fg)',
        nameWeight: 600,
        stripBg: 'oklch(0.22 0 0)',
        stripBorderBottom: 'var(--border)',
        descriptorColor: 'var(--fg-dim)',
        footerColor: 'var(--fg-faint)',
        statusWeight: 500,
      }
  }
}

function prefersReducedMotion(): boolean {
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') {
    return false
  }
  return window.matchMedia('(prefers-reduced-motion: reduce)').matches
}

/**
 * @xyflow types the `data` field as a generic record. We narrow at the
 * call boundary so the rest of the component is strictly typed.
 */
type StackCardProps = NodeProps & { data: StackCardData }

export function StackCardNode({ data, selected }: StackCardProps) {
  const v = data.variant
  const isSelected = selected || data.selected === true
  const showDur = v !== 'skip' && v !== 'queued'
  const durText = showDur ? formatDuration(data.durationMs) : '—'
  const s = variantStyle(v)

  // Short pulse: fires when selection transitions false → true, and only
  // when prefers-reduced-motion is not set.
  const [pulse, setPulse] = useState(false)
  const wasSelectedRef = useRef(isSelected)
  useEffect(() => {
    if (isSelected && !wasSelectedRef.current && !prefersReducedMotion()) {
      setPulse(true)
      const t = window.setTimeout(() => setPulse(false), 600)
      return () => window.clearTimeout(t)
    }
    wasSelectedRef.current = isSelected
    return undefined
  }, [isSelected])

  // Also reset the ref AFTER any selection change so the next true→false→true
  // cycle re-triggers the pulse.
  useEffect(() => {
    wasSelectedRef.current = isSelected
  }, [isSelected])

  const label = VARIANT_LABEL[v]

  return (
    <div
      className={`bd-stack-card ${v}${isSelected ? ' selected' : ''}${pulse ? ' pulse' : ''}`}
      data-testid={`dag-node-${data.nodeId}`}
      data-status={data.status}
      data-selected={isSelected ? 'true' : 'false'}
      onClick={() => data.onSelect?.(data.nodeId)}
      role="button"
      tabIndex={0}
      onKeyDown={(e) => {
        if (e.key === 'Enter' || e.key === ' ') {
          e.preventDefault()
          data.onSelect?.(data.nodeId)
        }
      }}
      style={{ position: 'relative', width: 180, height: 80 }}
    >
      {isSelected && (
        <svg
          className="bd-stack-caret"
          data-testid={`dag-node-caret-${data.nodeId}`}
          viewBox="-16 0 18 80"
          width="16"
          height="80"
          aria-hidden="true"
          style={{
            position: 'absolute',
            left: -16,
            top: 0,
            pointerEvents: 'none',
            overflow: 'visible',
          }}
        >
          {/* Per design v3-stack.html line 447 */}
          <path d="M-14 36 L-2 42 L-14 48 Z" fill="var(--accent)" />
        </svg>
      )}
      {/* Inline SVG card — geometry copied verbatim from v3-stack.html
          lines 359–487. Width/height match the wrapper so xyflow edge
          endpoints stay aligned. overflow:visible so the selection glow
          ring can paint outside the 180×80 box. */}
      <svg
        width="180"
        height="80"
        viewBox="0 0 180 80"
        style={{ display: 'block', overflow: 'visible' }}
        aria-hidden="true"
        focusable="false"
      >
        {isSelected && (
          <rect
            className="stack-card-glow"
            data-testid={`dag-node-glow-${data.nodeId}`}
            x={-3}
            y={-3}
            width={186}
            height={86}
            rx={12}
            fill="none"
            stroke="var(--accent)"
            strokeWidth={1.5}
            opacity={0.55}
          />
        )}
        {/* Base card rect — carries the same class hook the legacy mockup uses */}
        <rect
          className={`dag-node-rect stack-card-bg ${v}`}
          x={0}
          y={0}
          width={180}
          height={80}
          rx={10}
          fill="transparent"
        />
        {/* 4px status rail (left edge) */}
        <rect x={0} y={0} width={4} height={80} fill={s.statusVar} />
        {/* 22px header strip + 1px divider */}
        <rect x={4} y={0} width={176} height={22} fill={s.stripBg} />
        <rect x={4} y={21} width={176} height={1} fill={s.stripBorderBottom} />
        {/* Status dot */}
        <circle cx={18} cy={11} r={3} fill={s.statusVar} />
        {/* Status text — uppercase mono, letter-spaced */}
        <text
          x={28}
          y={15}
          fontSize={10}
          fontFamily="Geist Mono, ui-monospace, monospace"
          fill={s.statusVar}
          fontWeight={s.statusWeight}
          letterSpacing="0.06em"
          style={{ textTransform: 'uppercase' }}
        >
          {label}
        </text>
        {/* Duration — right-aligned, tabular-nums */}
        <text
          x={170}
          y={15}
          fontSize={10}
          fontFamily="Geist Mono, ui-monospace, monospace"
          fill={s.durColor}
          textAnchor="end"
          style={{ fontVariantNumeric: 'tabular-nums' }}
        >
          {durText}
        </text>
        {/* Display name */}
        <text
          x={16}
          y={42}
          fontSize={14}
          fontWeight={s.nameWeight}
          fill={s.nameColor}
        >
          {clipText(data.name, 22)}
        </text>
        {/* Descriptor line (mono) */}
        {data.descriptor && (
          <text
            x={16}
            y={58}
            fontSize={10.5}
            fontFamily="Geist Mono, ui-monospace, monospace"
            fill={s.descriptorColor}
          >
            {clipText(data.descriptor, 28)}
          </text>
        )}
        {/* Footer line (mono, faintest) */}
        {data.footer && (
          <text
            x={16}
            y={73}
            fontSize={10.5}
            fontFamily="Geist Mono, ui-monospace, monospace"
            fill={s.footerColor}
          >
            {clipText(data.footer, 28)}
          </text>
        )}
      </svg>
      {/* Connection handles — invisible but required by xyflow for edges. */}
      <Handle type="target" position={Position.Left} style={{ opacity: 0 }} />
      <Handle type="source" position={Position.Right} style={{ opacity: 0 }} />
    </div>
  )
}

/** Naive width-aware truncation. SVG <text> doesn't wrap; we just chop. */
function clipText(s: string, max: number): string {
  if (s.length <= max) return s
  return s.slice(0, Math.max(1, max - 1)) + '…'
}
