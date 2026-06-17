/**
 * Adversarial tests for the /jobs/$id trigger preview modal (issue #641).
 *
 * The modal exists so the user sees WHAT pipeline they are about to run
 * before firing a build. Failure modes that matter:
 *
 *   1. Trigger button opens the modal — and the pipeline YAML renders verbatim.
 *   2. Cancel button closes the modal WITHOUT firing the mutation.
 *   3. Confirm button fires the mutation against the right jobId AND closes
 *      the modal on success.
 *   4. Job with no pipelineScript still gets a usable modal — a graceful
 *      placeholder, not a blank pane.
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { RouterProvider, createRouter, createMemoryHistory } from '@tanstack/react-router'
import { routeTree } from '../routeTree.gen'
import { AuthContext, type AuthState } from '../auth/AuthProvider'
import { setAccessToken } from '../auth/tokenStore'
import { setupFetchMock, resetFetchMock } from './msw-handlers'
import type { JobDto } from '../api/types'

const SAMPLE_YAML =
  'stages:\n  - stage: build\n    steps:\n      - step: shell\n        cmd: echo hi'

const fakeAuth: AuthState = {
  user: { access_token: 'fake', expired: false } as unknown as AuthState['user'],
  isLoading: false,
  isAuthenticated: true,
  signinRedirect: async () => {},
  signinRedirectCallback: async () => ({} as never),
  signoutRedirect: async () => {},
}

function renderAt(path: string) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const router = createRouter({
    routeTree,
    history: createMemoryHistory({ initialEntries: [path] }),
  })
  return render(
    <AuthContext.Provider value={fakeAuth}>
      <QueryClientProvider client={qc}>
        <RouterProvider router={router} />
      </QueryClientProvider>
    </AuthContext.Provider>,
  )
}

function jobPayload(overrides: Partial<JobDto> = {}): JobDto {
  const base: JobDto = {
    id: 301,
    fullName: 'org/preview',
    displayName: 'preview-job',
    folderPath: null,
    enabled: true,
    pipelineScript: SAMPLE_YAML,
    createdAt: '2026-05-20T08:00:00Z',
    updatedAt: '2026-05-20T08:00:00Z',
  }
  return { ...base, ...overrides }
}

let triggerCalls: Array<{ url: string; method: string }> = []

function installMocks(job: JobDto) {
  setupFetchMock([
    (url, method) => {
      if (method !== 'GET') return null
      if (url.pathname === `/api/v1/jobs/${job.id}`) {
        return { status: 200, body: job }
      }
      return null
    },
    (url, method) => {
      if (method !== 'GET') return null
      if (url.pathname === `/api/v1/jobs/${job.id}/builds`) {
        return {
          status: 200,
          body: { items: [], total: 0, offset: 0, limit: 50 },
        }
      }
      return null
    },
    (url, method) => {
      if (method !== 'POST') return null
      if (url.pathname === `/api/v1/jobs/${job.id}/builds`) {
        triggerCalls.push({ url: url.pathname, method })
        return {
          status: 201,
          body: {
            buildId: 7777,
            buildNumber: 12,
            jobId: job.id,
            queuedPosition: 1,
          },
        }
      }
      return null
    },
  ])
}

beforeEach(() => {
  triggerCalls = []
  setAccessToken('fake')
})

afterEach(() => {
  resetFetchMock()
  setAccessToken(null)
  vi.restoreAllMocks()
})

describe('/jobs/$id trigger preview modal (issue #641)', () => {
  it('clicking Trigger opens the modal with the pipelineScript rendered verbatim', async () => {
    installMocks(jobPayload())
    renderAt('/pipelines/301')

    const btn = await screen.findByTestId('pipeline-detail-trigger-btn')
    fireEvent.click(btn)

    const body = await screen.findByTestId('trigger-preview-body')
    expect(body.textContent).toBe(SAMPLE_YAML)
    // Headline names the job — user knows what they are about to fire.
    const title = document.getElementById('trigger-preview-title')
    expect(title?.textContent).toContain('org/preview')
    expect(title?.textContent?.toLowerCase()).toContain('about to run pipeline')
    // No mutation yet — opening the modal must not fire a build.
    expect(triggerCalls.length).toBe(0)
  })

  it('Cancel closes the modal WITHOUT firing the trigger mutation', async () => {
    installMocks(jobPayload())
    renderAt('/pipelines/301')

    fireEvent.click(await screen.findByTestId('pipeline-detail-trigger-btn'))
    await screen.findByTestId('trigger-preview-body')

    fireEvent.click(screen.getByTestId('trigger-preview-cancel'))

    await waitFor(() =>
      expect(screen.queryByTestId('trigger-preview-body')).toBeNull(),
    )
    // Give any in-flight call a tick — it must not arrive.
    await new Promise((r) => setTimeout(r, 30))
    expect(triggerCalls.length).toBe(0)
  })

  it('Confirm fires the mutation against the right jobId and closes the modal', async () => {
    installMocks(jobPayload())
    renderAt('/pipelines/301')

    fireEvent.click(await screen.findByTestId('pipeline-detail-trigger-btn'))
    await screen.findByTestId('trigger-preview-body')

    fireEvent.click(screen.getByTestId('trigger-preview-confirm'))

    await waitFor(() => expect(triggerCalls.length).toBe(1))
    expect(triggerCalls[0].url).toBe('/api/v1/jobs/301/builds')
    expect(triggerCalls[0].method).toBe('POST')

    // On success the modal is dismissed.
    await waitFor(() =>
      expect(screen.queryByTestId('trigger-preview-body')).toBeNull(),
    )
  })

  it('renders a graceful placeholder when the job has no pipelineScript', async () => {
    installMocks(jobPayload({ pipelineScript: undefined }))
    renderAt('/pipelines/301')

    fireEvent.click(await screen.findByTestId('pipeline-detail-trigger-btn'))

    // Empty-state surface, not a blank pane.
    const empty = await screen.findByTestId('trigger-preview-empty')
    expect(empty.textContent?.toLowerCase()).toContain('pipeline not loaded')
    // The mono body MUST NOT render in parallel — would confuse the user.
    expect(screen.queryByTestId('trigger-preview-body')).toBeNull()
    // Confirm is still available — server has the canonical script.
    expect(screen.getByTestId('trigger-preview-confirm')).toBeInTheDocument()
  })
})
