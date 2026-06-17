/**
 * Sparkline — fix #10 (interactive cursor + value tooltip on mousemove).
 *
 * Pure SVG, no library. We render the path, an invisible overlay rect that
 * catches pointer events, and on pointermove we project the X position back
 * to the nearest data index and pop a `.spark-tip` chip next to the cursor.
 */
import { useRef, useState } from 'react'

interface SparklineProps {
  data: number[]
  width?: number
  height?: number
  stroke?: string
  /** Optional formatter for the tooltip value. */
  formatValue?: (v: number, idx: number) => string
  className?: string
}

export function Sparkline({
  data,
  width = 90,
  height = 36,
  stroke = 'var(--accent)',
  formatValue = (v) => String(v),
  className,
}: SparklineProps) {
  const wrapRef = useRef<HTMLDivElement>(null)
  const [hover, setHover] = useState<{ x: number; y: number; idx: number } | null>(null)

  if (data.length === 0) return null

  const min = Math.min(...data)
  const max = Math.max(...data)
  const range = max - min || 1
  const step = data.length > 1 ? width / (data.length - 1) : 0

  const points = data.map((v, i) => {
    const x = i * step
    const y = height - ((v - min) / range) * height
    return { x, y }
  })

  const pathD = points
    .map((p, i) => (i === 0 ? `M${p.x},${p.y}` : `L${p.x},${p.y}`))
    .join(' ')

  const onMove = (e: React.PointerEvent<SVGSVGElement>) => {
    const rect = e.currentTarget.getBoundingClientRect()
    const px = e.clientX - rect.left
    const idx = Math.max(
      0,
      Math.min(data.length - 1, Math.round((px / rect.width) * (data.length - 1))),
    )
    setHover({ x: points[idx].x, y: points[idx].y, idx })
  }

  return (
    <div
      ref={wrapRef}
      className={className}
      style={{ position: 'relative', width, height }}
    >
      <svg
        width={width}
        height={height}
        viewBox={`0 0 ${width} ${height}`}
        onPointerMove={onMove}
        onPointerLeave={() => setHover(null)}
        style={{ display: 'block', overflow: 'visible' }}
      >
        <path
          d={pathD}
          fill="none"
          stroke={stroke}
          strokeWidth={1.5}
          strokeLinecap="round"
          strokeLinejoin="round"
        />
        {hover && (
          <>
            <line
              x1={hover.x}
              x2={hover.x}
              y1={0}
              y2={height}
              stroke="var(--fg-faint)"
              strokeDasharray="2 2"
              strokeWidth={1}
            />
            <circle cx={hover.x} cy={hover.y} r={3} fill={stroke} />
          </>
        )}
      </svg>
      {hover && (
        <div
          className="spark-tip"
          style={{
            left: Math.min(width - 4, hover.x + 6),
            top: Math.max(-6, hover.y - 22),
          }}
        >
          {formatValue(data[hover.idx], hover.idx)}
        </div>
      )}
    </div>
  )
}
