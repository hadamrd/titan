/**
 * Tests for the real API hooks (useJobs, useJob, useJobBuilds, useBuild, useBuildNodes).
 * Uses hand-rolled fetch mock — no window.* globals, no MSW worker.
 */
import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { renderHook, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createElement } from 'react'
import type { ReactNode } from 'react'
import {
  setupFetchMock,
  resetFetchMock,
  SEED_JOB,
  SEED_BUILD,
  SEED_RUNNING_BUILD,
  SEED_FLOW_NODES,
} from './msw-handlers'
import { useJobs, useJob, useJobBuilds, useBuild, useBuildNodes } from '../api/hooks'

function makeWrapper() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return function Wrapper({ children }: { children: ReactNode }) {
    return createElement(QueryClientProvider, { client: qc }, children)
  }
}

beforeEach(() => setupFetchMock())
afterEach(() => resetFetchMock())

// ── useJobs ───────────────────────────────────────────────────────────────────

describe('useJobs()', () => {
  it('returns paginated job list', async () => {
    const { result } = renderHook(() => useJobs(), { wrapper: makeWrapper() })
    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(result.current.data?.items).toHaveLength(1)
    expect(result.current.data?.items[0].id).toBe(SEED_JOB.id)
    expect(result.current.data?.total).toBe(1)
  })

  it('every job has required fields', async () => {
    const { result } = renderHook(() => useJobs(), { wrapper: makeWrapper() })
    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    for (const job of result.current.data?.items ?? []) {
      expect(job).toHaveProperty('id')
      expect(job).toHaveProperty('fullName')
      expect(job).toHaveProperty('displayName')
      expect(typeof job.enabled).toBe('boolean')
    }
  })
})

// ── useJob ────────────────────────────────────────────────────────────────────

describe('useJob(id)', () => {
  it('returns a single job by id', async () => {
    const { result } = renderHook(() => useJob(SEED_JOB.id), { wrapper: makeWrapper() })
    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(result.current.data?.id).toBe(SEED_JOB.id)
    expect(result.current.data?.displayName).toBe(SEED_JOB.displayName)
  })

  it('surfaces ApiError with status 404 for unknown job', async () => {
    const { result } = renderHook(() => useJob(9999), { wrapper: makeWrapper() })
    await waitFor(() => expect(result.current.isError).toBe(true))
    const err = result.current.error as { status?: number }
    expect(err.status).toBe(404)
  })

  it('stays disabled when jobId is undefined', () => {
    const { result } = renderHook(() => useJob(undefined), { wrapper: makeWrapper() })
    expect(result.current.fetchStatus).toBe('idle')
  })
})

// ── useJobBuilds ──────────────────────────────────────────────────────────────

describe('useJobBuilds(jobId)', () => {
  it('returns builds for job 1', async () => {
    const { result } = renderHook(() => useJobBuilds(1), { wrapper: makeWrapper() })
    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(result.current.data?.items.length).toBeGreaterThan(0)
    expect(result.current.data?.items.map((b) => b.id)).toContain(SEED_BUILD.id)
  })

  it('stays disabled when jobId is undefined', () => {
    const { result } = renderHook(() => useJobBuilds(undefined), { wrapper: makeWrapper() })
    expect(result.current.fetchStatus).toBe('idle')
  })
})

// ── useBuild ──────────────────────────────────────────────────────────────────

describe('useBuild(id)', () => {
  it('returns build detail for a terminal build (no polling)', async () => {
    const { result } = renderHook(() => useBuild(SEED_BUILD.id), { wrapper: makeWrapper() })
    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(result.current.data?.id).toBe(SEED_BUILD.id)
    expect(result.current.data?.status).toBe('SUCCESS')
    expect(result.current.data?.durationMs).toBe(179000)
  })

  it('returns a running build', async () => {
    const { result } = renderHook(() => useBuild(SEED_RUNNING_BUILD.id), {
      wrapper: makeWrapper(),
    })
    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(result.current.data?.status).toBe('RUNNING')
    expect(result.current.data?.durationMs).toBeNull()
  })

  it('surfaces ApiError 404 for unknown build', async () => {
    const { result } = renderHook(() => useBuild(9999), { wrapper: makeWrapper() })
    await waitFor(() => expect(result.current.isError).toBe(true))
    const err = result.current.error as { status?: number }
    expect(err.status).toBe(404)
  })
})

// ── useBuildNodes ─────────────────────────────────────────────────────────────

describe('useBuildNodes(buildId)', () => {
  it('returns flow nodes for build 42', async () => {
    const { result } = renderHook(() => useBuildNodes(SEED_BUILD.id), { wrapper: makeWrapper() })
    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(result.current.data).toHaveLength(SEED_FLOW_NODES.length)
    expect(result.current.data?.[0].nodeId).toBe('n-1')
    expect(result.current.data?.[0].status).toBe('SUCCESS')
  })

  it('returns empty array for a build with no nodes', async () => {
    // build 43 has no seeded nodes
    const { result } = renderHook(() => useBuildNodes(SEED_RUNNING_BUILD.id), {
      wrapper: makeWrapper(),
    })
    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(result.current.data).toHaveLength(0)
  })
})
