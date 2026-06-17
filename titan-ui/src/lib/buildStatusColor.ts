/**
 * Shared status -> oklch token mapping for build-status visualisations
 * (sparklines, trend bars, etc.). Extracted so JobSparkline and
 * BuildDurationTrend agree on colour semantics without duplication.
 */
export function buildStatusColor(status: string): string {
  switch (status) {
    case 'SUCCESS':
      return 'var(--ok)'
    case 'FAILED':
    case 'FAILURE':
    case 'ABORTED':
    case 'UNSTABLE':
      return 'var(--fail)'
    default:
      return 'var(--muted)'
  }
}
