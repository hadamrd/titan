/**
 * Adversarial tests for the New-job dialog (#512).
 *
 * Covers the failure modes the SRE actually hits:
 *   - 400 problem+json from TitanYamlParser → inline error BELOW the script
 *     textarea (NEVER in a toast — context-jumping while editing YAML is bad).
 *   - 409 fullName collision → inline error below the name field.
 *   - 201 → dialog closes (we watch the onOpenChange contract).
 *   - Empty fullName → submit disabled.
 *   - Invalid fullName ("a b c") → inline pattern-error, submit disabled.
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import {
  RouterProvider,
  createRouter,
  createMemoryHistory,
  createRootRoute,
  createRoute,
  Outlet,
} from '@tanstack/react-router'
import { NewJobDialog } from '../components/NewJobDialog'
import { setupFetchMock, resetFetchMock } from './msw-handlers'
import type { JobDto } from '../api/types'

// Mock useNavigate so we can assert the Validate YAML link navigates without
// rooting around in router state (closes #751).
const navigateMock = vi.fn()
vi.mock('@tanstack/react-router', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@tanstack/react-router')>()
  return {
    ...actual,
    useNavigate: () => navigateMock,
  }
})

// ── Minimal in-test router (NewJobDialog calls useNavigate) ─────────────────

function Harness({ open, onOpenChange, onNetworkError }: {
  open: boolean
  onOpenChange: (o: boolean) => void
  onNetworkError?: (m: string) => void
}) {
  return (
    <>
      <Outlet />
      <NewJobDialog open={open} onOpenChange={onOpenChange} onNetworkError={onNetworkError} />
    </>
  )
}

function mount(props: {
  initialOpen?: boolean
  onOpenChange?: (o: boolean) => void
  onNetworkError?: (m: string) => void
}) {
  const open = props.initialOpen ?? true
  const onOpenChange = props.onOpenChange ?? (() => {})
  const onNetworkError = props.onNetworkError

  const rootRoute = createRootRoute({
    component: () => (
      <Harness open={open} onOpenChange={onOpenChange} onNetworkError={onNetworkError} />
    ),
  })
  const indexRoute = createRoute({
    getParentRoute: () => rootRoute,
    path: '/',
    component: () => null,
  })
  // The dialog navigates to /jobs/$jobId on success; we need a stub route to
  // accept that navigation without exploding.
  const jobRoute = createRoute({
    getParentRoute: () => rootRoute,
    path: '/jobs/$jobId',
    component: () => <div data-testid="stub-job-route">job</div>,
  })

  const router = createRouter({
    routeTree: rootRoute.addChildren([indexRoute, jobRoute]),
    history: createMemoryHistory({ initialEntries: ['/'] }),
  })

  const qc = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })

  return render(
    <QueryClientProvider client={qc}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
}

// ── Handlers ────────────────────────────────────────────────────────────────

interface MatchResult { status: number; body: unknown }
type Handler = (url: URL, method: string) => MatchResult | null

function jobsCreateHandler(result: MatchResult): Handler {
  return (url, method) => {
    if (method !== 'POST') return null
    if (url.pathname !== '/api/v1/jobs') return null
    return result
  }
}

beforeEach(() => {
  navigateMock.mockReset()
  // default: no handlers — tests install their own
})

afterEach(() => {
  resetFetchMock()
  vi.restoreAllMocks()
})

// ── Tests ───────────────────────────────────────────────────────────────────

describe('NewJobDialog', () => {
  it('submit is DISABLED while fullName is empty', async () => {
    setupFetchMock([])
    mount({ initialOpen: true })

    const submit = (await screen.findByTestId('new-job-submit')) as HTMLButtonElement
    expect(submit.disabled).toBe(true)
  })

  it('renders an inline pattern error for an invalid fullName ("a b c")', async () => {
    setupFetchMock([])
    mount({ initialOpen: true })

    const name = (await screen.findByTestId('new-job-fullname')) as HTMLInputElement
    fireEvent.change(name, { target: { value: 'a b c' } })

    // Inline error (NOT a toast) — under the fullName field.
    const err = screen.getByTestId('new-job-name-error')
    expect(err.textContent).toMatch(/letters|digits|only/i)

    const submit = screen.getByTestId('new-job-submit') as HTMLButtonElement
    expect(submit.disabled).toBe(true)
  })

  it('on 400, renders the parser detail BELOW the textarea, NOT in a toast', async () => {
    setupFetchMock([
      jobsCreateHandler({
        status: 400,
        body: {
          type: 'about:blank',
          title: 'Bad Request',
          status: 400,
          detail: 'stages: must be a non-empty list (line 1)',
          instance: null,
        },
      }),
    ])
    const onNetworkError = vi.fn()
    mount({ initialOpen: true, onNetworkError })

    fireEvent.change(await screen.findByTestId('new-job-fullname'), {
      target: { value: 'org/sample' },
    })
    // Submit (default script is valid client-side; server says nope)
    fireEvent.click(screen.getByTestId('new-job-submit'))

    const err = await screen.findByTestId('new-job-script-error')
    expect(err.textContent).toContain('stages: must be a non-empty list')
    // No toast — onNetworkError MUST NOT have fired for a 400.
    expect(onNetworkError).not.toHaveBeenCalled()
  })

  it('on 409, renders "Job name already taken" inline under fullName', async () => {
    setupFetchMock([
      jobsCreateHandler({
        status: 409,
        body: {
          type: 'about:blank',
          title: 'Conflict',
          status: 409,
          detail: "job with fullName 'org/sample' already exists",
          instance: null,
        },
      }),
    ])
    mount({ initialOpen: true })

    fireEvent.change(await screen.findByTestId('new-job-fullname'), {
      target: { value: 'org/sample' },
    })
    fireEvent.click(screen.getByTestId('new-job-submit'))

    const err = await screen.findByTestId('new-job-name-error')
    expect(err.textContent).toMatch(/already taken/i)
    // Script error MUST stay clear — the 409 is not a parser fault.
    expect(screen.queryByTestId('new-job-script-error')).toBeNull()
  })

  it('clicking "Validate YAML" navigates to /pipelines/validate (#751)', async () => {
    setupFetchMock([])
    mount({ initialOpen: true })

    const link = (await screen.findByTestId('new-job-validate-yaml')) as HTMLButtonElement
    // Must be a non-submit button so it never accidentally posts the form.
    expect(link.type).toBe('button')
    fireEvent.click(link)

    expect(navigateMock).toHaveBeenCalledWith({ to: '/pipelines/validate' })
  })

  it('on 201, calls onOpenChange(false) to close the dialog', async () => {
    const created: JobDto = {
      id: 1234,
      fullName: 'org/sample',
      displayName: 'org/sample',
      folderPath: null,
      enabled: true,
      createdAt: '2026-05-24T00:00:00Z',
      updatedAt: '2026-05-24T00:00:00Z',
    }
    setupFetchMock([jobsCreateHandler({ status: 201, body: created })])

    const onOpenChange = vi.fn()
    mount({ initialOpen: true, onOpenChange })

    fireEvent.change(await screen.findByTestId('new-job-fullname'), {
      target: { value: 'org/sample' },
    })
    fireEvent.click(screen.getByTestId('new-job-submit'))

    await waitFor(() => {
      expect(onOpenChange).toHaveBeenCalledWith(false)
    })
  })
})
