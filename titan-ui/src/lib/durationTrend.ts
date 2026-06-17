/**
 * Pure trend math for the /pipelines duration sparkline (closes #1096).
 *
 * The acceptance criterion is: "green if last-30 avg < prior-30 avg (improving),
 * red otherwise". We generalise "last N vs prior N" to "the newer half of the
 * returned series vs the older half" so the colour is self-contained in the same
 * window the sparkline draws (the API returns one window of `n` points; we don't
 * fetch a second `n` just to colour the first). The series is server-ordered
 * oldest→newest, so the *second* half is the most recent builds.
 *
 * Kept dependency-free + side-effect-free so it can be unit-tested in isolation
 * and reused by any future trend surface.
 */

/** Direction of a duration trend. `flat` = the two half-window averages are equal. */
export type TrendDirection = 'improving' | 'degrading' | 'flat'

/** Terminal build statuses that carry a settled duration worth plotting. */
const FINISHED_STATUSES = new Set(['SUCCESS', 'FAILED', 'UNSTABLE'])

/** Minimal shape of a build needed to derive the trend (subset of BuildDto). */
interface DurationBuild {
  readonly status: string
  readonly durationMs?: number | null
}

/**
 * Derive the oldest→newest duration series (in seconds) the sparkline plots,
 * from the page's bulk recent-builds list (server order is newest-first).
 *
 * Keeps only *finished* builds with a settled duration — the SAME predicate the
 * server-side `DurationTrendDao` applies — converts ms→s, and reverses to
 * oldest→newest. Centralising it here means the client filter and the
 * DAO/DTO contract share one definition and can't silently drift (closes the
 * sev3/architecture review thread on #1096).
 */
export function finishedDurationsSeconds(
  builds: readonly DurationBuild[] | undefined,
): number[] {
  return (builds ?? [])
    .filter((b) => b.durationMs != null && FINISHED_STATUSES.has(b.status))
    .map((b) => (b.durationMs as number) / 1000)
    .reverse()
}

function average(xs: readonly number[]): number {
  // Caller guarantees non-empty; guard anyway so a stray empty slice can't NaN.
  if (xs.length === 0) return 0
  return xs.reduce((sum, x) => sum + x, 0) / xs.length
}

/**
 * Classify a duration series (oldest→newest) as improving / degrading / flat by
 * comparing the average of its newer half against its older half.
 *
 * Adversarial cases:
 *   - 0 or 1 sample → `flat` (no prior window to compare against).
 *   - odd length → the middle sample is dropped so both halves are equal-sized
 *     and the comparison is unbiased.
 */
export function trendDirection(durations: readonly number[]): TrendDirection {
  if (durations.length < 2) return 'flat'
  const half = Math.floor(durations.length / 2)
  const prior = durations.slice(0, half) // older builds
  const recent = durations.slice(durations.length - half) // newest builds
  const priorAvg = average(prior)
  const recentAvg = average(recent)
  if (recentAvg < priorAvg) return 'improving'
  if (recentAvg > priorAvg) return 'degrading'
  return 'flat'
}

/**
 * Sparkline stroke colour for a duration series. Per the acceptance criterion,
 * ONLY an improving trend is green; degrading AND flat are red ("red otherwise").
 * Uses the shared oklch tokens so it tracks any future theme rename.
 */
export function trendColor(durations: readonly number[]): string {
  return trendDirection(durations) === 'improving' ? 'var(--ok)' : 'var(--fail)'
}
