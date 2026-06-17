/**
 * /integrations/github/{installId} — one installation's repos + discovered
 * pipelines. Closes #878.
 *
 * <p>The existing nested DTO (`installation.repos[].pipelines[]`) is reused
 * verbatim — no backend changes. We fetch the full installations list and
 * pick the one matching the URL param. This avoids adding a new endpoint
 * for the IA split; an `/installations/{id}` single-fetch endpoint can land
 * later if the list grows expensive.
 *
 * <p>"Install not found" is a first-class state: bad bookmark, stale link,
 * or uninstall happened mid-session. We render an honest empty surface
 * instead of crashing or 500'ing.
 */
import { useState } from 'react'
import { createFileRoute, Link, useParams } from '@tanstack/react-router'
import {
  AlertTriangle,
  CheckCircle2,
  ExternalLink,
  FileCode,
  Github,
  Loader2,
  RefreshCw,
} from 'lucide-react'
import { Button } from '@/components/ui/Button'
import { PageContainer, PageHeader } from '@/components/ui/Page'
import { Skeleton } from '@/components/ui/Skeleton'
import { GitHubLogo } from '@/components/integrations/ProviderLogos'
import { getProvider } from '@/components/integrations/providers'

const GITHUB = getProvider('github')
import {
  useGithubInstallations,
  useSyncInstallation,
  type DiscoveredPipelineDto,
  type GithubInstallationDto,
  type GithubRepoDto,
} from '@/api/githubApp'
import { ApiError } from '@/api/types'
import { useDocumentTitle } from '@/lib/useDocumentTitle'

export const Route = createFileRoute('/integrations/github/$installId')({
  component: IntegrationsGithubInstallPage,
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

function IntegrationsGithubInstallPage() {
  const { installId } = useParams({ from: '/integrations/github/$installId' })
  const installsQ = useGithubInstallations()
  const install =
    installsQ.data?.find((i) => String(i.id) === installId) ?? null

  useDocumentTitle(install ? `${install.accountLogin} · GitHub` : 'Installation')

  return (
    <PageContainer width="narrow">
      <div
        data-testid="install-breadcrumb"
        style={{
          fontSize: 11,
          fontFamily: 'var(--font-mono)',
          color: 'var(--fg-faint)',
          letterSpacing: '0.02em',
          marginBottom: 8,
        }}
      >
        <Link
          to="/integrations"
          style={{ color: 'inherit', textDecoration: 'none' }}
        >
          Integrations
        </Link>
        <span aria-hidden style={{ margin: '0 6px' }}>
          /
        </span>
        <Link
          to="/integrations/github"
          style={{ color: GITHUB.accent, textDecoration: 'none' }}
        >
          GitHub
        </Link>
        {install && (
          <>
            <span aria-hidden style={{ margin: '0 6px' }}>
              /
            </span>
            <span style={{ color: 'var(--fg-muted)' }}>{install.accountLogin}</span>
          </>
        )}
      </div>

      <PageHeader
        title={
          <span style={{ display: 'inline-flex', alignItems: 'center', gap: 10 }}>
            <span
              aria-hidden
              style={{
                display: 'inline-flex',
                alignItems: 'center',
                justifyContent: 'center',
                width: 32,
                height: 32,
                // Brand-tinted breadcrumb tile — small detail, mirrors the
                // /integrations grid card so the provider-identity reads
                // consistently across the IA.
                background: GITHUB.bg,
                border: '1px solid var(--border)',
                borderRadius: 6,
                color: GITHUB.accent,
              }}
            >
              <GitHubLogo size={18} />
            </span>
            {install ? install.accountLogin : 'Installation'}
          </span>
        }
        description={
          install
            ? `${install.repos.length} repos · ${install.accountType.toLowerCase()} install`
            : 'GitHub App installation detail.'
        }
        actions={
          <Link
            to="/integrations/github"
            className="btn btn-sm btn-ghost"
            data-testid="install-back-link"
          >
            ← All installations
          </Link>
        }
      />

      {installsQ.isLoading && (
        <div className="card" style={{ padding: 14 }}>
          <Skeleton style={{ height: 16, marginBottom: 8 }} />
          <Skeleton style={{ height: 16, marginBottom: 8 }} />
          <Skeleton style={{ height: 16 }} />
        </div>
      )}

      {installsQ.isError && (
        <div
          className="card"
          role="alert"
          style={{ padding: 14, color: 'var(--fail)' }}
          data-testid="install-detail-error"
        >
          {errorMessage(installsQ.error)}
        </div>
      )}

      {!installsQ.isLoading && !installsQ.isError && !install && (
        <NotFound installId={installId} />
      )}

      {!installsQ.isLoading && !installsQ.isError && install && (
        <InstallationBody install={install} />
      )}
    </PageContainer>
  )
}

function NotFound({ installId }: { installId: string }) {
  return (
    <div
      className="card"
      data-testid="install-not-found"
      role="status"
      style={{
        padding: 32,
        textAlign: 'center',
        display: 'grid',
        gap: 10,
        color: 'var(--fg-dim)',
      }}
    >
      <Github size={28} aria-hidden style={{ margin: '0 auto', color: 'var(--fg-faint)' }} />
      <div style={{ fontSize: 14, color: 'var(--fg-muted)' }}>
        Installation{' '}
        <code style={{ fontFamily: 'var(--font-mono)' }}>#{installId}</code> not found.
      </div>
      <div style={{ fontSize: 12 }}>It may have been uninstalled on GitHub.</div>
      <div>
        <Link
          to="/integrations/github"
          className="btn btn-sm btn-primary"
          data-testid="install-not-found-back"
        >
          Back to GitHub →
        </Link>
      </div>
    </div>
  )
}

// ── installation body — repos + pipelines (was the old InstallationCard) ────

function InstallationBody({ install }: { install: GithubInstallationDto }) {
  const syncMut = useSyncInstallation()
  const totalPipelines = install.repos.reduce((sum, r) => sum + r.pipelines.length, 0)
  // Default: hide repos with zero pipelines — they're noise (most accounts have
  // 50+ repos; only a handful carry .titan/pipelines/*.yml). Toggle to show all.
  const [showAll, setShowAll] = useState(false)
  const reposWithPipelines = install.repos.filter((r) => r.pipelines.length > 0)
  const hiddenCount = install.repos.length - reposWithPipelines.length
  const visibleRepos = showAll ? install.repos : reposWithPipelines

  function onSync() {
    syncMut.mutate({ installationId: install.id })
  }

  return (
    <div className="card" data-testid={`install-card-${install.id}`}>
      <div
        className="card-header"
        style={{ display: 'flex', alignItems: 'center', gap: 10 }}
      >
        <h3
          className="card-title"
          style={{ display: 'flex', alignItems: 'center', gap: 8 }}
        >
          <Github size={14} aria-hidden />
          {install.accountLogin}
          <span
            style={{
              fontSize: 11,
              color: 'var(--fg-faint)',
              fontFamily: 'var(--font-mono)',
              fontWeight: 400,
            }}
          >
            {install.accountType.toLowerCase()}
          </span>
        </h3>
        {install.suspended && (
          <span
            data-testid={`install-suspended-${install.id}`}
            style={{
              fontSize: 11,
              padding: '2px 8px',
              borderRadius: 999,
              background: 'var(--warn-bg, var(--bg-2))',
              border: '1px solid var(--warn)',
              color: 'var(--warn)',
              display: 'inline-flex',
              alignItems: 'center',
              gap: 4,
            }}
          >
            <AlertTriangle size={11} aria-hidden /> suspended
          </span>
        )}
        <div
          style={{
            marginLeft: 'auto',
            display: 'flex',
            alignItems: 'center',
            gap: 12,
            fontSize: 12,
            color: 'var(--fg-dim)',
            fontFamily: 'var(--font-mono)',
          }}
        >
          <span data-testid={`install-repo-count-${install.id}`}>
            {install.repos.length} repo{install.repos.length === 1 ? '' : 's'}
          </span>
          <span data-testid={`install-pipeline-count-${install.id}`}>
            {totalPipelines} pipeline{totalPipelines === 1 ? '' : 's'}
          </span>
          <Button
            size="sm"
            variant="ghost"
            onClick={onSync}
            disabled={syncMut.isPending}
            data-testid={`install-sync-btn-${install.id}`}
          >
            {syncMut.isPending ? (
              <Loader2 size={12} className="spin" aria-hidden />
            ) : (
              <RefreshCw size={12} aria-hidden />
            )}
            {syncMut.isPending ? 'Syncing…' : 'Sync now'}
          </Button>
        </div>
      </div>

      <div className="card-body" style={{ padding: 0 }}>
        {install.repos.length === 0 ? (
          <div
            style={{
              padding: 24,
              textAlign: 'center',
              color: 'var(--fg-faint)',
              fontSize: 12,
            }}
            data-testid={`install-no-repos-${install.id}`}
          >
            No repositories selected on GitHub for this installation.
          </div>
        ) : (
          <>
            {visibleRepos.length === 0 ? (
              <div
                style={{
                  padding: 18,
                  textAlign: 'center',
                  color: 'var(--fg-muted)',
                  fontSize: 12,
                }}
                data-testid={`install-no-pipelines-${install.id}`}
              >
                No repos with{' '}
                <code style={{ fontFamily: 'var(--font-mono)' }}>
                  .titan/pipelines/*.yml
                </code>{' '}
                discovered yet.
              </div>
            ) : (
              visibleRepos.map((repo) => (
                <RepoRow key={repo.fullName} install={install} repo={repo} />
              ))
            )}
            {hiddenCount > 0 && (
              <div
                style={{
                  padding: '10px 16px',
                  borderTop: '1px solid var(--border)',
                  fontSize: 11,
                  color: 'var(--fg-muted)',
                  display: 'flex',
                  alignItems: 'center',
                  gap: 8,
                }}
              >
                <button
                  type="button"
                  className="btn btn-ghost btn-xs"
                  onClick={() => setShowAll((v) => !v)}
                  data-testid={`install-toggle-all-${install.id}`}
                  style={{ fontSize: 11 }}
                >
                  {showAll
                    ? `Hide ${hiddenCount} repo${hiddenCount === 1 ? '' : 's'} without pipelines`
                    : `Show all ${install.repos.length} repos (${hiddenCount} without pipelines)`}
                </button>
              </div>
            )}
          </>
        )}
      </div>
    </div>
  )
}

// ── repo row + pipeline cards (lifted from the old monolith verbatim) ───────

function RepoRow({
  install,
  repo,
}: {
  install: GithubInstallationDto
  repo: GithubRepoDto
}) {
  const [expanded, setExpanded] = useState(repo.pipelines.length > 0)

  return (
    <div
      data-testid={`repo-${install.id}-${encodeURIComponent(repo.fullName)}`}
      style={{ borderTop: '1px solid var(--border)' }}
    >
      <div
        style={{
          padding: '12px 14px',
          display: 'flex',
          alignItems: 'center',
          gap: 10,
          fontSize: 13,
        }}
      >
        <button
          type="button"
          className="btn btn-sm btn-ghost"
          onClick={() => setExpanded((v) => !v)}
          aria-expanded={expanded}
          aria-label={expanded ? 'Collapse pipelines' : 'Expand pipelines'}
          data-testid={`repo-expand-${install.id}-${encodeURIComponent(repo.fullName)}`}
          style={{ minWidth: 24 }}
        >
          {expanded ? '▾' : '▸'}
        </button>
        <div style={{ flex: 1 }}>
          <div style={{ fontFamily: 'var(--font-mono)', fontSize: 13 }}>{repo.fullName}</div>
          <div style={{ fontSize: 11, color: 'var(--fg-faint)' }}>
            default branch: {repo.defaultBranch} · {repo.pipelines.length} pipeline
            {repo.pipelines.length === 1 ? '' : 's'}
          </div>
        </div>
        <a
          href={repo.htmlUrl}
          target="_blank"
          rel="noopener noreferrer"
          className="btn btn-sm btn-ghost"
          aria-label={`Open ${repo.fullName} on GitHub`}
        >
          <ExternalLink size={12} aria-hidden /> GitHub
        </a>
      </div>

      {expanded && repo.pipelines.length === 0 && (
        <div
          style={{
            padding: '0 14px 14px 48px',
            color: 'var(--fg-faint)',
            fontSize: 12,
          }}
          data-testid={`repo-no-pipelines-${install.id}-${encodeURIComponent(repo.fullName)}`}
        >
          No <code>.titan/pipelines/*.yml</code> files discovered. Add one and click Sync now.
        </div>
      )}

      {expanded && repo.pipelines.length > 0 && (
        <div
          style={{
            display: 'grid',
            gap: 10,
            padding: '0 14px 14px 48px',
          }}
        >
          {repo.pipelines.map((p) => (
            <PipelineCard
              key={p.filename}
              install={install}
              repo={repo}
              pipeline={p}
            />
          ))}
        </div>
      )}
    </div>
  )
}

function PipelineCard({
  install,
  repo,
  pipeline,
}: {
  install: GithubInstallationDto
  repo: GithubRepoDto
  pipeline: DiscoveredPipelineDto
}) {
  // Design 66: every discovered pipeline is auto-enabled by the scanner. No
  // user-facing enable button — if the file exists in `.titan/pipelines/`, the
  // pipeline is active. The right-side "Active" pill is purely informational.
  const triggers = pipeline.triggers.length > 0 ? pipeline.triggers.join(', ') : 'manual only'
  const testId = `pipeline-card-${install.id}-${encodeURIComponent(repo.fullName)}-${encodeURIComponent(pipeline.filename)}`

  return (
    <div
      className="card"
      data-testid={testId}
      style={{
        background: 'var(--bg-2)',
        padding: 12,
        display: 'grid',
        gridTemplateColumns: '1fr auto',
        gap: 8,
        alignItems: 'center',
      }}
    >
      <div style={{ display: 'grid', gap: 4 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
          <FileCode size={12} aria-hidden style={{ color: 'var(--fg-faint)' }} />
          <span style={{ fontWeight: 500, fontSize: 13 }}>{pipeline.name}</span>
          <span
            style={{
              fontFamily: 'var(--font-mono)',
              fontSize: 11,
              color: 'var(--fg-faint)',
            }}
          >
            {pipeline.filename}
          </span>
        </div>
        <div style={{ fontSize: 11, color: 'var(--fg-dim)', display: 'flex', gap: 12 }}>
          <span>
            {pipeline.stagesCount} stage{pipeline.stagesCount === 1 ? '' : 's'}
          </span>
          <span>triggers: {triggers}</span>
          {pipeline.paramsCount > 0 && (
            <span>
              {pipeline.paramsCount} param{pipeline.paramsCount === 1 ? '' : 's'}
            </span>
          )}
        </div>
      </div>
      <span
        data-testid={`${testId}-active`}
        style={{
          display: 'inline-flex',
          alignItems: 'center',
          gap: 4,
          fontSize: 11,
          color: 'var(--ok)',
          fontFamily: 'var(--font-mono)',
          padding: '2px 8px',
          borderRadius: 999,
          border: '1px solid var(--border)',
        }}
      >
        <CheckCircle2 size={11} aria-hidden /> active
      </span>
    </div>
  )
}
