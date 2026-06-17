/**
 * Render smoke-test for BuildsTable (#1071).
 *
 * Three states (one file):
 *  - isLoading=true → renders the skeleton row strip.
 *  - builds=[] + !isLoading → renders the supplied emptySlot.
 *  - builds=[N] → renders N rows.
 *  - split (inFlight + history) → renders both section headings.
 *
 * BuildRow is mocked because the real component uses
 * `@tanstack/react-router` Link, which would require a RouterProvider
 * to render — too heavy for a smoke-test.
 */
import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import type { BuildRowItem } from '../BuildRow'
import { BuildsTable } from '../BuildsTable'

// vi.mock() is hoisted above imports by Vitest at compile-time.
vi.mock('@/components/builds/BuildRow', () => ({
  BuildRow: ({ build }: { build: BuildRowItem }) => (
    <div data-testid={`mock-build-row-${build.id}`}>{build.jobName}</div>
  ),
}))

function fakeBuild(id: number, status: BuildRowItem['status'] = 'SUCCESS'): BuildRowItem {
  return {
    id,
    jobId: 100 + id,
    buildNumber: id,
    status,
    durationMs: 1234,
    startedAt: '2026-01-01T00:00:00Z',
    finishedAt: '2026-01-01T00:01:00Z',
    triggeredBy: null,
    triggerType: 'manual',
    jobName: `job-${id}`,
  }
}

describe('BuildsTable', () => {
  it('renders the skeleton row strip when isLoading=true', () => {
    render(
      <BuildsTable builds={[]} isLoading={true} timeFormat="relative" />,
    )
    expect(screen.getByTestId('builds-list-loading')).toBeInTheDocument()
  })

  it('renders the empty slot when builds=[] and !isLoading', () => {
    render(
      <BuildsTable
        builds={[]}
        isLoading={false}
        timeFormat="relative"
        emptySlot={<div data-testid="empty-marker">No builds</div>}
      />,
    )
    expect(screen.getByTestId('empty-marker')).toBeInTheDocument()
  })

  it('renders one row per build', () => {
    const builds = [fakeBuild(1), fakeBuild(2), fakeBuild(3)]
    render(<BuildsTable builds={builds} isLoading={false} timeFormat="relative" />)
    expect(screen.getByTestId('builds-list')).toBeInTheDocument()
    expect(screen.getByTestId('mock-build-row-1')).toBeInTheDocument()
    expect(screen.getByTestId('mock-build-row-2')).toBeInTheDocument()
    expect(screen.getByTestId('mock-build-row-3')).toBeInTheDocument()
  })

  it('renders inflight + history sections when split has inFlight rows', () => {
    const running = fakeBuild(10, 'RUNNING')
    const success = fakeBuild(11, 'SUCCESS')
    render(
      <BuildsTable
        builds={[running, success]}
        isLoading={false}
        timeFormat="relative"
        split={{ inFlight: [running], history: [success] }}
      />,
    )
    expect(screen.getByTestId('builds-section-inflight')).toBeInTheDocument()
    expect(screen.getByTestId('builds-section-history')).toBeInTheDocument()
    expect(screen.getByTestId('builds-list-inflight')).toBeInTheDocument()
    expect(screen.getByTestId('builds-list-history')).toBeInTheDocument()
  })

  it('falls back to flat list when split.inFlight is empty', () => {
    const success = fakeBuild(20)
    render(
      <BuildsTable
        builds={[success]}
        isLoading={false}
        timeFormat="relative"
        split={{ inFlight: [], history: [success] }}
      />,
    )
    expect(screen.queryByTestId('builds-section-inflight')).toBeNull()
    expect(screen.getByTestId('builds-list')).toBeInTheDocument()
  })
})
