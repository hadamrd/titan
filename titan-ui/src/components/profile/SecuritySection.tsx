import { ExternalLink } from 'lucide-react'
import { Button } from '@/components/ui/Button'
import { keycloakAccountUrl } from './sections'

export function SecuritySection({ iss }: { iss: string | null }) {
  const acctUrl = keycloakAccountUrl(iss)
  return (
    <>
      <div className="setting-row">
        <div>
          <div className="setting-label">Password</div>
          <div className="setting-desc">
            Managed by your identity provider — change it in the Account Console.
          </div>
        </div>
        <div className="setting-control">
          {acctUrl !== null ? (
            <a
              href={acctUrl}
              target="_blank"
              rel="noreferrer noopener"
              data-testid="kc-account-link"
            >
              <Button type="button" variant="outline" size="sm">
                Open Account Console <ExternalLink size={12} aria-hidden />
              </Button>
            </a>
          ) : (
            <span className="dim" style={{ fontSize: 12, color: 'var(--fg-dim)' }}>
              issuer unavailable
            </span>
          )}
        </div>
      </div>

      <div className="setting-row">
        <div>
          <div className="setting-label">Two-factor authentication</div>
          <div className="setting-desc">
            TOTP / WebAuthn flows are surfaced inline once the IdP exposes the
            credential-management API. Today: configure 2FA via the Account
            Console link above.
          </div>
        </div>
        <div className="setting-control">
          <span className="coming-soon-tag" data-testid="2fa-coming-soon">coming soon</span>
        </div>
      </div>
    </>
  )
}
