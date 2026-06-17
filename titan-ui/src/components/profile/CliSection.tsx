import { Check, Copy } from 'lucide-react'
import { useState } from 'react'

export function CliSection() {
  const [copied, setCopied] = useState(false)
  const cmd = 'curl -fsSL https://get.titan.dev/install.sh | sh'

  async function onCopyInstall() {
    try {
      if (
        typeof navigator !== 'undefined' &&
        typeof navigator.clipboard !== 'undefined'
      ) {
        await navigator.clipboard.writeText(cmd)
        setCopied(true)
        setTimeout(() => setCopied(false), 1200)
      }
    } catch {
      // non-fatal: text is selectable
    }
  }

  return (
    <>
      <div className="setting-row setting-row-stacked">
        <div>
          <div className="setting-label">Titan CLI</div>
          <div className="setting-desc">
            Install on macOS / Linux via the install script. Use a personal
            access token (see the Access tokens section) for{' '}
            <code style={{ fontFamily: 'var(--font-mono)' }}>titan login</code>.
          </div>
        </div>
        <button
          type="button"
          onClick={() => {
            void onCopyInstall()
          }}
          data-testid="cli-install-copy"
          className="cli-install-snippet"
          aria-label="Copy CLI install command"
        >
          <span className="cli-prompt" aria-hidden>$</span>
          <span className="cli-cmd">{cmd}</span>
          {copied ? (
            <Check size={12} aria-hidden className="cli-ic ok" />
          ) : (
            <Copy size={12} aria-hidden className="cli-ic" />
          )}
        </button>
      </div>

      <div className="setting-row">
        <div>
          <div className="setting-label">GitHub</div>
          <div className="setting-desc">
            One-click connect to your GitHub org with auto-sync. Lands once the
            App-install OAuth dance is wired server-side.
          </div>
        </div>
        <div className="setting-control">
          <span className="coming-soon-tag" data-testid="github-coming-soon">coming soon</span>
        </div>
      </div>
    </>
  )
}
