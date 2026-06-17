/**
 * /workers/events — dedicated route for the full agent events stream.
 * Hoisted off the main /workers page (closes #865) so the operational
 * pool table isn't dominated by event noise. The Workers page links here
 * via a small footer affordance.
 *
 * Data: AgentEventsPanel reads from /api/v1/agents/events with the
 * existing DataTable primitive (cursor + URL state).
 */
import { createFileRoute, Link } from '@tanstack/react-router'
import { ArrowLeft } from 'lucide-react'
import { AgentEventsPanel } from '@/components/AgentEventsPanel'
import { PageContainer, PageHeader } from '@/components/ui/Page'
import { useDocumentTitle } from '@/lib/useDocumentTitle'

export const Route = createFileRoute('/workers/events')({
  component: WorkerEventsPage,
})

function WorkerEventsPage() {
  useDocumentTitle('Worker events')
  return (
    <PageContainer width="default">
      <PageHeader
        title="Worker events"
        description="Full agent event timeline · joined / left / drained"
        actions={
          <Link to="/workers" className="btn btn-sm btn-ghost">
            <ArrowLeft size={12} aria-hidden /> Workers
          </Link>
        }
      />
      <AgentEventsPanel />
    </PageContainer>
  )
}
