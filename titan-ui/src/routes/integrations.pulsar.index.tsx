/**
 * /integrations/pulsar — provider-detail surface for Pulsar SCM nodes (#1283).
 *
 * <p>Backs the real admin registration endpoint that landed in #1293
 * ({@code PulsarSourcesApi}): list / register / sync Pulsar nodes. Mirrors the
 * look + IA of {@code /integrations/github} (breadcrumb · brand-tile header ·
 * hairline divider · "REGISTERED · N" section · hairline-separated rows).
 *
 * <p>Unlike GitHub (App-pattern, repo-picking happens server-side via an
 * external install URL), a Pulsar node is registered by URL straight from
 * Titan — so this page owns the connect FORM. On submit it POSTs to
 * {@code /api/v1/pulsar/sources}; a duplicate / invalid-URL / unreachable node
 * surfaces the server's typed problem inline rather than a generic failure.
 *
 * <p>Each registered source has a Sync action → re-probe the node and refresh
 * its repoCount / lastPolledAt in place.
 */
import { useState } from 'react'
import { createFileRoute, Link } from '@tanstack/react-router'
import { ChevronLeft, ExternalLink, Loader2, Plus, RefreshCw } from 'lucide-react'
import { Input } from '@/components/ui/Input'
import { Skeleton } from '@/components/ui/Skeleton'
import {
  usePulsarSources,
  useRegisterPulsarSource,
  useSyncPulsarSource,
  pulsarErrorMessage,
  type PulsarSourceDto,
} from '@/api/pulsar'
import { ApiError } from '@/api/types'
import { useDocumentTitle } from '@/lib/useDocumentTitle'
import { PageContainer, PageHeader } from '@/components/ui/Page'
import { PulsarLogo } from '@/components/integrations/ProviderLogos'
import { getProvider } from '@/components/integrations/providers'

export const Route = createFileRoute('/integrations/pulsar/')({
  component: IntegrationsPulsarIndexPage,
})

const PULSAR = getProvider('pulsar')

function listErrorMessage(err: unknown): string {
  if (err instanceof ApiError) {
    if (err.status === 403) return 'You need the ADMIN role to view Pulsar sources.'
    if (err.status >= 500) return 'Server error — please retry.'
    return err.problem.detail ?? err.message
  }
  return 'Request failed.'
}

function IntegrationsPulsarIndexPage() {
  useDocumentTitle('Pulsar · Integrations')
  const sourcesQ = usePulsarSources()
  const sources = sourcesQ.data ?? []
  const sourceCount = sources.length

  return (
    <PageContainer width="default">
      {/* Breadcrumb */}
      <div
        data-testid="pulsar-breadcrumb"
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
        <span style={{ color: 'var(--fg)' }}>Pulsar</span>
      </div>

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
                background: PULSAR.bg,
                border: '1px solid var(--border)',
                borderRadius: 12,
                color: PULSAR.accent,
              }}
            >
              <PulsarLogo size={30} brandColor />
            </span>
            Pulsar
          </span>
        }
        description={PULSAR.tagline}
        actions={
          <a
            href="https://github.com/hadamrd/dashboard-plugin#readme"
            target="_blank"
            rel="noopener noreferrer"
            className="btn btn-ghost"
            style={{ display: 'inline-flex', alignItems: 'center', gap: 6, fontSize: 12 }}
          >
            <ExternalLink size={12} aria-hidden /> Docs
          </a>
        }
      />

      <div style={{ borderTop: '1px solid var(--border)', marginBottom: 32 }} />

      {/* Connect form */}
      <ConnectPulsarNode />

      {/* "REGISTERED · N" section label */}
      <div
        style={{
          display: 'flex',
          alignItems: 'baseline',
          justifyContent: 'space-between',
          margin: '36px 0 12px',
          fontSize: 11,
          fontWeight: 600,
          letterSpacing: '0.08em',
          textTransform: 'uppercase',
          color: 'var(--fg-faint)',
        }}
      >
        <span>Registered</span>
        <span
          style={{
            fontFamily: 'var(--font-mono)',
            fontWeight: 400,
            fontSize: 12,
            letterSpacing: 0,
          }}
        >
          {sourceCount}
        </span>
      </div>

      {sourcesQ.isLoading && (
        <div style={{ padding: '20px 0' }}>
          <Skeleton style={{ height: 18, marginBottom: 10 }} />
          <Skeleton style={{ height: 18, marginBottom: 10 }} />
          <Skeleton style={{ height: 18 }} />
        </div>
      )}

      {sourcesQ.isError && (
        <div
          className="card"
          role="alert"
          style={{ padding: 14, color: 'var(--fail)' }}
          data-testid="pulsar-list-error"
        >
          {listErrorMessage(sourcesQ.error)}
        </div>
      )}

      {!sourcesQ.isLoading && !sourcesQ.isError && sourceCount === 0 && <EmptySources />}

      {!sourcesQ.isLoading && !sourcesQ.isError && sourceCount > 0 && (
        <div>
          {sources.map((s) => (
            <SourceRow key={s.id} source={s} />
          ))}
        </div>
      )}
    </PageContainer>
  )
}

// ── connect form ──────────────────────────────────────────────────────────────

function ConnectPulsarNode() {
  const [nodeUrl, setNodeUrl] = useState('')
  const [nodeName, setNodeName] = useState('')
  const registerMut = useRegisterPulsarSource()

  const trimmedUrl = nodeUrl.trim()
  const canSubmit = trimmedUrl.length > 0 && !registerMut.isPending

  function onSubmit(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault()
    if (!canSubmit) return
    registerMut.mutate(
      { nodeUrl: trimmedUrl, nodeName: nodeName.trim() || null },
      {
        onSuccess: () => {
          setNodeUrl('')
          setNodeName('')
        },
      },
    )
  }

  return (
    <form
      onSubmit={onSubmit}
      data-testid="pulsar-connect-form"
      className="card"
      style={{ padding: 18, display: 'grid', gap: 14 }}
    >
      <div style={{ fontSize: 14, fontWeight: 600, color: 'var(--fg)' }}>
        Connect a Pulsar node
      </div>
      <div style={{ display: 'grid', gap: 12, gridTemplateColumns: '2fr 1fr', alignItems: 'end' }}>
        <label style={{ display: 'grid', gap: 6, minWidth: 0 }}>
          <span style={{ fontSize: 12, color: 'var(--fg-muted)' }}>
            Node URL <span style={{ color: 'var(--fail)' }}>*</span>
          </span>
          <Input
            type="url"
            inputMode="url"
            required
            placeholder="https://pulsar.example.com"
            value={nodeUrl}
            onChange={(e) => setNodeUrl(e.target.value)}
            data-testid="pulsar-node-url-input"
            aria-label="Pulsar node URL"
            disabled={registerMut.isPending}
          />
        </label>
        <label style={{ display: 'grid', gap: 6, minWidth: 0 }}>
          <span style={{ fontSize: 12, color: 'var(--fg-muted)' }}>Label (optional)</span>
          <Input
            type="text"
            placeholder="prod-east"
            value={nodeName}
            onChange={(e) => setNodeName(e.target.value)}
            data-testid="pulsar-node-name-input"
            aria-label="Pulsar node label"
            disabled={registerMut.isPending}
          />
        </label>
      </div>

      {registerMut.isError && (
        <div
          role="alert"
          data-testid="pulsar-connect-error"
          style={{ fontSize: 12.5, color: 'var(--fail)' }}
        >
          {pulsarErrorMessage(registerMut.error)}
        </div>
      )}

      <div style={{ display: 'flex', justifyContent: 'flex-end' }}>
        <button
          type="submit"
          className="btn btn-primary"
          disabled={!canSubmit}
          data-testid="pulsar-connect-submit"
          style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}
        >
          {registerMut.isPending ? (
            <Loader2 size={13} className="spin" aria-hidden />
          ) : (
            <Plus size={13} aria-hidden />
          )}
          Register node
        </button>
      </div>
    </form>
  )
}

// ── source row ──────────────────────────────────────────────────────────────

function SourceRow({ source }: { source: PulsarSourceDto }) {
  const syncMut = useSyncPulsarSource()
  const createdDate = formatYmd(source.createdAt)
  const polled =
    source.lastPolledAt != null ? `synced ${formatYmd(source.lastPolledAt)}` : 'never synced'
  const repos =
    source.repoCount != null
      ? `${source.repoCount} repo${source.repoCount === 1 ? '' : 's'}`
      : 'repos unknown'

  function onSync() {
    syncMut.mutate({ id: source.id })
  }

  return (
    <div
      data-testid={`pulsar-source-row-${source.id}`}
      style={{
        display: 'grid',
        gridTemplateColumns: 'auto 1fr auto auto',
        alignItems: 'center',
        gap: 18,
        padding: '22px 4px',
        borderTop: '1px solid var(--border)',
        borderBottom: '1px solid var(--border)',
        marginTop: -1,
      }}
    >
      <span
        aria-hidden
        title="registered"
        style={{
          width: 9,
          height: 9,
          borderRadius: 999,
          background: 'var(--ok)',
          display: 'inline-block',
          flexShrink: 0,
        }}
      />

      <div style={{ display: 'grid', gap: 4, minWidth: 0 }}>
        <div style={{ fontSize: 15, fontWeight: 600, color: 'var(--fg)' }}>
          {source.nodeName ?? source.nodeUrl}
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
          <span data-testid={`pulsar-source-url-${source.id}`}>{source.nodeUrl}</span>
          <Dot />
          <span data-testid={`pulsar-source-repos-${source.id}`}>{repos}</span>
          <Dot />
          <span data-testid={`pulsar-source-polled-${source.id}`}>{polled}</span>
          <Dot />
          <span>added {createdDate}</span>
        </div>
      </div>

      {/* Event chips — what the node feeds the ledger */}
      {PULSAR.events.length > 0 && (
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
          {PULSAR.events.map((ev) => (
            <span
              key={ev}
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

      <div style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}>
        <button
          type="button"
          className="btn btn-sm btn-ghost"
          onClick={onSync}
          disabled={syncMut.isPending}
          aria-label={`Sync ${source.nodeName ?? source.nodeUrl}`}
          title="Re-probe node &amp; refresh repo count"
          data-testid={`pulsar-source-sync-${source.id}`}
          style={{ padding: '4px 6px' }}
        >
          {syncMut.isPending ? (
            <Loader2 size={14} className="spin" aria-hidden />
          ) : (
            <RefreshCw size={14} aria-hidden />
          )}
        </button>
      </div>

      {syncMut.isError && (
        <div
          role="alert"
          data-testid={`pulsar-source-sync-error-${source.id}`}
          style={{ gridColumn: '1 / -1', fontSize: 12, color: 'var(--fail)' }}
        >
          {pulsarErrorMessage(syncMut.error)}
        </div>
      )}
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

function EmptySources() {
  return (
    <div
      data-testid="pulsar-sources-empty"
      style={{
        padding: '40px 24px',
        textAlign: 'center',
        display: 'grid',
        gap: 12,
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
          background: PULSAR.bg,
          border: '1px solid var(--border)',
          borderRadius: 12,
          color: PULSAR.accent,
        }}
      >
        <PulsarLogo size={36} brandColor />
      </span>
      <div style={{ fontSize: 16, fontWeight: 500, color: 'var(--fg)' }}>
        No Pulsar nodes registered yet
      </div>
      <div style={{ maxWidth: 460, lineHeight: 1.5, fontSize: 13 }}>
        Register a Pulsar node above so Titan can poll its change events and feed
        the build ledger.
      </div>
    </div>
  )
}
