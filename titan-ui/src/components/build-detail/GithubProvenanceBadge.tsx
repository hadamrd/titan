/**
 * GithubProvenanceBadge — build-detail provenance badge for GitHub-App-triggered
 * builds (issue #892, finishing the UX half of #835).
 *
 * Renders, near the build header:
 *
 *     via GitHub App · {org}/{repo} · view on github.com
 *
 * where "view on github.com" is an external link to the commit on github.com,
 * opened in a new tab with `rel="noopener noreferrer"`.
 *
 * Render contract (mirrors the server's NON_NULL provenance projection):
 *   - Renders ONLY when `meta.provenance === "github-app"` AND both
 *     `repoFullName` and `commitUrl` are present non-empty strings.
 *   - For any other trigger type, or a partial/orphaned provenance bag, returns
 *     null — the build header is then byte-identical to a manually-triggered
 *     run. No broken link, no literal "undefined".
 */
import type { TriggerMetaDto } from '@/api/types'

/** Discriminator value the server stamps for GitHub-App-originated builds. */
export const GITHUB_APP_PROVENANCE = 'github-app'

export function GithubProvenanceBadge({
  meta,
}: {
  meta: TriggerMetaDto | null | undefined
}) {
  // Adversarial: provenance may be set while repoFullName / commitUrl are null
  // (orphaned linkage, force-push with no head commit). Suppress in that case.
  if (!meta || meta.provenance !== GITHUB_APP_PROVENANCE) return null
  const repoFullName = meta.repoFullName?.trim()
  const commitUrl = meta.commitUrl?.trim()
  if (!repoFullName || !commitUrl) return null

  return (
    <span
      className="bd-gh-provenance"
      data-testid="github-provenance-badge"
      style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}
    >
      <span>via GitHub App</span>
      <span className="sep">·</span>
      <span
        className="mono"
        data-testid="github-provenance-repo"
        style={{ fontFamily: 'var(--font-mono)' }}
      >
        {repoFullName}
      </span>
      <span className="sep">·</span>
      <a
        href={commitUrl}
        target="_blank"
        rel="noopener noreferrer"
        data-testid="github-provenance-link"
      >
        view on github.com
      </a>
    </span>
  )
}
