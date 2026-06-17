/**
 * /builds/compare?a=<id>&b=<id> — side-by-side build diff (closes #716).
 *
 * <p>Pure route wrapper: parses + validates the two build ids from the URL,
 * fetches each build + its flow nodes via {@link useBuild}/{@link useBuildNodes},
 * and hands the resolved data to {@link BuildCompareView}. The view component
 * has no network coupling so it can be unit-tested with hand-built DTOs.
 *
 * <p>Search-param contract: {@code a} and {@code b} are positive integers
 * (build ids). Missing / malformed inputs render an inline error rather than
 * a blank page — a stale bookmark must still tell the SRE what went wrong.
 */
import { createFileRoute } from '@tanstack/react-router'
import { useBuild, useBuildNodes, useJob } from '@/api/hooks'
import { BuildCompareView } from '@/components/BuildCompareView'
import { PageContainer, PageHeader } from '@/components/ui/Page'
import { Skeleton } from '@/components/ui/Skeleton'
import { useDocumentTitle } from '@/lib/useDocumentTitle'

interface CompareSearch {
  a: number | null
  b: number | null
}

function toBuildId(raw: unknown): number | null {
  if (typeof raw === 'number' && Number.isFinite(raw) && raw > 0) {
    return Math.floor(raw)
  }
  if (typeof raw === 'string' && raw.trim() !== '') {
    const n = Number(raw)
    if (Number.isFinite(n) && n > 0) return Math.floor(n)
  }
  return null
}

function validateSearch(raw: Record<string, unknown>): CompareSearch {
  return { a: toBuildId(raw.a), b: toBuildId(raw.b) }
}

export const Route = createFileRoute('/builds/compare')({
  component: BuildComparePage,
  validateSearch,
})

function BuildComparePage() {
  const { a, b } = Route.useSearch()
  useDocumentTitle(
    a !== null && b !== null ? `Compare #${a} ↔ #${b}` : 'Compare builds',
  )

  // Hooks called unconditionally — the hook itself handles undefined ids by
  // disabling the query. NEVER early-return before the hook calls or we
  // break the React rules-of-hooks invariant (CONSTITUTION §6).
  const buildAQ = useBuild(a ?? undefined)
  const buildBQ = useBuild(b ?? undefined)
  const nodesAQ = useBuildNodes(a ?? undefined)
  const nodesBQ = useBuildNodes(b ?? undefined)
  const jobAQ = useJob(buildAQ.data?.jobId)
  const jobBQ = useJob(buildBQ.data?.jobId)

  if (a === null || b === null) {
    return (
      <PageContainer width="wide">
        <PageHeader title="Compare builds" />
        <div
          data-testid="compare-missing-params"
          style={{ fontSize: 13, color: 'var(--fail)' }}
        >
          Comparison requires two build ids — open this page via the Compare
          button on a build detail page.
        </div>
      </PageContainer>
    )
  }

  const compareTitle = `Compare #${a} ↔ #${b}`

  if (buildAQ.isLoading || buildBQ.isLoading) {
    return (
      <PageContainer width="wide">
        <PageHeader title={compareTitle} />
        <Skeleton style={{ height: 120, width: '100%' }} />
      </PageContainer>
    )
  }

  if (buildAQ.isError || !buildAQ.data) {
    return (
      <PageContainer width="wide">
        <PageHeader title={compareTitle} />
        <p style={{ color: 'var(--fail)', fontSize: 13 }}>
          Build A (#{a}) could not be loaded.
        </p>
      </PageContainer>
    )
  }
  if (buildBQ.isError || !buildBQ.data) {
    return (
      <PageContainer width="wide">
        <PageHeader title={compareTitle} />
        <p style={{ color: 'var(--fail)', fontSize: 13 }}>
          Build B (#{b}) could not be loaded.
        </p>
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
      />
    </PageContainer>
  )
}
