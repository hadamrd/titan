/**
 * useLogStream — SSE log streaming hook for the build-detail page.
 *
 * Extracted verbatim from `/builds/$buildId.tsx` (ticket #851). Behaviour:
 *  - Reconnects on transport error with exponential backoff (500 ms → 30 s).
 *  - Clears the line buffer whenever the selected `taskId` (per-step log
 *    filter) changes — otherwise lines from the previous step would bleed
 *    into the newly selected step.
 *  - Goes to `done` cleanly when the SSE emits an `event: done` frame OR
 *    when the build becomes terminal (we proactively abort the request so
 *    we don't reconnect against a now-stale stream).
 */
import { useEffect, useRef, useState } from 'react'
import { streamBuildLogs } from '@/api/client'

/**
 * Connection lifecycle surfaced to the UI:
 *  - `connecting`   — first attempt to open the stream (no data yet).
 *  - `live`         — the stream is open and frames are flowing.
 *  - `reconnecting` — a transient transport drop; we're backing off and will
 *                     retry. Distinct from `connecting` so the UI can tell the
 *                     operator "we lost the stream and are recovering" instead
 *                     of a misleading first-connect spinner (#1264).
 *  - `done`         — terminal: the build closed and the stream ended cleanly.
 *  - `error`        — retained for callers that pattern-match the union; the
 *                     hook now routes recoverable transport errors through
 *                     `reconnecting` rather than `error`.
 */
export type SseState = 'connecting' | 'live' | 'reconnecting' | 'done' | 'error'

export interface UseLogStreamResult {
  lines: string[]
  sseState: SseState
}

export function useLogStream(
  buildId: number,
  buildTerminal: boolean,
  taskId: string | null,
): UseLogStreamResult {
  const [lines, setLines] = useState<string[]>([])
  const [sseState, setSseState] = useState<SseState>('connecting')
  const backoffMs = useRef(500)
  const abortRef = useRef<AbortController | null>(null)
  const reconnectTimer = useRef<ReturnType<typeof setTimeout> | null>(null)
  const doneRef = useRef(false)

  useEffect(() => {
    let cancelled = false
    doneRef.current = false
    setLines([])

    async function open(isRetry = false) {
      if (cancelled) return
      const controller = new AbortController()
      abortRef.current = controller
      // First attempt is an honest `connecting`; a backoff-driven retry is a
      // distinct `reconnecting` so the UI never shows a first-connect spinner
      // after the stream has already been live once (#1264).
      setSseState(isRetry ? 'reconnecting' : 'connecting')

      try {
        await streamBuildLogs(buildId, controller.signal, {
          onOpen: () => {
            if (cancelled) return
            setSseState('live')
            backoffMs.current = 500
          },
          onFrame: (frame) => {
            if (cancelled) return
            if (frame.event === 'log') {
              setSseState('live')
              backoffMs.current = 500
              setLines((prev) => [...prev, frame.data])
            } else if (frame.event === 'done') {
              doneRef.current = true
              setSseState('done')
              controller.abort()
            }
          },
        }, taskId)
        if (cancelled) return
        if (!doneRef.current) setSseState('done')
      } catch (err) {
        if (cancelled) return
        if (controller.signal.aborted && doneRef.current) return
        if (controller.signal.aborted && !cancelled) return
        // A transient transport drop: surface `reconnecting` (not `error` /
        // `connecting`) while we back off, then retry. The retry re-enters
        // `open(true)` which keeps the state at `reconnecting` until a frame
        // or onOpen flips it back to `live`.
        setSseState('reconnecting')
        const delay = backoffMs.current
        backoffMs.current = Math.min(backoffMs.current * 2, 30_000)
        reconnectTimer.current = setTimeout(() => {
          void open(true)
        }, delay)
        void err
      }
    }

    void open()

    return () => {
      cancelled = true
      if (reconnectTimer.current !== null) clearTimeout(reconnectTimer.current)
      abortRef.current?.abort()
      abortRef.current = null
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [buildId, taskId])

  useEffect(() => {
    if (buildTerminal && abortRef.current) {
      if (reconnectTimer.current !== null) clearTimeout(reconnectTimer.current)
      doneRef.current = true
      abortRef.current.abort()
      abortRef.current = null
      setSseState('done')
    }
  }, [buildTerminal])

  return { lines, sseState }
}
