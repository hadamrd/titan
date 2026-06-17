/**
 * Settings — Titan v3, 0.1.0 release polish (forge-loop tick #45).
 *
 * Workspace-level read-only view: identity, notifications policy, worker
 * fleet snapshot, visual preferences pointer (the canonical edit point is the
 * Tweaks ⚙ panel from PR #378), and an "About this Titan" footer.
 *
 * NO writable workspace mutations in 0.1.0 — the workspace name + timezone
 * are display-only until a `/api/v1/workspace` endpoint exists.
 */
import { createFileRoute, Link } from '@tanstack/react-router'
import { Bell, Server, Settings2, Tag } from 'lucide-react'
import { useServerInfo, useWorkspaceInfo } from '@/api/hooks'
import { TITAN_UI_VERSION } from '@/lib/version'
import { Badge } from '@/components/ui/Badge'
import { PageContainer, PageHeader } from '@/components/ui/Page'
import { useDocumentTitle } from '@/lib/useDocumentTitle'

export const Route = createFileRoute('/settings')({
  component: SettingsPage,
})

function SettingsPage() {
  useDocumentTitle('Settings')
  const ws = useWorkspaceInfo()
  // Server identity tile — graceful degradation: if /api/v1/info errors, fall
  // back to the pinned TITAN_UI_VERSION so Settings still renders. Commit and
  // builtAt show "—" in that case (no honest value we could synthesize).
  const serverInfo = useServerInfo()
  const serverVersion = serverInfo.data?.version ?? TITAN_UI_VERSION
  const serverCommit = serverInfo.data?.commit ?? '—'
  const serverBuiltAt = serverInfo.data?.builtAt ?? '—'

  return (
    <PageContainer width="narrow">
      <PageHeader
        title="Settings"
        description="Workspace-wide configuration · read-only in 0.1.0."
      />

      <div className="card">
        <div className="card-header">
          <h3 className="card-title">General</h3>
          <span className="card-sub" style={{ marginLeft: 'auto' }}>display only</span>
        </div>
        <dl className="kv-grid" style={{ padding: '14px 16px' }}>
          <KV label="Workspace name" value={ws.name} />
          <KV label="Default timezone" value={ws.timezone} mono />
        </dl>
      </div>

      <div className="card" style={{ marginTop: 14 }}>
        <div className="card-header">
          <h3 className="card-title">
            <Bell size={12} aria-hidden style={{ marginRight: 6, verticalAlign: -1 }} />
            Notifications
          </h3>
        </div>
        <div style={{ padding: '14px 16px', fontSize: 13, color: 'var(--fg)' }}>
          <p style={{ margin: 0, color: 'var(--fg-dim)' }}>
            Per-pipeline notifications are declared with the <code>notify:</code> step
            in PDL — e.g. <code>notify: { '{ slack: "#deploys" }' }</code>. Channel
            credentials live in the credential store.
          </p>
          <p style={{ marginTop: 10, fontSize: 12 }}>
            <Link to="/settings" className="link" aria-disabled>
              Manage Slack credentials →
            </Link>{' '}
            <span style={{ color: 'var(--fg-faint)' }}>
              (credential UI ships post-0.1.0)
            </span>
          </p>
        </div>
      </div>

      <div className="card" style={{ marginTop: 14 }}>
        <div className="card-header">
          <h3 className="card-title">
            <Server size={12} aria-hidden style={{ marginRight: 6, verticalAlign: -1 }} />
            Worker pools
          </h3>
          <Link to="/workers" className="link" style={{ marginLeft: 'auto', fontSize: 12 }}>
            View workers →
          </Link>
        </div>
        <dl className="kv-grid" style={{ padding: '14px 16px' }}>
          <KV
            label="Registered workers"
            value={ws.workerCount === null ? '—' : String(ws.workerCount)}
            mono
          />
          <KV
            label="Default queue label"
            value={ws.pools[0] ?? 'default'}
            mono
          />
          <div
            style={{
              display: 'grid',
              gridTemplateColumns: '160px 1fr',
              gap: 12,
              padding: '6px 0',
            }}
          >
            <dt style={{ fontSize: 12, color: 'var(--fg-dim)' }}>Pools</dt>
            <dd style={{ margin: 0, display: 'flex', gap: 6, flexWrap: 'wrap' }}>
              {ws.pools.length === 0 ? (
                <span style={{ fontSize: 13, color: 'var(--fg-dim)' }}>—</span>
              ) : (
                ws.pools.map((p) => (
                  <Badge key={p} variant="info">
                    <Tag size={10} aria-hidden /> {p}
                  </Badge>
                ))
              )}
            </dd>
          </div>
        </dl>
      </div>

      <div className="card" style={{ marginTop: 14 }}>
        <div className="card-header">
          <h3 className="card-title">
            <Settings2 size={12} aria-hidden style={{ marginRight: 6, verticalAlign: -1 }} />
            Visual preferences
          </h3>
        </div>
        <div style={{ padding: '14px 16px', fontSize: 13, color: 'var(--fg-dim)' }}>
          Theme, density, accent and time-format live in the <strong>Tweaks</strong>{' '}
          panel (top-right ⚙ button). Choices persist to <code>localStorage</code> on
          this device.
        </div>
      </div>

      <div className="card" style={{ marginTop: 14 }}>
        <div className="card-header">
          <h3 className="card-title">About this Titan</h3>
          <Badge variant="accent" style={{ marginLeft: 'auto' }}>
            v{serverVersion}
          </Badge>
        </div>
        <dl className="kv-grid" style={{ padding: '14px 16px' }}>
          <KV label="Server version" value={serverVersion} mono />
          <KV label="Commit" value={serverCommit} mono />
          <KV label="Built at" value={serverBuiltAt} mono />
          <KV label="UI version" value={ws.uiVersion} mono />
          <KV label="Docs" value="docs.titan-ci.io" mono />
        </dl>
      </div>
    </PageContainer>
  )
}

function KV({ label, value, mono }: { label: string; value: string; mono?: boolean }) {
  return (
    <div
      style={{
        display: 'grid',
        gridTemplateColumns: '160px 1fr',
        gap: 12,
        padding: '6px 0',
        borderBottom: '1px solid var(--border)',
      }}
    >
      <dt style={{ fontSize: 12, color: 'var(--fg-dim)' }}>{label}</dt>
      <dd
        style={{
          fontSize: 13,
          margin: 0,
          fontFamily: mono ? 'var(--font-mono)' : undefined,
          color: 'var(--fg)',
        }}
      >
        {value}
      </dd>
    </div>
  )
}
