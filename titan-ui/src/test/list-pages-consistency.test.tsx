/**
 * Consistency tests for the four operator list pages (#1190): workers, queue,
 * approvals, audit. Each must render the shared PageHeader frame + the shared
 * DataTable primitive, treat status via <StatusDot>/<Badge>, and — the
 * adversarial sad path — degrade a rejected fetch to the shared error panel
 * (testId `${page}-error`) with a retry link, never a raw string or blank route.
 */
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import {
  Outlet,
  RouterProvider,
  createMemoryHistory,
  createRootRoute,
  createRoute,
  createRouter,
} from '@tanstack/react-router'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { User, UserManager } from 'oidc-client-ts'

import { AuthProvider } from '../auth/AuthProvider'
import { setAccessToken } from '../auth/tokenStore'
import { Route as WorkersRoute } from '../routes/workers'
import { Route as QueueRoute } from '../routes/queue'
import { Route as ApprovalsRoute } from '../routes/approvals/index'
import { Route as AuditRoute } from '../routes/audit'
import { resetFetchMock, setupFetchMock } from './msw-handlers'

interface MatchResult { status: number; body: unknown }
type Handler = (url: URL, method: string) => MatchResult | null

function ok(path: string, body: unknown): Handler {
  return (url, method) =>
    method === 'GET' && url.pathname === path ? { status: 200, body } : null
}

function fail(path: string, status = 500): Handler {
  return (url, method) =>
    method === 'GET' && url.pathname === path
      ? {
          status,
          body: { type: 'about:blank', title: 'Boom', status, detail: 'backend exploded', instance: null },
        }
      : null
}

function makeUser(roles: string[]): User {
  return {
    access_token: 'test-token',
    expired: false,
    profile: { sub: 'u1', preferred_username: 'alice', groups: roles },
  } as unknown as User
}

function makeUserManager(user: User): UserManager {
  return {
    getUser: async () => user,
    signinRedirect: async () => undefined,
    signinRedirectCallback: async () => user,
    signoutRedirect: async () => undefined,
    removeUser: async () => undefined,
    events: {
      addUserLoaded: () => {}, removeUserLoaded: () => {},
      addUserUnloaded: () => {}, removeUserUnloaded: () => {},
      addAccessTokenExpired: () => {}, removeAccessTokenExpired: () => {},
    },
  } as unknown as UserManager
}

// TanStack's Route type is deeply generic; the test only reads
// `.options.component` / `.options.validateSearch`, so an `any` param keeps the
// generic mount harness readable without re-deriving the full Route generics.
// eslint-disable-next-line @typescript-eslint/no-explicit-any
function mount(route: any, path: string, handlers: Handler[], withAuth = false) {
  setupFetchMock(handlers)
  setAccessToken('test-token')
  const rootRoute = createRootRoute({ component: () => <Outlet /> })
  const child = createRoute({
    getParentRoute: () => rootRoute,
    path,
    component: route.options.component,
    validateSearch: route.options.validateSearch,
  })
  const router = createRouter({
    routeTree: rootRoute.addChildren([child]),
    history: createMemoryHistory({ initialEntries: [path] }),
  })
  const qc = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  const tree = (
    <QueryClientProvider client={qc}>
      <RouterProvider router={router} />
    </QueryClientProvider>
  )
  return render(
    withAuth ? <AuthProvider userManager={makeUserManager(makeUser(['ADMIN']))}>{tree}</AuthProvider> : tree,
  )
}

beforeEach(() => setAccessToken(null))
afterEach(() => {
  resetFetchMock()
  setAccessToken(null)
})

// ── Fixtures ──────────────────────────────────────────────────────────────────

const WORKERS = {
  items: [
    {
      id: 'w-1', name: 'alpha-1', state: 'ONLINE', pool: 'pool-a', labels: ['default'],
      currentTasks: 2, maxConcurrent: 4, cpuPct: null, memPct: null, diskPct: null,
      lastSeenAt: '2026-06-04T11:59:30Z', registeredAt: '2026-06-01T08:00:00Z',
    },
  ],
  total: 1, offset: 0, limit: 200,
}

const QUEUE = {
  items: [
    { taskId: 9001, buildId: 42, jobId: 1, jobName: 'main', queuedAt: '2026-06-04T10:00:00Z', waitingMs: 4000, priority: 5, requestedLabels: 'linux' },
  ],
  total: 1, offset: 0, limit: 200,
}

const APPROVALS = {
  items: [
    { id: 5, buildId: 42, prompt: 'Ship it?', approvers: ['alice'], expiresAt: '2026-06-04T13:00:00Z' },
  ],
  total: 1, offset: 0, limit: 50,
}

const AUDIT = {
  items: [
    { id: 1, occurredAt: '2026-06-04T09:00:00Z', actor: 'alice', action: 'JOB_CREATE', targetType: 'JOB', targetId: '42', detailsJson: '{"fullName":"org/x"}' },
  ],
  total: 1, offset: 0, limit: 50,
}

// ── Shared frame + DataTable + status primitive (populated) ────────────────────

describe('list pages — shared frame, DataTable, status primitive', () => {
  it('workers: PageHeader + per-pool DataTable + StatusDot', async () => {
    const { container } = mount(WorkersRoute, '/workers', [ok('/api/v1/workers', WORKERS)])
    await waitFor(() => expect(screen.getByTestId('worker-row-w-1')).toBeInTheDocument())
    expect(screen.getByRole('heading', { name: /workers/i })).toBeInTheDocument()
    expect(container.querySelector('table.tt')).not.toBeNull()
    expect(container.querySelector('.status-dot')).not.toBeNull()
  })

  it('queue: PageHeader + DataTable + StatusDot pressure cell', async () => {
    const { container } = mount(QueueRoute, '/queue', [
      ok('/api/v1/queue', QUEUE),
      ok('/api/v1/queue/recent', []),
    ])
    await waitFor(() => expect(screen.getByTestId('queue-row-9001')).toBeInTheDocument())
    expect(screen.getByRole('heading', { name: /build queue/i })).toBeInTheDocument()
    expect(screen.getByTestId('queue')).toBeInTheDocument()
    expect(container.querySelector('.status-dot')).not.toBeNull()
  })

  it('approvals: PageHeader + DataTable + Badge approver chip', async () => {
    const { container } = mount(ApprovalsRoute, '/approvals', [ok('/api/v1/approvals', APPROVALS)], true)
    await waitFor(() => expect(screen.getByTestId('approval-row-5')).toBeInTheDocument())
    expect(screen.getByRole('heading', { name: /approvals/i })).toBeInTheDocument()
    expect(container.querySelector('table.tt')).not.toBeNull()
    // approver rendered as a Badge primitive (.badge), not an inline span
    expect(container.querySelector('.badge')).not.toBeNull()
  })

  it('audit: PageHeader + DataTable + action Badge', async () => {
    const { container } = mount(AuditRoute, '/audit', [ok('/api/v1/audit', AUDIT)], true)
    await waitFor(() => expect(screen.getByTestId('audit-action-1')).toBeInTheDocument())
    expect(screen.getByRole('heading', { name: /audit log/i })).toBeInTheDocument()
    expect(container.querySelector('table.tt')).not.toBeNull()
    expect(screen.getByTestId('audit-action-1')).toHaveClass('badge')
  })
})

// ── Adversarial: rejected fetch → shared error panel + retry ───────────────────

describe('list pages — error state degrades to the shared panel (adversarial)', () => {
  it('workers error → workers-error panel + retry, no crash, no raw string', async () => {
    mount(WorkersRoute, '/workers', [fail('/api/v1/workers')])
    const panel = await screen.findByTestId('workers-error')
    expect(panel).toHaveClass('tt-empty')
    expect(panel.textContent).toContain('backend exploded')
    expect(screen.getByText('retry')).toBeInTheDocument()
    expect(screen.queryByTestId('workers-empty')).toBeNull()
  })

  it('queue error → queue-error panel + retry', async () => {
    mount(QueueRoute, '/queue', [fail('/api/v1/queue'), ok('/api/v1/queue/recent', [])])
    const panel = await screen.findByTestId('queue-error')
    expect(panel).toHaveClass('tt-empty')
    expect(screen.getByText('retry')).toBeInTheDocument()
    expect(screen.queryByTestId('queue-empty')).toBeNull()
  })

  it('approvals error → approvals-error panel + retry', async () => {
    mount(ApprovalsRoute, '/approvals', [fail('/api/v1/approvals')], true)
    const panel = await screen.findByTestId('approvals-error')
    expect(panel).toHaveClass('tt-empty')
    expect(screen.getByText('retry')).toBeInTheDocument()
    expect(screen.queryByTestId('approvals-empty')).toBeNull()
  })

  it('audit 500 → audit-error panel; 403 → dedicated role panel (not generic)', async () => {
    mount(AuditRoute, '/audit', [fail('/api/v1/audit', 500)], true)
    const panel = await screen.findByTestId('audit-error')
    expect(panel).toHaveClass('tt-empty')
    resetFetchMock()

    mount(AuditRoute, '/audit', [fail('/api/v1/audit', 403)], true)
    const panel403 = await screen.findByTestId('audit-error-403')
    expect(panel403.textContent).toMatch(/admin role required/i)
    expect(screen.queryByTestId('audit-empty')).toBeNull()
  })
})
