/**
 * Partition a builds list into `{ inFlight, history }` for the /builds
 * "All" view. In-flight = any non-terminal status; history = terminal.
 *
 * The predicate is sourced from {@link TERMINAL_STATUSES} in @/api/types so
 * a new BuildStatus value (e.g. PAUSED) is automatically classified as
 * in-flight unless explicitly registered as terminal — fail-safe on the
 * side of "make sure the user can see it".
 *
 * Pure function, no side effects: trivial to unit-test, trivial to reuse
 * elsewhere (pipeline detail page already needs the same split).
 *
 * #917 — pin in-flight runs to a sticky top section so a single RUNNING
 * build is never buried under a wall of SUCCESS rows.
 */
import { TERMINAL_STATUSES, type BuildStatus } from '@/api/types'

export interface PartitionedBuilds<B extends { status: BuildStatus }> {
  inFlight: B[]
  history: B[]
}

export function partitionBuilds<B extends { status: BuildStatus }>(
  builds: readonly B[],
): PartitionedBuilds<B> {
  const inFlight: B[] = []
  const history: B[] = []
  for (const b of builds) {
    if (TERMINAL_STATUSES.has(b.status)) {
      history.push(b)
    } else {
      inFlight.push(b)
    }
  }
  return { inFlight, history }
}
