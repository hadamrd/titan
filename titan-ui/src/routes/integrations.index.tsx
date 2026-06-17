/**
 * /integrations — provider INDEX as a vertical hairline list (matches the user's
 * design intent: full-width rows, generous spacing, one row per provider).
 *
 * <p>Renders ALL known SCM providers. GitHub is wired live; the rest
 * (GitLab, Bitbucket, Gerrit) are deliberately visible-but-disabled so the
 * product's direction is legible from one screen.
 *
 * <p>Visual contract:
 *   <ul>
 *     <li>Full-width hairline-separated rows, NOT a card grid. The mock the
 *         user supplied is one column with breathing room — that's what
 *         scales as more providers land. (doc 64).</li>
 *     <li>Per row: 44x44 brand-tinted logo tile, name, tagline, and a
 *         right-aligned action area showing either "N integrations →" or
 *         "+ Connect" or "Coming soon".</li>
 *     <li>Disabled rows have {@code aria-disabled="true"} and {@code
 *         cursor: not-allowed} and are NOT rendered as anchors.</li>
 *   </ul>
 *
 * <p>Hard scope guards (do not add):
 *   <ul>
 *     <li>No setup wizard route — GitHub handles repo-picking server-side.</li>
 *     <li>No auth-method picker — Titan only supports the GitHub App.</li>
 *     <li>No mock data for the inactive providers.</li>
 *   </ul>
 */
import type { CSSProperties } from 'react'
import { createFileRoute, Link } from '@tanstack/react-router'
import { ExternalLink, ChevronRight, Plus } from 'lucide-react'
import { useGithubApp, useGithubInstallations } from '@/api/githubApp'
import { useDocumentTitle } from '@/lib/useDocumentTitle'
import { PageContainer, PageHeader } from '@/components/ui/Page'
import { PROVIDERS, type ProviderMeta } from '@/components/integrations/providers'

export const Route = createFileRoute('/integrations/')({
  component: IntegrationsIndexPage,
})

function IntegrationsIndexPage() {
  useDocumentTitle('Integrations')
  const appQ = useGithubApp()
  const installsQ = useGithubInstallations()

  const installCount = installsQ.data?.length ?? 0
  const githubConnected = !!appQ.data && installCount > 0
  const githubLoading = appQ.isLoading || installsQ.isLoading

  // Total connected count for the page subtitle — only GitHub today, but
  // wired to sum across providers so when the next one lands the copy is
  // already correct.
  const totalConnected = githubConnected ? 1 : 0

  return (
    <PageContainer width="default">
      <PageHeader
        title="Integrations"
        description={
          <>
            Connect Titan to source control providers ·{' '}
            <span style={{ fontFamily: 'var(--font-mono)' }}>{totalConnected}</span>{' '}
            connected
          </>
        }
        actions={
          <a
            href="https://github.com/hadamrd/dashboard-plugin#readme"
            target="_blank"
            rel="noopener noreferrer"
            className="btn btn-ghost"
            style={{ display: 'inline-flex', alignItems: 'center', gap: 6, fontSize: 12 }}
          >
            <ExternalLink size={12} aria-hidden />
            Docs
          </a>
        }
      />

      <div className="card" style={{ padding: 0, overflow: 'hidden' }}>
        {PROVIDERS.map((p, i) => {
          const isFirst = i === 0
          if (p.key === 'github') {
            return (
              <ProviderRow
                key={p.key}
                provider={p}
                isFirst={isFirst}
                statusKind={
                  githubLoading ? 'loading' : githubConnected ? 'connected' : 'idle'
                }
                installCount={installCount}
              />
            )
          }
          // Other available providers (Pulsar) link to their own detail route
          // with an "idle / Connect" affordance; the source count lives on the
          // detail page. Unavailable providers stay disabled placeholders.
          return (
            <ProviderRow
              key={p.key}
              provider={p}
              isFirst={isFirst}
              statusKind={p.available ? 'idle' : 'disabled'}
              installCount={0}
            />
          )
        })}
      </div>

      <div
        data-testid="integrations-roadmap-note"
        style={{
          marginTop: 22,
          fontSize: 12,
          color: 'var(--fg-faint)',
          lineHeight: 1.5,
        }}
      >
        Need something else? Custom OIDC, Azure DevOps, and self-hosted Git over SSH are on the roadmap.{' '}
        <a
          href="https://github.com/hadamrd/dashboard-plugin/issues"
          target="_blank"
          rel="noopener noreferrer"
          style={{ color: 'var(--accent, var(--fg-muted))', textDecoration: 'underline' }}
        >
          Request a provider →
        </a>
      </div>
    </PageContainer>
  )
}

type StatusKind = 'connected' | 'idle' | 'loading' | 'disabled'

interface ProviderRowProps {
  provider: ProviderMeta
  isFirst: boolean
  statusKind: StatusKind
  installCount: number
}

function ProviderRow({ provider, isFirst, statusKind, installCount }: ProviderRowProps) {
  const Logo = provider.logo
  const disabled = !provider.available
  const testId = `provider-card-${provider.key}`

  const rowStyle: CSSProperties = {
    display: 'grid',
    gridTemplateColumns: 'auto 1fr auto auto',
    alignItems: 'center',
    gap: 16,
    padding: '18px 20px',
    borderTop: isFirst ? 'none' : '1px solid var(--border)',
    textDecoration: 'none',
    color: 'inherit',
    cursor: disabled ? 'not-allowed' : 'pointer',
    opacity: disabled ? 0.55 : 1,
    transition: 'background-color 120ms ease',
  }

  const inner = (
    <>
      <span
        aria-hidden
        style={{
          display: 'inline-flex',
          alignItems: 'center',
          justifyContent: 'center',
          width: 44,
          height: 44,
          background: provider.bg,
          border: '1px solid var(--border)',
          borderRadius: 8,
          color: provider.accent,
        }}
      >
        <Logo size={22} brandColor={provider.available} />
      </span>
      <div style={{ display: 'grid', gap: 3, minWidth: 0 }}>
        <div style={{ fontSize: 14.5, fontWeight: 500 }}>{provider.name}</div>
        <div style={{ fontSize: 12.5, color: 'var(--fg-dim)' }}>{provider.tagline}</div>
      </div>

      {/* Right side: action affordance. Three states — disabled / idle / connected. */}
      <div
        data-testid={`${testId}-status`}
        style={{
          display: 'inline-flex',
          alignItems: 'center',
          gap: 8,
          fontSize: 12,
          color: 'var(--fg-muted)',
          whiteSpace: 'nowrap',
        }}
      >
        {statusKind === 'connected' && (
          <>
            <span
              style={{
                fontFamily: 'var(--font-mono)',
                fontWeight: 600,
                fontSize: 15,
                color: 'var(--fg)',
              }}
            >
              {installCount}
            </span>
            <span style={{ color: 'var(--fg-muted)' }}>
              {installCount === 1 ? 'integration' : 'integrations'}
            </span>
          </>
        )}
        {statusKind === 'idle' && (
          <span
            style={{
              display: 'inline-flex',
              alignItems: 'center',
              gap: 4,
              padding: '3px 9px',
              borderRadius: 999,
              border: '1px solid var(--border)',
              color: 'var(--ok, var(--fg-muted))',
              fontFamily: 'var(--font-mono)',
              fontSize: 11,
            }}
          >
            <Plus size={10} aria-hidden /> Connect
          </span>
        )}
        {statusKind === 'loading' && (
          <span style={{ fontFamily: 'var(--font-mono)', fontSize: 11, color: 'var(--fg-faint)' }}>
            Checking…
          </span>
        )}
        {statusKind === 'disabled' && (
          <span
            style={{
              fontFamily: 'var(--font-mono)',
              fontSize: 11,
              color: 'var(--fg-faint)',
              padding: '3px 9px',
              borderRadius: 999,
              border: '1px solid var(--border)',
            }}
          >
            Coming soon
          </span>
        )}
      </div>

      <ChevronRight
        size={16}
        aria-hidden
        style={{ color: 'var(--fg-faint)', opacity: disabled ? 0.4 : 1 }}
      />
    </>
  )

  if (disabled) {
    return (
      <div data-testid={testId} aria-disabled="true" style={rowStyle}>
        {inner}
      </div>
    )
  }

  // Per-provider detail route. Both live providers have a `/integrations/{key}`
  // file route; the union keeps the `to` prop type-safe.
  const to = provider.key === 'pulsar' ? '/integrations/pulsar' : '/integrations/github'
  return (
    <Link to={to} data-testid={testId} style={rowStyle}>
      {inner}
    </Link>
  )
}
