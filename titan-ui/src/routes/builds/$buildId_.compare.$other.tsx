/**
 * /builds/$buildId/compare/$other — path-form side-by-side build diff
 * (closes #1077).
 *
 * <p>SRE-facing comparison page. The legacy {@code /builds/compare?a=X&b=Y}
 * route still exists (closes #716) and remains a back-compat alias — the
 * Compare picker now emits links in the path form because they round-trip
 * better through copy-paste and breadcrumbs.
 *
 * <p>Path params are validated as positive integers; malformed ids surface
 * an inline 404 message rather than a blank shell. Same-id-vs-itself is a
 * legitimate gesture (sanity-checking the diff machinery on a known-good
 * pair) so we render a "comparing build with itself" notice but still draw
 * the full table — every delta will read "=" and the SRE moves on.
 *
 * <p>Network coupling lives here, not in {@link BuildCompareView}, so the
 * view stays unit-testable with hand-built DTOs.
 *
 * <p>File-naming: the {@code $buildId_} trailing underscore UN-NESTS this
 * route from {@code builds/$buildId.tsx} (GH #51). The build-detail page is a
 * leaf that never renders an {@code <Outlet/>}, so as a nested child this
 * route matched the URL but never rendered — the parent swallowed the subtree
 * and {@code /builds/<a>/compare/<b>} (including the garbage-id 404 path)
 * came up as build-detail-or-blank. Same bug class as the PR #343
 * /login/callback fix. The URL is unchanged: {@code /builds/$buildId/compare/$other}.
 */
import { createFileRoute, Link } from '@tanstack/react-router'
import { useArtifacts, useBuild, useBuildNodes, useJob } from '@/api/hooks'
import { BuildCompareView } from '@/components/BuildCompareView'
import { PageContainer, PageHeader } from '@/components/ui/Page'
import { Skeleton } from '@/components/ui/Skeleton'
import { useDocumentTitle } from '@/lib/useDocumentTitle'

function toBuildId(raw: string | undefined): number | null {
  if (raw === undefined || raw.trim() === '') return null
  const n = Number(raw)
  if (!Number.isFinite(n) || n <= 0 || !Number.isInteger(n)) return null
  return n
}

export const Route = createFileRoute('/builds/$buildId_/compare/$other')({
  component: BuildComparePathPage,
})

function BuildComparePathPage() {
  const params = Route.useParams()
  const a = toBuildId(params.buildId)
  const b = toBuildId(params.other)
  useDocumentTitle(
    a !== null && b !== null ? `Compare #${a} ↔ #${b}` : 'Compare builds',
  )

  // Hooks called unconditionally — the hooks themselves treat undefined ids
  // as "query disabled" so we never violate rules-of-hooks on the bail paths.
  const buildAQ = useBuild(a ?? undefined)
  const buildBQ = useBuild(b ?? undefined)
  const nodesAQ = useBuildNodes(a ?? undefined)
  const nodesBQ = useBuildNodes(b ?? undefined)
  const jobAQ = useJob(buildAQ.data?.jobId)
  const jobBQ = useJob(buildBQ.data?.jobId)
  const artsAQ = useArtifacts(a ?? undefined)
  const artsBQ = useArtifacts(b ?? undefined)

  if (a === null || b === null) {
    return (
      <PageContainer width="wide">
        <PageHeader title="Compare builds" />
        <div
          data-testid="compare-invalid-id"
          role="alert"
          style={{
            fontSize: 13,
            color: 'var(--fail)',
            maxWidth: 560,
          }}
        >
          <h2 style={{ margin: '0 0 8px', fontSize: 15 }}>Build not found</h2>
          <p style={{ margin: 0, color: 'var(--fg-muted)' }}>
            One or both build ids in this URL are not valid integers. Open the
            comparison from the Compare button on a build detail page.
          </p>
          <p style={{ marginTop: 12 }}>
            <Link to="/builds" search={{ tab: 'all', q: '' }}>
              ← Back to builds
            </Link>
          </p>
        </div>
      </PageContainer>
    )
  }

  const compareTitle = `Compare #${a} ↔ #${b}`

  if (buildAQ.isLoading || buildBQ.isLoading) {
    return (
      <PageContainer width="wide" data-testid="compare-loading">
        <PageHeader title={compareTitle} />
        <Skeleton style={{ height: 120, width: '100%' }} />
      </PageContainer>
    )
  }

  if (buildAQ.isError || !buildAQ.data) {
    return (
      <PageContainer width="wide">
        <PageHeader title={compareTitle} />
        <div
          data-testid="compare-build-a-missing"
          role="alert"
          style={{ color: 'var(--fail)', fontSize: 13 }}
        >
          Build A (#{a}) could not be loaded — it may have been deleted or you
          may not have access.
        </div>
      </PageContainer>
    )
  }
  if (buildBQ.isError || !buildBQ.data) {
    return (
      <PageContainer width="wide">
        <PageHeader title={compareTitle} />
        <div
          data-testid="compare-build-b-missing"
          role="alert"
          style={{ color: 'var(--fail)', fontSize: 13 }}
        >
          Build B (#{b}) could not be loaded — it may have been deleted or you
          may not have access.
        </div>
      </PageContainer>
    )
  }

  return (
    <PageContainer width="wide">
      <PageHeader title={compareTitle} />
      <BuildCompareView
        buildA={buildAQ.data}
        buildB={buildBQ.data}
        nodesA={nodesAQ.data ?? []}
        nodesB={nodesBQ.data ?? []}
        jobNameA={jobAQ.data?.displayName ?? null}
        jobNameB={jobBQ.data?.displayName ?? null}
        artifactsA={artsAQ.data?.items ?? []}
        artifactsB={artsBQ.data?.items ?? []}
        sameBuild={a === b}
      />
    </PageContainer>
  )
}
