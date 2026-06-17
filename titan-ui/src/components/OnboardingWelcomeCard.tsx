/**
 * OnboardingWelcomeCard — the "first 90 seconds" hook that lives on the
 * Overview page until a user dismisses it or runs their first build.
 *
 * Gate logic (all four must hold to render):
 *   - activity feed has finished loading
 *   - activity feed is empty (no terminal builds have ever existed)
 *   - stats reports buildsToday === 0 OR stats is unavailable
 *   - localStorage `titan.onboarding.dismissed` is NOT "1"
 *
 * Dismissal is local-only — no backend endpoint. The card shows once per
 * browser per Titan instance; persistence across instances is a follow-up
 * (would need a server-side "first-build-ever" timestamp).
 */
import { useState } from 'react'
import { Link } from '@tanstack/react-router'
import { ArrowRight, Sparkles, X } from 'lucide-react'
import { useActivity, useStats } from '@/api/hooks'
import { ONBOARDING_DISMISSED_KEY } from '@/routes/onboarding'

export function OnboardingWelcomeCard() {
  const activity = useActivity(1)
  const stats = useStats()
  const [dismissed, setDismissed] = useState<boolean>(() => {
    try {
      return window.localStorage.getItem(ONBOARDING_DISMISSED_KEY) === '1'
    } catch {
      return false
    }
  })

  if (dismissed) return null
  if (activity.isLoading) return null
  // A query *error* OR an in-flight stats *load* must not read as "fresh, no
  // builds yet". On error/while-loading `stats.data` is undefined and the feed
  // looks empty — but we cannot confirm the instance is fresh, so we bail rather
  // than show the onboarding primary on what may be an established controller
  // (and avoid a transient primary swap while a slow stats request settles).
  // This mirrors the `isFreshInstance` gate on the Overview page
  // (routes/index.tsx) so exactly one primary action survives in every data
  // state (H5).
  if (activity.isError || stats.isError || stats.isLoading) return null
  const items = activity.data?.pages.flatMap((p) => p.items) ?? []
  if (items.length > 0) return null
  // If stats says builds-today > 0 we still hide — activity might lag.
  if (stats.data && stats.data.buildsToday > 0) return null

  function onDismiss(): void {
    try {
      window.localStorage.setItem(ONBOARDING_DISMISSED_KEY, '1')
    } catch {
      /* ignore */
    }
    setDismissed(true)
  }

  return (
    <div
      className="card"
      role="region"
      aria-label="Onboarding"
      style={{
        marginBottom: 14,
        borderColor: 'var(--accent)',
        background:
          'linear-gradient(135deg, color-mix(in oklab, var(--accent) 8%, var(--bg)) 0%, var(--bg) 60%)',
      }}
    >
      <div
        className="card-body"
        style={{
          display: 'grid',
          gridTemplateColumns: '1fr auto',
          gap: 18,
          alignItems: 'center',
        }}
      >
        <div style={{ display: 'grid', gap: 6 }}>
          <div
            style={{
              display: 'inline-flex',
              alignItems: 'center',
              gap: 6,
              fontSize: 11,
              color: 'var(--accent)',
              textTransform: 'uppercase',
              letterSpacing: '0.08em',
              fontFamily: 'var(--font-mono)',
            }}
          >
            <Sparkles size={11} aria-hidden /> Welcome to Titan
          </div>
          <div style={{ fontSize: 16, fontWeight: 600 }}>Let&apos;s run your first build</div>
          <div style={{ fontSize: 12, color: 'var(--fg-dim)' }}>
            Four short steps — connect a repo, pick a trigger, drop in a pipeline,
            and watch the logs stream. Takes about a minute.
          </div>
        </div>
        <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
          <Link to="/onboarding" className="btn btn-sm btn-primary">
            Start <ArrowRight size={12} aria-hidden />
          </Link>
          <button
            type="button"
            className="btn btn-sm btn-ghost"
            onClick={onDismiss}
            aria-label="Dismiss onboarding"
          >
            <X size={12} aria-hidden />
          </button>
        </div>
      </div>
    </div>
  )
}
