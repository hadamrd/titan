/**
 * GitHub App API — wire layer for the V1 headline "Connect GitHub" experience
 * (design/63, epic #831). The backend lives in `io.adaptiq.titan.scm.github`
 * (child A — #832) and is being implemented in parallel; the field shapes here
 * mirror the design doc and the agreed DTO surface:
 *
 *   GET    /api/v1/github-app                                — current App row (404 if none)
 *   POST   /api/v1/github-app/manifest-callback?code=...     — finalise App creation
 *   GET    /api/v1/github-app/installations                  — installs known to this rig
 *   POST   /api/v1/github-app/installations/{id}/sync        — rescan repos
 *
 * Per-install repos + per-repo discovered pipelines come back nested inside the
 * installation list so the /integrations/github page can render in a single
 * round-trip. (The backend agent has agreed to this shape — see #836 review.)
 *
 * All hooks are CSP-clean (no window.* server context) and route through the
 * same apiFetch helper as the rest of the surface. Auth: bearer mirrored from
 * the AuthProvider via tokenStore.
 */
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { getAccessToken } from '@/auth/tokenStore'
import { ApiError, type ProblemJson } from './types'

// Same-origin: nginx proxies /api → titan-server. Closes #898.
const BASE_URL = ''

// ── wire types ───────────────────────────────────────────────────────────────

/**
 * One row of `titan_github_app`. There is at most one per rig (the App is a
 * tenant-singleton — see design/63 §2). `htmlUrl` is e.g.
 * `https://github.com/apps/titan-acme`; the install URL is
 * `${htmlUrl}/installations/new`.
 */
export interface GithubAppDto {
  id: number
  appId: number
  slug: string
  name: string
  htmlUrl: string
  createdAt: string // ISO-8601
}

/**
 * One discovered pipeline file inside a repo — the parsed `name`/`stages`/
 * `triggers` come from the server-side `TitanYamlParser` so the UI does not
 * re-parse YAML. `paramsCount` mirrors the declared `parameters:` block.
 */
export interface DiscoveredPipelineDto {
  filename: string // e.g. ".titan/pipelines/build.yml"
  name: string // pipeline display name (from YAML `name:` or filename)
  stagesCount: number
  triggers: ReadonlyArray<string> // e.g. ["push", "pull_request"]
  paramsCount: number
  /**
   * True iff a {@code jobs} row already exists for this repo+filename — the UI
   * disables the [Enable] button and shows "Enabled" instead.
   */
  enabled: boolean
  /** Populated server-side only when {@code enabled === true}. */
  jobId?: number | null
}

export interface GithubRepoDto {
  /** Full repo name, e.g. `hadamrd/titan-e2e-fixture`. */
  fullName: string
  /** Default branch (used for the [Sync now] fetch). */
  defaultBranch: string
  htmlUrl: string
  pipelines: ReadonlyArray<DiscoveredPipelineDto>
}

export interface GithubInstallationDto {
  id: number
  /** GitHub's numeric install id — kept distinct from our row id. */
  githubInstallationId: number
  accountLogin: string // e.g. "acme-co"
  accountType: 'Organization' | 'User'
  /** True when the org admin has paused the App on GitHub. */
  suspended: boolean
  createdAt: string // ISO-8601
  repos: ReadonlyArray<GithubRepoDto>
}

// ── apiFetch (local copy — see hooks.ts rationale) ───────────────────────────

async function apiFetch<T>(path: string, init?: RequestInit): Promise<T> {
  const token = getAccessToken()
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    ...(init?.headers as Record<string, string> | undefined),
  }
  if (token) headers['Authorization'] = `Bearer ${token}`
  const res = await fetch(`${BASE_URL}${path}`, { ...init, headers })
  if (!res.ok) {
    let problem: ProblemJson
    try {
      problem = (await res.json()) as ProblemJson
    } catch {
      problem = {
        type: 'about:blank',
        title: res.statusText || 'Unknown error',
        status: res.status,
        detail: null,
        instance: null,
      }
    }
    throw new ApiError(res.status, problem)
  }
  if (res.status === 204 || res.headers.get('content-length') === '0') {
    return null as T
  }
  return res.json() as Promise<T>
}

// ── hooks ────────────────────────────────────────────────────────────────────

/**
 * GET /api/v1/github-app — returns the rig's App row, or {@code null} when the
 * admin has not yet completed the manifest-callback step. A 404 from the server
 * is normalised to {@code null} so consumers can branch on data shape without
 * try/catching every render.
 */
export function useGithubApp() {
  return useQuery<GithubAppDto | null, ApiError>({
    queryKey: ['github-app'],
    queryFn: async () => {
      try {
        const result = await apiFetch<GithubAppDto | null>('/api/v1/github-app')
        return result ?? null
      } catch (e) {
        if (e instanceof ApiError && e.status === 404) return null
        throw e
      }
    },
    staleTime: 10_000,
  })
}

/**
 * GET /api/v1/github-app/installations — installs known to this rig, with
 * nested repos + pipelines. Polls every 2s while the admin is on the "Connect
 * GitHub" screen so a newly-installed App appears without a manual refresh.
 * The {@code refetchIntervalInBackground: false} default keeps the poll quiet
 * once the tab loses focus.
 */
export function useGithubInstallations(opts?: { pollMs?: number }) {
  return useQuery<GithubInstallationDto[], ApiError>({
    queryKey: ['github-app', 'installations'],
    queryFn: () => apiFetch<GithubInstallationDto[]>('/api/v1/github-app/installations'),
    refetchInterval: opts?.pollMs ?? false,
    staleTime: 5_000,
  })
}

/**
 * POST /api/v1/github-app/manifest-callback?code=... — exchanges the temp code
 * GitHub redirected back with for the App's id + private key + webhook secret,
 * server-side. The UI never sees these — the server persists them in
 * `titan_github_app`. Idempotent: a re-POST with the same code returns the
 * existing row.
 */
export function useManifestCallback() {
  const qc = useQueryClient()
  return useMutation<GithubAppDto, ApiError, { code: string }>({
    mutationFn: ({ code }) =>
      apiFetch<GithubAppDto>(
        `/api/v1/github-app/manifest-callback?code=${encodeURIComponent(code)}`,
        { method: 'POST' },
      ),
    onSuccess: (data) => {
      qc.setQueryData(['github-app'], data)
    },
  })
}

/**
 * POST /api/v1/github-app/installations/{id}/sync — re-runs the repo + pipeline
 * scanner for one installation. The mutation invalidates the installations
 * query so the table re-renders with the fresh `repos[].pipelines[]` content.
 */
export function useSyncInstallation() {
  const qc = useQueryClient()
  return useMutation<GithubInstallationDto, ApiError, { installationId: number }>({
    mutationFn: ({ installationId }) =>
      apiFetch<GithubInstallationDto>(
        `/api/v1/github-app/installations/${installationId}/sync`,
        { method: 'POST' },
      ),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ['github-app', 'installations'] })
    },
  })
}

// ── App Manifest helper ──────────────────────────────────────────────────────

/**
 * Build the App Manifest payload per design/63 §1. The manifest is what GitHub
 * uses to one-click-create the App with the right permissions + webhook URL —
 * no admin has to fill 12 form fields by hand. {@code redirectUrl} is where
 * GitHub bounces back to with {@code ?code=...} (our /onboarding route).
 * {@code webhookUrl} is the rig-public URL the App will POST events to.
 *
 * Permissions + events come straight from the design doc:
 *   - contents: read           (fetch .titan/pipelines/*.yml + checkout)
 *   - metadata: read           (list repos in the install)
 *   - issues: none             (deliberately out of scope V1)
 *   - pull_requests: read      (PR-event triggers, base/head info)
 *   - statuses: write          (post pass/fail back to the commit)
 *   - administration: read     (used by the install enumerator)
 *
 * Events: push, pull_request, installation, installation_repositories, repository
 */
export interface ManifestInput {
  /** Display name for the App on GitHub. Defaults to the rig hostname. */
  name: string
  /** Where GitHub redirects after the user approves manifest creation. */
  redirectUrl: string
  /** Public webhook ingress URL (smee tunnel in dev, https://… in prod). */
  webhookUrl: string
}

export interface AppManifest {
  name: string
  url: string
  hook_attributes: { url: string }
  redirect_url: string
  callback_urls: string[]
  public: boolean
  default_permissions: {
    contents: 'read'
    metadata: 'read'
    pull_requests: 'read'
    statuses: 'write'
    administration: 'read'
  }
  default_events: ReadonlyArray<
    'push' | 'pull_request' | 'installation' | 'installation_repositories' | 'repository'
  >
}

export function buildManifest(input: ManifestInput): AppManifest {
  // Strip path/query off the redirect URL so the "homepage" link on the App's
  // settings page lands on the rig root, not on /onboarding?code=...
  let homepage = input.redirectUrl
  try {
    const u = new URL(input.redirectUrl)
    homepage = `${u.protocol}//${u.host}`
  } catch {
    /* keep raw — the validator on GitHub's side will surface the error */
  }
  return {
    name: input.name,
    url: homepage,
    hook_attributes: { url: input.webhookUrl },
    redirect_url: input.redirectUrl,
    callback_urls: [input.redirectUrl],
    public: false,
    default_permissions: {
      contents: 'read',
      metadata: 'read',
      pull_requests: 'read',
      statuses: 'write',
      administration: 'read',
    },
    // App-level events (installation, installation_repositories) are delivered
    // by GitHub implicitly — listing them here makes the manifest validator
    // reject the App: "Default events unsupported / not supported by
    // permissions: installation and installation_repositories". Only register
    // the events that ARE webhook-event names and require explicit subscription.
    default_events: ['push', 'pull_request', 'repository'],
  }
}
