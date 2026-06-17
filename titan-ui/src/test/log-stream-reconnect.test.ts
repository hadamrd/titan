/**
 * useLogStream — connection-lifecycle tests (#1264).
 *
 * These pin the state machine the build-detail Logs tab depends on:
 *   connecting → live → done (happy path), and the new explicit
 *   `reconnecting` state on a transient transport drop (previously collapsed
 *   into `connecting`, which lied to the operator).
 *
 * Adversarial coverage (manifesto: every external-dependency assumption gets a
 * false-case test): a mid-stream transport error must NOT crash, must surface
 * `reconnecting`, must back off and recover to `live`; an unmount mid-backoff
 * must NOT leave a runaway reconnect timer that fires a stale request.
 */
import { describe, it, expect, beforeEach, afterEach, vi, type Mock } from 'vitest'
import { renderHook, act, waitFor } from '@testing-library/react'

vi.mock('@/api/client', () => ({ streamBuildLogs: vi.fn() }))

import { streamBuildLogs } from '@/api/client'
import { useLogStream } from '@/hooks/useLogStream'

const mockStream = streamBuildLogs as unknown as Mock

beforeEach(() => {
  mockStream.mockReset()
})

afterEach(() => {
  vi.useRealTimers()
})

describe('useLogStream — happy path', () => {
  it('goes connecting → live on first log frame, appends the line, and ends `done` with no reconnect', async () => {
    mockStream.mockImplementation(async (_id, _signal, cb) => {
      cb.onOpen?.()
      cb.onFrame({ event: 'log', data: 'hello' })
      cb.onFrame({ event: 'done', data: 'SUCCESS' })
    })

    const { result } = renderHook(() => useLogStream(1, false, null))

    await waitFor(() => expect(result.current.sseState).toBe('done'))
    expect(result.current.lines).toEqual(['hello'])
    // `done` is terminal — exactly one connection, no reconnect loop.
    expect(mockStream).toHaveBeenCalledTimes(1)
  })

  it('clears the line buffer when the taskId (per-step filter) changes', async () => {
    mockStream.mockImplementation(async (_id, _signal, cb) => {
      cb.onOpen?.()
      cb.onFrame({ event: 'log', data: 'from-step-A' })
    })

    const { result, rerender } = renderHook(
      ({ taskId }) => useLogStream(1, false, taskId),
      { initialProps: { taskId: 'A' as string | null } },
    )
    await waitFor(() => expect(result.current.lines).toEqual(['from-step-A']))

    mockStream.mockImplementation(async (_id, _signal, cb) => {
      cb.onOpen?.()
      cb.onFrame({ event: 'log', data: 'from-step-B' })
    })
    rerender({ taskId: 'B' })

    // Lines from step A must NOT bleed into step B's view.
    await waitFor(() => expect(result.current.lines).toEqual(['from-step-B']))
  })
})

describe('useLogStream — adversarial transport drop', () => {
  it('surfaces `reconnecting` on a mid-stream error, backs off, then recovers to `live`', async () => {
    vi.useFakeTimers()
    let call = 0
    mockStream.mockImplementation(async (_id, signal: AbortSignal, cb) => {
      call += 1
      if (call === 1) {
        cb.onOpen?.()
        cb.onFrame({ event: 'log', data: 'before-drop' })
        throw new Error('transport drop') // simulate the connection dying
      }
      // Reconnect attempt succeeds and stays open (live) until aborted.
      cb.onOpen?.()
      cb.onFrame({ event: 'log', data: 'after-recover' })
      await new Promise<void>((resolve) => {
        signal.addEventListener('abort', () => resolve())
      })
    })

    const { result } = renderHook(() => useLogStream(7, false, null))

    // First connection drops → distinct `reconnecting` (NOT `connecting`/`error`).
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0)
    })
    expect(result.current.sseState).toBe('reconnecting')
    expect(result.current.lines).toEqual(['before-drop'])

    // Backoff is 500ms for the first retry; firing it re-opens and recovers.
    await act(async () => {
      await vi.advanceTimersByTimeAsync(500)
    })
    expect(result.current.sseState).toBe('live')
    expect(result.current.lines).toEqual(['before-drop', 'after-recover'])
    expect(mockStream).toHaveBeenCalledTimes(2)
  })

  it('does NOT fire a stale reconnect after unmount mid-backoff (no runaway timer leak)', async () => {
    vi.useFakeTimers()
    mockStream.mockImplementation(async (_id, _signal, cb) => {
      cb.onOpen?.()
      throw new Error('transport drop')
    })

    const { result, unmount } = renderHook(() => useLogStream(9, false, null))

    await act(async () => {
      await vi.advanceTimersByTimeAsync(0)
    })
    expect(result.current.sseState).toBe('reconnecting')
    expect(mockStream).toHaveBeenCalledTimes(1)

    unmount()
    // The pending backoff timer must have been cleared by cleanup — advancing
    // well past 30s (the backoff ceiling) must NOT trigger another request.
    await act(async () => {
      await vi.advanceTimersByTimeAsync(60_000)
    })
    expect(mockStream).toHaveBeenCalledTimes(1)
  })

  it('aborts the stream and goes `done` when the build becomes terminal, with no reconnect', async () => {
    vi.useFakeTimers()
    mockStream.mockImplementation(async (_id, signal: AbortSignal, cb) => {
      cb.onOpen?.()
      cb.onFrame({ event: 'log', data: 'running…' })
      // Stay "open" until aborted — mimic a long-lived live stream.
      await new Promise<void>((resolve) => {
        signal.addEventListener('abort', () => resolve())
      })
    })

    const { result, rerender } = renderHook(
      ({ terminal }) => useLogStream(11, terminal, null),
      { initialProps: { terminal: false } },
    )

    await act(async () => {
      await vi.advanceTimersByTimeAsync(0)
    })
    expect(result.current.sseState).toBe('live')

    // Build reaches a terminal state → hook proactively aborts and closes.
    rerender({ terminal: true })
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0)
    })
    expect(result.current.sseState).toBe('done')

    // No reconnect after terminal abort.
    await act(async () => {
      await vi.advanceTimersByTimeAsync(60_000)
    })
    expect(mockStream).toHaveBeenCalledTimes(1)
  })
})
