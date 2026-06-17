/**
 * Shared date/duration formatters used across the UI.
 *
 * Extracted from the build-detail route in PR #415 so the same null-safe
 * rules apply on every page that surfaces a duration or a timestamp. The
 * shipping of this module was missed by that PR — restored here as part of
 * tick #41 to unblock `tsc -p` on every route that imports `@/lib/format`.
 *
 * Rule of the file: null / undefined / NaN / negative input renders as an
 * em-dash (`—`), NEVER as `0s` / `NaNm NaNs`. Lying about missing signal is
 * worse than admitting it.
 */

/**
 * Format a millisecond duration as a short human string.
 *
 * Returns `—` for null / undefined / NaN / negative inputs — those signal
 * "no measurement", not "0 seconds". Sub-minute → `Ns`, otherwise `Mm Ns`.
 */
export function formatDuration(ms: number | null | undefined): string {
  if (ms == null || !Number.isFinite(ms) || ms < 0) return '—'
  const s = Math.floor(ms / 1000)
  if (s < 60) return `${s}s`
  return `${Math.floor(s / 60)}m ${s % 60}s`
}

/**
 * Duration of a build from its started/finished timestamps. When the build
 * is still running (`finishedAt` is null) the end is `Date.now()` so the
 * caller sees a live ticking duration on each render.
 */
export function formatBuildDuration(
  startedAt: string | null | undefined,
  finishedAt: string | null | undefined,
): string {
  if (!startedAt) return '—'
  const start = Date.parse(startedAt)
  if (!Number.isFinite(start)) return '—'
  const end = finishedAt ? Date.parse(finishedAt) : Date.now()
  if (!Number.isFinite(end)) return '—'
  return formatDuration(end - start)
}

/**
 * Format an ISO timestamp as either a coarse relative time or an absolute
 * locale string. Used by `/builds`, `/jobs`, and `/pipelines` so the rhythm
 * is identical across surfaces. Null / unparseable input → `—`.
 */
export function formatDate(
  iso: string | null | undefined,
  mode: 'relative' | 'absolute' = 'relative',
): string {
  if (!iso) return '—'
  const ts = Date.parse(iso)
  if (!Number.isFinite(ts)) return '—'
  if (mode === 'absolute') {
    return new Date(ts).toLocaleString(undefined, {
      dateStyle: 'short',
      timeStyle: 'short',
    })
  }
  const sec = Math.floor((Date.now() - ts) / 1000)
  if (sec < 0) return 'just now'
  if (sec < 60) return `${sec}s ago`
  if (sec < 3600) return `${Math.floor(sec / 60)}m ago`
  if (sec < 86_400) return `${Math.floor(sec / 3600)}h ago`
  return `${Math.floor(sec / 86_400)}d ago`
}
