/**
 * Vitest for the ETag-aware fetch path used by `fetchBuild` and `fetchJob`
 * (issue #1099).
 *
 * Contract:
 *   1. First request → no `If-None-Match`; client stores the server's `ETag`
 *      against the URL.
 *   2. Second request to the same URL → carries `If-None-Match: <etag>`.
 *   3. Server returns 304 with empty body → client resolves to the cached
 *      body (we MUST NOT throw).
 *   4. Server returns 200 with a new payload + new ETag → cache updates.
 *
 * The test stubs `fetch` directly so it is a pure unit test, no network.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchBuild, fetchJob, __resetEtagCacheForTests } from '../api/client'

vi.mock('@/auth/tokenStore', () => ({
  getAccessToken: () => null,
}))

const ORIGINAL_FETCH = globalThis.fetch

beforeEach(() => {
  __resetEtagCacheForTests()
})

afterEach(() => {
  globalThis.fetch = ORIGINAL_FETCH
  vi.restoreAllMocks()
})

function jsonResponse(body: unknown, etag: string, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json', ETag: etag },
  })
}

function notModified(etag: string): Response {
  return new Response(null, {
    status: 304,
    headers: { ETag: etag },
  })
}

describe('ETag-aware fetch (#1099)', () => {
  it('sends no If-None-Match on first request, captures ETag', async () => {
    const calls: Array<{ url: string; init?: RequestInit }> = []
    globalThis.fetch = vi.fn(async (url: string, init?: RequestInit) => {
      calls.push({ url: url.toString(), init })
      return jsonResponse({ id: 7, status: 'RUNNING' }, 'W/"abc"')
    }) as unknown as typeof fetch

    const build = await fetchBuild(7)
    expect(build).toMatchObject({ id: 7, status: 'RUNNING' })
    expect(calls).toHaveLength(1)
    const firstHeaders = calls[0].init?.headers as Record<string, string>
    expect(firstHeaders['If-None-Match']).toBeUndefined()
  })

  it('replays cached body on 304 response', async () => {
    const fetchStub = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse({ id: 7, status: 'RUNNING' }, 'W/"abc"'))
      .mockResolvedValueOnce(notModified('W/"abc"'))
    globalThis.fetch = fetchStub as unknown as typeof fetch

    const first = await fetchBuild(7)
    const second = await fetchBuild(7)

    // Second call must have sent If-None-Match.
    const secondCall = fetchStub.mock.calls[1]
    const headers = (secondCall[1] as RequestInit).headers as Record<string, string>
    expect(headers['If-None-Match']).toBe('W/"abc"')

    // 304 → cached body returned, not a parse error from the empty 304 body.
    expect(second).toEqual(first)
    expect(second).toMatchObject({ id: 7, status: 'RUNNING' })
  })

  it('updates the cache when the ETag changes (status flipped to SUCCESS)', async () => {
    const fetchStub = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse({ id: 7, status: 'RUNNING' }, 'W/"hash-1"'))
      .mockResolvedValueOnce(jsonResponse({ id: 7, status: 'SUCCESS' }, 'W/"hash-2"'))
      .mockResolvedValueOnce(notModified('W/"hash-2"'))
    globalThis.fetch = fetchStub as unknown as typeof fetch

    const first = await fetchBuild(7)
    expect(first).toMatchObject({ status: 'RUNNING' })

    const second = await fetchBuild(7)
    expect(second).toMatchObject({ status: 'SUCCESS' })

    // Third call: server says still hash-2 → cached SUCCESS payload returned.
    const third = await fetchBuild(7)
    expect(third).toMatchObject({ status: 'SUCCESS' })

    // The third request must have sent the *latest* etag, not the stale one.
    const thirdCall = fetchStub.mock.calls[2]
    const headers = (thirdCall[1] as RequestInit).headers as Record<string, string>
    expect(headers['If-None-Match']).toBe('W/"hash-2"')
  })

  it('isolates the cache per URL — job and build do not collide', async () => {
    const fetchStub = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse({ id: 1, fullName: 'org/job' }, 'W/"job-1"'))
      .mockResolvedValueOnce(jsonResponse({ id: 2, status: 'RUNNING' }, 'W/"bld-1"'))
    globalThis.fetch = fetchStub as unknown as typeof fetch

    await fetchJob(1)
    await fetchBuild(2)

    const buildCall = fetchStub.mock.calls[1]
    const headers = (buildCall[1] as RequestInit).headers as Record<string, string>
    // Must NOT have leaked the job's etag onto the build URL.
    expect(headers['If-None-Match']).toBeUndefined()
  })

  it('propagates a non-ok, non-304 response as ApiError', async () => {
    const fetchStub = vi.fn().mockResolvedValueOnce(
      new Response(
        JSON.stringify({
          type: 'about:blank',
          title: 'Not Found',
          status: 404,
          detail: 'build 7 not found',
        }),
        { status: 404, headers: { 'Content-Type': 'application/problem+json' } },
      ),
    )
    globalThis.fetch = fetchStub as unknown as typeof fetch

    await expect(fetchBuild(7)).rejects.toMatchObject({ status: 404 })
  })
})
