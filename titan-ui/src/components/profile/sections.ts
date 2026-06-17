export type SectionKey =
  | 'profile'
  | 'notifications'
  | 'security'
  | 'sessions'
  | 'tokens'
  | 'cli'
  | 'appearance'

export interface SectionDef {
  k: SectionKey
  /** Short label shown in the left tab-nav. */
  label: string
  /** Card title for the section panel (H6 section-header tier). */
  title: string
  /** One-line muted description under the card title. */
  description: string
}

// Every tab renders inside the same Card shell: a `title`/`description` header
// + body. Keeping the header text here (not inside each section component) is
// what makes the 7 panels share one consistent container/header pattern (H8).
export const SECTIONS: SectionDef[] = [
  {
    k: 'profile',
    label: 'Profile',
    title: 'Profile',
    description: 'Identity surfaced from your OIDC provider — read-only here.',
  },
  {
    k: 'notifications',
    label: 'Notifications',
    title: 'Notifications',
    description: 'Email digests and Slack delivery for your account.',
  },
  {
    k: 'security',
    label: 'Security & 2FA',
    title: 'Security & two-factor',
    description: 'Password and multi-factor are managed by your identity provider.',
  },
  {
    k: 'sessions',
    label: 'Sessions',
    title: 'Active sessions',
    description: 'Devices and browsers with a live session on your account.',
  },
  {
    k: 'tokens',
    label: 'Access tokens',
    title: 'Personal access tokens',
    description: 'Authenticate the Titan CLI, CI scripts, and webhooks as you.',
  },
  {
    k: 'cli',
    label: 'CLI & integrations',
    title: 'CLI & integrations',
    description: 'Install the Titan CLI and connect external services.',
  },
  {
    k: 'appearance',
    label: 'Appearance',
    title: 'Appearance',
    description: 'Theme, density, and motion preferences — saved per device.',
  },
]

export function sectionDef(k: SectionKey): SectionDef {
  // SECTIONS is exhaustive over SectionKey, so the fallback is unreachable in
  // practice — but typing the return as non-optional keeps callers clean.
  return SECTIONS.find((s) => s.k === k) ?? SECTIONS[0]
}

const SECTION_KEYS: SectionKey[] = [
  'profile',
  'notifications',
  'security',
  'sessions',
  'tokens',
  'cli',
  'appearance',
]

export function hashToSection(hash: string): SectionKey {
  const raw = hash.replace(/^#/, '') as SectionKey
  return SECTION_KEYS.includes(raw) ? raw : 'profile'
}

export function shortIssuer(iss: string | null): string {
  if (iss === null) return 'unknown'
  const m = iss.match(/\/realms\/([^/?#]+)/)
  if (m) return `keycloak / ${m[1]}`
  try {
    return new URL(iss).host
  } catch {
    return iss
  }
}

export function keycloakAccountUrl(iss: string | null): string | null {
  if (iss === null) return null
  try {
    const u = new URL(iss)
    if (u.pathname.includes('/realms/')) {
      return `${u.origin}${u.pathname.replace(/\/$/, '')}/account`
    }
    return u.origin
  } catch {
    return null
  }
}
