import { Badge, type BadgeVariant } from '@/components/ui/Badge'

/**
 * Colored "why this build failed" badge (issue #1105). Renders the heuristic root cause diagnosed
 * by the server-side {@code BuildFailureClassifier} on a FAILED build, with a "why this was
 * classified" tooltip carrying the matching log snippet.
 *
 * Renders nothing when there is no cause (success / not-yet-classified) so callers can drop it in
 * unconditionally.
 */

type CauseMeta = { label: string; variant: BadgeVariant; why: string }

// Keyed by the FailureCause wire name persisted on builds.failure_cause.
const CAUSE_META: Record<string, CauseMeta> = {
  test_failure: { label: 'Test failure', variant: 'fail', why: 'A test assertion failed.' },
  compile_error: { label: 'Compile error', variant: 'fail', why: 'Source failed to compile.' },
  oom: { label: 'Out of memory', variant: 'warn', why: 'The build ran out of memory (OOM).' },
  timeout: { label: 'Timeout', variant: 'warn', why: 'A step exceeded its time budget.' },
  network: { label: 'Network', variant: 'info', why: 'A network / DNS error interrupted the build.' },
  rate_limit: { label: 'Rate limited', variant: 'info', why: 'An upstream API rate limit was hit.' },
  unknown: { label: 'Cause unknown', variant: 'default', why: 'No known failure signature matched.' },
}

interface Props {
  failureCause?: string | null
  failureCauseDetail?: string | null
}

export function FailureCauseBadge({ failureCause, failureCauseDetail }: Props) {
  if (!failureCause) return null
  const meta = CAUSE_META[failureCause] ?? CAUSE_META.unknown
  const snippet = failureCauseDetail?.trim()
  const tooltip = snippet ? `Why this was classified: ${snippet}` : meta.why
  return (
    <Badge
      variant={meta.variant}
      title={tooltip}
      data-testid="failure-cause-badge"
      data-cause={failureCause}
    >
      {meta.label}
    </Badge>
  )
}
