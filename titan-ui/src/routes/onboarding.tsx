/**
 * /onboarding — the V1 headline "Connect GitHub" golden path (design/63, epic
 * #831). Two screens, in order:
 *
 *   Screen 1 — Create Titan GitHub App  (ADMIN-only; one-time per rig)
 *     The admin clicks "Create Titan GitHub App". We POST an App Manifest JSON
 *     to https://github.com/settings/apps/new?manifest=... via a hidden
 *     auto-submitting form (the only way GitHub accepts the payload — they
 *     don't honour `?manifest=` on a plain GET because the JSON is too big).
 *     GitHub redirects back to /onboarding?code=... and we POST the code to
 *     /api/v1/github-app/manifest-callback. Server-side that exchange yields
 *     the App id + private key + webhook secret which the server stashes
 *     in `titan_github_app`. We never see the secrets.
 *
 *   Screen 2 — Connect GitHub  (everyone)
 *     If the App exists (useGithubApp().data is non-null), we surface "Install
 *     on GitHub" → app.html_url + /installations/new. While the admin walks
 *     through the GitHub install consent screen we poll
 *     /api/v1/github-app/installations every 2s; on the first installation we
 *     see, we navigate to /integrations/github.
 *
 * The 4-step "paste a Git URL" wizard moved to /onboarding/manual — linked at
 * the bottom of this page for GitLab / Gitea / Bitbucket / air-gapped flows.
 *
 * Constitution conformance:
 *   - No CSS @import after @tailwind (none added here)
 *   - No meta CSP (none added here)
 *   - Hooks unconditional — every useEffect / useNavigate fires before any return
 *   - No window.* server context — TanStack Query handles data
 *   - No `any`; uses the typed DTOs from src/api/githubApp.ts
 */
import { useEffect, useMemo, useRef, useState } from 'react'
import { createFileRoute, Link, useNavigate, useSearch } from '@tanstack/react-router'
import { ArrowRight, CheckCircle2, Github, Loader2, ShieldAlert } from 'lucide-react'
import { Button } from '@/components/ui/Button'
import {
  buildManifest,
  useGithubApp,
  useGithubInstallations,
  useManifestCallback,
} from '@/api/githubApp'
import { useAuthRoles, hasRole } from '@/lib/auth'
import { ApiError } from '@/api/types'
import { useDocumentTitle } from '@/lib/useDocumentTitle'
import { PageContainer, PageHeader } from '@/components/ui/Page'
import { getRuntimeConfig } from '@/runtimeConfig'

/**
 * Back-compat re-export — the prior /onboarding owned this key (read by
 * OnboardingWelcomeCard on /). The manual wizard moved, the key did not.
 */
export { ONBOARDING_DISMISSED_KEY } from './onboarding_.manual'

interface OnboardingSearch {
  /** Set by GitHub's redirect after the App Manifest exchange. */
  code?: string
  /** Set by GitHub's redirect after a fresh install. We accept + ignore — the
   *  install poll picks it up by id, this only proves the admin came back. */
  installation_id?: string
}

export const Route = createFileRoute('/onboarding')({
  validateSearch: (raw: Record<string, unknown>): OnboardingSearch => {
    const out: OnboardingSearch = {}
    if (typeof raw.code === 'string' && raw.code.length > 0) out.code = raw.code
    if (typeof raw.installation_id === 'string' && raw.installation_id.length > 0) {
      out.installation_id = raw.installation_id
    }
    return out
  },
  component: OnboardingPage,
})

function errorMessage(err: unknown): string {
  if (err instanceof ApiError) {
    if (err.status === 403) return 'Admin role required to create the GitHub App.'
    if (err.status === 409) return 'A GitHub App is already configured on this rig.'
    if (err.status >= 500) return 'Server error — please retry.'
    return err.problem.detail ?? err.message
  }
  return 'Request failed.'
}

function OnboardingPage() {
  useDocumentTitle('Connect GitHub')
  const navigate = useNavigate()
  const search = useSearch({ from: '/onboarding' })
  const roles = useAuthRoles()
  const isAdmin = hasRole(roles, 'ADMIN')

  const appQ = useGithubApp()
  const manifestCb = useManifestCallback()
  // Poll installations every 2s only on screen 2 — i.e. once the App exists.
  // Until then there's nothing to poll for.
  const installsQ = useGithubInstallations({
    pollMs: appQ.data ? 2000 : undefined,
  })

  // GitHub redirected back with a temp `code` — exchange it exactly once.
  // Hook is unconditional (CONSTITUTION §6). We guard with a ref so React 19's
  // double-effect-in-StrictMode does not POST twice.
  const exchangedRef = useRef(false)
  useEffect(() => {
    if (exchangedRef.current) return
    if (!search.code) return
    if (manifestCb.isPending || manifestCb.isSuccess) return
    exchangedRef.current = true
    manifestCb.mutate(
      { code: search.code },
      {
        onSettled: () => {
          // Drop the ?code= so a refresh does not re-POST (the server is
          // idempotent on the code anyway, but keep the URL clean).
          void navigate({ to: '/onboarding', search: {}, replace: true })
        },
      },
    )
  }, [search.code, manifestCb, navigate])

  // Once an installation lands, jump to the integrations page. This is the
  // moment-of-truth handoff: the admin sees their repos discovered.
  useEffect(() => {
    const installs = installsQ.data
    if (!installs || installs.length === 0) return
    if (!appQ.data) return
    void navigate({ to: '/integrations/github', replace: true })
  }, [installsQ.data, appQ.data, navigate])

  // Decide which screen we're on. `app` controls everything.
  const app = appQ.data ?? null
  const exchanging = manifestCb.isPending || (search.code !== undefined && !manifestCb.isError)

  return (
    <PageContainer width="narrow">
      <PageHeader
        title="Connect GitHub"
        description="One install, every repo discovered, zero webhook copy-paste."
      />

      <Stepper screen={app === null ? 1 : 2} />

      <div className="card" style={{ marginTop: 14 }} data-testid="onboarding-card">
        {exchanging && !manifestCb.isError && (
          <ExchangeScreen />
        )}

        {!exchanging && app === null && (
          <ScreenCreateApp
            isAdmin={isAdmin}
            errorMsg={manifestCb.isError ? errorMessage(manifestCb.error) : null}
            loadError={appQ.isError ? errorMessage(appQ.error) : null}
            loading={appQ.isLoading}
          />
        )}

        {!exchanging && app !== null && (
          <ScreenConnect
            app={app}
            polling={installsQ.isFetching}
            error={installsQ.isError ? errorMessage(installsQ.error) : null}
          />
        )}
      </div>

      <ManualFallbackFooter />
    </PageContainer>
  )
}

// ── screens ──────────────────────────────────────────────────────────────────

function ExchangeScreen() {
  return (
    <>
      <div className="card-header">
        <h3 className="card-title">Finalising App registration</h3>
      </div>
      <div
        className="card-body"
        data-testid="onboarding-exchange"
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 10,
          color: 'var(--fg-muted)',
          fontSize: 13,
        }}
      >
        <Loader2 size={14} className="spin" aria-hidden />
        Exchanging GitHub callback code…
      </div>
    </>
  )
}

interface ScreenCreateAppProps {
  isAdmin: boolean
  errorMsg: string | null
  loadError: string | null
  loading: boolean
}

function ScreenCreateApp({ isAdmin, errorMsg, loadError, loading }: ScreenCreateAppProps) {
  const formRef = useRef<HTMLFormElement | null>(null)

  // The manifest is built fresh on every render so the rig hostname is always
  // current. This is cheap (a few field assignments), and avoids a stale URL
  // if the admin renames the rig mid-session.
  const manifestJson = useMemo(() => {
    // Prefer the server-supplied publicUrl (from /api/v1/system/ui-config — see
    // runtimeConfig.ts) so prod / k3s rigs use the Cloudflare-fronted ingress
    // hostname even when the admin is hitting an internal IP. Fall back to
    // window.location.origin for dev — but GitHub's validator will reject
    // localhost, so dev requires either a tunnel (smee/ngrok) or a server-side
    // override that sets publicUrl. Closes #898.
    const cfgBase = (() => {
      try {
        return getRuntimeConfig().publicUrl
      } catch {
        // Reachable only in tests / pre-boot — runtime config is always loaded
        // before this component mounts in production.
        return ''
      }
    })()
    const browserOrigin =
      typeof window !== 'undefined' && window.location ? window.location.origin : ''
    const origin = cfgBase || browserOrigin
    return JSON.stringify(
      buildManifest({
        name: origin ? `Titan @ ${new URL(origin).host}` : 'Titan CI',
        redirectUrl: `${origin}/onboarding`,
        webhookUrl: `${origin}/api/v1/github-app/events`,
      }),
    )
  }, [])

  function submitManifest() {
    formRef.current?.submit()
  }

  if (loading) {
    return (
      <div className="card-body" style={{ color: 'var(--fg-muted)', fontSize: 13 }}>
        Loading…
      </div>
    )
  }

  return (
    <>
      <div className="card-header">
        <h3 className="card-title">Step 1 — Create the Titan GitHub App</h3>
        <span className="card-sub" style={{ marginLeft: 'auto' }}>
          one-time · admin only
        </span>
      </div>
      <div className="card-body" style={{ display: 'grid', gap: 14 }}>
        <p style={{ margin: 0, color: 'var(--fg-muted)', fontSize: 13, lineHeight: 1.55 }}>
          Titan ships as its own GitHub App per rig — that&apos;s how it discovers your
          repositories, installs the webhook automatically, and posts commit-status
          results back to pull requests. We&apos;ll send a pre-filled manifest to GitHub;
          you approve, GitHub bounces you back here.
        </p>

        <ul
          style={{
            margin: 0,
            paddingLeft: 16,
            display: 'grid',
            gap: 4,
            color: 'var(--fg-dim)',
            fontSize: 12,
          }}
        >
          <li>Repository contents · read</li>
          <li>Repository metadata · read</li>
          <li>Pull requests · read</li>
          <li>Commit statuses · write</li>
          <li>Webhooks: push, pull_request, installation, installation_repositories, repository</li>
        </ul>

        {!isAdmin && (
          <div
            role="alert"
            data-testid="onboarding-not-admin"
            style={{
              display: 'flex',
              gap: 8,
              alignItems: 'flex-start',
              padding: 12,
              background: 'var(--bg-2)',
              border: '1px solid var(--warn)',
              borderRadius: 6,
              fontSize: 12,
              color: 'var(--fg-muted)',
            }}
          >
            <ShieldAlert size={14} aria-hidden style={{ color: 'var(--warn)' }} />
            <div>
              Only an admin can create the GitHub App. Ask your Titan admin to finish
              this step, then return to <code>/onboarding</code> to install.
            </div>
          </div>
        )}

        {loadError && (
          <div role="alert" style={{ fontSize: 12, color: 'var(--fail)' }}>
            {loadError}
          </div>
        )}

        {errorMsg && (
          <div role="alert" style={{ fontSize: 12, color: 'var(--fail)' }}>
            {errorMsg}
          </div>
        )}

        {/* Hidden form — GitHub requires POST for App Manifest payloads (the
            URL form's payload exceeds practical GET length).  We submit on
            click rather than auto-submit so the admin can read the page first. */}
        <form
          ref={formRef}
          method="post"
          action="https://github.com/settings/apps/new"
          style={{ display: 'none' }}
          aria-hidden="true"
        >
          <input type="hidden" name="manifest" value={manifestJson} />
        </form>

        <div style={{ display: 'flex', justifyContent: 'flex-end' }}>
          <Button
            size="sm"
            type="button"
            disabled={!isAdmin}
            onClick={submitManifest}
            data-testid="onboarding-create-app-btn"
          >
            <Github size={12} aria-hidden /> Create Titan GitHub App
          </Button>
        </div>
      </div>
    </>
  )
}

interface ScreenConnectProps {
  app: { htmlUrl: string; name: string; slug: string }
  polling: boolean
  error: string | null
}

function ScreenConnect({ app, polling, error }: ScreenConnectProps) {
  // Trailing slash isn't guaranteed by GitHub's html_url — handle both forms.
  const installUrl = app.htmlUrl.endsWith('/')
    ? `${app.htmlUrl}installations/new`
    : `${app.htmlUrl}/installations/new`

  return (
    <>
      <div className="card-header">
        <h3 className="card-title">Step 2 — Install on GitHub</h3>
        <span className="card-sub" style={{ marginLeft: 'auto' }}>
          choose one or more orgs · choose all repos or just a few
        </span>
      </div>
      <div className="card-body" style={{ display: 'grid', gap: 14 }}>
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: 8,
            padding: 10,
            background: 'var(--bg-2)',
            border: '1px solid var(--line)',
            borderRadius: 6,
            fontSize: 12,
          }}
          data-testid="onboarding-app-row"
        >
          <CheckCircle2 size={14} style={{ color: 'var(--ok)' }} aria-hidden />
          <span>
            App registered as <strong>{app.name}</strong>{' '}
            <span style={{ color: 'var(--fg-faint)', fontFamily: 'var(--font-mono)' }}>
              ({app.slug})
            </span>
          </span>
        </div>

        <p style={{ margin: 0, color: 'var(--fg-muted)', fontSize: 13, lineHeight: 1.55 }}>
          Open GitHub to pick the org and repositories you want Titan to watch. When you
          return, Titan will scan for <code>.titan/pipelines/*.yml</code> files
          automatically — no webhook URL to paste, no secret to copy.
        </p>

        {error && (
          <div role="alert" style={{ fontSize: 12, color: 'var(--fail)' }}>
            {error}
          </div>
        )}

        <div
          style={{
            display: 'flex',
            justifyContent: 'space-between',
            alignItems: 'center',
            gap: 12,
          }}
        >
          <div
            data-testid="onboarding-polling"
            style={{
              fontSize: 12,
              color: 'var(--fg-dim)',
              display: 'flex',
              alignItems: 'center',
              gap: 6,
            }}
          >
            <Loader2
              size={12}
              aria-hidden
              className={polling ? 'spin' : undefined}
              style={{ opacity: polling ? 1 : 0.4 }}
            />
            Waiting for installation… we&apos;ll redirect you as soon as one appears.
          </div>
          <a
            href={installUrl}
            target="_blank"
            rel="noopener noreferrer"
            className="btn btn-sm btn-primary"
            data-testid="onboarding-install-link"
          >
            Install on GitHub <ArrowRight size={12} aria-hidden />
          </a>
        </div>
      </div>
    </>
  )
}

// ── chrome ───────────────────────────────────────────────────────────────────

function Stepper({ screen }: { screen: 1 | 2 }) {
  const steps = ['Create App', 'Install'] as const
  return (
    <div
      role="list"
      aria-label="Onboarding steps"
      style={{
        display: 'grid',
        gridTemplateColumns: 'repeat(2, 1fr)',
        gap: 8,
        marginTop: 6,
      }}
    >
      {steps.map((label, i) => {
        const idx = (i + 1) as 1 | 2
        const done = screen > idx
        const current = screen === idx
        return (
          <div
            key={label}
            role="listitem"
            aria-current={current ? 'step' : undefined}
            style={{
              padding: '10px 12px',
              borderRadius: 6,
              background: 'var(--bg-2)',
              border: `1px solid ${current ? 'var(--accent)' : 'var(--line)'}`,
              fontSize: 12,
              fontFamily: 'var(--font-mono)',
              color: current ? 'var(--fg)' : done ? 'var(--fg-muted)' : 'var(--fg-dim)',
              display: 'flex',
              alignItems: 'center',
              gap: 8,
            }}
          >
            <span
              style={{
                width: 18,
                height: 18,
                borderRadius: 999,
                background: done ? 'var(--ok)' : current ? 'var(--accent)' : 'transparent',
                border: done || current ? 'none' : '1px solid var(--line)',
                color: done || current ? '#000' : 'var(--fg-faint)',
                display: 'inline-flex',
                alignItems: 'center',
                justifyContent: 'center',
                fontSize: 10,
              }}
              aria-hidden
            >
              {done ? '✓' : idx}
            </span>
            {label}
          </div>
        )
      })}
    </div>
  )
}

function ManualFallbackFooter() {
  // Linked at the bottom — visually de-emphasised so it doesn't compete with the
  // primary CTA, but findable for the customer who needs it.
  const [open, setOpen] = useState(false)
  return (
    <div
      style={{
        marginTop: 18,
        padding: '10px 14px',
        background: 'transparent',
        fontSize: 12,
        color: 'var(--fg-dim)',
        textAlign: 'center',
      }}
    >
      {!open ? (
        <button
          type="button"
          className="btn btn-sm btn-ghost"
          onClick={() => setOpen(true)}
          data-testid="onboarding-manual-toggle"
        >
          Not on GitHub? Show other options →
        </button>
      ) : (
        <div
          data-testid="onboarding-manual-footer"
          style={{ display: 'inline-flex', gap: 10, alignItems: 'center' }}
        >
          <span>
            GitLab · Gitea · Bitbucket · air-gapped:{' '}
          </span>
          <Link
            to="/onboarding/manual"
            className="btn btn-sm btn-ghost"
            data-testid="onboarding-manual-link"
          >
            Or connect a Git URL directly →
          </Link>
        </div>
      )}
    </div>
  )
}
