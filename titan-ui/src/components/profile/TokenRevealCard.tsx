import { Copy } from 'lucide-react'
import { useState } from 'react'
import type { PersonalAccessTokenCreatedDto } from '@/api/types'
import { Button } from '@/components/ui/Button'

interface TokenRevealCardProps {
  created: PersonalAccessTokenCreatedDto
  onDismiss: () => void
}

export function TokenRevealCard({ created, onDismiss }: TokenRevealCardProps) {
  const [copied, setCopied] = useState(false)

  async function onCopy() {
    try {
      if (
        typeof navigator !== 'undefined' &&
        typeof navigator.clipboard !== 'undefined'
      ) {
        await navigator.clipboard.writeText(created.token)
        setCopied(true)
      }
    } catch {
      // non-fatal
    }
  }

  return (
    <div
      data-testid="token-reveal"
      style={{
        padding: 12,
        marginBottom: 14,
        border: '1px solid var(--accent-soft, var(--border))',
        borderRadius: 6,
        background: 'var(--bg-soft, transparent)',
      }}
    >
      <div
        style={{
          fontSize: 12,
          fontWeight: 600,
          color: 'var(--fg)',
          marginBottom: 4,
        }}
      >
        Token created — copy it now.
      </div>
      <div style={{ fontSize: 11, color: 'var(--fg-dim)', marginBottom: 8 }}>
        This is the only time you will see this token. Store it somewhere safe;
        you cannot retrieve it again.
      </div>
      <div
        style={{
          display: 'flex',
          gap: 8,
          alignItems: 'center',
          flexWrap: 'wrap',
        }}
      >
        <code
          data-testid="token-secret"
          className="token-secret"
          style={{
            flex: 1,
            minWidth: 240,
            padding: '6px 8px',
            background: 'var(--bg, #111)',
            border: '1px solid var(--border)',
            borderRadius: 4,
            fontFamily: 'var(--font-mono)',
            fontSize: 12,
            userSelect: 'all',
            wordBreak: 'break-all',
          }}
        >
          {created.token}
        </code>
        <Button type="button" variant="outline" size="sm" onClick={onCopy}>
          <Copy size={12} aria-hidden /> {copied ? 'Copied' : 'Copy'}
        </Button>
        <Button type="button" variant="ghost" size="sm" onClick={onDismiss}>
          I&apos;ve copied it
        </Button>
      </div>
    </div>
  )
}
