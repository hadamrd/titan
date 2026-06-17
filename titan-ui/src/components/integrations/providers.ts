/**
 * SCM-provider catalogue for the `/integrations` provider grid.
 *
 * <p>V1 ships GitHub only (real wire). GitLab / Bitbucket / Gerrit are
 * visible-but-disabled placeholders so the grid communicates the product's
 * direction without overpromising. To wire one of them up later, the
 * contributor flips {@code available: true}, drops a route at
 * {@code /integrations/{key}}, and the card becomes a live link.
 *
 * <p>Brand-colour accents are deliberate exceptions to doc 64's
 * "color = signal" rule, scoped to the provider-grid card chrome only
 * (logo wash + corner accent) and the per-provider breadcrumb tile. Data
 * rows on the per-provider detail page stay monochrome.
 *
 * <p>Per-provider {@code accent} / {@code bg} are oklch literals (not token
 * names) because they are brand-tinted exceptions and live outside the
 * signal-only token palette. They render in the logo backdrop on the grid
 * card AND on the breadcrumb tile of {@code /integrations/{provider}}.
 */
import type { FC } from 'react'
import {
  GitHubLogo,
  GitLabLogo,
  BitbucketLogo,
  GerritLogo,
  PulsarLogo,
  type ProviderLogoProps,
} from './ProviderLogos'

export type ProviderKey = 'github' | 'pulsar' | 'gitlab' | 'bitbucket' | 'gerrit'

export interface ProviderMeta {
  key: ProviderKey
  name: string
  tagline: string
  /** oklch literal — brand-tinted accent for the logo glyph stroke / wordmark. */
  accent: string
  /** oklch literal — subtle background wash behind the logo (card + breadcrumb tile). */
  bg: string
  /** {@code true} iff the provider has a live route + working hooks. */
  available: boolean
  logo: FC<ProviderLogoProps>
  /**
   * Webhook events the rig subscribes to for this provider. Hard-coded — this
   * is a property of the App registration, not the install, so it doesn't come
   * from the DTO. For GitHub the source-of-truth list is the
   * {@code default_events} in {@code buildManifest()} (api/githubApp.ts):
   * {@code push}, {@code pull_request}, {@code repository}.
   *
   * <p>Rendered as small mono chips on the install row so an admin can see at
   * a glance what the App is wired to react to.
   */
  events: ReadonlyArray<string>
}

export const PROVIDERS: ReadonlyArray<ProviderMeta> = [
  {
    key: 'github',
    name: 'GitHub',
    tagline: 'App-based install. Discovers .titan/pipelines/*.yml in every selected repo.',
    // Warm-grey on near-black — keeps GitHub's "ink on paper" feel without
    // breaking the dark surface.
    accent: 'oklch(0.86 0.005 60)',
    bg: 'oklch(0.20 0.005 60)',
    available: true,
    logo: GitHubLogo,
    events: ['push', 'pull_request', 'repository'],
  },
  {
    key: 'pulsar',
    name: 'Pulsar',
    // Engine ingestion is live on trunk via TWO paths: the App-pattern webhook
    // PulsarWebhookApi (POST /api/v1/pulsar/events, HMAC → enqueueBuild, #1287)
    // and the poll-based PulsarScannerScheduler → PulsarEventSource (#1281); both
    // feed the ledger 'build' check (PulsarCheckReporter, #1282). The admin-facing
    // source-registration endpoint now exists too — GET|POST /api/v1/pulsar/sources
    // + POST /api/v1/pulsar/sources/{id}/sync (PulsarSourcesApi, #1293) — so the
    // card is live: /integrations/pulsar registers a node, lists sources, and syncs.
    tagline: 'Register a Pulsar node — signed change events feed the build ledger.',
    // Pulse-violet — the ledger-heartbeat accent. Kept distinct from the SCM
    // greys/oranges/blues so the first-party node reads as "ours".
    accent: 'oklch(0.74 0.16 295)',
    bg: 'oklch(0.22 0.05 295)',
    available: true,
    logo: PulsarLogo,
    events: ['change.merged', 'change.opened'],
  },
  {
    key: 'gitlab',
    name: 'GitLab',
    tagline: 'OAuth + project webhooks. Coming soon.',
    accent: 'oklch(0.72 0.18 50)', // GitLab tango orange
    bg: 'oklch(0.24 0.04 50)',
    available: false,
    logo: GitLabLogo,
    events: [],
  },
  {
    key: 'bitbucket',
    name: 'Bitbucket',
    tagline: 'Workspace tokens + repo webhooks. Coming soon.',
    accent: 'oklch(0.70 0.16 250)', // Bitbucket Atlassian blue
    bg: 'oklch(0.22 0.04 250)',
    available: false,
    logo: BitbucketLogo,
    events: [],
  },
  {
    key: 'gerrit',
    name: 'Gerrit',
    tagline: 'SSH + change-event stream. Coming soon.',
    accent: 'oklch(0.72 0.14 155)', // review-arrow green
    bg: 'oklch(0.22 0.03 155)',
    available: false,
    logo: GerritLogo,
    events: [],
  },
]

/** O(1) lookup by key. */
export function getProvider(key: ProviderKey): ProviderMeta {
  const p = PROVIDERS.find((x) => x.key === key)
  if (!p) throw new Error(`unknown provider: ${key}`)
  return p
}
