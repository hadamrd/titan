/**
 * StarButton — pin/unpin a job to the sidebar 'Starred' section (#703).
 *
 * Wraps the {@link useStarJob} / {@link useUnstarJob} mutations and the
 * {@link useStarredJobs} cache to render a single self-contained star toggle
 * that the /jobs row and /jobs/$id header can both drop in.
 *
 * <p><strong>Optimistic behavior.</strong> A click flips the icon instantly
 * (TanStack Query's onMutate prepends/removes the row from the cache); on a
 * network/409 failure the cache rolls back AND we call {@code onError} so the
 * caller can surface a toast. The 10-cap is mirrored client-side via
 * {@link MAX_STARRED_JOBS} so we never even fire the request when the user is
 * already at cap — a server 409 would still rollback cleanly but the UX latency
 * is worse than an inline guard.
 *
 * <p>Filled star ({@code fill="currentColor"}) = pinned; outline = unpinned.
 * Same visual idiom as the v0 /jobs row star (#636) so SREs already trained on
 * the pattern see no regression.
 */
import { Star } from 'lucide-react'
import { type MouseEvent } from 'react'
import {
  MAX_STARRED_JOBS,
  useStarredJobs,
  useStarJob,
  useUnstarJob,
} from '@/api/hooks'
import type { JobDto } from '@/api/types'
import { ApiError } from '@/api/types'

export interface StarButtonProps {
  job: JobDto
  /** Pixel size of the star glyph; defaults to 14 to match /jobs row density. */
  size?: number
  /** Called with a human-readable message on mutation failure (toast/inline). */
  onError?: (message: string) => void
  /** data-testid override (defaults to {@code star-job-{id}}). */
  testId?: string
}

export function StarButton({ job, size = 14, onError, testId }: StarButtonProps) {
  const { data: starred } = useStarredJobs()
  const star = useStarJob()
  const unstar = useUnstarJob()

  const isStarred = !!starred?.some((j) => j.id === job.id)
  const atCap = !isStarred && (starred?.length ?? 0) >= MAX_STARRED_JOBS
  const busy = star.isPending || unstar.isPending

  function onClick(e: MouseEvent<HTMLButtonElement>) {
    e.stopPropagation()
    e.preventDefault()
    if (busy) return
    if (isStarred) {
      unstar.mutate(
        { jobId: job.id },
        { onError: (err) => onError?.(messageFor(err)) },
      )
      return
    }
    if (atCap) {
      onError?.(
        `Starred-jobs cap reached (${MAX_STARRED_JOBS}). Unstar one before pinning a new job.`,
      )
      return
    }
    star.mutate(
      { jobId: job.id, job },
      { onError: (err) => onError?.(messageFor(err)) },
    )
  }

  const title = isStarred
    ? 'Unstar job'
    : atCap
      ? `At ${MAX_STARRED_JOBS}-star cap — unstar one to pin another`
      : 'Star job'

  return (
    <button
      type="button"
      onClick={onClick}
      title={title}
      aria-label={title}
      aria-pressed={isStarred}
      disabled={busy}
      data-testid={testId ?? `star-job-${job.id}`}
      style={{
        padding: 4,
        width: size + 12,
        height: size + 12,
        display: 'inline-flex',
        alignItems: 'center',
        justifyContent: 'center',
        background: 'none',
        border: 0,
        cursor: busy ? 'progress' : atCap ? 'not-allowed' : 'pointer',
        color: isStarred ? 'var(--accent)' : 'var(--fg-dim)',
        opacity: atCap ? 0.5 : 1,
      }}
    >
      <Star
        size={size}
        aria-hidden
        fill={isStarred ? 'currentColor' : 'none'}
        strokeWidth={isStarred ? 1.5 : 2}
      />
    </button>
  )
}

function messageFor(err: unknown): string {
  if (err instanceof ApiError) {
    if (err.status === 409) {
      return err.problem.detail ?? `Starred-jobs cap reached (${MAX_STARRED_JOBS}).`
    }
    if (err.status === 404) return 'Job not found.'
    return err.problem.detail ?? err.message
  }
  return 'Failed to update starred jobs.'
}
