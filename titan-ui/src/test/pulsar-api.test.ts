/**
 * Unit tests for the Pulsar SCM-source API client (src/api/pulsar.ts), backing
 * the Integrations card against PulsarSourcesApi (#1293). Hand-rolled fetch
 * stub — no MSW worker, no window.* globals. Adversarial: asserts the request
 * shape (method, body, trimming) AND that typed problem+json errors surface as
 * ApiError with the right status, not a generic throw.
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import {
  listPulsarSources,
  registerPulsarSource,
  syncPulsarSource,
  pulsarErrorMessage,
  type PulsarSourceDto,
} from '../api/pulsar'
import { ApiError } from '../api/types'

const SOURCE: PulsarSourceDto = {
  id: 7,
  nodeUrl: 'https://pulsar.example.com',
  nodeName: 'prod-east',
  repoCount: 4,
  lastPolledAt: '2026-06-15T10:00:00Z',
  createdAt: '2026-06-15T09:00:00Z',
}

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function problemResponse(status: number, title: string, detail: string): Response {
  return new Response(
    JSON.stringify({ type: 'about:blank', title, status, detail, instance: null }),
    { status, headers: { 'Content-Type': 'application/problem+json' } },
  )
}

let fetchMock: ReturnType<typeof vi.fn>

beforeEach(() => {
  fetchMock = vi.fn()
  vi.stubGlobal('fetch', fetchMock)
})
afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('listPulsarSources()', () => {
  it('GETs /api/v1/pulsar/sources and returns the rows', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse(200, [SOURCE]))
    const rows = await listPulsarSources()
    expect(rows).toHaveLength(1)
    expect(rows[0].id).toBe(7)
    expect(rows[0].nodeUrl).toBe('https://pulsar.example.com')
    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe('/api/v1/pulsar/sources')
    // No method override → defaults to GET (init has no method).
    expect(init?.method).toBeUndefined()
  })

  it('tolerates the server omitting null optional fields', async () => {
    // Server JsonInclude.NON_NULL strips nodeName/repoCount/lastPolledAt.
    const lean = { id: 9, nodeUrl: 'https://p2.example.com', createdAt: '2026-06-15T09:00:00Z' }
    fetchMock.mockResolvedValueOnce(jsonResponse(200, [lean]))
    const rows = await listPulsarSources()
    expect(rows[0].repoCount).toBeUndefined()
    expect(rows[0].nodeName).toBeUndefined()
    expect(rows[0].lastPolledAt).toBeUndefined()
  })
})

describe('registerPulsarSource()', () => {
  it('POSTs the trimmed nodeUrl + nodeName and returns 201 body', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse(201, SOURCE))
    const created = await registerPulsarSource({
      nodeUrl: '  https://pulsar.example.com  ',
      nodeName: '  prod-east  ',
    })
    expect(created.id).toBe(7)
    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe('/api/v1/pulsar/sources')
    expect(init?.method).toBe('POST')
    expect(JSON.parse(init!.body as string)).toEqual({
      nodeUrl: 'https://pulsar.example.com',
      nodeName: 'prod-east',
    })
  })

  it('omits nodeName entirely when blank (matches RegisterPulsarSourceRequest)', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse(201, SOURCE))
    await registerPulsarSource({ nodeUrl: 'https://pulsar.example.com', nodeName: '   ' })
    const body = JSON.parse(fetchMock.mock.calls[0][1]!.body as string)
    expect(body).toEqual({ nodeUrl: 'https://pulsar.example.com' })
    expect('nodeName' in body).toBe(false)
  })

  it('surfaces a 409 duplicate as a typed ApiError, not a generic throw', async () => {
    fetchMock.mockResolvedValueOnce(
      problemResponse(409, 'pulsar-source-exists', 'a Pulsar source is already registered'),
    )
    const err = await registerPulsarSource({ nodeUrl: 'https://dup.example.com' }).catch(
      (e) => e,
    )
    expect(err).toBeInstanceOf(ApiError)
    expect((err as ApiError).status).toBe(409)
    expect(pulsarErrorMessage(err)).toMatch(/already registered/i)
  })

  it('surfaces a 400 invalid-URL as a typed ApiError', async () => {
    fetchMock.mockResolvedValueOnce(
      problemResponse(400, 'nodeUrl must be a well-formed http(s) URL', 'bad url'),
    )
    const err = await registerPulsarSource({ nodeUrl: 'not-a-url' }).catch((e) => e)
    expect(err).toBeInstanceOf(ApiError)
    expect((err as ApiError).status).toBe(400)
  })

  it('surfaces a 502 unreachable-node as a typed ApiError', async () => {
    fetchMock.mockResolvedValueOnce(
      problemResponse(502, 'pulsar-node-unreachable', 'did not respond'),
    )
    const err = await registerPulsarSource({ nodeUrl: 'https://down.example.com' }).catch(
      (e) => e,
    )
    expect(err).toBeInstanceOf(ApiError)
    expect((err as ApiError).status).toBe(502)
    expect(pulsarErrorMessage(err)).toMatch(/reach|respond/i)
  })
})

describe('syncPulsarSource()', () => {
  it('POSTs /api/v1/pulsar/sources/{id}/sync and returns the refreshed row', async () => {
    const refreshed = { ...SOURCE, repoCount: 6, lastPolledAt: '2026-06-15T11:00:00Z' }
    fetchMock.mockResolvedValueOnce(jsonResponse(200, refreshed))
    const out = await syncPulsarSource(7)
    expect(out.repoCount).toBe(6)
    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe('/api/v1/pulsar/sources/7/sync')
    expect(init?.method).toBe('POST')
  })

  it('surfaces a 404 unknown-id as a typed ApiError', async () => {
    fetchMock.mockResolvedValueOnce(problemResponse(404, 'not found', 'pulsar source 99 not found'))
    const err = await syncPulsarSource(99).catch((e) => e)
    expect(err).toBeInstanceOf(ApiError)
    expect((err as ApiError).status).toBe(404)
  })
})
