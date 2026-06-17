/**
 * Profile — v3 redesign (closes #574).
 *
 * Settings-grid + 7-section left nav. Section components live in
 * `@/components/profile/`. This file owns only the route definition,
 * layout composition, and the section selector (URL hash sync).
 *
 * Honest about what is wired vs. "Coming soon":
 *   1. Profile           — real OIDC claim surface (display name / email / handle)
 *   2. Notifications     — Coming soon (no /api/v1/notifications endpoint yet)
 *   3. Security & 2FA    — link out to Keycloak Account Console; 2FA Coming soon
 *   4. Sessions          — Coming soon (no GET /api/v1/sessions endpoint yet)
 *   5. Access tokens     — REAL: preserves PR #464 / #526 PAT CRUD verbatim
 *   6. CLI & integrations — install snippet (clipboard); GitHub Coming soon
 *   7. Appearance        — pointer to the TweaksPanel (top-right, persisted per device)
 */
import { createFileRoute } from '@tanstack/react-router'
import { LogOut } from 'lucide-react'
import type { ReactNode } from 'react'
import { useEffect, useState } from 'react'
import { useCurrentUser } from '@/api/hooks'
import { useAuth } from '@/auth/AuthProvider'
import { AppearanceSection } from '@/components/profile/AppearanceSection'
import { CliSection } from '@/components/profile/CliSection'
import { NotificationsSection } from '@/components/profile/NotificationsSection'
import { PersonalAccessTokensCard } from '@/components/profile/PersonalAccessTokensCard'
import { ProfileSection } from '@/components/profile/ProfileSection'
import { SecuritySection } from '@/components/profile/SecuritySection'
import {
  SECTIONS,
  type SectionKey,
  hashToSection,
  sectionDef,
} from '@/components/profile/sections'
import { SessionsSection } from '@/components/profile/SessionsSection'
import { Button } from '@/components/ui/Button'
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/Card'
import { PageContainer, PageHeader } from '@/components/ui/Page'
import { useDocumentTitle } from '@/lib/useDocumentTitle'

export const Route = createFileRoute('/profile')({
  component: ProfilePage,
})

function ProfilePage() {
  useDocumentTitle('Profile & settings')
  const me = useCurrentUser()
  const { signoutRedirect } = useAuth()
  const [section, setSectionState] = useState<SectionKey>(() =>
    typeof window === 'undefined' ? 'profile' : hashToSection(window.location.hash),
  )
  useEffect(() => {
    const onHash = () => setSectionState(hashToSection(window.location.hash))
    window.addEventListener('hashchange', onHash)
    return () => window.removeEventListener('hashchange', onHash)
  }, [])
  const setSection = (k: SectionKey) => {
    setSectionState(k)
    if (typeof window !== 'undefined' && window.location.hash !== `#${k}`) {
      history.replaceState(null, '', `#${k}`)
    }
  }

  return (
    // `narrow` is max-w-3xl in the shared frame; the settings page wants the
    // chart's form/detail width (max-w-5xl) so the tab-nav + token table breathe
    // without sprawling. tailwind-merge lets the explicit max-w-5xl win (H1).
    <PageContainer width="narrow" className="max-w-5xl">
      <PageHeader
        title="Profile & settings"
        description="Personal settings — applies only to your account"
        actions={
          me !== null ? (
            <Button
              type="button"
              variant="outline"
              size="sm"
              onClick={() => {
                void signoutRedirect()
              }}
            >
              <LogOut size={14} aria-hidden /> Sign out
            </Button>
          ) : undefined
        }
      />

      {me === null ? (
        <Card
          style={{ padding: '24px', fontSize: 13, color: 'var(--fg-dim)' }}
          data-testid="profile-not-signed-in"
        >
          Not signed in.
        </Card>
      ) : (
        <div className="settings-grid" data-testid="profile-settings-grid">
          <nav
            className="settings-nav"
            aria-label="Settings sections"
            data-testid="settings-nav"
          >
            {SECTIONS.map((s) => (
              <button
                key={s.k}
                type="button"
                className={`item ${section === s.k ? 'active' : ''}`}
                onClick={() => setSection(s.k)}
                data-testid={`nav-${s.k}`}
                aria-current={section === s.k ? 'page' : undefined}
                style={{
                  textAlign: 'left',
                  background: 'transparent',
                  border: 0,
                  font: 'inherit',
                  cursor: 'pointer',
                  width: '100%',
                }}
              >
                {s.label}
              </button>
            ))}
          </nav>

          <div data-testid={`section-${section}`}>
            <SectionCard sectionKey={section}>
              {section === 'profile' && <ProfileSection />}
              {section === 'notifications' && <NotificationsSection />}
              {section === 'security' && <SecuritySection iss={me.issuer} />}
              {section === 'sessions' && <SessionsSection />}
              {section === 'tokens' && <PersonalAccessTokensCard />}
              {section === 'cli' && <CliSection />}
              {section === 'appearance' && <AppearanceSection />}
            </SectionCard>
          </div>
        </div>
      )}
    </PageContainer>
  )
}

/**
 * Shared shell every tab renders inside (H8): a `Card` with a consistent
 * title/description header and a body. Centralising the header here — instead
 * of each section drawing its own — is what makes the 7 panels feel like one
 * page rather than "assembled by different people".
 */
function SectionCard({
  sectionKey,
  children,
}: {
  sectionKey: SectionKey
  children: ReactNode
}) {
  const def = sectionDef(sectionKey)
  return (
    <Card data-testid="section-card">
      <CardHeader>
        <div className="min-w-0">
          <CardTitle>{def.title}</CardTitle>
          <CardDescription>{def.description}</CardDescription>
        </div>
      </CardHeader>
      <CardContent>{children}</CardContent>
    </Card>
  )
}
