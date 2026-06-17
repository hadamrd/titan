import { useEffect, useState } from 'react'

/**
 * Force a re-render every second while `active` is true. Used to make
 * `formatBuildDuration`-driven cells (which already read {@code Date.now()})
 * live-tick on in-flight rows. No-op when {@code active === false}, so the
 * all-terminal case has zero timer cost.
 *
 * <p>Extracted from {@code /builds/index.tsx} (#917) for re-use on the
 * per-pipeline page (#927). CONSTITUTION §6: the caller must invoke this
 * hook unconditionally — pass {@code false} to opt out, never gate the
 * call itself behind an early return.
 */
export function useTickWhileActive(active: boolean): void {
  const [, setTick] = useState(0)
  useEffect(() => {
    if (!active) return
    const id = setInterval(() => setTick((n) => (n + 1) % 1_000_000), 1000)
    return () => clearInterval(id)
  }, [active])
}
