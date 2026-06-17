/**
 * Tests for the fetch-based SSE log streamer in `api/client.ts`.
 *
 * Why this exists: browser `EventSource` cannot attach an `Authorization`
 * header, so the Logs tab was wedged on 401 against the OIDC-gated server
 * endpoint (forge loop tick #68). We replaced it with `streamBuildLogs`,
 * which uses `fetch` + a manual SSE parser. These tests pin:
 *
 *   1. the request carries `Authorization: Bearer <token>` (regression guard)
 *   2. the parser turns `event:`/`data:` frames into `SseFrame` objects
 *   3. `AbortController` cleanly stops the loop
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { streamBuildLogs, type SseFrame } from '../api/client'
import { setAccessToken } from '../auth/tokenStore'

function streamBody(chunks: string[]): ReadableStream<Uint8Array> {
  const encoder = new TextEncoder()
  let i = 0
  return new ReadableStream<Uint8Array>({
    pull(controller) {
      if (i < chunks.length) {
        controller.enqueue(encoder.encode(chunks[i++]))
      } else {
        controller.close()
      }
    },
  })
}

const originalFetch = globalThis.fetch

beforeEach(() => {
  setAccessToken('test-bearer-xyz')
})

afterEach(() => {
  setAccessToken(null)
  globalThis.fetch = originalFetch
  vi.restoreAllMocks()
})

describe('streamBuildLogs()', () => {
  it('sends Authorization: Bearer <token> on the SSE request', async () => {
    const seen: { url: string; headers: Headers }[] = []
    globalThis.fetch = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      seen.push({
        url: String(input),
        headers: new Headers(init?.headers),
      })
      return new Response(streamBody(['event: done\ndata: \n\n']), {
        status: 200,
        headers: { 'Content-Type': 'text/event-stream' },
      })
    }) as typeof fetch

    const controller = new AbortController()
    const frames: SseFrame[] = []
    await streamBuildLogs(22, controller.signal, { onFrame: (f) => frames.push(f) })

    expect(seen).toHaveLength(1)
    expect(seen[0].url).toContain('/api/v1/builds/22/logs')
    expect(seen[0].headers.get('Authorization')).toBe('Bearer test-bearer-xyz')
    expect(seen[0].headers.get('Accept')).toBe('text/event-stream')
  })

  it('parses event/data frames split across chunk boundaries', async () => {
    globalThis.fetch = vi.fn(async () => {
      return new Response(
        streamBody([
          'event: log\ndata: hello',
          ' world\n\nevent: log\nda',
          'ta: line two\n\nevent: done\ndata: \n\n',
        ]),
        { status: 200, headers: { 'Content-Type': 'text/event-stream' } },
      )
    }) as typeof fetch

    const frames: SseFrame[] = []
    await streamBuildLogs(22, new AbortController().signal, {
      onFrame: (f) => frames.push(f),
    })

    expect(frames).toEqual([
      { event: 'log', data: 'hello world' },
      { event: 'log', data: 'line two' },
      { event: 'done', data: '' },
    ])
  })

  it('throws ApiError on non-2xx responses', async () => {
    globalThis.fetch = vi.fn(async () => {
      return new Response(
        JSON.stringify({
          type: 'about:blank',
          title: 'Unauthorized',
          status: 401,
          detail: 'token expired',
          instance: null,
        }),
        { status: 401, headers: { 'Content-Type': 'application/problem+json' } },
      )
    }) as typeof fetch

    await expect(
      streamBuildLogs(22, new AbortController().signal, { onFrame: () => {} }),
    ).rejects.toMatchObject({ status: 401 })
  })

  it('aborts the fetch when the AbortController fires', async () => {
    // Real `fetch` rejects with AbortError when its signal is already aborted.
    // We mimic that contract so the call surface is exercised end-to-end.
    let receivedSignal: AbortSignal | undefined
    globalThis.fetch = vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
      receivedSignal = init?.signal ?? undefined
      if (init?.signal?.aborted) {
        const err = new Error('aborted') as Error & { name: string }
        err.name = 'AbortError'
        throw err
      }
      return new Response(streamBody(['event: done\ndata: \n\n']), { status: 200 })
    }) as typeof fetch

    const controller = new AbortController()
    controller.abort()

    await expect(
      streamBuildLogs(22, controller.signal, { onFrame: () => {} }),
    ).rejects.toMatchObject({ name: 'AbortError' })
    expect(receivedSignal).toBe(controller.signal)
  })
})
