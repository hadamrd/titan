/**
 * Titan brand glyph — v3.
 *
 * A notched square with an inner mechanical bar — "operator tool" feel,
 * not a generic letter-T. Cold-boot scale-in animation runs ONCE per
 * page session (module-level flag); subsequent route mounts do not
 * re-trigger it.
 *
 * Respects `prefers-reduced-motion` — see .brand-glyph.glyph-in in
 * components.css.
 */
import { useEffect, useState } from 'react'

// Module-scoped: true the first time this module is evaluated in the page.
// Subsequent <TitanGlyph /> mounts (route changes etc.) see false → no anim.
let coldBootConsumed = false

export function TitanGlyph() {
  const [coldBoot, setColdBoot] = useState(() => !coldBootConsumed)

  useEffect(() => {
    if (coldBoot) {
      coldBootConsumed = true
      // Remove the class after the animation ends so repeated renders stay quiet.
      const t = window.setTimeout(() => setColdBoot(false), 360)
      return () => window.clearTimeout(t)
    }
  }, [coldBoot])

  return (
    <div className={`brand-glyph${coldBoot ? ' glyph-in' : ''}`} aria-hidden>
      <svg viewBox="0 0 24 24" fill="none">
        {/* Notched square — operator-tool feel */}
        <path
          d="M3 3 H17 L21 7 V21 H7 L3 17 Z"
          fill="var(--accent)"
          opacity={0.16}
        />
        <path
          d="M3 3 H17 L21 7 V21 H7 L3 17 Z"
          stroke="var(--accent)"
          strokeWidth={1.6}
          strokeLinejoin="miter"
        />
        {/* Inner mechanical bars */}
        <rect x={8} y={9.5} width={8} height={2} fill="var(--accent)" />
        <rect x={8} y={13} width={5} height={1.5} fill="var(--accent)" opacity={0.7} />
      </svg>
    </div>
  )
}
