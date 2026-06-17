/**
 * Empty state for the pipelines list (extracted from
 * `routes/pipelines/index.tsx` for issue #1070).
 *
 * Rendered when the `useJobs` query resolves with zero pipelines. Pure
 * presentation — no data fetching. The route decides when to show it.
 */
import { Briefcase } from 'lucide-react'
import { EmptyState } from '@/components/EmptyState'

export function JobsEmptyState() {
  return (
    <EmptyState
      data-testid="jobs-empty"
      icon={<Briefcase size={28} aria-hidden />}
      title="No pipelines yet"
      message="Create your first pipeline to start running builds."
      action={{ label: 'Create your first pipeline', to: '/onboarding' }}
    />
  )
}
