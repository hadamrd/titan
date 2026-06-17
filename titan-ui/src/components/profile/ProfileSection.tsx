import { ShieldCheck } from 'lucide-react'
import { useCurrentUser } from '@/api/hooks'
import { Badge } from '@/components/ui/Badge'
import { Input } from '@/components/ui/Input'
import { shortIssuer } from './sections'

export function ProfileSection() {
  const me = useCurrentUser()
  if (me === null) return null
  return (
    <>
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 18,
          padding: '18px 0',
          borderBottom: '1px solid var(--border)',
        }}
      >
        <div
          className="profile-avatar-lg"
          aria-hidden
          style={{
            width: 64,
            height: 64,
            fontSize: 22,
            borderRadius: 16,
            display: 'inline-flex',
            alignItems: 'center',
            justifyContent: 'center',
          }}
        >
          {me.initials}
        </div>
        <div>
          <div style={{ fontSize: 16, fontWeight: 600 }}>{me.name}</div>
          <div
            className="dim"
            style={{
              fontSize: 12,
              color: 'var(--fg-dim)',
              fontFamily: 'var(--font-mono)',
            }}
          >
            {me.email}
          </div>
          <div style={{ marginTop: 8, display: 'flex', gap: 6, flexWrap: 'wrap' }}>
            <Badge variant="info">
              <ShieldCheck size={10} aria-hidden /> {shortIssuer(me.issuer)}
            </Badge>
          </div>
        </div>
      </div>

      <div className="setting-row">
        <div>
          <div className="setting-label">Display name</div>
          <div className="setting-desc">From your identity provider — read-only here.</div>
        </div>
        <div className="setting-control">
          <Input value={me.name} readOnly aria-label="Display name" />
        </div>
      </div>

      <div className="setting-row">
        <div>
          <div className="setting-label">Email</div>
          <div className="setting-desc">Primary email on your OIDC account.</div>
        </div>
        <div className="setting-control">
          <Input value={me.email} readOnly aria-label="Email" />
        </div>
      </div>

      <div className="setting-row">
        <div>
          <div className="setting-label">Issuer</div>
          <div className="setting-desc">Authority that signed your current session.</div>
        </div>
        <div className="setting-control">
          <Input
            value={me.issuer ?? '—'}
            readOnly
            aria-label="Issuer"
            style={{ fontFamily: 'var(--font-mono)', fontSize: 12 }}
          />
        </div>
      </div>
    </>
  )
}
