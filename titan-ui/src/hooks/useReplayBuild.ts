/**
 * useReplayActions — bundles the replay-from-node and replay-from-failed
 * mutations behind a single hook + a single error channel. Extracted from
 * `/builds/$buildId.tsx` (ticket #851) so the container can compose actions
 * instead of inlining mutation plumbing.
 *
 * Both mutations share `replayError` because the build-detail page only has
 * room for ONE error toast under the topbar — UX-equivalent to the
 * pre-refactor behaviour.
 */
import { useState } from 'react'
import { useNavigate } from '@tanstack/react-router'
import { useReplayBuild, useReplayFromFailedStage } from '@/api/hooks'
import { ApiError } from '@/api/types'

export interface UseReplayActionsResult {
  replayError: string | null
  clearReplayError: () => void
  isReplayPending: boolean
  isReplayFromFailedPending: boolean
  replayFromNode: (nodeId: string) => void
  replayFromFailed: () => void
}

export function useReplayActions(buildId: number): UseReplayActionsResult {
  const navigate = useNavigate()
  const replay = useReplayBuild()
  const replayFromFailed = useReplayFromFailedStage()
  const [replayError, setReplayError] = useState<string | null>(null)

  const replayFromNode = (nodeId: string) => {
    setReplayError(null)
    replay.mutate(
      { buildId, nodeId },
      {
        onSuccess: ({ newBuildId }) => {
          void navigate({
            to: '/builds/$buildId',
            params: { buildId: String(newBuildId) },
          })
        },
        onError: (err) => {
          if (err instanceof ApiError) {
            setReplayError(err.problem.detail ?? `Replay failed (${err.status}).`)
          } else {
            setReplayError('Replay failed — network error.')
          }
        },
      },
    )
  }

  const runReplayFromFailed = () => {
    setReplayError(null)
    replayFromFailed.mutate(
      { buildId },
      {
        onSuccess: (newBuild) => {
          void navigate({
            to: '/builds/$buildId',
            params: { buildId: String(newBuild.id) },
          })
        },
        onError: (err) => {
          if (err instanceof ApiError) {
            setReplayError(
              err.problem.detail ?? `Replay-from-failed failed (${err.status}).`,
            )
          } else {
            setReplayError('Replay-from-failed — network error.')
          }
        },
      },
    )
  }

  return {
    replayError,
    clearReplayError: () => setReplayError(null),
    isReplayPending: replay.isPending,
    isReplayFromFailedPending: replayFromFailed.isPending,
    replayFromNode,
    replayFromFailed: runReplayFromFailed,
  }
}
