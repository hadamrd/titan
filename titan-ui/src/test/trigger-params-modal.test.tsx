/**
 * Adversarial tests for the trigger-with-params modal (closes #779 — UI half
 * of #774). Covers the five failure modes that matter:
 *
 *   1. 0 declared params → no modal, direct fire on Run pipeline click.
 *   2. 3 declared params (mix of types) → modal renders 3 type-correct inputs,
 *      every default pre-filled exactly as declared.
 *   3. Override one param → POST body carries it; un-overridden params are NOT
 *      in the wire body (server applies defaults).
 *   4. Cancel → no API call fires.
 *   5. Backend rejects with 400 → toast/inline error renders, modal STAYS open
 *      with values intact (no data loss for the user).
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { RouterProvider, createRouter, createMemoryHistory } from '@tanstack/react-router'
import { routeTree } from '../routeTree.gen'
import { AuthContext, type AuthState } from '../auth/AuthProvider'
import { setAccessToken } from '../auth/tokenStore'
import { setupFetchMock, resetFetchMock } from './msw-handlers'
import type { JobDto, PipelineParameterDto } from '../api/types'
import { TriggerParamsModal } from '../components/TriggerParamsModal'

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

const job: JobDto = {
  id: 501,
  fullName: 'org/params-job',
  displayName: 'params-job',
  folderPath: null,
  enabled: true,
  createdAt: '2026-05-20T08:00:00Z',
  updatedAt: '2026-05-20T08:00:00Z',
  pipelineScript: 'pipeline: {}',
}

interface TriggerCall {
  body: Record<string, unknown>
}

let triggerCalls: TriggerCall[] = []
let triggerResponse: { status: number; body: unknown } = {
  status: 201,
  body: { buildId: 9999, buildNumber: 42, status: 'QUEUED' },
}

function installMocks(parameters: PipelineParameterDto[]) {
  setupFetchMock([
    (url, method) => {
      if (method !== 'GET') return null
      if (url.pathname === `/api/v1/jobs/${job.id}`) {
        return { status: 200, body: job }
      }
      if (url.pathname === `/api/v1/jobs/${job.id}/parameters`) {
        return { status: 200, body: parameters }
      }
      if (url.pathname === `/api/v1/jobs/${job.id}/builds`) {
        return {
          status: 200,
          body: { items: [], total: 0, offset: 0, limit: 50 },
        }
      }
      if (url.pathname === `/api/v1/jobs/${job.id}/stats`) {
        return {
          status: 200,
          body: {
            totalBuilds: 0,
            failedBuilds: 0,
            failureRate: 0,
            p50DurationMs: null,
            p95DurationMs: null,
            dailyBuckets: [],
            window: '30d',
          },
        }
      }
      if (url.pathname === `/api/v1/jobs/${job.id}/triggers`) {
        return { status: 200, body: [] }
      }
      if (url.pathname === '/api/v1/credentials') {
        return { status: 200, body: { items: [], total: 0, offset: 0, limit: 200 } }
      }
      if (url.pathname === '/api/v1/me/starred-jobs') {
        return { status: 200, body: [] }
      }
      return null
    },
    (url, method, body) => {
      if (method !== 'POST') return null
      if (url.pathname !== `/api/v1/jobs/${job.id}/builds`) return null
      const parsed = body ? (JSON.parse(body) as Record<string, unknown>) : {}
      triggerCalls.push({ body: parsed })
      return triggerResponse
    },
  ])
  setAccessToken('fake')
}

beforeEach(() => {
  triggerCalls = []
  triggerResponse = {
    status: 201,
    body: { buildId: 9999, buildNumber: 42, status: 'QUEUED' },
  }
})

afterEach(() => {
  resetFetchMock()
  setAccessToken(null)
  vi.restoreAllMocks()
})

describe('TriggerParamsModal (#779)', () => {
  it('0 params → no modal, direct fire', async () => {
    installMocks([])
    renderAt(`/pipelines/${job.id}`)

    const btn = await screen.findByTestId('pipeline-detail-trigger-btn')
    fireEvent.click(btn)

    // The preview modal (existing 0-params behavior) shows — confirm fires the build.
    const confirm = await screen.findByTestId('trigger-preview-confirm')
    fireEvent.click(confirm)

    await waitFor(() => expect(triggerCalls.length).toBe(1))
    // Body MUST NOT include a `parameters` key when the user had nothing to override.
    expect(triggerCalls[0].body).not.toHaveProperty('parameters')

    // The dedicated params modal MUST NOT have rendered.
    expect(screen.queryByTestId('trigger-params-confirm')).toBeNull()
  })

  it('3 params (string/boolean/choice) → modal renders 3 type-correct inputs, defaults pre-filled', async () => {
    const params: PipelineParameterDto[] = [
      {
        name: 'branch',
        type: 'string',
        defaultValue: 'main',
        description: 'Git ref to build',
        required: false,
        choices: [],
      },
      {
        name: 'dryRun',
        type: 'boolean',
        defaultValue: true,
        description: null,
        required: false,
        choices: [],
      },
      {
        name: 'env',
        type: 'choice',
        defaultValue: 'staging',
        description: 'Deployment target',
        required: true,
        choices: ['dev', 'staging', 'prod'],
      },
    ]
    installMocks(params)
    renderAt(`/pipelines/${job.id}`)

    const btn = await screen.findByTestId('pipeline-detail-trigger-btn')
    fireEvent.click(btn)

    // Modal opens once /parameters resolves.
    await screen.findByTestId('trigger-params-confirm')

    const branchInput = screen.getByTestId('trigger-param-branch-input') as HTMLInputElement
    expect(branchInput.type).toBe('text')
    expect(branchInput.value).toBe('main')

    const dryRunInput = screen.getByTestId('trigger-param-dryRun-input') as HTMLInputElement
    expect(dryRunInput.type).toBe('checkbox')
    expect(dryRunInput.checked).toBe(true)

    const envInput = screen.getByTestId('trigger-param-env-input') as HTMLSelectElement
    expect(envInput.tagName).toBe('SELECT')
    expect(envInput.value).toBe('staging')
    // Options match the declared `choices` array.
    expect(Array.from(envInput.options).map((o) => o.value)).toEqual(['dev', 'staging', 'prod'])

    // Hint/description renders under the labeled field.
    expect(screen.getByText('Git ref to build')).toBeInTheDocument()
    expect(screen.getByText('Deployment target')).toBeInTheDocument()
  })

  it('override one param → POST body carries it; un-overridden params are NOT in body', async () => {
    const params: PipelineParameterDto[] = [
      {
        name: 'branch',
        type: 'string',
        defaultValue: 'main',
        required: false,
        choices: [],
      },
      {
        name: 'count',
        type: 'number',
        defaultValue: 3,
        required: false,
        choices: [],
      },
    ]
    installMocks(params)
    renderAt(`/pipelines/${job.id}`)

    fireEvent.click(await screen.findByTestId('pipeline-detail-trigger-btn'))
    await screen.findByTestId('trigger-params-confirm')

    // Override branch; leave count at its default.
    const branchInput = screen.getByTestId('trigger-param-branch-input')
    fireEvent.change(branchInput, { target: { value: 'feature/x' } })

    fireEvent.click(screen.getByTestId('trigger-params-confirm'))

    await waitFor(() => expect(triggerCalls.length).toBe(1))
    const body = triggerCalls[0].body as { parameters?: Record<string, string> }
    expect(body.parameters).toEqual({ branch: 'feature/x' })
    expect(body.parameters).not.toHaveProperty('count')
  })

  it('cancel → no API call fires', async () => {
    const params: PipelineParameterDto[] = [
      {
        name: 'branch',
        type: 'string',
        defaultValue: 'main',
        required: false,
        choices: [],
      },
    ]
    installMocks(params)
    renderAt(`/pipelines/${job.id}`)

    fireEvent.click(await screen.findByTestId('pipeline-detail-trigger-btn'))
    await screen.findByTestId('trigger-params-confirm')

    fireEvent.click(screen.getByTestId('trigger-params-cancel'))

    // Wait a tick — no POST should have happened.
    await new Promise((r) => setTimeout(r, 30))
    expect(triggerCalls.length).toBe(0)
    // Modal closed.
    expect(screen.queryByTestId('trigger-params-confirm')).toBeNull()
  })

  // Regression for #791 — Playwright strict-mode violation on /jobs/$id when
  // a second "Run pipeline" affordance leaked into the chrome. The page MUST
  // surface exactly one button with that accessible name, regardless of
  // whether the job declares 0 or N parameters.
  it('regression #791 — exactly one "Run pipeline" button (0 params)', async () => {
    installMocks([])
    renderAt(`/pipelines/${job.id}`)
    await screen.findByTestId('pipeline-detail-trigger-btn')
    expect(screen.getAllByRole('button', { name: /run pipeline/i })).toHaveLength(1)
  })

  it('regression #791 — exactly one "Run pipeline" button (3 params)', async () => {
    const params: PipelineParameterDto[] = [
      { name: 'branch', type: 'string', defaultValue: 'main', required: false, choices: [] },
      { name: 'dryRun', type: 'boolean', defaultValue: false, required: false, choices: [] },
      {
        name: 'env',
        type: 'choice',
        defaultValue: 'staging',
        required: true,
        choices: ['dev', 'staging', 'prod'],
      },
    ]
    installMocks(params)
    renderAt(`/pipelines/${job.id}`)
    await screen.findByTestId('pipeline-detail-trigger-btn')
    // Before clicking: still exactly one button on the page.
    expect(screen.getAllByRole('button', { name: /run pipeline/i })).toHaveLength(1)

    // Clicking should open the params modal (proves keeper is the params-aware path).
    fireEvent.click(screen.getByTestId('pipeline-detail-trigger-btn'))
    await screen.findByTestId('trigger-params-confirm')
  })

  it('backend 400 → error renders, modal stays open with values intact', async () => {
    const params: PipelineParameterDto[] = [
      {
        name: 'branch',
        type: 'string',
        defaultValue: 'main',
        required: false,
        choices: [],
      },
    ]
    installMocks(params)
    triggerResponse = {
      status: 400,
      body: {
        type: 'about:blank',
        title: 'Bad Request',
        status: 400,
        detail: 'unknown parameter: branch',
        instance: null,
      },
    }
    renderAt(`/pipelines/${job.id}`)

    fireEvent.click(await screen.findByTestId('pipeline-detail-trigger-btn'))
    await screen.findByTestId('trigger-params-confirm')

    const branchInput = screen.getByTestId('trigger-param-branch-input') as HTMLInputElement
    fireEvent.change(branchInput, { target: { value: 'feature/x' } })

    fireEvent.click(screen.getByTestId('trigger-params-confirm'))

    // The 400 surfaces inline.
    const err = await screen.findByTestId('trigger-params-error')
    expect(err.textContent).toContain('unknown parameter: branch')

    // Modal is still open AND the user's edited value is preserved.
    const stillBranch = screen.getByTestId('trigger-param-branch-input') as HTMLInputElement
    expect(stillBranch.value).toBe('feature/x')
  })
})

// Regression for the operator-reported "modal keeps returning, can't get rid of
// it" trap: when the trigger request stalls (isPending stays true), EVERY exit —
// the Cancel button, Escape, and a backdrop click — was disabled/guarded, leaving
// the user stuck. A modal must ALWAYS be dismissable. Confirm stays guarded (no
// double-submit). This is the test that would have caught the golden-path break.
describe('TriggerParamsModal — never traps the user while a trigger is pending', () => {
  const oneParam: PipelineParameterDto[] = [
    { name: 'branch', type: 'string', defaultValue: 'main', required: false, choices: [] },
  ]

  it('Cancel / Escape / backdrop all dismiss even when isPending', () => {
    const onCancel = vi.fn()
    render(
      <TriggerParamsModal
        jobFullName="org/params-job"
        parameters={oneParam}
        isPending={true}
        onCancel={onCancel}
        onConfirm={() => {}}
      />,
    )
    // Cancel button must be enabled + functional while pending (was disabled → trap).
    const cancelBtn = screen.getByTestId('trigger-params-cancel') as HTMLButtonElement
    expect(cancelBtn.disabled).toBe(false)
    fireEvent.click(cancelBtn)
    expect(onCancel).toHaveBeenCalledTimes(1)
    // Escape dismisses while pending.
    fireEvent.keyDown(window, { key: 'Escape' })
    expect(onCancel).toHaveBeenCalledTimes(2)
    // Backdrop click dismisses while pending.
    fireEvent.click(screen.getByTestId('trigger-params-backdrop'))
    expect(onCancel).toHaveBeenCalledTimes(3)
    // Confirm STAYS disabled while pending — no double-submit.
    expect(
      (screen.getByTestId('trigger-params-confirm') as HTMLButtonElement).disabled,
    ).toBe(true)
  })
})
