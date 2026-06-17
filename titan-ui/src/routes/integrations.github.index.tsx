/**
 * /integrations/github — provider-detail surface: GitHub header + the list of
 * installations (closes #878 redesign; spaced per user's mock 2026-05-25).
 *
 * <p>IA in the new design:
 * <pre>
 *   /integrations              → provider INDEX (hairline list of all SCMs)
 *   /integrations/github       → THIS FILE: header + installs + "Add another"
 *   /integrations/github/$id   → per-install repos + discovered pipelines
 * </pre>
 *
 * <p>Visual: big 56x56 brand-tinted logo tile · H1 "GitHub" · tagline ·
 * top-right "Docs" + "+ Add integration" primary CTA. Hairline below the
 * header. "INSTALLED · {count}" section label. Rows are hairline-separated
 * with NO card wrapper — generous vertical padding for breathing room.
 *
 * <p>Action icons on each row: re-sync + Settings (external link to GitHub's
 * install-settings page; uninstall / suspend / repo-access lives there).
 * NO Remove — we don't own uninstall.
 *
 * <p>"Add another GitHub integration" lives at the bottom as an outline
 * button with an explainer to clarify that multi-org install is supported.
 */
import { createFileRoute, Link } from '@tanstack/react-router'
import {
  AlertTriangle,
  ExternalLink,
  Loader2,
  Plus,
  RefreshCw,
  Settings,
  ChevronLeft,
} from 'lucide-react'
import { Skeleton } from '@/components/ui/Skeleton'
import {
  useGithubApp,
  useGithubInstallations,
  useSyncInstallation,
  type GithubAppDto,
  type GithubInstallationDto,
} from '@/api/githubApp'
import { ApiError } from '@/api/types'
import { useDocumentTitle } from '@/lib/useDocumentTitle'
import { PageContainer, PageHeader } from '@/components/ui/Page'
import { GitHubLogo } from '@/components/integrations/ProviderLogos'
import { getProvider } from '@/components/integrations/providers'

export const Route = createFileRoute('/integrations/github/')({
  component: IntegrationsGithubIndexPage,
})

function errorMessage(err: unknown): string {
  if (err instanceof ApiError) {
    if (err.status === 403) return 'Not allowed.'
    if (err.status === 404) return 'GitHub App is not configured yet.'
    if (err.status >= 500) return 'Server error — please retry.'
    return err.problem.detail ?? err.message
  }
  return 'Request failed.'
}

function installNewUrl(app: GithubAppDto): string {
  return `${app.htmlUrl.replace(/\/$/, '')}/installations/new`
}

const GITHUB = getProvider('github')

function IntegrationsGithubIndexPage() {
  useDocumentTitle('GitHub · Integrations')
  const appQ = useGithubApp()
  const installsQ = useGithubInstallations()
  const installCount = installsQ.data?.length ?? 0
  const showAddCta = !!appQ.data

  return (
    <PageContainer width="default">
      {/* Breadcrumb — slim row above the H1 */}
      <div
        data-testid="github-breadcrumb"
        style={{
          fontSize: 12.5,
          color: 'var(--fg-faint)',
          marginBottom: 28,
          display: 'flex',
          alignItems: 'center',
          gap: 6,
        }}
      >
        <Link
          to="/integrations"
          style={{
            color: 'inherit',
            textDecoration: 'none',
            display: 'inline-flex',
            alignItems: 'center',
            gap: 2,
          }}
        >
          <ChevronLeft size={12} aria-hidden /> Integrations
        </Link>
        <span aria-hidden style={{ color: 'var(--fg-faint)' }}>
          /
        </span>
        <span style={{ color: 'var(--fg)' }}>GitHub</span>
      </div>

      {/* Shared page frame header — brand-tinted logo tile in the title, tagline
          as the description, and Docs + "Add integration" as the action cluster
          (one dominant primary CTA, UX chart H5).

          Hero weight, deliberately: this is the GitHub *gateway* — the single
          entry point into the provider — so it carries the larger 56×56 brand
          tile (the documented design intent above), versus the 32×32 tile on the
          per-install detail sibling. We keep the shared <PageHeader> <h1> rather
          than reviving the old bespoke 38px/700-weight heading: H8 (match sibling
          pages, no one-off pattern) and the H1 sweep's whole consistency premise
          take precedence over a custom hero font. The tile size restores the
          parent>child visual hierarchy without breaking the shared frame. */}
      <PageHeader
        title={
          <span style={{ display: 'inline-flex', alignItems: 'center', gap: 14 }}>
            <span
              aria-hidden
              style={{
                display: 'inline-flex',
                alignItems: 'center',
                justifyContent: 'center',
                width: 56,
                height: 56,
                background: GITHUB.bg,
                border: '1px solid var(--border)',
                borderRadius: 12,
                color: GITHUB.accent,
              }}
            >
              <GitHubLogo size={30} brandColor />
            </span>
            GitHub
          </span>
        }
        description={GITHUB.tagline}
        actions={
          <>
            <a
              href="https://docs.github.com/en/apps"
              target="_blank"
              rel="noopener noreferrer"
              className="btn btn-ghost"
              style={{ display: 'inline-flex', alignItems: 'center', gap: 6, fontSize: 12 }}
            >
              <ExternalLink size={12} aria-hidden /> Docs
            </a>
            {showAddCta ? (
              <a
                href={installNewUrl(appQ.data!)}
                target="_blank"
                rel="noopener noreferrer"
                className="btn btn-primary"
                data-testid="github-add-integration-btn"
                style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}
              >
                <Plus size={13} aria-hidden /> Add integration
              </a>
            ) : (
              <Link
                to="/onboarding"
                className="btn btn-primary"
                data-testid="github-onboarding-btn"
              >
                Set up GitHub App
              </Link>
            )}
          </>
        }
      />

      {/* Hairline divider between header and content */}
      <div style={{ borderTop: '1px solid var(--border)', marginBottom: 32 }} />

      {appQ.isError && (
        <div
          className="card"
          role="alert"
          data-testid="app-status-error"
          style={{ padding: 14, color: 'var(--fail)', marginBottom: 14 }}
        >
          {errorMessage(appQ.error)}
        </div>
      )}

      {/* "INSTALLED · N" section label, all-caps faint with mono count right */}
      <div
        style={{
          display: 'flex',
          alignItems: 'baseline',
          justifyContent: 'space-between',
          marginBottom: 12,
          fontSize: 11,
          fontWeight: 600,
          letterSpacing: '0.08em',
          textTransform: 'uppercase',
          color: 'var(--fg-faint)',
        }}
      >
        <span>Installed</span>
        <span
          style={{
            fontFamily: 'var(--font-mono)',
            fontWeight: 400,
            fontSize: 12,
            letterSpacing: 0,
          }}
        >
          {installCount}
        </span>
      </div>

      {installsQ.isLoading && (
        <div style={{ padding: '20px 0' }}>
          <Skeleton style={{ height: 18, marginBottom: 10 }} />
          <Skeleton style={{ height: 18, marginBottom: 10 }} />
          <Skeleton style={{ height: 18 }} />
        </div>
      )}

      {installsQ.isError && (
        <div
          className="card"
          role="alert"
          style={{ padding: 14, color: 'var(--fail)' }}
          data-testid="integrations-error"
        >
          {errorMessage(installsQ.error)}
        </div>
      )}

      {!installsQ.isLoading && !installsQ.isError && installCount === 0 && (
        <EmptyInstalls app={appQ.data ?? null} />
      )}

      {/* The install list — hairline-separated rows, NO card wrapper, ample padding */}
      {!installsQ.isLoading &&
        !installsQ.isError &&
        installsQ.data &&
        installsQ.data.length > 0 && (
          <div>
            {installsQ.data.map((inst) => (
              <InstallRow key={inst.id} install={inst} />
            ))}

            {/* Bottom CTA — outline button + explainer text */}
            {appQ.data && (
              <div
                style={{
                  marginTop: 36,
                  display: 'flex',
                  alignItems: 'center',
                  gap: 20,
                  flexWrap: 'wrap',
                }}
              >
                <a
                  href={installNewUrl(appQ.data)}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="btn"
                  data-testid="github-add-another-btn"
                  style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}
                >
                  <Plus size={12} aria-hidden /> Add another GitHub integration
                </a>
                <span style={{ fontSize: 12, color: 'var(--fg-faint)' }}>
                  You can install Titan into multiple orgs, accounts, or workspaces.
                </span>
              </div>
            )}
          </div>
        )}
    </PageContainer>
  )
}

// ── installation row — dense mono meta line + event chips + action cluster ───

function InstallRow({ install }: { install: GithubInstallationDto }) {
  const syncMut = useSyncInstallation()
  const createdDate = formatYmd(install.createdAt)
  const accountUrl = `github.com/${install.accountLogin}`
  const settingsUrl = `https://github.com/settings/installations/${install.githubInstallationId}`
  const dotColor = install.suspended ? 'var(--warn)' : 'var(--ok)'

  function onSync() {
    syncMut.mutate({ installationId: install.id })
  }

  return (
    <div
      data-testid={`install-row-${install.id}`}
      style={{
        display: 'grid',
        gridTemplateColumns: 'auto 1fr auto auto',
        alignItems: 'center',
        gap: 18,
        padding: '24px 4px',
        borderTop: '1px solid var(--border)',
        borderBottom: '1px solid var(--border)',
        marginTop: -1, // collapse adjacent borders when multiple rows
      }}
    >
      {/* Status dot */}
      <span
        aria-hidden
        title={install.suspended ? 'suspended on GitHub' : 'active'}
        style={{
          width: 9,
          height: 9,
          borderRadius: 999,
          background: dotColor,
          display: 'inline-block',
          flexShrink: 0,
        }}
      />

      {/* Meta block — clickable to install detail */}
      <Link
        to="/integrations/github/$installId"
        params={{ installId: String(install.id) }}
        data-testid={`install-row-meta-${install.id}`}
        style={{
          display: 'grid',
          gap: 4,
          minWidth: 0,
          textDecoration: 'none',
          color: 'inherit',
        }}
      >
        <div style={{ fontSize: 15, fontWeight: 600, color: 'var(--fg)' }}>
          {install.accountLogin}
          {install.suspended && (
            <span
              data-testid={`install-suspended-${install.id}`}
              style={{
                marginLeft: 8,
                fontSize: 10.5,
                padding: '1px 6px',
                borderRadius: 999,
                border: '1px solid var(--warn)',
                color: 'var(--warn)',
                display: 'inline-flex',
                alignItems: 'center',
                gap: 4,
                fontWeight: 400,
                verticalAlign: 'middle',
              }}
            >
              <AlertTriangle size={9} aria-hidden /> suspended
            </span>
          )}
        </div>
        <div
          style={{
            fontSize: 12.5,
            color: 'var(--fg-muted)',
            fontFamily: 'var(--font-mono)',
            fontVariantNumeric: 'tabular-nums',
            display: 'flex',
            flexWrap: 'wrap',
            gap: 8,
            alignItems: 'center',
          }}
        >
          <span data-testid={`install-url-${install.id}`}>{accountUrl}</span>
          <Dot />
          <span>GitHub App</span>
          <Dot />
          <span data-testid={`install-repo-count-${install.id}`}>
            {install.repos.length} repo{install.repos.length === 1 ? '' : 's'}
          </span>
          <Dot />
          <span data-testid={`install-added-${install.id}`}>added {createdDate}</span>
        </div>
      </Link>

      {/* Event chips — what the App subscribes to */}
      {GITHUB.events.length > 0 && (
        <div
          data-testid={`install-events-${install.id}`}
          style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}
        >
          {GITHUB.events.map((ev) => (
            <span
              key={ev}
              data-testid={`install-event-${install.id}-${ev}`}
              style={{
                fontSize: 11,
                fontFamily: 'var(--font-mono)',
                color: 'var(--fg)',
                padding: '3px 8px',
                borderRadius: 5,
                background: 'var(--bg-2, rgba(255,255,255,0.04))',
                border: '1px solid var(--border)',
              }}
            >
              {ev}
            </span>
          ))}
        </div>
      )}

      {/* Action cluster — re-sync + Settings external */}
      <div
        style={{
          display: 'inline-flex',
          alignItems: 'center',
          gap: 4,
        }}
      >
        <button
          type="button"
          className="btn btn-sm btn-ghost"
          onClick={onSync}
          disabled={syncMut.isPending}
          aria-label={`Re-sync ${install.accountLogin}`}
          title="Re-sync repos &amp; pipelines"
          data-testid={`install-sync-icon-${install.id}`}
          style={{ padding: '4px 6px' }}
        >
          {syncMut.isPending ? (
            <Loader2 size={14} className="spin" aria-hidden />
          ) : (
            <RefreshCw size={14} aria-hidden />
          )}
        </button>
        <a
          href={settingsUrl}
          target="_blank"
          rel="noopener noreferrer"
          className="btn btn-sm btn-ghost"
          aria-label={`Manage ${install.accountLogin} on GitHub`}
          title="Manage on GitHub (uninstall, repo access, suspend)"
          data-testid={`install-settings-link-${install.id}`}
          style={{
            padding: '4px 6px',
            display: 'inline-flex',
            alignItems: 'center',
            gap: 2,
          }}
        >
          <Settings size={14} aria-hidden />
          <ExternalLink size={10} aria-hidden style={{ opacity: 0.7 }} />
        </a>
      </div>
    </div>
  )
}

function Dot() {
  return (
    <span aria-hidden style={{ color: 'var(--fg-faint)' }}>
      ·
    </span>
  )
}

function formatYmd(iso: string): string {
  try {
    const d = new Date(iso)
    if (Number.isNaN(d.getTime())) return iso
    const y = d.getUTCFullYear()
    const m = String(d.getUTCMonth() + 1).padStart(2, '0')
    const day = String(d.getUTCDate()).padStart(2, '0')
    return `${y}-${m}-${day}`
  } catch {
    return iso
  }
}

function EmptyInstalls({ app }: { app: GithubAppDto | null }) {
  return (
    <div
      data-testid="integrations-empty"
      style={{
        padding: '48px 24px',
        textAlign: 'center',
        display: 'grid',
        gap: 14,
        justifyItems: 'center',
        color: 'var(--fg-dim)',
        borderTop: '1px solid var(--border)',
        borderBottom: '1px solid var(--border)',
      }}
    >
      <span
        aria-hidden
        style={{
          display: 'inline-flex',
          alignItems: 'center',
          justifyContent: 'center',
          width: 64,
          height: 64,
          background: GITHUB.bg,
          border: '1px solid var(--border)',
          borderRadius: 12,
          color: GITHUB.accent,
        }}
      >
        <GitHubLogo size={36} brandColor />
      </span>
      <div style={{ fontSize: 16, fontWeight: 500, color: 'var(--fg)' }}>
        No GitHub integrations yet
      </div>
      <div style={{ maxWidth: 460, lineHeight: 1.5, fontSize: 13 }}>
        Install Titan on your GitHub account so it can listen for commits, run
        pipelines, and post checks back.
      </div>
      <div style={{ marginTop: 6 }}>
        {app ? (
          <a
            href={installNewUrl(app)}
            target="_blank"
            rel="noopener noreferrer"
            className="btn btn-primary"
            data-testid="github-add-first-btn"
            style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}
          >
            <Plus size={13} aria-hidden /> Install on GitHub
          </a>
        ) : (
          <Link
            to="/onboarding"
            className="btn btn-primary"
            data-testid="github-empty-onboarding"
          >
            Set up Titan GitHub App →
          </Link>
        )}
      </div>
    </div>
  )
}
