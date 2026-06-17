/**
 * SCM-provider logos as inline JSX SVGs.
 *
 * <p>Why inline (not imported from a CDN, not `<img src>`):
 *   <ul>
 *     <li><b>CSP-clean.</b> No remote asset references — the rig serves a strict
 *         `img-src 'self'` and we don't want exceptions per provider.</li>
 *     <li><b>Themable.</b> `currentColor` flows from the parent so the same SVG
 *         renders correctly on light + dark surfaces. Brand-colour usage is
 *         opt-in via the {@code brandColor} prop — the provider grid uses it,
 *         but list rows use {@code currentColor} for monochrome restraint.</li>
 *     <li><b>Zero bundle cost vs. lucide-react.</b> Each glyph is one path.</li>
 *   </ul>
 *
 * <p>Per doc 64, color is signal — the grid's coloured logos are an exception
 * confined to the provider-grid cards (the "brand window") and never appear
 * in data rows or headers.
 */
import type { CSSProperties, FC } from 'react'

export interface ProviderLogoProps {
  /** Glyph size in px. Defaults to 24 for grid cards; pass 14 for list rows. */
  size?: number
  /**
   * When {@code true}, render the provider's brand colour. When omitted or
   * {@code false}, the glyph inherits {@code currentColor} (mono).
   */
  brandColor?: boolean
  /** Extra inline styles (rare — mostly for {@code opacity} on disabled cards). */
  style?: CSSProperties
  /** Decorative by default — caller passes {@code aria-label} on the container. */
  title?: string
}

export const GitHubLogo: FC<ProviderLogoProps> = ({ size = 24, style, title }) => (
  <svg
    width={size}
    height={size}
    viewBox="0 0 24 24"
    fill="currentColor"
    aria-hidden={title ? undefined : true}
    role={title ? 'img' : undefined}
    style={style}
  >
    {title ? <title>{title}</title> : null}
    <path d="M12 .5C5.65.5.5 5.65.5 12c0 5.08 3.29 9.39 7.86 10.91.58.1.79-.25.79-.56 0-.28-.01-1.02-.02-2-3.2.7-3.88-1.54-3.88-1.54-.52-1.34-1.28-1.7-1.28-1.7-1.05-.72.08-.71.08-.71 1.16.08 1.77 1.19 1.77 1.19 1.03 1.77 2.7 1.26 3.36.96.1-.75.4-1.26.73-1.55-2.55-.29-5.24-1.28-5.24-5.69 0-1.26.45-2.29 1.18-3.1-.12-.29-.51-1.46.11-3.05 0 0 .97-.31 3.18 1.18a11.04 11.04 0 0 1 5.78 0c2.21-1.49 3.18-1.18 3.18-1.18.62 1.59.23 2.76.11 3.05.74.81 1.18 1.84 1.18 3.1 0 4.42-2.7 5.4-5.26 5.69.41.35.78 1.05.78 2.12 0 1.53-.01 2.77-.01 3.15 0 .31.21.67.8.56C20.21 21.38 23.5 17.07 23.5 12 23.5 5.65 18.35.5 12 .5z" />
  </svg>
)

export const GitLabLogo: FC<ProviderLogoProps> = ({
  size = 24,
  brandColor = false,
  style,
  title,
}) => (
  <svg
    width={size}
    height={size}
    viewBox="0 0 24 24"
    aria-hidden={title ? undefined : true}
    role={title ? 'img' : undefined}
    style={style}
  >
    {title ? <title>{title}</title> : null}
    {/* Tango — single-fill rendering of the GitLab fox; brand colour only on the
        provider-grid card. */}
    <path
      fill={brandColor ? '#FC6D26' : 'currentColor'}
      d="M12 21.42 8.59 10.92H4.31L12 21.42zM12 21.42l3.41-10.5H19.7L12 21.42zm-7.69-10.5L3.27 14.7a.73.73 0 0 0 .26.82L12 21.42 4.31 10.92zm0 0 1.43-4.4a.36.36 0 0 1 .69 0l1.16 3.58H4.31zM12 21.42l3.41-10.5h4.99l-1.16-3.58a.36.36 0 0 0-.69 0l-1.43 4.4-2.41-7.4a.36.36 0 0 0-.69 0L12 9.84 9.98 4.34a.36.36 0 0 0-.69 0L8.59 10.92 12 21.42zM19.7 10.92 20.73 14.7a.73.73 0 0 1-.26.82L12 21.42l7.69-10.5z"
    />
  </svg>
)

export const BitbucketLogo: FC<ProviderLogoProps> = ({
  size = 24,
  brandColor = false,
  style,
  title,
}) => (
  <svg
    width={size}
    height={size}
    viewBox="0 0 24 24"
    aria-hidden={title ? undefined : true}
    role={title ? 'img' : undefined}
    style={style}
  >
    {title ? <title>{title}</title> : null}
    <path
      fill={brandColor ? '#2684FF' : 'currentColor'}
      d="M2.65 3a.5.5 0 0 0-.5.58l2.86 17.5a.68.68 0 0 0 .67.57h13.74a.5.5 0 0 0 .5-.42l2.86-17.65a.5.5 0 0 0-.5-.58H2.65zm11.7 11.85h-4.7l-1.27-6.67h7.05l-1.08 6.67z"
    />
  </svg>
)

export const PulsarLogo: FC<ProviderLogoProps> = ({
  size = 24,
  brandColor = false,
  style,
  title,
}) => (
  <svg
    width={size}
    height={size}
    viewBox="0 0 24 24"
    aria-hidden={title ? undefined : true}
    role={title ? 'img' : undefined}
    style={style}
  >
    {title ? <title>{title}</title> : null}
    {/* A radar/pulse motif — concentric arcs emanating from a central node, the
        "ledger heartbeat" Pulsar is named for. No canonical wordmark exists for
        the in-house Pulsar SCM, so we ship a pulse glyph that rhymes with the
        sibling provider logos. Brand-tinted violet when the provider is live. */}
    <g
      fill="none"
      stroke={brandColor ? '#8B5CF6' : 'currentColor'}
      strokeWidth="2"
      strokeLinecap="round"
    >
      <circle cx="12" cy="12" r="2" fill={brandColor ? '#8B5CF6' : 'currentColor'} stroke="none" />
      <path d="M16.2 7.8a6 6 0 0 1 0 8.4" />
      <path d="M7.8 16.2a6 6 0 0 1 0-8.4" />
      <path d="M19 5a9.5 9.5 0 0 1 0 14" />
      <path d="M5 19a9.5 9.5 0 0 1 0-14" />
    </g>
  </svg>
)

export const GerritLogo: FC<ProviderLogoProps> = ({
  size = 24,
  brandColor = false,
  style,
  title,
}) => (
  <svg
    width={size}
    height={size}
    viewBox="0 0 24 24"
    aria-hidden={title ? undefined : true}
    role={title ? 'img' : undefined}
    style={style}
  >
    {title ? <title>{title}</title> : null}
    {/* Stylised review-arrow — Gerrit has no canonical wordmark glyph, so we
        ship a code-review-arrow motif (lines coming together) that visually
        rhymes with the others. Brand-tinted teal when the provider goes live. */}
    <g
      fill="none"
      stroke={brandColor ? '#3D9970' : 'currentColor'}
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      <circle cx="6" cy="6" r="2.4" />
      <circle cx="6" cy="18" r="2.4" />
      <circle cx="18" cy="12" r="2.4" />
      <path d="M6 8.4v7.2" />
      <path d="M8.4 6h4.4a3 3 0 0 1 3 3v.6" />
      <path d="M8.4 18h4.4a3 3 0 0 0 3-3v-.6" />
    </g>
  </svg>
)
